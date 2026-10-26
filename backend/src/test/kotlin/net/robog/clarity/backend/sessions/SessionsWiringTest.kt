package net.robog.clarity.backend.sessions

import dev.zacsweers.metro.createGraphFactory
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.LocalTime
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
import net.robog.clarity.shared.api.EnrollmentRequest
import net.robog.clarity.shared.api.EnrollmentResponse
import net.robog.clarity.shared.api.Problem
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.session.ModelKeyRequest
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionKind
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.session.SetupStatus
import net.robog.clarity.shared.session.StartSession
import net.robog.clarity.shared.session.Today
import org.testcontainers.postgresql.PostgreSQLContainer

private const val GOOD_KEY = "sk-ant-good-key-0123456789"
private const val DOWN_KEY = "sk-ant-down"
private const val ENROLL_TOKEN = "enroll-token-for-tests"

/**
 * The real application graph: Metro wires the sessions and setup classes (and their replacement
 * bindings), the routes sit behind device authentication, and the model key is validated against a
 * fake Models endpoint, stored, and enables the agents without a restart. Never the real API.
 */
@EnabledIf(ContainerRuntimeAvailable::class)
class SessionsWiringTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    val modelRequests = AtomicInteger()
    lateinit var models: io.ktor.server.engine.EmbeddedServer<*, *>
    lateinit var baseUrl: String
    lateinit var dataDir: java.nio.file.Path

    fun config(apiKey: String? = null) = AppConfig(
        instance = InstanceConfig("Test Clarity"),
        http = HttpConfig(0),
        database = DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4),
        dataDir = dataDir,
        enrollment = EnrollmentConfig(EnrollmentMode.TOKEN, token = Secret(ENROLL_TOKEN)),
        connectors = ConnectorsConfig(autoGrant = true, secretsDir = dataDir.resolve("secrets")),
        agent = AgentConfig(anthropicApiKey = apiKey, reviewInterval = 2.hours, anthropicBaseUrl = baseUrl),
    )

    beforeSpec {
        postgres.start()
        dataDir = Files.createTempDirectory("clarity-sessions").resolve("data")
        models = embeddedServer(Netty, port = 0) {
            routing {
                get("/v1/models") {
                    modelRequests.incrementAndGet()
                    // the SDK presents the key as a bearer token (or x-api-key, depending on the client mode)
                    val key = call.request.headers["x-api-key"] ?: call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
                    when (key) {
                        GOOD_KEY -> call.respondText(
                            """{"data":[{"id":"claude-sonnet-5-5","type":"model","display_name":"Sonnet","created_at":"2026-01-01T00:00:00Z"}],"has_more":false,"first_id":"claude-sonnet-5-5","last_id":"claude-sonnet-5-5"}""",
                            ContentType.Application.Json,
                        )
                        DOWN_KEY -> call.respondText("""{"type":"error","error":{"type":"api_error","message":"overloaded"}}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                        else -> call.respondText(
                            """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""",
                            ContentType.Application.Json, HttpStatusCode.Unauthorized,
                        )
                    }
                }
                // Whatever the lead asks of the model once it has a key: the test never answers it.
                post("/v1/messages") { call.respondText("""{"type":"error","error":{"type":"api_error","message":"no model in tests"}}""", ContentType.Application.Json, HttpStatusCode.InternalServerError) }
            }
        }.start(wait = false)
        baseUrl = "http://127.0.0.1:${models.engine.resolvedConnectors().first().port}"
    }
    afterSpec {
        models.stop(100, 500)
        postgres.stop()
    }

    suspend fun ApplicationTestBuilder.device(): Pair<HttpClient, String> {
        val client = createClient { install(ContentNegotiation) { json(ClarityJson) } }
        val device = TestDevice()
        val enrolled = client.post("/api/v1/enroll") {
            contentType(ContentType.Application.Json)
            setBody(EnrollmentRequest(device.spkiBase64Url, "Pixel", ENROLL_TOKEN))
        }.body<EnrollmentResponse>()
        return client to device.withId(enrolled.deviceId.value).token(Clock.System.now())
    }

    test("settings, setup and sessions are device-only and the graph wires") {
        val graph = createGraphFactory<AppGraph.Factory>().create(config())
        testApplication {
            application { clarity(graph) }
            val (client, jwt) = device()
            fun io.ktor.client.request.HttpRequestBuilder.asDevice() = header(HttpHeaders.Authorization, "Bearer $jwt")

            for (path in listOf("/api/v1/settings", "/api/v1/setup", "/api/v1/sessions", "/api/v1/today")) {
                client.get(path).status shouldBe HttpStatusCode.Unauthorized
            }
            client.put("/api/v1/setup/model-key") { contentType(ContentType.Application.Json); setBody(ModelKeyRequest("x")) }.status shouldBe HttpStatusCode.Unauthorized

            // settings: defaults, then the person's own
            client.get("/api/v1/settings") { asDevice() }.body<Settings>() shouldBe Settings()
            val mine = Settings(
                morningCall = Settings.MorningCall(at = LocalTime(8, 30), days = setOf(1, 3, 5), headsUpMinutes = null, mode = Settings.MorningCall.Mode.NOTIFY),
                homeZone = "Europe/Berlin",
                micAlwaysOn = false,
            )
            client.put("/api/v1/settings") { asDevice(); contentType(ContentType.Application.Json); setBody(mine) }.body<Settings>() shouldBe mine
            client.get("/api/v1/settings") { asDevice() }.body<Settings>() shouldBe mine
            client.put("/api/v1/settings") {
                asDevice(); contentType(ContentType.Application.Json); setBody(mine.copy(homeZone = "Mars/Base"))
            }.status shouldBe HttpStatusCode.BadRequest
            client.get("/api/v1/settings") { asDevice() }.body<Settings>().homeZone shouldBe "Europe/Berlin"

            // sessions
            client.get("/api/v1/sessions?active=true") { asDevice() }.body<List<Session>>().shouldBeEmpty()
            val call = client.post("/api/v1/sessions") {
                asDevice(); contentType(ContentType.Application.Json); setBody(StartSession(SessionKind.MORNING_CALL))
            }
            call.status shouldBe HttpStatusCode.Created
            val started = call.body<Session>()
            started.state shouldBe Session.State.BUSY
            client.get("/api/v1/sessions?active=true") { asDevice() }.body<List<Session>>().map { it.id } shouldBe listOf(started.id)
            client.get("/api/v1/sessions/${started.id}") { asDevice() }.body<Session>().id shouldBe started.id
            client.get("/api/v1/sessions/nope") { asDevice() }.status shouldBe HttpStatusCode.NotFound
            client.get("/api/v1/sessions?active=maybe") { asDevice() }.status shouldBe HttpStatusCode.BadRequest

            val next: SessionControl = SessionControl.Next
            val busy = client.post("/api/v1/sessions/${started.id}/control") { asDevice(); contentType(ContentType.Application.Json); setBody(next) }
            busy.status shouldBe HttpStatusCode.Conflict
            busy.body<Problem>().title shouldBe "Session is busy"

            val snooze: SessionControl = SessionControl.Snooze(15)
            client.post("/api/v1/sessions/${started.id}/control") { asDevice(); contentType(ContentType.Application.Json); setBody(snooze) }
                .body<Session>().state shouldBe Session.State.SNOOZED

            // a held action that does not exist cannot start an amend session
            client.post("/api/v1/sessions") {
                asDevice(); contentType(ContentType.Application.Json); setBody(StartSession(SessionKind.AMEND, HeldActionId("00000000-0000-0000-0000-000000000000")))
            }.status shouldBe HttpStatusCode.BadRequest

            // starting an interview ends the snoozed call: one at a time
            val interview = client.post("/api/v1/sessions") {
                asDevice(); contentType(ContentType.Application.Json); setBody(StartSession(SessionKind.VALUES_INTERVIEW))
            }.body<Session>()
            interview.question shouldBe 0
            interview.questionCount shouldBe 7
            client.get("/api/v1/sessions?active=true") { asDevice() }.body<List<Session>>().map { it.id } shouldBe listOf(interview.id)
            client.get("/api/v1/sessions/${started.id}") { asDevice() }.body<Session>().state shouldBe Session.State.ENDED

            // Today starts empty
            val today = client.get("/api/v1/today") { asDevice() }.body<Today>()
            today.called shouldBe false
            today.priorities.shouldBeEmpty()
            today.heldCount shouldBe 0
        }
    }

    test("the model key is validated with one Models call, stored 0600, never echoed, and survives a restart") {
        val before = modelRequests.get()
        val graph = createGraphFactory<AppGraph.Factory>().create(config())
        testApplication {
            application { clarity(graph) }
            val (client, jwt) = device()
            fun io.ktor.client.request.HttpRequestBuilder.asDevice() = header(HttpHeaders.Authorization, "Bearer $jwt")
            suspend fun put(key: String) = client.put("/api/v1/setup/model-key") { asDevice(); contentType(ContentType.Application.Json); setBody(ModelKeyRequest(key)) }

            val initial = client.get("/api/v1/setup") { asDevice() }.body<SetupStatus>()
            initial.modelKey shouldBe SetupStatus.KeyState.MISSING
            initial.connectorsReady shouldBe 0
            initial.constitutionVersion shouldBe null

            put("   ").status shouldBe HttpStatusCode.BadRequest
            modelRequests.get() shouldBe before // a blank key never reaches Anthropic

            val rejected = put("sk-ant-wrong")
            rejected.status shouldBe HttpStatusCode.UnprocessableEntity
            rejected.bodyAsText() shouldNotContain "sk-ant-wrong"
            client.get("/api/v1/setup") { asDevice() }.body<SetupStatus>().modelKey shouldBe SetupStatus.KeyState.INVALID
            Files.exists(dataDir.resolve("anthropic-api-key")) shouldBe false

            val unreachable = put(DOWN_KEY)
            unreachable.status shouldBe HttpStatusCode.BadGateway
            Files.exists(dataDir.resolve("anthropic-api-key")) shouldBe false

            val accepted = put(GOOD_KEY)
            accepted.status shouldBe HttpStatusCode.OK
            accepted.bodyAsText() shouldNotContain GOOD_KEY
            accepted.body<SetupStatus>().modelKey shouldBe SetupStatus.KeyState.VALID
            client.get("/api/v1/setup") { asDevice() }.body<SetupStatus>().modelKey shouldBe SetupStatus.KeyState.VALID

            val file = dataDir.resolve("anthropic-api-key")
            Files.readString(file).trim() shouldBe GOOD_KEY
            PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe "rw-------"
        }

        // a restart picks the stored key up by itself
        val restarted = createGraphFactory<AppGraph.Factory>().create(config())
        testApplication {
            application { clarity(restarted) }
            val (client, jwt) = device()
            client.get("/api/v1/setup") { header(HttpHeaders.Authorization, "Bearer $jwt") }.body<SetupStatus>().modelKey shouldBe SetupStatus.KeyState.VALID
        }
    }

    test("a key from the server's own secret wins: the app cannot replace it") {
        val graph = createGraphFactory<AppGraph.Factory>().create(config(apiKey = "sk-ant-from-secret"))
        testApplication {
            application { clarity(graph) }
            val (client, jwt) = device()
            fun io.ktor.client.request.HttpRequestBuilder.asDevice() = header(HttpHeaders.Authorization, "Bearer $jwt")
            client.get("/api/v1/setup") { asDevice() }.body<SetupStatus>().modelKey shouldBe SetupStatus.KeyState.VALID
            val before = runCatching { Files.readString(dataDir.resolve("anthropic-api-key")) }.getOrNull()
            val attempt = client.put("/api/v1/setup/model-key") { asDevice(); contentType(ContentType.Application.Json); setBody(ModelKeyRequest(GOOD_KEY)) }
            attempt.status shouldBe HttpStatusCode.Conflict
            attempt.bodyAsText() shouldContain "ANTHROPIC_API_KEY_FILE"
            runCatching { Files.readString(dataDir.resolve("anthropic-api-key")) }.getOrNull() shouldBe before
        }
    }
})
