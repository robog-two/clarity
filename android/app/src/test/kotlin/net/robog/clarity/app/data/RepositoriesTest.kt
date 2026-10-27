package net.robog.clarity.app.data

import com.google.crypto.tink.subtle.X25519
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.robog.clarity.app.connector.ConfigForms
import net.robog.clarity.app.connector.FormValues
import net.robog.clarity.app.connector.TinkConfigSealer
import net.robog.clarity.app.nav.RootState
import net.robog.clarity.app.nav.RootViewModel
import net.robog.clarity.app.net.DeviceJwtSigner
import net.robog.clarity.app.net.KtorClarityApi
import net.robog.clarity.app.net.createHttpClient
import net.robog.clarity.app.push.pushConditions
import net.robog.clarity.app.testutil.FakeDeviceKey
import net.robog.clarity.app.testutil.FakePushPrefs
import net.robog.clarity.app.testutil.FakeSessionStore
import net.robog.clarity.app.testutil.InMemoryFeedStore
import net.robog.clarity.app.testutil.agentItem
import net.robog.clarity.app.testutil.signedIn
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.api.Problem
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.connector.ConnectorSummary
import net.robog.clarity.shared.connector.GrantState
import net.robog.clarity.shared.feed.InputAccepted
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.noun.Noun
import net.robog.clarity.shared.session.SetupStatus
import net.robog.clarity.shared.session.Settings

private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

private class Rig(handler: (HttpRequestData) -> Pair<HttpStatusCode, String>) {
    val requests = mutableListOf<HttpRequestData>()
    val sessions = FakeSessionStore(signedIn())
    val api = KtorClarityApi(
        createHttpClient(
            DeviceJwtSigner(FakeDeviceKey()), sessions,
            MockEngine { request ->
                requests += request
                val (status, body) = handler(request)
                respond(body, status, JSON_HEADERS)
            },
        ),
        sessions,
    )
    suspend fun body(i: Int) = String(requests[i].body.toByteArray())
}

class SetupRepositoryTest {
    @Test fun statusOrNothing() = runTest {
        val rig = Rig { HttpStatusCode.OK to ClarityJson.encodeToString(SetupStatus.serializer(), SetupStatus(SetupStatus.KeyState.VALID, 0, null, Settings())) }
        assertEquals(SetupStatus.KeyState.VALID, DefaultSetupRepository(rig.api).status()?.modelKey)
        assertEquals(null, DefaultSetupRepository(Rig { HttpStatusCode.NotFound to "" }.api).status())
        assertEquals(null, DefaultSetupRepository(Rig { HttpStatusCode.InternalServerError to "" }.api).status())
    }
}

class ConnectorsRepositoryTest {
    private val sealingPriv = X25519.generatePrivateKey()
    private val sealingKey = Base64.getUrlEncoder().withoutPadding().encodeToString(X25519.publicFromPrivate(sealingPriv))

    private fun connector(key: String?) = ConnectorSummary(
        ConnectorId("imap-personal"),
        ConnectorManifest(
            kind = "imap", displayName = "Mail", version = "1", produces = setOf(Noun.MESSAGE),
            configSchema = Json.parseToJsonElement(
                """{"required":["host","password"],"properties":{"host":{"type":"string"},"password":{"type":"string","format":"password"}}}""",
            ).jsonObject,
            sealingKey = key,
        ),
        grants = mapOf(Noun.MESSAGE to GrantState.PENDING), lastSeenAt = null,
    )

