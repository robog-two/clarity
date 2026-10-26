package net.robog.clarity.backend.vault

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.security.KeyPair
import java.sql.ResultSet
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.agent.FakeBrowser
import net.robog.clarity.backend.agent.str
import net.robog.clarity.backend.agent.LeadRig
import net.robog.clarity.backend.agent.RunScope
import net.robog.clarity.backend.agent.args
import net.robog.clarity.backend.agent.callTool
import net.robog.clarity.backend.agent.end
import net.robog.clarity.backend.agent.leadSystemBlocks
import net.robog.clarity.backend.agent.roleRequest
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.DevicePrincipal
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.governance.ActivityStore
import net.robog.clarity.backend.governance.ConnectorRouter
import net.robog.clarity.backend.governance.GovernanceRoutes
import net.robog.clarity.backend.governance.HeldActionService
import net.robog.clarity.backend.governance.NoConnectorPreferences
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.Devices
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.governance.ApprovalPayload
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.SignedDecision
import net.robog.clarity.shared.governance.payloadFor
import net.robog.clarity.shared.governance.HeldDecision
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.vault.CredentialKind
import net.robog.clarity.shared.vault.CredentialMeta
import net.robog.clarity.shared.vault.CredentialRequest
import net.robog.clarity.shared.vault.DeviceVaultKey
import net.robog.clarity.shared.vault.FieldSpec
import net.robog.clarity.shared.vault.FieldType
import net.robog.clarity.shared.vault.GrantReceipt
import net.robog.clarity.shared.vault.NewCredential
import net.robog.clarity.shared.vault.RegisterVaultKey
import net.robog.clarity.shared.vault.ReleaseCode
import net.robog.clarity.shared.vault.ReleaseGrant
import net.robog.clarity.shared.vault.VaultEnvelope
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.testcontainers.postgresql.PostgreSQLContainer

private const val PASSWORD = "hunter2!-Zebra"
private const val USERNAME = "dana.vault@example.com"
private const val CODE = "58301947"

private suspend inline fun <reified T : Any> HttpClient.postAs(device: String, path: String, body: T): HttpResponse = post(path) {
    header(HttpHeaders.Authorization, "Bearer $device"); contentType(ContentType.Application.Json); setBody(body)
}

private val approve: String = ClarityJson.encodeToString(HeldDecision.serializer(), HeldDecision.Approve())

private suspend fun HttpClient.postAs(device: String, path: String, json: String): HttpResponse = post(path) {
    header(HttpHeaders.Authorization, "Bearer $device"); contentType(ContentType.Application.Json); setBody(json)
}

private suspend fun HttpClient.getAs(device: String, path: String): HttpResponse = get(path) { header(HttpHeaders.Authorization, "Bearer $device") }

/**
 * The vault end to end against Postgres: device keys, requests, sealed credentials, the phone's
 * grant release, the browser wrapper under real governance, and a scan of every table proving no
 * released value was ever persisted. The model and the browser are fakes.
 */
