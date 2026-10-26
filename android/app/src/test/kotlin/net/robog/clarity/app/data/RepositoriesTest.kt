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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import net.robog.clarity.app.testutil.FakeDeviceKey
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
    @Test fun acceptedKeyWorks() = runTest {
        val rig = Rig { r ->
            if (r.method == HttpMethod.Put) HttpStatusCode.NoContent to "" else
                HttpStatusCode.OK to ClarityJson.encodeToString(SetupStatus.serializer(), SetupStatus(SetupStatus.KeyState.VALID, 0, null, Settings()))
        }
        assertEquals(ModelKeyOutcome.Works, DefaultSetupRepository(rig.api).submitModelKey("  sk-ant-x "))
        assertEquals("""{"apiKey":"sk-ant-x"}""", rig.body(0))
    }

    @Test fun rejectedByTheServerOrReportedInvalid() = runTest {
        val problem = ClarityJson.encodeToString(Problem.serializer(), Problem(title = "Invalid key", status = 422, detail = "invalid x-api-key"))
        val rejected = DefaultSetupRepository(Rig { HttpStatusCode.UnprocessableEntity to problem }.api).submitModelKey("k")
        assertEquals(ModelKeyOutcome.Rejected("invalid x-api-key"), rejected)

        val invalid = Rig { r ->
            if (r.method == HttpMethod.Put) HttpStatusCode.OK to "{}" else
                HttpStatusCode.OK to ClarityJson.encodeToString(SetupStatus.serializer(), SetupStatus(SetupStatus.KeyState.INVALID, 0, null, Settings()))
        }
        assertEquals(ModelKeyOutcome.Rejected(null), DefaultSetupRepository(invalid.api).submitModelKey("k"))
    }

    @Test fun missingRoutesAndOutages() = runTest {
        assertEquals(ModelKeyOutcome.Unavailable, DefaultSetupRepository(Rig { HttpStatusCode.NotFound to "" }.api).submitModelKey("k"))
        assertIs<ModelKeyOutcome.Failed>(DefaultSetupRepository(Rig { HttpStatusCode.InternalServerError to "" }.api).submitModelKey("k"))
        assertEquals(null, DefaultSetupRepository(Rig { HttpStatusCode.NotFound to "" }.api).status())
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
        override suspend fun submitModelKey(apiKey: String) = ModelKeyOutcome.Works
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

}
