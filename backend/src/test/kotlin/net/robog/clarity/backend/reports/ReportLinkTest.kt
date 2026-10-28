package net.robog.clarity.backend.reports

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import net.robog.clarity.backend.agent.SettingsProvider
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.DevicePrincipal
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.report.ReportDetail
import net.robog.clarity.shared.report.ReportFormat
import net.robog.clarity.shared.report.ReportKind
import net.robog.clarity.shared.report.ReportLink
import net.robog.clarity.shared.report.ReportStyle
import net.robog.clarity.shared.session.Settings
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

/** The token's shape and the page, as pure functions. */
class ReportLinkPureTest : FunSpec({
    test("a token is a lookup and a 144-bit secret, and nothing else parses as one") {
        val secret = ByteArray(LINK_SECRET_BYTES) { it.toByte() }
        val token = linkToken("AAAAAAAAAAAA", secret)
        token.length shouldBe 36
        val (lookup, back) = splitLinkToken(token).shouldNotBeNull()
        lookup shouldBe "AAAAAAAAAAAA"
        back.toList() shouldBe secret.toList()
        (LINK_SECRET_BYTES * 8 >= 128) shouldBe true
        listOf("", "short", token + "A", token.dropLast(1), token.replaceRange(3, 4, "/"), "../" + token.drop(3)).forEach { splitLinkToken(it).shouldBeNull() }
        linkPath(token) shouldBe "/g/$token"
    }

    test("the page escapes what it shows and carries only the title, the date and the downloads") {
        val page = goodsPage("<script>alert(1)</script> & \"Devils\"", "Updated October 28, 2026", listOf(PageFormat("Word / Pages", "/g/t/docx", ".docx")))
        page shouldNotContain "<script>"
        page shouldContain "&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;Devils&quot;"
        page shouldContain """href="/g/t/docx""""
        page shouldContain "Word / Pages"
        page shouldNotContain "http" // no external fonts, scripts or links
        updatedLine(Instant.parse("2026-10-28T03:00:00Z"), TimeZone.of("America/New_York")) shouldBe "Updated October 27, 2026"
    }
})

