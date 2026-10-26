package net.robog.clarity.backend.governance

import dev.zacsweers.metro.createGraphFactory
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import net.robog.clarity.backend.auth.TestDevice
import net.robog.clarity.backend.clarity
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.AppConfig
import net.robog.clarity.backend.config.ConnectorsConfig
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.EnrollmentConfig
import net.robog.clarity.backend.config.EnrollmentMode
import net.robog.clarity.backend.config.HttpConfig
import net.robog.clarity.backend.config.InstanceConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.di.AppGraph
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.action.ActionPoll
import net.robog.clarity.shared.action.ActionResult
import net.robog.clarity.shared.api.EnrollmentRequest
import net.robog.clarity.shared.api.EnrollmentResponse
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.ActivityEntry
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.noun.Noun
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * The real application graph: proves Metro wires the governance classes, that the routes sit behind
 * the real device/connector authentication, and that V3 migrates on top of V1/V2. With no model key
 * the guard model fails, so every proposal must ask rather than act.
 */
@EnabledIf(ContainerRuntimeAvailable::class)
class GovernanceWiringTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    val key = "gov-key-" + java.util.UUID.randomUUID()
    lateinit var config: AppConfig

    beforeSpec {
        postgres.start()
        val dir = Files.createTempDirectory("clarity-gov")
        Files.createDirectories(dir.resolve("secrets")).resolve("connector_mail_key").writeText("$key\n")
        config = AppConfig(
            instance = InstanceConfig("Test Clarity"),
            http = HttpConfig(0),
            database = DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4),
            dataDir = dir.resolve("data"),
            enrollment = EnrollmentConfig(EnrollmentMode.TOFU, token = null),
            connectors = ConnectorsConfig(autoGrant = true, secretsDir = dir.resolve("secrets")),
            agent = AgentConfig(anthropicApiKey = null, reviewInterval = 2.hours),
        )
    }
    afterSpec { postgres.stop() }

    test("routes are authenticated, the graph builds, and the action channel works for a provisioned connector") {
        val graph = createGraphFactory<AppGraph.Factory>().create(config)
        testApplication {
            application { clarity(graph) }
            val client = createClient { install(ContentNegotiation) { json(ClarityJson) } }

            for (path in listOf("/api/v1/constitution", "/api/v1/held", "/api/v1/activity", "/ingest/v1/actions")) {
                client.get(path).status shouldBe HttpStatusCode.Unauthorized
            }

            val device = TestDevice()
            val enrolled = client.post("/api/v1/enroll") {
                contentType(ContentType.Application.Json)
                setBody(EnrollmentRequest(device.spkiBase64Url, "Pixel"))
            }.body<EnrollmentResponse>()
            val jwt = device.withId(enrolled.deviceId.value).token(Clock.System.now())
            fun io.ktor.client.request.HttpRequestBuilder.asDevice() = header(HttpHeaders.Authorization, "Bearer $jwt")

            client.get("/api/v1/constitution") { asDevice() }.status shouldBe HttpStatusCode.NotFound
            client.post("/api/v1/constitution") {
                asDevice(); contentType(ContentType.Application.Json)
                setBody(Constitution(1, listOf(line("v1")), updatedAt = T0))
            }.status shouldBe HttpStatusCode.Created
            client.get("/api/v1/constitution") { asDevice() }.body<Constitution>().version shouldBe 1
            client.get("/api/v1/held") { asDevice() }.body<List<HeldAction>>().shouldBeEmpty()
            client.get("/api/v1/activity") { asDevice() }.body<List<ActivityEntry>>().shouldBeEmpty()

            // the gate from the real graph: no model key means the guard cannot double-check, so it asks
            val outcome = graph.actionGate.propose(
                sample(ActionKind.SAVE_DRAFT), "draft", emptyList(), trusted(), net.robog.clarity.shared.governance.Urgency.NORMAL,
            )
            (outcome is ProposalOutcome.Held) shouldBe true

            // a provisioned connector announces what it handles; an approved action reaches only it
            client.put("/ingest/v1/manifest") {
                header(HttpHeaders.Authorization, "Bearer $key"); contentType(ContentType.Application.Json)
                setBody(ConnectorManifest("mail", "Mail", "1", setOf(Noun.MESSAGE), handles = setOf(ActionKind.SAVE_DRAFT)))
            }.status shouldBe HttpStatusCode.NoContent
            val held = client.get("/api/v1/held") { asDevice() }.body<List<HeldAction>>().single()
            client.post("/api/v1/held/${held.id}/decision") {
                asDevice(); contentType(ContentType.Application.Json)
                setBody("""{"type":"approve"}""")
            }.status shouldBe HttpStatusCode.OK

            val polled = client.get("/ingest/v1/actions?wait=2") { header(HttpHeaders.Authorization, "Bearer $key") }.body<ActionPoll>()
            polled.actions.single().action.kind shouldBe ActionKind.SAVE_DRAFT
            client.post("/ingest/v1/actions/${polled.actions.single().id}/result") {
                header(HttpHeaders.Authorization, "Bearer $key"); contentType(ContentType.Application.Json)
                setBody(ActionResult(ActionOutcome.Done()))
            }.status shouldBe HttpStatusCode.NoContent
            client.get("/api/v1/held/${held.id}") { asDevice() }.body<HeldAction>().status shouldBe HeldAction.Status.EXECUTED
        }
    }
})
