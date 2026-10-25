package net.robog.clarity.backend.integration

import dev.zacsweers.metro.createGraphFactory
import io.kotest.core.annotation.Condition
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.reflect.KClass
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import net.robog.clarity.backend.persistence.ExposedNounReader
import net.robog.clarity.backend.persistence.Messages
import net.robog.clarity.backend.persistence.TimeRange
import net.robog.clarity.backend.triggers.ExposedTriggerStore
import net.robog.clarity.shared.query.RecordQuery
import net.robog.clarity.shared.query.TriggerSpec
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.api.EnrollmentRequest
import net.robog.clarity.shared.api.EnrollmentResponse
import net.robog.clarity.shared.api.InstanceInfo
import net.robog.clarity.shared.api.Problem
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.connector.ConnectorSummary
import net.robog.clarity.shared.connector.GrantState
import net.robog.clarity.shared.ingest.IngestBatch
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.ingest.IngestResponse
import net.robog.clarity.shared.ingest.RecordOutcome
import net.robog.clarity.shared.connector.ConnectorTestReport
import net.robog.clarity.shared.connector.ConnectorTestRequest
import net.robog.clarity.shared.noun.ContactPayload
import net.robog.clarity.shared.noun.DocumentPayload
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Noun
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer

/** Integration tests are skipped (not failed) on machines without a container runtime. */
class ContainerRuntimeAvailable : Condition {
    override fun evaluate(kclass: KClass<out Spec>): Boolean =
        runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
}

private val keyA = "key-a-" + UUID.randomUUID()
private val keyB = "key-b-" + UUID.randomUUID()
private val sent = Instant.parse("2026-03-01T10:00:00Z")

private fun message(body: String, sender: String = "alice@example.com") = MessagePayload(
    channel = MessagePayload.Channel.EMAIL,
    account = Handle.email("me@example.com"),
    sender = Handle.email(sender),
    recipients = listOf(Handle.email("bob@example.com"), Handle.email("carol@example.com")),
    subject = "Lunch",
    body = body,
    sentAt = sent,
)

private fun upsert(id: String, version: String, payload: net.robog.clarity.shared.noun.Payload) =
    IngestRecord.Upsert(SourceId(id), version, Clock.System.now(), payload)