/** The secret link against real Postgres: one live link, revocation, and what the public routes reach. */
@EnabledIf(ContainerRuntimeAvailable::class)
class ReportLinkTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }

    suspend fun fresh(): Pair<ExposedReportStore, ExposedReportLinkStore> {
        database.tx { exec("TRUNCATE report, report_revision, report_rendition, report_source, report_link RESTART IDENTITY CASCADE") }
        return ExposedReportStore(database, Clock.System) to ExposedReportLinkStore(database, Clock.System)
    }

    test("V26: one live link per Good, the same one each time, and a new one after turning it off") {
        val (store, links) = fresh()
        val (report, _) = store.create(ReportKind.DOCUMENT, "The Devils", "# Background", ReportStyle(), null)
        links.live(report.id).shouldBeNull()
        val first = links.ensure(report.id).shouldNotBeNull()
        links.ensure(report.id) shouldBe first
        links.live(report.id) shouldBe first
        links.resolve(first) shouldBe report.id
        links.revoke(report.id) shouldBe true
        links.revoke(report.id) shouldBe false
        links.resolve(first).shouldBeNull()
        val second = links.ensure(report.id).shouldNotBeNull()
        second shouldNotBe first
        links.resolve(first).shouldBeNull()
        links.resolve(second) shouldBe report.id
        links.ensure("00000000-0000-0000-0000-000000000000").shouldBeNull()
        // the right lookup with a wrong secret opens nothing
        links.resolve(second.take(12) + "A".repeat(24)).shouldBeNull()
    }

    test("the routes: device auth on every report route; the token opens only its own Good; revoked and unknown are 404") {
        val (store, links) = fresh()
        val (devils, _) = store.create(ReportKind.DOCUMENT, "The Devils", "# Background\n\nRussell.", ReportStyle(), null)
        val (flu, _) = store.create(ReportKind.DOCUMENT, "The 1918 flu", "# Causes\n\nSpread.", ReportStyle(), null)
        val renderer = CountingRenderer()
        val service = ReportService(store, renderer, ReportBlobs(reportsAppConfig(Files.createTempDirectory("links"))), Clock.System)
        testApplication {
            application {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(ClarityJson) }
                install(Authentication) { bearer(AuthProviders.DEVICE) { authenticate { DevicePrincipal(DeviceId("d1"), null) } } }
                ReportRoutes(service, store, links).install(this)
                ReportLinkRoutes(service, store, links, SettingsProvider { Settings() }).install(this)
            }
            val client = createClient { }
            val auth: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header(HttpHeaders.Authorization, "Bearer device") }

            // Everything under /api/v1/reports needs the device.
            val id = devils.id
            listOf("/api/v1/reports", "/api/v1/reports/$id", "/api/v1/reports/$id/source", "/api/v1/reports/$id/download/pdf").forEach {
                client.get(it).status shouldBe HttpStatusCode.Unauthorized
            }
            client.post("/api/v1/reports/$id/link").status shouldBe HttpStatusCode.Unauthorized
            client.delete("/api/v1/reports/$id/link").status shouldBe HttpStatusCode.Unauthorized
            links.live(id).shouldBeNull()

            val made = client.post("/api/v1/reports/$id/link", auth)
            made.status shouldBe HttpStatusCode.OK
            val path = ClarityJson.decodeFromString(ReportLink.serializer(), made.bodyAsText()).path
            path shouldStartWith "/g/"
            ClarityJson.decodeFromString(ReportLink.serializer(), client.post("/api/v1/reports/$id/link", auth).bodyAsText()).path shouldBe path
            ClarityJson.decodeFromString(ReportDetail.serializer(), client.get("/api/v1/reports/$id", auth).bodyAsText()).link shouldBe path
            ClarityJson.decodeFromString(ReportDetail.serializer(), client.get("/api/v1/reports/${flu.id}", auth).bodyAsText()).link.shouldBeNull()
            client.post("/api/v1/reports/00000000-0000-0000-0000-000000000000/link", auth).status shouldBe HttpStatusCode.NotFound

            // The page: no auth, only this Good, the headers that keep it private.
            val page = client.get(path)
            page.status shouldBe HttpStatusCode.OK
            page.headers["Referrer-Policy"] shouldBe "no-referrer"
            page.headers["X-Robots-Tag"]!! shouldContain "noindex"
            page.headers[HttpHeaders.CacheControl]!! shouldContain "no-store"
            page.headers["Content-Security-Policy"]!! shouldContain "default-src 'none'"
            val html = page.bodyAsText()
            html shouldContain "The Devils"
            html shouldNotContain "1918"
            html shouldNotContain "Russell." // the text itself is not on the page
            ReportFormat.DOCUMENT.forEach { html shouldContain "$path/${it.wireName}" }
            File("build/goods-page.html").writeText(html) // for looking at it in a browser

            val pdf = client.get("$path/pdf")
            pdf.status shouldBe HttpStatusCode.OK
            pdf.headers[HttpHeaders.ContentDisposition]!! shouldContain "The Devils.pdf"
            pdf.headers["Referrer-Policy"] shouldBe "no-referrer"
            pdf.bodyAsBytes().decodeToString() shouldBe "pdf:${"# Background\n\nRussell.".length}:v1"
            client.get("$path/md").bodyAsText() shouldBe "# Background\n\nRussell."
            client.get("$path/pages").status shouldBe HttpStatusCode.NotFound
            client.get(GOODS_FONT_PATH).status shouldBe HttpStatusCode.OK

            // Unknown, malformed and turned-off tokens: a plain 404 page.
            val token = path.removePrefix("/g/")
            client.get("/g/" + token.take(12) + "B".repeat(24)).status shouldBe HttpStatusCode.NotFound
            client.get("/g/not-a-token").status shouldBe HttpStatusCode.NotFound
            client.get("/g/not-a-token/pdf").status shouldBe HttpStatusCode.NotFound
            client.delete("/api/v1/reports/$id/link", auth).status shouldBe HttpStatusCode.NoContent
            val gone = client.get(path)
            gone.status shouldBe HttpStatusCode.NotFound
            gone.bodyAsText() shouldContain "isn’t available"
            client.get("$path/pdf").status shouldBe HttpStatusCode.NotFound
            File("build/goods-404.html").writeText(gone.bodyAsText())

            // The next share makes a new link.
            ClarityJson.decodeFromString(ReportLink.serializer(), client.post("/api/v1/reports/$id/link", auth).bodyAsText()).path shouldNotBe path
        }
    }
})
