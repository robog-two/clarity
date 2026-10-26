package net.robog.clarity.backend.governance

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.time.Clock
import kotlin.uuid.Uuid
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.DevicePrincipal
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.Connectors
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.Devices
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.vault.ExposedVaultStore
import net.robog.clarity.backend.vault.VaultCryptoReference
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.ApprovalPayload
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Decision
import net.robog.clarity.shared.governance.GuardVerdict
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.HeldDecision
import net.robog.clarity.shared.governance.SignedDecision
import net.robog.clarity.shared.governance.Urgency
import net.robog.clarity.shared.governance.actionHash
import net.robog.clarity.shared.governance.payloadFor
import net.robog.clarity.shared.noun.Noun
import net.robog.clarity.shared.vault.DeviceVaultKey
import net.robog.clarity.shared.vault.CredentialId
import net.robog.clarity.shared.vault.CredentialKind
import net.robog.clarity.shared.vault.CredentialMeta
import net.robog.clarity.shared.vault.FieldSpec
import net.robog.clarity.shared.vault.FieldType
import net.robog.clarity.shared.governance.Provenance
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.backend.feed.ExposedFeedStore
import net.robog.clarity.backend.feed.FeedHub
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.vault.GovernedVaultHolds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.testcontainers.postgresql.PostgreSQLContainer

private val b64 = Base64.getUrlEncoder().withoutPadding()