@EnabledIf(ContainerRuntimeAvailable::class)
class VaultFlowTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database
    lateinit var rig: LeadRig
    lateinit var heldService: HeldActionService
    val phone = Uuid.random().toString()
    val tablet = Uuid.random().toString()
    val stranger = Uuid.random().toString()
    val phoneKey: KeyPair = VaultCryptoReference.newDeviceKey()
    val tabletKey: KeyPair = VaultCryptoReference.newDeviceKey()
    val browser = FakeBrowser()
    lateinit var credentialId: String
    lateinit var browserTools: Map<String, Tool>

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
        database.tx {
            listOf(phone, tablet, stranger).forEach { id ->
                Devices.insert {
                    it[Devices.id] = Uuid.parse(id)
                    it[name] = "device $id"
                    it[publicKey] = ByteArray(8)
                    it[createdAt] = Clock.System.now()
                }
            }
        }
        rig = LeadRig(database)
        rig.reset()
        heldService = HeldActionService(database, Clock.System, rig.heldStore, rig.constitutions, rig.outbox, ConnectorRouter(database, NoConnectorPreferences()), rig.events, rig.vaultStore)
        browserTools = rig.browserGate.wrap(RunScope("lead", isLead = true), browser.tools).associateBy { it.name }
    }
    afterSpec { postgres.stop() }

    fun ApplicationTestBuilder.setup(): HttpClient {
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(ClarityJson) }
            install(Authentication) { bearer(AuthProviders.DEVICE) { authenticate { DevicePrincipal(DeviceId(it.token)) } } }
            VaultRoutes(rig.vaultService).install(this)
            GovernanceRoutes(rig.constitutions, heldService, ActivityStore(database)).install(this)
        }
        return createClient { install(ContentNegotiation) { json(ClarityJson) } }
    }

    /** Runs one scripted lead turn that makes [calls] in order (one per model call) and returns the tool results the model saw. */
    suspend fun runTurn(runId: String, vararg calls: Pair<String, JsonObject>): List<String> {
        var n = 0
        rig.model.handler = { _ ->
            if (n < calls.size) callTool("call-$runId-$n", calls[n].first, calls[n].second).also { n++ } else end("done")
        }
        val tools = browserTools.values.toList() + VaultTools(rig.vaultService, rig.vaultHolds).all(RunScope(runId, isLead = true))
        val request = roleRequest(
            rig.config.roles.lead, runId, leadSystemBlocks(null), listOf(Message.user("go")), tools, emptyList(), 10, rig.large.sink,
        )
        rig.runtime.harness(rig.journal).run(request).toList()
        return rig.journal.load(runId).flatMap { it.content }.filterIsInstance<Block.Result>().map { it.content }
    }

    suspend fun heldOf(kind: Class<out ProposedAction>): List<HeldAction> = rig.heldStore.list().filter { kind.isInstance(it.action) }

    /** Every table's rows as text: the secret values must appear in none of them. */
    suspend fun dumpDatabase(): String = database.tx {
        val tables = mutableListOf<String>()
        exec("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'") { rs: ResultSet ->
            while (rs.next()) tables += rs.getString(1)
        }
        buildString {
            tables.forEach { table ->
                exec("SELECT t::text FROM \"$table\" t") { rs: ResultSet -> while (rs.next()) appendLine(rs.getString(1)) }
            }
        }
    }

    val loginFields = listOf(FieldSpec("username", "Username", FieldType.USERNAME), FieldSpec("password", "Password", FieldType.PASSWORD))
    val toolInput = { name: String ->
        buildJsonObject {
            put("kind", "login")
            put("site", "bank.example.com")
            put("purpose", "Pay the water bill")
            put("name", name)
            put("fields", buildJsonArray {
                add(buildJsonObject { put("name", "username"); put("label", "Username"); put("type", "username") })
                add(buildJsonObject { put("name", "password"); put("label", "Password"); put("type", "password") })
            })
        }
    }

    test("devices register their vault keys; bad keys are refused") {
        testApplication {
            val client = setup()
            client.postAs(phone, "/api/v1/vault/devices", RegisterVaultKey(VaultCryptoReference.publicKeyWire(phoneKey))).status shouldBe HttpStatusCode.Created
            client.postAs(tablet, "/api/v1/vault/devices", RegisterVaultKey(VaultCryptoReference.publicKeyWire(tabletKey))).status shouldBe HttpStatusCode.Created
            client.postAs(phone, "/api/v1/vault/devices", RegisterVaultKey("garbage")).status shouldBe HttpStatusCode.BadRequest
            val keys: List<DeviceVaultKey> = client.getAs(phone, "/api/v1/vault/devices").body()
            keys.map { it.deviceId.value }.toSet() shouldBe setOf(phone, tablet)
        }
    }

    test("request_credential puts a form card in the feed; the phone completes it with sealed envelopes; only the owner can fetch its copy") {
        val tool = VaultTools(rig.vaultService, rig.vaultHolds).all(RunScope("r", isLead = true)).first { it.name == "request_credential" }
        val reply = tool.invoke(toolInput("bank_login")).shouldBeInstanceOf<ToolResult.Ok>().content
        reply shouldContain "[secret:bank_login_password]"

        val card = rig.feedStore.page(0, 50).items.flatMap { it.cards }.filterIsInstance<Card.CredentialRequest>().single()
        testApplication {
            val client = setup()
            val request: CredentialRequest = client.getAs(phone, "/api/v1/vault/requests/${card.requestId}").body()
            request.name shouldBe "bank_login"
            request.fields shouldBe loginFields

            // a bad envelope (wrong context) and an unregistered device are refused
            val sealedForPhone = VaultCryptoReference.seal(DeviceId(phone), VaultCryptoReference.publicKeyWire(phoneKey), request.id.value, mapOf("username" to USERNAME, "password" to PASSWORD))
            client.postAs(phone, "/api/v1/vault/credentials", NewCredential(request.id, envelopes = listOf(sealedForPhone.copy(context = "other")))).status shouldBe HttpStatusCode.BadRequest
            val sealedForStranger = VaultCryptoReference.seal(DeviceId(stranger), VaultCryptoReference.publicKeyWire(phoneKey), request.id.value, mapOf("password" to PASSWORD))
            client.postAs(phone, "/api/v1/vault/credentials", NewCredential(request.id, envelopes = listOf(sealedForStranger))).status shouldBe HttpStatusCode.UnprocessableEntity
            // hints cannot smuggle a secret
            client.postAs(phone, "/api/v1/vault/credentials", NewCredential(request.id, hints = mapOf("password" to PASSWORD), envelopes = listOf(sealedForPhone))).status shouldBe HttpStatusCode.BadRequest

            val sealedForTablet = VaultCryptoReference.seal(DeviceId(tablet), VaultCryptoReference.publicKeyWire(tabletKey), request.id.value, mapOf("username" to USERNAME, "password" to PASSWORD))
            val created = client.postAs(phone, "/api/v1/vault/credentials", NewCredential(request.id, mapOf("username" to "dana"), listOf(sealedForPhone, sealedForTablet)))
            created.status shouldBe HttpStatusCode.Created
            val meta: CredentialMeta = created.body()
            credentialId = meta.id.value
            meta.name shouldBe "bank_login"
            meta.kind shouldBe CredentialKind.LOGIN
            client.postAs(phone, "/api/v1/vault/credentials", NewCredential(request.id, envelopes = listOf(sealedForPhone))).status shouldBe HttpStatusCode.Conflict

            val listed: List<CredentialMeta> = client.getAs(phone, "/api/v1/vault/credentials").body()
            listed.map { it.name } shouldContainExactly listOf("bank_login")
            listed.single().toString() shouldNotContain PASSWORD

            // each device gets only its own copy, which only its key opens
            val mine: VaultEnvelope = client.getAs(phone, "/api/v1/vault/credentials/$credentialId/envelope").body()
            VaultCryptoReference.open(mine, phoneKey.private) shouldBe mapOf("username" to USERNAME, "password" to PASSWORD)
            val theirs: VaultEnvelope = client.getAs(tablet, "/api/v1/vault/credentials/$credentialId/envelope").body()
            theirs.deviceId.value shouldBe tablet
            VaultCryptoReference.open(theirs, tabletKey.private)["password"] shouldBe PASSWORD
            client.getAs(stranger, "/api/v1/vault/credentials/$credentialId/envelope").status shouldBe HttpStatusCode.NotFound
        }
    }

    test("a placeholder without a grant holds a credential-use request; the generic route cannot approve it; the phone's release does") {
        browser.url = "https://bank.example.com/login"
        val seen = runTurn("run-a", "browser__fill_by_uid" to args("uid" to "1_2", "value" to "[secret:bank_login_password]"))
        seen.single() shouldContain "approve on their phone"
        browser.calls("fill_by_uid").size shouldBe 0

        val held = heldOf(ProposedAction.UseCredential::class.java).single()
        held.status shouldBe HeldAction.Status.HELD
        val use = held.action.shouldBeInstanceOf<ProposedAction.UseCredential>()
        use.site shouldBe "example.com"
        use.fields shouldBe listOf("username", "password")

        // asking again while it waits does not stack up more requests
        runTurn("run-a2", "browser__fill_by_uid" to args("uid" to "1_2", "value" to "[secret:bank_login_password]"))
        heldOf(ProposedAction.UseCredential::class.java) shouldHaveSize 1

        testApplication {
            val client = setup()
            // the generic decision route refuses to approve it
            client.postAs(phone, "/api/v1/held/${held.id}/decision", approve).status shouldBe HttpStatusCode.Conflict
            rig.heldStore.get(held.id)!!.status shouldBe HeldAction.Status.HELD
            // a release for a field it never asked for, from a device with no vault key, or for another kind of item: refused
            client.postAs(phone, "/api/v1/vault/grants/${held.id}", ReleaseGrant(mapOf("username" to USERNAME, "pin" to "1"))).status shouldBe HttpStatusCode.BadRequest
            client.postAs(phone, "/api/v1/vault/grants/${held.id}", ReleaseGrant(mapOf("username" to USERNAME))).status shouldBe HttpStatusCode.BadRequest // password is required
            client.postAs(stranger, "/api/v1/vault/grants/${held.id}", ReleaseGrant(mapOf("username" to USERNAME, "password" to PASSWORD))).status shouldBe HttpStatusCode.UnprocessableEntity
            client.postAs(phone, "/api/v1/vault/grants/${Uuid.random()}", ReleaseGrant(mapOf("username" to USERNAME, "password" to PASSWORD))).status shouldBe HttpStatusCode.NotFound
            rig.grants.liveCount() shouldBe 0

            val ok = client.postAs(phone, "/api/v1/vault/grants/${held.id}", ReleaseGrant(mapOf("username" to USERNAME, "password" to PASSWORD), ttlSeconds = 9999))
            ok.status shouldBe HttpStatusCode.OK
            val receipt: GrantReceipt = ok.body()
            (receipt.expiresAt - Clock.System.now() <= 5.minutes) shouldBe true
            rig.heldStore.get(held.id)!!.status shouldBe HeldAction.Status.APPROVED
            // a second release for the same item is refused
            client.postAs(phone, "/api/v1/vault/grants/${held.id}", ReleaseGrant(mapOf("username" to USERNAME, "password" to PASSWORD))).status shouldBe HttpStatusCode.Conflict
        }
    }

    test("with the grant the browser receives the value and the agent, the journal and the results hold only the placeholder") {
        browser.pageText = "Signed in as $USERNAME, password $PASSWORD, echoed as q=${java.net.URLEncoder.encode(PASSWORD, Charsets.UTF_8)}"
        val seen = runTurn(
            "run-b",
            "browser__fill_by_uid" to args("uid" to "1_1", "value" to "[secret:bank_login_username]"),
            "browser__fill_by_uid" to args("uid" to "1_2", "value" to "[secret:bank_login_password]"),
            "browser__get_page_text" to JsonObject(emptyMap()),
        )
        browser.calls("fill_by_uid").map { it.str("value") } shouldContainExactly listOf(USERNAME, PASSWORD)
        seen[0] shouldBe "filled 1_1 with [secret:bank_login_username]"
        seen[1] shouldBe "filled 1_2 with [secret:bank_login_password]"
        seen[2] shouldBe "Signed in as [secret:bank_login_username], password [secret:bank_login_password], echoed as q=[secret:bank_login_password]"
        seen.joinToString("\n") shouldNotContain PASSWORD

        val journal = rig.journal.load("run-b")
        val transcript = ClarityJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(Message.serializer()), journal)
        transcript shouldContain "[secret:bank_login_password]"
        transcript shouldNotContain PASSWORD
        transcript shouldNotContain USERNAME
    }

    test("a two-factor code is asked from the person, released to the grant store only, and typed as a placeholder") {
        val ask = VaultTools(rig.vaultService, rig.vaultHolds).all(RunScope("r", isLead = true)).first { it.name == "ask_code" }
        val reply = ask.invoke(buildJsonObject { put("site", "bank.example.com"); put("purpose", "Log in to the bank") }).shouldBeInstanceOf<ToolResult.Ok>().content
        val held = heldOf(ProposedAction.AskCode::class.java).single()
        reply shouldContain "[secret:code_${held.id}]"
        runTurn("run-c", "browser__type_text" to args("text" to "[secret:code_${held.id}]")).single() shouldContain "not entered"

        testApplication {
            val client = setup()
            client.postAs(phone, "/api/v1/vault/codes/${held.id}", ReleaseCode("1")).status shouldBe HttpStatusCode.BadRequest
            client.postAs(phone, "/api/v1/vault/codes/${held.id}", ReleaseCode(CODE)).status shouldBe HttpStatusCode.OK
            client.postAs(phone, "/api/v1/vault/codes/${held.id}", ReleaseCode(CODE)).status shouldBe HttpStatusCode.Conflict
            // a code is not a credential release, and vice versa
            val use = heldOf(ProposedAction.UseCredential::class.java).single()
            client.postAs(phone, "/api/v1/vault/codes/${use.id}", ReleaseCode(CODE)).status shouldBe HttpStatusCode.UnprocessableEntity
        }
        val typed = runTurn("run-c2", "browser__type_text" to args("text" to "[secret:code_${held.id}]")).single()
        typed shouldBe "typed [secret:code_${held.id}]"
        browser.calls("type_text").single().str("text") shouldBe CODE
    }

    test("an approved browser step runs once when the agent repeats it; approving is recorded without any connector") {
        runTurn("run-d0", "browser__take_snapshot" to JsonObject(emptyMap()))
        val first = runTurn("run-d1", "browser__click_by_uid" to args("uid" to "1_4")).single()
        first shouldContain "Waiting"
        browser.calls("click_by_uid").size shouldBe 0
        val held = heldOf(ProposedAction.ExternalTool::class.java).single { it.status == HeldAction.Status.HELD }
        held.action.summary shouldBe "Click button \"Send money\" on bank.example.com/login"

        testApplication {
            val client = setup()
            val approved = client.postAs(phone, "/api/v1/held/${held.id}/decision", approve)
            approved.status shouldBe HttpStatusCode.OK
            approved.body<HeldAction>().status shouldBe HeldAction.Status.APPROVED
        }
        runTurn("run-d2", "browser__click_by_uid" to args("uid" to "1_4")).single() shouldBe "clicked 1_4"
        browser.calls("click_by_uid").size shouldBe 1
        rig.heldStore.get(held.id)!!.status shouldBe HeldAction.Status.EXECUTED
        // one yes is one step: the same click asks again
        runTurn("run-d3", "browser__click_by_uid" to args("uid" to "1_4")).single() shouldContain "Waiting"
        browser.calls("click_by_uid").size shouldBe 1
    }

    test("deleting a credential revokes its grants; the next placeholder is unknown") {
        testApplication {
            val client = setup()
            client.delete("/api/v1/vault/credentials/$credentialId") { header(HttpHeaders.Authorization, "Bearer $phone") }.status shouldBe HttpStatusCode.NoContent
            client.delete("/api/v1/vault/credentials/$credentialId") { header(HttpHeaders.Authorization, "Bearer $phone") }.status shouldBe HttpStatusCode.NotFound
        }
        rig.grants.find("bank_login", "example.com") shouldBe null
        runTurn("run-e", "browser__fill_by_uid" to args("uid" to "1_2", "value" to "[secret:bank_login_password]")).single() shouldContain "Unknown placeholder"
    }

    test("saving a credential tells the lead through the inbox, with metadata only") {
        val pending = rig.inbox.pending().map { it.item.summary }
        pending.single { it.contains("bank_login") && it.contains("is ready") } shouldNotContain PASSWORD
    }

    test("a device with an approval key must sign the release; the signature covers the action and works once") {
        val approvalKey = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val wire = java.util.Base64.getUrlEncoder().withoutPadding()
        val tool = VaultTools(rig.vaultService, rig.vaultHolds).all(RunScope("r2", isLead = true)).first { it.name == "ask_code" }
        tool.invoke(buildJsonObject { put("site", "shop.example.org"); put("purpose", "Log in to the shop") }).shouldBeInstanceOf<ToolResult.Ok>()
        val held = heldOf(ProposedAction.AskCode::class.java).single { it.status == HeldAction.Status.HELD }
        fun sign(key: KeyPair, payload: String) = wire.encodeToString(
            java.security.Signature.getInstance("SHA256withECDSA").apply { initSign(key.private); update(payload.toByteArray()) }.sign(),
        )
        testApplication {
            val client = setup()
            client.postAs(tablet, "/api/v1/vault/devices", RegisterVaultKey(VaultCryptoReference.publicKeyWire(tabletKey), approvalKey = wire.encodeToString(approvalKey.public.encoded))).status shouldBe HttpStatusCode.Created
            client.postAs(tablet, "/api/v1/vault/devices", RegisterVaultKey(VaultCryptoReference.publicKeyWire(tabletKey), approvalKey = "garbage")).status shouldBe HttpStatusCode.BadRequest
            // unsigned, signed by another key, or signed for another decision: refused
            client.postAs(tablet, "/api/v1/vault/codes/${held.id}", ReleaseCode(CODE)).status shouldBe HttpStatusCode.Forbidden
            val other = VaultCryptoReference.newDeviceKey()
            client.postAs(tablet, "/api/v1/vault/codes/${held.id}", ReleaseCode(CODE, signature = sign(other, ApprovalPayload.payloadFor(held, SignedDecision.APPROVE)))).status shouldBe HttpStatusCode.Forbidden
            client.postAs(tablet, "/api/v1/vault/codes/${held.id}", ReleaseCode(CODE, signature = sign(approvalKey, ApprovalPayload.payloadFor(held, SignedDecision.DECLINE)))).status shouldBe HttpStatusCode.Forbidden
            rig.heldStore.get(held.id)!!.status shouldBe HeldAction.Status.HELD
            client.postAs(tablet, "/api/v1/vault/codes/${held.id}", ReleaseCode(CODE, signature = sign(approvalKey, ApprovalPayload.payloadFor(held, SignedDecision.APPROVE)))).status shouldBe HttpStatusCode.OK
            rig.heldStore.get(held.id)!!.status shouldBe HeldAction.Status.APPROVED
        }
    }

    test("no released value is in any database row") {
        val dump = dumpDatabase()
        dump shouldContain "bank_login" // the dump really includes our data
        dump shouldContain "[secret:bank_login_password]" // placeholders are what got stored
        dump shouldNotContain PASSWORD
        dump shouldNotContain USERNAME
        dump shouldNotContain CODE
        dump shouldNotContain java.net.URLEncoder.encode(PASSWORD, Charsets.UTF_8)
    }
})