    @Test fun savesSealedConfigThenGrantsEveryNoun() = runTest {
        val rig = Rig { r ->
            when {
                r.method == HttpMethod.Get -> HttpStatusCode.OK to """{"version":4,"values":{}}"""
                else -> HttpStatusCode.NoContent to ""
            }
        }
        val repo = DefaultConnectorsRepository(rig.api, TinkConfigSealer())
        val outcome = repo.save(connector(sealingKey), FormValues(texts = mapOf("host" to "imap.home.local", "password" to "hunter2")))
        assertEquals(SaveConnectorOutcome.Saved, outcome)

        val put = rig.requests.indexOfFirst { it.method == HttpMethod.Put && it.url.encodedPath.endsWith("/config") }
        val config = Json.parseToJsonElement(rig.body(put)).jsonObject
        assertEquals("5", config["version"]!!.jsonPrimitive.content, "bumps the stored version")
        val values = config["values"]!!.jsonObject
        assertEquals("imap.home.local", values["host"]!!.jsonPrimitive.content)
        assertEquals("sealed", values["password"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue("hunter2" !in rig.body(put), "the password never leaves the phone in the clear")

        val grants = rig.requests.indexOfFirst { it.url.encodedPath.endsWith("/grants") }
        assertTrue(grants > put, "grants come after the config")
        assertEquals("""{"grants":{"message":"granted"}}""", rig.body(grants))
    }

    @Test fun accountsKeepTheirStoredSecretsAndSealNewOnesPerItem() = runTest {
        val stored = """{"version":4,"values":{"accounts":[{"id":"k3j9x2p1","label":"Work","password":{"type":"sealed","algorithm":"A","ciphertext":"STORED"}}]}}"""
        val rig = Rig { r -> if (r.method == HttpMethod.Get) HttpStatusCode.OK to stored else HttpStatusCode.NoContent to "" }
        val summary = ConnectorSummary(
            ConnectorId("mail"),
            ConnectorManifest(
                kind = "imap", displayName = "Mail", version = "1", produces = setOf(Noun.MESSAGE),
                configSchema = net.robog.clarity.app.testutil.MAIL_ACCOUNTS_SCHEMA, sealingKey = sealingKey,
            ),
            grants = mapOf(Noun.MESSAGE to GrantState.PENDING), lastSeenAt = null,
        )
        val form = ConfigForms.parse(summary.manifest!!.configSchema)
        var values = form.valuesFromStored(Json.parseToJsonElement(stored).jsonObject["values"]!!.jsonObject)
        val itemForm = form.fields.single().collection!!.itemForm
        val fresh = itemForm.initialValues().withText("label", "Home").withText("imapHost", "imap.x").withText("smtpHost", "smtp.x")
            .withText("username", "sam").withText("password", "hunter2")
        values = values.withItem("accounts", net.robog.clarity.app.connector.ItemValues("m4n8q7r2", fresh))

        assertEquals(SaveConnectorOutcome.Saved, DefaultConnectorsRepository(rig.api, TinkConfigSealer()).save(summary, values))
        val put = rig.requests.indexOfFirst { it.method == HttpMethod.Put && it.url.encodedPath.endsWith("/config") }
        val body = rig.body(put)
        assertTrue("hunter2" !in body)
        val accounts = Json.parseToJsonElement(body).jsonObject["values"]!!.jsonObject["accounts"]!!.jsonArray.map { it.jsonObject }
        assertEquals("STORED", accounts[0]["password"]!!.jsonObject["ciphertext"]!!.jsonPrimitive.content, "kept unchanged")
        assertEquals("A", accounts[0]["password"]!!.jsonObject["algorithm"]!!.jsonPrimitive.content)
        assertEquals("sealed", accounts[1]["password"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("m4n8q7r2", accounts[1]["id"]!!.jsonPrimitive.content)
    }

    @Test fun withoutASealingKeySecretsAreNotSent() = runTest {
        val rig = Rig { HttpStatusCode.NoContent to "" }
        val outcome = DefaultConnectorsRepository(rig.api, TinkConfigSealer()).save(connector(null), FormValues(texts = mapOf("host" to "h", "password" to "pw")))
        assertEquals(SaveConnectorOutcome.NoSealingKey, outcome)
        assertTrue(rig.requests.none { it.method == HttpMethod.Put })
    }

    @Test fun serverErrorsAreReturned() = runTest {
        val rig = Rig { r -> if (r.method == HttpMethod.Put) HttpStatusCode.NotFound to "" else HttpStatusCode.NotFound to "" }
        val outcome = DefaultConnectorsRepository(rig.api, TinkConfigSealer()).save(connector(sealingKey), FormValues(texts = mapOf("host" to "h", "password" to "pw")))
        assertIs<SaveConnectorOutcome.Failed>(outcome)
        assertIs<ApiError.NotFound>(outcome.error)
    }
}

class FeedRepositoryTest {
    @Test fun sentInputIsStoredRightAwayWithAStableClientId() = runTest {
        val accepted = InputAccepted(agentItem(5, "x").copy(author = net.robog.clarity.shared.feed.FeedItem.Author.USER))
        val rig = Rig { HttpStatusCode.OK to ClarityJson.encodeToString(InputAccepted.serializer(), accepted) }
        val store = InMemoryFeedStore()
        val repo = DefaultFeedRepository(
            rig.api, store,
            net.robog.clarity.app.data.feed.FeedSync(rig.api, net.robog.clarity.app.testutil.FakeEventSource(emptyList()), store),
            IdGenerator { "uuid-1" },
        )
        val r = repo.send("hello", InputSource.VOICE, null)
        assertIs<ApiResult.Success<InputAccepted>>(r)
        assertEquals(listOf(5L), store.snapshot.map { it.seq })
        val sent = Json.parseToJsonElement(rig.body(0)).jsonObject
        assertEquals("uuid-1", sent["clientId"]!!.jsonPrimitive.content)
        assertEquals("voice", sent["source"]!!.jsonPrimitive.content)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RootViewModelTest {
    private class Flag(initial: Boolean) : OnboardingFlagStore {
        val state = kotlinx.coroutines.flow.MutableStateFlow(initial)
        override val done = state
        override suspend fun markDone() { state.value = true }
    }

    private class Setup(val status: SetupStatus?) : SetupRepository {
        override suspend fun status() = status
    }

    private class Sessions(enrolled: Boolean) : SessionRepository {
        val store = FakeSessionStore(if (enrolled) signedIn() else null)
        override val session = store.session
        override suspend fun forget() = store.clear()
    }

    private val complete = SetupStatus(SetupStatus.KeyState.VALID, 2, 1, Settings())

    private fun rootState(enrolled: Boolean, done: Boolean, status: SetupStatus?): RootState {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val vm = RootViewModel(Sessions(enrolled), Flag(done), Setup(status))
            var result = RootState.Loading
            kotlinx.coroutines.runBlocking {
                result = vm.state.first { it != RootState.Loading }
            }
            return result
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun notEnrolledShowsOnboarding() = assertEquals(RootState.Onboarding, rootState(false, false, null))

    @Test fun enrolledAndFinishedShowsTheApp() = assertEquals(RootState.Ready, rootState(true, true, null))

    @Test fun enrolledButUnfinishedKeepsOnboardingUntilTheServerSaysSetupIsComplete() {
        assertEquals(RootState.Onboarding, rootState(true, false, null))
        assertEquals(RootState.Onboarding, rootState(true, false, complete.copy(constitutionVersion = null)))
        assertEquals(RootState.Ready, rootState(true, false, complete))
    }

    @Test fun serverCompleteSetupMarksThisDeviceDoneSoPushCanStart() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val flag = Flag(false)
            val vm = RootViewModel(Sessions(true), flag, Setup(complete))
            kotlinx.coroutines.runBlocking { assertEquals(RootState.Ready, vm.state.first { it != RootState.Loading }) }
            assertEquals(true, flag.state.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /** Like the DataStore one: done and redo re-emit on every write to the file, whatever key changed. */
    private class FileFlag(done: Boolean = false, redo: Boolean = false) : OnboardingFlagStore {
        data class File(val done: Boolean, val redo: Boolean, val writes: Int = 0)
        val file = kotlinx.coroutines.flow.MutableStateFlow(File(done, redo))
        override val done = file.map { it.done }
        override val redo = file.map { it.redo }
        override suspend fun markDone() = file.update { it.copy(done = true, redo = false, writes = it.writes + 1) }
        override suspend fun startRedo() = file.update { it.copy(redo = true, writes = it.writes + 1) }
        /** Any other key in the same file (battery asked, the SMS cursor...). */
        fun otherWrite() = file.update { it.copy(writes = it.writes + 1) }
    }

    private class CountingSetup(val status: SetupStatus?) : SetupRepository {
        var asks = 0
        override suspend fun status(): SetupStatus? { asks++; return status }
    }

    /** Runs [block] with the root's state collected, on one test dispatcher. */
    private fun withRoot(sessions: Sessions, flag: OnboardingFlagStore, setup: SetupRepository, block: suspend kotlinx.coroutines.test.TestScope.(RootViewModel) -> Unit) {
        val main = UnconfinedTestDispatcher()
        Dispatchers.setMain(main)
        try {
            runTest(main) {
                val vm = RootViewModel(sessions, flag, setup)
                backgroundScope.launch { vm.state.collect {} }
                block(vm)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun theServerFallbackMarksDoneOnceAndSettlesOnTheApp() {
        val flag = FileFlag()
        val setup = CountingSetup(complete)
        withRoot(Sessions(true), flag, setup) { vm ->
            advanceUntilIdle()
            assertEquals(RootState.Ready, vm.state.value)
            assertEquals(1, setup.asks)
            assertEquals(1, flag.file.value.writes, "marking done re-emits the file, which lands on Ready without another write")
            repeat(3) { flag.otherWrite() }
            advanceUntilIdle()
            assertEquals(RootState.Ready, vm.state.value)
            assertEquals(1, setup.asks)
        }
    }

    @Test fun otherWritesDuringOnboardingDoNotAskTheServerAgain() {
        val flag = FileFlag()
        val setup = CountingSetup(complete.copy(constitutionVersion = null))
        withRoot(Sessions(true), flag, setup) { vm ->
            advanceUntilIdle()
            assertEquals(RootState.Onboarding, vm.state.value)
            repeat(3) { flag.otherWrite() }
            advanceUntilIdle()
            assertEquals(RootState.Onboarding, vm.state.value, "onboarding is not swapped for the app halfway through")
            assertEquals(1, setup.asks)
            flag.markDone()
            advanceUntilIdle()
            assertEquals(RootState.Ready, vm.state.value, "its last screen still shows the app")
        }
    }

    @Test fun redoShowsOnboardingAgainAndFinishingItShowsTheApp() {
        val flag = FileFlag(done = true)
        val setup = CountingSetup(complete)
        withRoot(Sessions(true), flag, setup) { vm ->
            advanceUntilIdle()
            assertEquals(RootState.Ready, vm.state.value)
            flag.startRedo()
            advanceUntilIdle()
            assertEquals(RootState.Onboarding, vm.state.value)
            flag.otherWrite()
            advanceUntilIdle()
            assertEquals(RootState.Onboarding, vm.state.value)
            flag.markDone()
            advanceUntilIdle()
            assertEquals(RootState.Ready, vm.state.value)
            assertEquals(0, setup.asks, "a finished device never asks the server")
        }
    }

    @Test fun afterTheServerFallbackThePushServiceShouldRun() {
        val sessions = Sessions(true)
        val flag = FileFlag()
        val prefs = FakePushPrefs(enabled = true)
        withRoot(sessions, flag, CountingSetup(complete)) { vm ->
            assertFalse(pushConditions(sessions.store, FileFlag(), prefs).first().shouldRun, "not set up: no service")
            advanceUntilIdle()
            assertEquals(RootState.Ready, vm.state.value)
            assertTrue(pushConditions(sessions.store, flag, prefs).first().shouldRun)
            prefs.setEnabled(false)
            assertFalse(pushConditions(sessions.store, flag, prefs).first().shouldRun, "the person's switch still wins")
        }
    }

    @Test fun anIncompleteServerKeepsThePushServiceOff() {
        val sessions = Sessions(true)
        val flag = FileFlag()
        withRoot(sessions, flag, CountingSetup(null)) { vm ->
            advanceUntilIdle()
            assertEquals(RootState.Onboarding, vm.state.value)
            assertFalse(pushConditions(sessions.store, flag, FakePushPrefs(enabled = true)).first().shouldRun)
        }
    }
}