private fun signingKey(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

private fun sign(key: KeyPair, payload: String): String =
    b64.encodeToString(Signature.getInstance("SHA256withECDSA").apply { initSign(key.private); update(payload.toByteArray()) }.sign())

/** Phone approvals against Postgres: a "never alone" kind needs a signed yes; everything about the signature is checked. */
@EnabledIf(ContainerRuntimeAvailable::class)
class PhoneApprovalFlowTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database
    lateinit var heldStore: HeldActionStore
    lateinit var constitutions: ConstitutionStore
    lateinit var gate: ActionGate
    lateinit var held: HeldActionService
    lateinit var vault: ExposedVaultStore
    val phone = Uuid.random().toString()
    val other = Uuid.random().toString()
    val phoneKey = signingKey()
    val otherKey = signingKey()
    var n = 0

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(
            net.robog.clarity.backend.config.DatabaseConfig(postgres.jdbcUrl, postgres.username, net.robog.clarity.backend.config.Secret(postgres.password), maxPoolSize = 6),
        )
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
        database.tx {
            listOf(phone, other).forEach { id ->
                Devices.insert { it[Devices.id] = Uuid.parse(id); it[name] = "d"; it[publicKey] = ByteArray(8); it[createdAt] = Clock.System.now() }
            }
            Connectors.insert {
                it[id] = "mail"; it[provisioned] = true; it[createdAt] = Clock.System.now()
                it[manifest] = ConnectorManifest("mail", "Mail", "1", setOf(Noun.MESSAGE), handles = setOf(ActionKind.SEND_EMAIL, ActionKind.SAVE_DRAFT))
            }
        }
        val clock = Clock.System
        val events = GovernanceEvents()
        heldStore = HeldActionStore(database, clock)
        constitutions = ConstitutionStore(database, clock, heldStore, events)
        val outbox = ActionOutbox(database, clock, heldStore, OutboxSignals(), events, ActionResults())
        val router = ConnectorRouter(database, NoConnectorPreferences())
        vault = ExposedVaultStore(database, clock)
        gate = ActionGate(Guard(GuardModel { GuardVerdict(Decision.ALLOW, emptyList(), "fine") }), constitutions, heldStore, outbox, router, events, UtcPersonZone(), clock)
        held = HeldActionService(database, clock, heldStore, constitutions, outbox, router, events, vault)
        constitutions.create(
            constitution(autonomy = mapOf(ActionKind.SEND_EMAIL to AutonomyTier.NEVER_ALONE, ActionKind.SAVE_DRAFT to AutonomyTier.ASK_FIRST, ActionKind.PAYMENT to AutonomyTier.NEVER)),
        )
        vault.upsertDeviceKey(DeviceVaultKey(DeviceId(phone), VaultCryptoReference.publicKeyWire(VaultCryptoReference.newDeviceKey())), b64.encodeToString(phoneKey.public.encoded))
    }
    afterSpec { postgres.stop() }

    suspend fun hold(kind: ActionKind): HeldAction {
        val outcome = gate.propose(sample(kind, "k-${n++}"), "why", emptyList(), trusted(), Urgency.NORMAL)
        val id = (outcome as? ProposalOutcome.Held)?.heldId ?: (outcome as ProposalOutcome.Blocked).heldId
        return heldStore.get(id)!!
    }

    fun ApplicationTestBuilder.setup(device: String): HttpClient {
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(ClarityJson) }
            install(Authentication) { bearer(AuthProviders.DEVICE) { authenticate { DevicePrincipal(DeviceId(device)) } } }
            GovernanceRoutes(constitutions, held, ActivityStore(database)).install(this)
        }
        return createClient { install(ContentNegotiation) { json(ClarityJson) } }
    }

    suspend fun HttpClient.signed(id: String, body: SignedDecision): HttpResponse = post("/api/v1/held/$id/signed-decision") {
        header(HttpHeaders.Authorization, "Bearer x"); contentType(ContentType.Application.Json); setBody(body)
    }

    suspend fun HttpClient.generic(id: String, decision: HeldDecision): HttpResponse = post("/api/v1/held/$id/decision") {
        header(HttpHeaders.Authorization, "Bearer x"); contentType(ContentType.Application.Json)
        setBody(ClarityJson.encodeToString(HeldDecision.serializer(), decision))
    }

    test("the verdict marks a never-alone kind; the generic route refuses to approve it but still declines") {
        val h = hold(ActionKind.SEND_EMAIL)
        h.verdict.phoneApproval shouldBe true
        hold(ActionKind.SAVE_DRAFT).verdict.phoneApproval shouldBe false
        testApplication {
            val client = setup(phone)
            client.generic(h.id.value, HeldDecision.Approve()).status shouldBe HttpStatusCode.Conflict
            heldStore.get(h.id)!!.status shouldBe HeldAction.Status.HELD
            client.generic(h.id.value, HeldDecision.Decline).status shouldBe HttpStatusCode.OK
        }
    }

    test("an ask-first kind is still approved from the generic route") {
        val h = hold(ActionKind.SAVE_DRAFT)
        testApplication {
            setup(phone).generic(h.id.value, HeldDecision.Approve()).status shouldBe HttpStatusCode.OK
        }
    }

    test("a good signature approves; replaying it is refused") {
        val h = hold(ActionKind.SEND_EMAIL)
        val signature = sign(phoneKey, ApprovalPayload.payloadFor(h, SignedDecision.APPROVE))
        testApplication {
            val client = setup(phone)
            client.signed(h.id.value, SignedDecision(SignedDecision.APPROVE, signature)).status shouldBe HttpStatusCode.OK
            heldStore.get(h.id)!!.status shouldBe HeldAction.Status.APPROVED
            // the same signature again: refused (replay)
            client.signed(h.id.value, SignedDecision(SignedDecision.APPROVE, signature)).status shouldBe HttpStatusCode.Conflict
        }
        vault.markSignatureUsed(PhoneApprovals.signatureHash(signature), h.id.value, DeviceId(phone)) shouldBe false
    }

    test("a signature by another key, over another action or for another decision is refused") {
        val h = hold(ActionKind.SEND_EMAIL)
        val h2 = hold(ActionKind.SEND_EMAIL)
        testApplication {
            val client = setup(phone)
            val wrongKey = sign(otherKey, ApprovalPayload.payloadFor(h, SignedDecision.APPROVE))
            client.signed(h.id.value, SignedDecision(SignedDecision.APPROVE, wrongKey)).status shouldBe HttpStatusCode.Forbidden
            val wrongAction = sign(phoneKey, ApprovalPayload.payloadFor(h2, SignedDecision.APPROVE))
            client.signed(h.id.value, SignedDecision(SignedDecision.APPROVE, wrongAction)).status shouldBe HttpStatusCode.Forbidden
            val declineSig = sign(phoneKey, ApprovalPayload.payloadFor(h, SignedDecision.DECLINE))
            client.signed(h.id.value, SignedDecision(SignedDecision.APPROVE, declineSig)).status shouldBe HttpStatusCode.Forbidden
            client.signed(h.id.value, SignedDecision("keep", declineSig)).status shouldBe HttpStatusCode.Forbidden
            val tampered = ApprovalPayload.payload(h.id, "0".repeat(64), SignedDecision.APPROVE)
            client.signed(h.id.value, SignedDecision(SignedDecision.APPROVE, sign(phoneKey, tampered))).status shouldBe HttpStatusCode.Forbidden
            heldStore.get(h.id)!!.status shouldBe HeldAction.Status.HELD
        }
    }

    test("a device without an approval key cannot sign") {
        val h = hold(ActionKind.SEND_EMAIL)
        testApplication {
            val client = setup(other)
            val sig = sign(otherKey, ApprovalPayload.payloadFor(h, SignedDecision.APPROVE))
            client.signed(h.id.value, SignedDecision(SignedDecision.APPROVE, sig)).status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("a signed decline declines; a never kind stays refused even with a valid signature") {
        val h = hold(ActionKind.SEND_EMAIL)
        val never = hold(ActionKind.PAYMENT)
        never.status shouldBe HeldAction.Status.BLOCKED
        never.verdict.phoneApproval shouldBe false
        testApplication {
            val client = setup(phone)
            client.signed(h.id.value, SignedDecision(SignedDecision.DECLINE, sign(phoneKey, ApprovalPayload.payloadFor(h, SignedDecision.DECLINE)))).status shouldBe HttpStatusCode.OK
            heldStore.get(h.id)!!.status shouldBe HeldAction.Status.DECLINED
            val sig = sign(phoneKey, ApprovalPayload.payloadFor(never, SignedDecision.APPROVE))
            client.signed(never.id.value, SignedDecision(SignedDecision.APPROVE, sig)).status shouldBe HttpStatusCode.Conflict
            heldStore.get(never.id)!!.status shouldBe HeldAction.Status.BLOCKED
        }
    }

    test("two parallel uses of one login (its email and password fields) ask the person once") {
        val slowGate = ActionGate(
            Guard(GuardModel { delay(300); GuardVerdict(Decision.ALLOW, emptyList(), "fine") }),
            constitutions, heldStore, ActionOutbox(database, Clock.System, heldStore, OutboxSignals(), GovernanceEvents(), ActionResults()),
            ConnectorRouter(database, NoConnectorPreferences()), GovernanceEvents(), UtcPersonZone(), Clock.System,
        )
        val holds = GovernedVaultHolds(slowGate, heldStore, Clock.System)
        val login = CredentialMeta(
            CredentialId(Uuid.random().toString()), "shop_login", CredentialKind.LOGIN, "shop.example.com", "orders",
            listOf(FieldSpec("email", "Email", FieldType.EMAIL), FieldSpec("password", "Password", FieldType.PASSWORD)), createdAt = Clock.System.now(),
        )
        val waits = coroutineScope {
            (1..2).map { async { holds.useCredential(login, "example.com", "https://shop.example.com/login", Provenance("run", untrustedInput = true), "sign in") } }.awaitAll()
        }
        val ids = waits.map { (it as net.robog.clarity.backend.vault.Hold.Wait).heldId }
        ids.distinct().size shouldBe 1
        heldStore.list(setOf(HeldAction.Status.HELD), limit = 200)
            .count { (it.action as? ProposedAction.UseCredential)?.credentialId == login.id } shouldBe 1
    }

    test("a yes to a browser step is used by the same click after a fresh snapshot, once") {
        val gate = ActionGate(
            Guard(GuardModel { GuardVerdict(Decision.ALLOW, emptyList(), "fine") }),
            constitutions, heldStore, ActionOutbox(database, Clock.System, heldStore, OutboxSignals(), GovernanceEvents(), ActionResults()),
            ConnectorRouter(database, NoConnectorPreferences()), GovernanceEvents(), UtcPersonZone(), Clock.System,
        )
        val holds = GovernedVaultHolds(gate, heldStore, Clock.System)
        val page = Provenance("run", untrustedInput = true)
        fun click(uid: String) = ProposedAction.ExternalTool(
            "browser:pending", "Click button \"Book now\" on shop.example.com/visit", "browser", "click_by_uid",
            kotlinx.serialization.json.buildJsonObject { put("uid", kotlinx.serialization.json.JsonPrimitive(uid)) },
        )
        val first = holds.browserStep(click("e260"), page, "book", emptyList()).shouldBeInstanceOf<net.robog.clarity.backend.vault.Hold.Wait>()
        heldStore.transition(first.heldId!!, PENDING_STATUSES, HeldAction.Status.APPROVED) shouldBe true

        holds.browserStep(click("e370"), page, "book", emptyList()) shouldBe net.robog.clarity.backend.vault.Hold.Proceed
        heldStore.get(first.heldId!!)!!.status shouldBe HeldAction.Status.EXECUTED
        // one yes is one click
        holds.browserStep(click("e371"), page, "book", emptyList()).shouldBeInstanceOf<net.robog.clarity.backend.vault.Hold.Wait>()
    }

    test("browser steps match without uids, but a different label or typed value is a different step") {
        fun step(summary: String, input: String) =
            ProposedAction.ExternalTool("k", summary, "browser", "fill_by_uid", ClarityJson.parseToJsonElement(input) as kotlinx.serialization.json.JsonObject)
        val a = step("Type into \"Email\" on x.com", """{"uid":"e1","value":"a"}""")
        net.robog.clarity.backend.vault.sameBrowserStep(a, step("Type into \"Email\" on x.com", """{"uid":"e9","value":"a"}""")) shouldBe true
        net.robog.clarity.backend.vault.sameBrowserStep(a, step("Type into \"Email\" on x.com", """{"uid":"e1","value":"b"}""")) shouldBe false
        net.robog.clarity.backend.vault.sameBrowserStep(a, step("Type into \"Name\" on x.com", """{"uid":"e1","value":"a"}""")) shouldBe false
    }

    test("the action hash is stable and the payload has the documented shape") {
        val a = sample(ActionKind.SEND_EMAIL, "k")
        ApprovalPayload.actionHash(a).length shouldBe 64
        ApprovalPayload.actionHash(a) shouldBe ApprovalPayload.actionHash(a)
        ApprovalPayload.payload(HeldActionId("h"), "abc", "approve") shouldBe "clarity-approval-v1|h|abc|approve"
    }
})