@EnabledIf(ContainerRuntimeAvailable::class)
class ClarityIntegrationTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var dir: Path

    beforeSpec {
        postgres.start()
        dir = Files.createTempDirectory("clarity-it")
        val secrets = Files.createDirectories(dir.resolve("secrets"))
        secrets.resolve("connector_a_key").writeText("$keyA\n")
        secrets.resolve("connector_b_key").writeText("$keyB\n")
    }
    afterSpec { postgres.stop() }

    fun config() = AppConfig(
        instance = InstanceConfig("Test Clarity"),
        http = HttpConfig(0),
        database = DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4),
        dataDir = dir.resolve("data"),
        enrollment = EnrollmentConfig(EnrollmentMode.TOFU, token = null),
        connectors = ConnectorsConfig(autoGrant = true, secretsDir = dir.resolve("secrets")),
        agent = AgentConfig(anthropicApiKey = null, reviewInterval = 2.hours),
    )

    fun ApplicationTestBuilder.jsonClient(): HttpClient =
        createClient { install(ContentNegotiation) { json(ClarityJson) } }

    suspend fun HttpResponse.problem(): Problem = ClarityJson.decodeFromString(Problem.serializer(), bodyAsText())

    test("boots, enrolls a device, authenticates it, and ingests with cross-connector dedup") {
        val graph = createGraphFactory<AppGraph.Factory>().create(config())
        testApplication {
            application { clarity(graph) }
            val client = jsonClient()

            // --- liveness / readiness
            client.get("/healthz").status shouldBe HttpStatusCode.OK
            client.get("/readyz").status shouldBe HttpStatusCode.OK

            // --- unauthenticated access is a problem+json 401
            val unauth = client.get("/api/v1/instance")
            unauth.status shouldBe HttpStatusCode.Unauthorized
            unauth.contentType()?.withoutParameters() shouldBe ContentType.Application.ProblemJson
            unauth.problem().status shouldBe 401

            // --- enrollment: first device is accepted (TOFU), the next one needs the token
            val device = TestDevice()
            val enrolled = client.post("/api/v1/enroll") {
                contentType(ContentType.Application.Json)
                setBody(EnrollmentRequest(device.spkiBase64Url, "Pixel"))
            }
            enrolled.status shouldBe HttpStatusCode.OK
            val enrollment = enrolled.body<EnrollmentResponse>()
            enrollment.instance.name shouldBe "Test Clarity"

            val second = TestDevice()
            client.post("/api/v1/enroll") {
                contentType(ContentType.Application.Json)
                setBody(EnrollmentRequest(second.spkiBase64Url, "Tablet"))
            }.status shouldBe HttpStatusCode.Forbidden

            val token = config().dataDir.resolve("enrollment-token").readText().trim()
            client.post("/api/v1/enroll") {
                contentType(ContentType.Application.Json)
                setBody(EnrollmentRequest(second.spkiBase64Url, "Tablet", token))
            }.status shouldBe HttpStatusCode.OK

            client.post("/api/v1/enroll") {
                contentType(ContentType.Application.Json)
                setBody(EnrollmentRequest(TestDevice(TestDevice.generate("secp384r1")).spkiBase64Url, "Bad", token))
            }.status shouldBe HttpStatusCode.BadRequest

            // --- device JWT authenticates GET /api/v1/instance
            val deviceId = enrollment.deviceId.value
            fun bearer(jwt: String) = "Bearer $jwt"
            val jwt = device.withId(deviceId).token(Clock.System.now())
            val instance = client.get("/api/v1/instance") { header(HttpHeaders.Authorization, bearer(jwt)) }
            instance.status shouldBe HttpStatusCode.OK
            instance.body<InstanceInfo>().name shouldBe "Test Clarity"

            // a token for an unknown device, or with an over-long lifetime, is refused
            client.get("/api/v1/instance") {
                header(HttpHeaders.Authorization, bearer(device.token(Clock.System.now())))
            }.status shouldBe HttpStatusCode.Unauthorized
            client.get("/api/v1/instance") {
                header(HttpHeaders.Authorization, bearer(device.withId(deviceId).token(Clock.System.now(), lifetimeSeconds = 900)))
            }.status shouldBe HttpStatusCode.Unauthorized

            // --- provisioned connectors announce manifests; autoGrant grants the produced noun
            val manifest = ConnectorManifest(kind = "test", displayName = "Test", version = "1", produces = setOf(Noun.MESSAGE))
            for (key in listOf(keyA, keyB)) {
                client.put("/ingest/v1/manifest") {
                    header(HttpHeaders.Authorization, bearer(key))
                    contentType(ContentType.Application.Json)
                    setBody(manifest)
                }.status shouldBe HttpStatusCode.NoContent
            }
            val summaries = client.get("/api/v1/connectors") { header(HttpHeaders.Authorization, bearer(jwt)) }
                .body<List<ConnectorSummary>>()
            summaries.map { it.id.value } shouldBe listOf("a", "b")
            summaries.first().grants shouldBe mapOf(Noun.MESSAGE to GrantState.GRANTED)

            suspend fun push(key: String, vararg records: IngestRecord): List<RecordOutcome> =
                client.post("/ingest/v1/batches") {
                    header(HttpHeaders.Authorization, bearer(key))
                    contentType(ContentType.Application.Json)
                    setBody(IngestBatch(records.toList()))
                }.also { it.status shouldBe HttpStatusCode.OK }.body<IngestResponse>().results.map { it.outcome }

            // --- the same message from two connectors dedups into one entity
            val first = push(keyA, upsert("uid-1", "v1", message("Lunch tomorrow?")))
            val created = first.single().shouldBeInstanceOf<RecordOutcome.Created>()
            val merged = push(keyB, upsert("imap-77", "etag1", message("  Lunch\n tomorrow?  ", sender = " ALICE@example.com ")))
                .single().shouldBeInstanceOf<RecordOutcome.Merged>()
            merged.entityId shouldBe created.entityId

            // --- exact replay is idempotent
            push(keyA, upsert("uid-1", "v1", message("Lunch tomorrow?")))
                .single() shouldBe RecordOutcome.Unchanged(created.entityId)

            // --- the canonical read model sees one message; participant lookups use the jsonb index
            val db = Database.connect(graph.dataSource)
            val reader = ExposedNounReader(db)
            val range = TimeRange(sent - 1.hours, sent + 1.hours)
            reader.messages(range).single().payload.body shouldBe "Lunch tomorrow?"
            reader.messages(range, participant = "CAROL@example.com").size shouldBe 1
            reader.messages(range, participant = "zed@example.com") shouldBe emptyList()
            reader.changedSince(Instant.fromEpochSeconds(0)).size shouldBe 1

            // --- trigger store round trip
            val triggers = ExposedTriggerStore(db, Clock.System)
            val spec = TriggerSpec("lunch", Noun.MESSAGE, RecordQuery.Contains("body", "lunch"), "reply yes")
            val stored = triggers.create(spec)
            triggers.enabledFor(Noun.MESSAGE).map { it.spec } shouldBe listOf(spec)
            triggers.enabledFor(Noun.CONTACT) shouldBe emptyList()
            triggers.delete(stored.id) shouldBe true
            triggers.delete(stored.id) shouldBe false
            triggers.list() shouldBe emptyList()

            // --- an ungranted noun is rejected
            push(keyA, upsert("c-1", "1", ContactPayload("Alice", listOf(Handle.email("alice@example.com")))))
                .single().shouldBeInstanceOf<RecordOutcome.Rejected>().reason shouldContain "not granted"

            transaction(db) { Messages.selectAll().count() } shouldBe 1L

            // --- tombstone from one connector keeps the entity alive while the other still has it
            push(keyA, IngestRecord.Tombstone(SourceId("uid-1"), "v2", Clock.System.now(), Noun.MESSAGE))
                .single() shouldBe RecordOutcome.Removed
            transaction(db) {
                Messages.selectAll().where { Messages.deletedAt.isNull() }.count()
            } shouldBe 1L
            push(keyB, IngestRecord.Tombstone(SourceId("imap-77"), "etag2", Clock.System.now(), Noun.MESSAGE))
                .single() shouldBe RecordOutcome.Removed
            transaction(db) {
                Messages.selectAll().where { Messages.deletedAt.isNull() }.count()
            } shouldBe 0L

            // --- batch size limit
            val tooMany = List(IngestBatch.MAX_RECORDS + 1) { upsert("x$it", "1", message("m$it")) }
            val big = client.post("/ingest/v1/batches") {
                header(HttpHeaders.Authorization, bearer(keyA))
                contentType(ContentType.Application.Json)
                setBody(IngestBatch(tooMany))
            }
            big.status shouldBe HttpStatusCode.PayloadTooLarge
            big.problem().status shouldBe 413

            // --- connector config with ETag
            val etag0 = client.get("/ingest/v1/config") { header(HttpHeaders.Authorization, bearer(keyA)) }
                .headers[HttpHeaders.ETag]
            etag0 shouldBe "\"0\""
            client.get("/ingest/v1/config") {
                header(HttpHeaders.Authorization, bearer(keyA))
                header(HttpHeaders.IfNoneMatch, etag0)
            }.status shouldBe HttpStatusCode.NotModified
            val put = client.put("/api/v1/connectors/a/config") {
                header(HttpHeaders.Authorization, bearer(jwt))
                contentType(ContentType.Application.Json)
                setBody(JsonObject(mapOf("folder" to JsonPrimitive("INBOX"))))
            }
            put.body<ConnectorConfig>().version shouldBe 1
            val refreshed = client.get("/ingest/v1/config") {
                header(HttpHeaders.Authorization, bearer(keyA))
                header(HttpHeaders.IfNoneMatch, etag0)
            }
            refreshed.status shouldBe HttpStatusCode.OK
            refreshed.headers[HttpHeaders.ETag] shouldBe "\"1\""

            // --- unknown connector -> 404 problem; bad id -> 400
            client.put("/api/v1/connectors/nope/grants") {
                header(HttpHeaders.Authorization, bearer(jwt))
                contentType(ContentType.Application.Json)
                setBody(mapOf(Noun.MESSAGE to GrantState.GRANTED))
            }.status shouldBe HttpStatusCode.NotFound
            client.get("/api/v1/connectors/BAD_ID/config") { header(HttpHeaders.Authorization, bearer(jwt)) }
                .status shouldBe HttpStatusCode.BadRequest

            // --- the phone as a connector: device JWT on /ingest is connector device-<id>, ungranted at first
            val phone = ConnectorId("device-$deviceId")
            val phoneMessage = upsert("sms-1", "1", message("From the phone"))
            fun phoneBatch() = IngestBatch(listOf(phoneMessage))
            suspend fun phonePush(): RecordOutcome = client.post("/ingest/v1/batches") {
                header(HttpHeaders.Authorization, bearer(device.withId(deviceId).token(Clock.System.now())))
                contentType(ContentType.Application.Json)
                setBody(phoneBatch())
            }.body<IngestResponse>().results.single().outcome
            phonePush().shouldBeInstanceOf<RecordOutcome.Rejected>()
            client.put("/api/v1/connectors/$phone/grants") {
                header(HttpHeaders.Authorization, bearer(jwt))
                contentType(ContentType.Application.Json)
                setBody(mapOf(Noun.MESSAGE to GrantState.GRANTED))
            }.status shouldBe HttpStatusCode.OK
            // different version so the earlier rejected attempt is not an idempotent replay
            val granted = client.post("/ingest/v1/batches") {
                header(HttpHeaders.Authorization, bearer(device.withId(deviceId).token(Clock.System.now())))
                contentType(ContentType.Application.Json)
                setBody(IngestBatch(listOf(upsert("sms-1", "2", message("From the phone")))))
            }.body<IngestResponse>().results.single().outcome
            granted.shouldBeInstanceOf<RecordOutcome.Created>()
        }
    }

    test("documents are ingested, deduplicated by store and path, and searchable; connector tests round-trip") {
        val graph = createGraphFactory<AppGraph.Factory>().create(config())
        testApplication {
            application { clarity(graph) }
            val client = jsonClient()
            val device = TestDevice()
            // the first test enrolled a device already (TOFU), so this one needs the enrollment token
            val token = config().dataDir.resolve("enrollment-token").readText().trim()
            val deviceId = client.post("/api/v1/enroll") {
                contentType(ContentType.Application.Json)
                setBody(EnrollmentRequest(device.spkiBase64Url, "Tablet", token))
            }.also { it.status shouldBe HttpStatusCode.OK }.body<EnrollmentResponse>().deviceId.value
            val appAuth = "Bearer " + device.withId(deviceId).token(Clock.System.now())

            val manifest = ConnectorManifest(kind = "files", displayName = "Files", version = "1", produces = setOf(Noun.DOCUMENT))
            client.put("/ingest/v1/manifest") {
                header(HttpHeaders.Authorization, "Bearer $keyA")
                contentType(ContentType.Application.Json)
                setBody(manifest)
            }.status shouldBe HttpStatusCode.NoContent

            suspend fun push(vararg records: IngestRecord): List<RecordOutcome> =
                client.post("/ingest/v1/batches") {
                    header(HttpHeaders.Authorization, "Bearer $keyA")
                    contentType(ContentType.Application.Json)
                    setBody(IngestBatch(records.toList()))
                }.also { it.status shouldBe HttpStatusCode.OK }.body<IngestResponse>().results.map { it.outcome }

            fun doc(path: String, excerpt: String) = DocumentPayload(
                store = "webdav:https://cloud.example/dav", path = path, title = path.substringAfterLast('/'),
                mimeType = "text/markdown", size = 42, modifiedAt = sent, excerpt = excerpt,
            )

            val created = push(upsert("/Notes/plan.md", "etag1", doc("/Notes/plan.md", "Quarterly roadmap and hiring plan")))
                .single().shouldBeInstanceOf<RecordOutcome.Created>()
            // same file reported with a messy path and a newer version merges into the same entity
            push(upsert("/Notes//plan.md", "etag2", doc("Notes//plan.md/", "Quarterly roadmap, hiring plan and budget")))
                .single().shouldBeInstanceOf<RecordOutcome.Merged>().entityId shouldBe created.entityId
            push(upsert("/Notes/other.txt", "e1", doc("/Notes/other.txt", "Grocery list"))).single()
                .shouldBeInstanceOf<RecordOutcome.Created>()

            val db = Database.connect(graph.dataSource)
            val reader = ExposedNounReader(db)
            val hits = reader.searchDocuments("quarterly budg")
            hits.map { it.payload.path } shouldBe listOf("/Notes/plan.md")
            reader.searchDocuments("roadmap").single().id shouldBe EntityId(created.entityId.value)
            reader.searchDocuments("zebra") shouldBe emptyList()
            reader.searchDocuments("!!!") shouldBe emptyList()
            reader.document(EntityId(created.entityId.value))!!.payload.excerpt shouldContain "budget"
            reader.document(EntityId("not-a-uuid")) shouldBe null

            push(IngestRecord.Tombstone(SourceId("/Notes/other.txt"), "e2", Clock.System.now(), Noun.DOCUMENT))
                .single() shouldBe RecordOutcome.Removed
            reader.searchDocuments("grocery") shouldBe emptyList()

            // --- connection test round trip
            suspend fun pending(): HttpResponse = client.get("/ingest/v1/test") { header(HttpHeaders.Authorization, "Bearer $keyA") }
            pending().status shouldBe HttpStatusCode.NoContent
            client.post("/api/v1/connectors/nope/test") { header(HttpHeaders.Authorization, appAuth) }.status shouldBe HttpStatusCode.NotFound
            val asked = client.post("/api/v1/connectors/a/test") { header(HttpHeaders.Authorization, appAuth) }
            asked.status shouldBe HttpStatusCode.Accepted
            val request = asked.body<ConnectorTestRequest>()
            pending().body<ConnectorTestRequest>().requestId shouldBe request.requestId
            // a stale report is refused, the right one is accepted and clears the request
            client.post("/ingest/v1/test") {
                header(HttpHeaders.Authorization, "Bearer $keyA")
                contentType(ContentType.Application.Json)
                setBody(ConnectorTestReport("stale", true, "old", emptyMap(), Clock.System.now()))
            }.status shouldBe HttpStatusCode.Conflict
            client.post("/ingest/v1/test") {
                header(HttpHeaders.Authorization, "Bearer $keyA")
                contentType(ContentType.Application.Json)
                setBody(ConnectorTestReport(request.requestId, true, "Connected, 2 calendars", mapOf("calendars" to 2), Clock.System.now()))
            }.status shouldBe HttpStatusCode.NoContent
            pending().status shouldBe HttpStatusCode.NoContent
            val listed = client.get("/api/v1/connectors") { header(HttpHeaders.Authorization, appAuth) }.body<List<ConnectorSummary>>()
            listed.first { it.id.value == "a" }.lastTest!!.run {
                ok shouldBe true
                message shouldBe "Connected, 2 calendars"
                counts shouldBe mapOf("calendars" to 2)
            }
            listed.first { it.id.value == "b" }.lastTest shouldBe null
        }
    }
})
