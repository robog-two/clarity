package net.robog.clarity.backend.attachments

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.writeFully
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.ConnectorPrincipal
import net.robog.clarity.backend.auth.DevicePrincipal
import net.robog.clarity.backend.config.AttachmentsConfig
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.dedup.DedupKey
import net.robog.clarity.backend.governance.OutboxSignals
import net.robog.clarity.backend.ingest.upsertCanonical
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.ExposedNounReader
import net.robog.clarity.backend.reports.reportsAppConfig
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.fetch.FetchFailure
import net.robog.clarity.shared.fetch.FetchPoll
import net.robog.clarity.shared.noun.AttachmentMeta
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.postgresql.PostgreSQLContainer

/** The device route for an attachment and the connector's fetch channel, over HTTP and Postgres. */
@EnabledIf(ContainerRuntimeAvailable::class)
class AttachmentRoutesDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine").withStartupAttempts(3)
    lateinit var database: Database
    val t0 = Instant.parse("2026-11-13T12:00:00Z")
    val me = Handle.email("me@example.com", "Me")
    val content = ByteArray(3_000) { (it % 97).toByte() }
    val limit = 10_000L

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
        transaction(database) {
            exec("INSERT INTO connector (id, manifest) VALUES ('mail', '{\"kind\":\"mail\",\"displayName\":\"Mail\",\"version\":\"1\",\"produces\":[\"message\"],\"fetches\":true}'), ('other', '{\"kind\":\"mail\",\"displayName\":\"Mail\",\"version\":\"1\",\"produces\":[\"message\"],\"fetches\":true}'), ('plain', '{\"kind\":\"plain\",\"displayName\":\"Plain\",\"version\":\"1\",\"produces\":[\"message\"]}')")
            exec("INSERT INTO connector_grant (connector_id, noun, state, updated_at) VALUES ('mail', 'message', 'granted', now()), ('other', 'message', 'granted', now()), ('plain', 'message', 'granted', now())")
        }
    }
    afterSpec { postgres.stop() }
    beforeTest { transaction(database) { exec("TRUNCATE attachment_fetch") } }

    fun message(name: String, attachments: List<AttachmentMeta>): String {
        val payload = MessagePayload(
            MessagePayload.Channel.EMAIL, me, Handle.email("sam@example.com", "Sam Example"), listOf(me), "Report", "see attached", t0,
            messageId = "<$name@example.com>", attachments = attachments,
        )
        val id = transaction(database) { upsertCanonical(payload, DedupKey(MessageDigest.getInstance("SHA-256").digest(name.toByteArray())), t0).id }
        transaction(database) {
            exec("INSERT INTO source_link (connector_id, noun, source_id, entity_id, latest_version) VALUES ('mail', 'message', 'acct/INBOX/1/${name.hashCode().toUInt()}', '$id', '2')")
        }
        return id.toString()
    }

    fun withApp(wait: kotlin.time.Duration = 5_000.milliseconds, cited: Boolean = true, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        val config = reportsAppConfig(Files.createTempDirectory("att")).copy(attachments = AttachmentsConfig(maxBytes = limit, waitTimeout = wait))
        val store = AttachmentFetchStore(database)
        val signals = OutboxSignals()
        val fetcher = AttachmentFetcher(database, store, ExposedNounReader(database), Clock.System, config, signals)
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(ClarityJson) }
            install(Authentication) {
                bearer(AuthProviders.DEVICE) { authenticate { DevicePrincipal(DeviceId("d1"), null) } }
                bearer(AuthProviders.CONNECTOR) { authenticate { token -> ConnectorPrincipal(ConnectorId(token.token)) } }
            }
            AttachmentRoutes(fetcher, store, signals, config, Clock.System) { cited }.install(this)
        }
        block(createClient { })
    }

    suspend fun HttpClient.poll(connector: String) =
        ClarityJson.decodeFromString(FetchPoll.serializer(), get("/ingest/v1/fetches?wait=5") { header(HttpHeaders.Authorization, "Bearer $connector") }.bodyAsText())

    test("the app downloads an attachment: the request goes to the connector, the bytes come back with their name and type") {
        val id = message("download", listOf(AttachmentMeta("2", "Q3 € report.pdf", "application/pdf", 3_000)))
        withApp { http ->
            coroutineScope {
                val download = async { http.get("/api/v1/messages/$id/attachments/2") { header(HttpHeaders.Authorization, "Bearer device") } }
                val request = http.poll("mail").fetches.single()
                request.part shouldBe "2"
                request.maxBytes shouldBe limit
                http.put("/ingest/v1/fetches/${request.id}?filename=${java.net.URLEncoder.encode("Q3 € report.pdf", "UTF-8")}&type=application/pdf") {
                    header(HttpHeaders.Authorization, "Bearer mail")
                    contentType(ContentType.Application.OctetStream)
                    setBody(content)
                }.status shouldBe HttpStatusCode.NoContent
                val response = download.await()
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldBe "application/pdf"
                response.headers[HttpHeaders.ContentDisposition]!! shouldContain "attachment; filename=\""
                response.headers[HttpHeaders.ContentDisposition]!! shouldContain "filename*=UTF-8''Q3%20%E2%82%AC%20report.pdf"
                response.bodyAsBytes().toList() shouldBe content.toList()
            }
            // the second download needs no connector
            http.get("/api/v1/messages/$id/attachments/2") { header(HttpHeaders.Authorization, "Bearer device") }.status shouldBe HttpStatusCode.OK
        }
    }

    test("a message no sources card cites is 404 and the connector is never asked") {
        val id = message("uncited", listOf(AttachmentMeta("2", "a.pdf", "application/pdf", 100)))
        withApp(cited = false) { http ->
            http.get("/api/v1/messages/$id/attachments/2") { header(HttpHeaders.Authorization, "Bearer device") }.status shouldBe HttpStatusCode.NotFound
            transaction(database) { exec("SELECT count(*) FROM attachment_fetch") { rs -> rs.next(); rs.getInt(1) } } shouldBe 0
        }
    }

    test("unknown message, unknown part and a part over the limit are 404, 404 and 413 without asking the connector") {
        val id = message("refused", listOf(AttachmentMeta("2", "a.pdf", "application/pdf", 100), AttachmentMeta("3", "big.zip", "application/zip", limit * 2)))
        withApp { http ->
            fun get(path: String) = kotlinx.coroutines.runBlocking { http.get(path) { header(HttpHeaders.Authorization, "Bearer device") }.status }
            get("/api/v1/messages/${java.util.UUID.randomUUID()}/attachments/2") shouldBe HttpStatusCode.NotFound
            get("/api/v1/messages/not-an-id/attachments/2") shouldBe HttpStatusCode.NotFound
            get("/api/v1/messages/$id/attachments/9") shouldBe HttpStatusCode.NotFound
            get("/api/v1/messages/$id/attachments/..") shouldBe HttpStatusCode.NotFound
            get("/api/v1/messages/$id/attachments/3") shouldBe HttpStatusCode.PayloadTooLarge
            http.get("/ingest/v1/fetches") { header(HttpHeaders.Authorization, "Bearer mail") }.bodyAsText() shouldBe """{"fetches":[]}"""
        }
    }

    test("a connector that does not answer in time is a 504") {
        val id = message("silent", listOf(AttachmentMeta("2", "a.pdf", "application/pdf", 100)))
        withApp(wait = 400.milliseconds) { http ->
            http.get("/api/v1/messages/$id/attachments/2") { header(HttpHeaders.Authorization, "Bearer device") }.status shouldBe HttpStatusCode.GatewayTimeout
        }
    }

    test("a connector's error becomes the matching status, and an upload over the limit is refused with 413") {
        val id = message("errors", listOf(AttachmentMeta("2", "a.pdf", "application/pdf", 100), AttachmentMeta("4", "b.pdf", "application/pdf", 100)))
        withApp { http ->
            coroutineScope {
                val first = async { http.get("/api/v1/messages/$id/attachments/2") { header(HttpHeaders.Authorization, "Bearer device") }.status }
                val request = http.poll("mail").fetches.single()
                http.post("/ingest/v1/fetches/${request.id}/error") {
                    header(HttpHeaders.Authorization, "Bearer mail")
                    contentType(ContentType.Application.Json)
                    setBody(ClarityJson.encodeToString(FetchFailure.serializer(), FetchFailure(FetchFailure.Code.NOT_FOUND, "the message is gone")))
                }.status shouldBe HttpStatusCode.NoContent
                first.await() shouldBe HttpStatusCode.NotFound
            }
            coroutineScope {
                val second = async { http.get("/api/v1/messages/$id/attachments/4") { header(HttpHeaders.Authorization, "Bearer device") }.status }
                val request = http.poll("mail").fetches.single()
                http.put("/ingest/v1/fetches/${request.id}") {
                    header(HttpHeaders.Authorization, "Bearer mail")
                    contentType(ContentType.Application.OctetStream)
                    setBody(ByteArray((limit + 1).toInt()))
                }.status shouldBe HttpStatusCode.PayloadTooLarge
                second.await() shouldBe HttpStatusCode.PayloadTooLarge
            }
        }
    }

    test("another connector cannot see or answer a request that is not its own") {
        val id = message("foreign", listOf(AttachmentMeta("2", "a.pdf", "application/pdf", 100)))
        withApp { http ->
            coroutineScope {
                val download = async { http.get("/api/v1/messages/$id/attachments/2") { header(HttpHeaders.Authorization, "Bearer device") }.status }
                delay(300)
                http.get("/ingest/v1/fetches") { header(HttpHeaders.Authorization, "Bearer other") }.bodyAsText() shouldBe """{"fetches":[]}"""
                val request = http.poll("mail").fetches.single()
                http.put("/ingest/v1/fetches/${request.id}") {
                    header(HttpHeaders.Authorization, "Bearer other")
                    setBody(content)
                }.status shouldBe HttpStatusCode.NotFound
                http.post("/ingest/v1/fetches/${request.id}/error") {
                    header(HttpHeaders.Authorization, "Bearer other")
                    contentType(ContentType.Application.Json)
                    setBody(ClarityJson.encodeToString(FetchFailure.serializer(), FetchFailure(FetchFailure.Code.FAILED, "x")))
                }.status shouldBe HttpStatusCode.NotFound
                http.put("/ingest/v1/fetches/not-a-uuid") { header(HttpHeaders.Authorization, "Bearer mail"); setBody(content) }.status shouldBe HttpStatusCode.NotFound
                http.put("/ingest/v1/fetches/${request.id}") { header(HttpHeaders.Authorization, "Bearer mail"); setBody(content) }.status shouldBe HttpStatusCode.NoContent
                download.await() shouldBe HttpStatusCode.OK
            }
        }
    }

    test("an upload with no Content-Length that goes over the limit is refused after the limit, and fails the download") {
        val id = message("chunked", listOf(AttachmentMeta("2", "a.pdf", "application/pdf", 100)))
        withApp { http ->
            coroutineScope {
                val download = async { http.get("/api/v1/messages/$id/attachments/2") { header(HttpHeaders.Authorization, "Bearer device") }.status }
                val request = http.poll("mail").fetches.single()
                http.put("/ingest/v1/fetches/${request.id}") {
                    header(HttpHeaders.Authorization, "Bearer mail")
                    setBody(object : io.ktor.http.content.OutgoingContent.WriteChannelContent() {
                        override val contentType = ContentType.Application.OctetStream
                        override suspend fun writeTo(channel: io.ktor.utils.io.ByteWriteChannel) {
                            repeat(3) { channel.writeFully(ByteArray((limit / 2).toInt() + 1)) }
                        }
                    })
                }.status shouldBe HttpStatusCode.PayloadTooLarge
                download.await() shouldBe HttpStatusCode.PayloadTooLarge
            }
        }
    }

    test("an upload that fits is stored even when it is sent in chunks") {
        val id = message("chunked-ok", listOf(AttachmentMeta("2", "a.pdf", "application/pdf", 100)))
        withApp { http ->
            coroutineScope {
                val download = async { http.get("/api/v1/messages/$id/attachments/2") { header(HttpHeaders.Authorization, "Bearer device") } }
                val request = http.poll("mail").fetches.single()
                http.put("/ingest/v1/fetches/${request.id}?type=application/pdf") {
                    header(HttpHeaders.Authorization, "Bearer mail")
                    setBody(object : io.ktor.http.content.OutgoingContent.WriteChannelContent() {
                        override val contentType = ContentType.Application.OctetStream
                        override suspend fun writeTo(channel: io.ktor.utils.io.ByteWriteChannel) { channel.writeFully(content) }
                    })
                }.status shouldBe HttpStatusCode.NoContent
                download.await().bodyAsBytes().toList() shouldBe content.toList()
            }
        }
    }

    test("the routes need the right kind of login") {
        withApp { http ->
            http.get("/api/v1/messages/x/attachments/2").status shouldBe HttpStatusCode.Unauthorized
            http.get("/ingest/v1/fetches").status shouldBe HttpStatusCode.Unauthorized
        }
    }
})
