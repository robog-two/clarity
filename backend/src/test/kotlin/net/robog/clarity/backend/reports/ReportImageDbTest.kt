package net.robog.clarity.backend.reports

import arrow.core.Either
import arrow.core.right
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.DevicePrincipal
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.report.ReportFormat
import net.robog.clarity.shared.report.ReportKind
import net.robog.clarity.shared.report.ReportStyle
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

/** A renderer that records the images each render was given. */
private class RecordingRenderer : Renderer {
    val calls = CopyOnWriteArrayList<Map<String, RenderImage>>()
    override suspend fun version(): String? = "v1"
    override suspend fun render(source: String, style: ReportStyle, format: ReportFormat, images: Map<String, RenderImage>): Either<RenderError, Rendered> {
        calls += images
        return Rendered("${format.wireName}:${images.size}".encodeToByteArray(), "v1").right()
    }
}

private const val HARE = "https://example.com/hare.png"
private const val GONE = "https://example.com/gone.png"
private val SOURCE = "# Salon\n\n![The hare]($HARE)\n\n![Gone]($GONE)\n"

/** Report images against real Postgres: fetching and storing, the route, and what a render is given. */
@EnabledIf(ContainerRuntimeAvailable::class)
class ReportImageDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine").withStartupAttempts(3)
    lateinit var database: Database

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }

    val png = testImage("png", 8, 6)

    suspend fun fresh(): ExposedReportStore {
        database.tx { exec("TRUNCATE report, report_revision, report_rendition, report_source, report_link, report_image RESTART IDENTITY CASCADE") }
        return ExposedReportStore(database, Clock.System)
    }

    test("a fetched image is stored once per report and key; a failure is stored too and not tried again") {
        val store = fresh()
        val (report, _) = store.create(ReportKind.DOCUMENT, "Salon", SOURCE, ReportStyle(), null)
        val fetcher = MapImageFetcher(mapOf(HARE to FetchedImage("image/png", png, 8, 6)))
        val images = testImages(database, fetcher)

        images.fetchMissing(report.id, SOURCE)
        fetcher.asked.sorted() shouldContainExactly listOf(GONE, HARE)

        val hare = images.image(report.id, imageKey(HARE)).shouldNotBeNull()
        hare.contentType shouldBe "image/png"
        hare.bytes!!.toList() shouldBe png.toList()
        hare.width to hare.height shouldBe (8 to 6)
        hare.url shouldBe HARE
        // the failed one is a row with an error and no bytes, so there is no image to serve
        images.image(report.id, imageKey(GONE)).shouldBeNull()
        ExposedReportImageStore(database).find(report.id, imageKey(GONE)).shouldNotBeNull().let {
            it.bytes.shouldBeNull()
            it.error shouldBe "not found"
        }

        // a second pass (a later revision naming the same images) fetches nothing
        images.fetchMissing(report.id, SOURCE + "\nMore text.\n")
        fetcher.asked shouldHaveSize 2
        // a new image in a later revision is fetched, the old ones are not
        images.fetchMissing(report.id, SOURCE + "\n![New](https://example.com/new.png)\n")
        fetcher.asked shouldHaveSize 3
    }

    test("images are kept per report: the same address in another report is its own row") {
        val store = fresh()
        val (one, _) = store.create(ReportKind.DOCUMENT, "One", SOURCE, ReportStyle(), null)
        val (two, _) = store.create(ReportKind.DOCUMENT, "Two", SOURCE, ReportStyle(), null)
        val fetcher = MapImageFetcher(mapOf(HARE to FetchedImage("image/png", png, null, null)))
        val images = testImages(database, fetcher)
        images.fetchMissing(one.id, SOURCE)
        images.image(one.id, imageKey(HARE)).shouldNotBeNull()
        images.image(two.id, imageKey(HARE)).shouldBeNull()
        // the image is deleted with its report
        database.tx { exec("DELETE FROM report WHERE id = '${one.id}'") }
        database.tx { exec("SELECT count(*) FROM report_image") { rs -> rs.next(); rs.getInt(1) } } shouldBe 0
    }

    test("a download gets the fetched images; a file made while one is still to come is not cached") {
        val store = fresh()
        val (report, _) = store.create(ReportKind.DOCUMENT, "Salon", SOURCE, ReportStyle(), null)
        val fetcher = MapImageFetcher(mapOf(HARE to FetchedImage("image/png", png, 8, 6)))
        val images = testImages(database, fetcher)
        val renderer = RecordingRenderer()
        val blobs = ReportBlobs(reportsAppConfig(Files.createTempDirectory("img-blobs")))
        val service = ReportService(store, renderer, blobs, Clock.System, images)

        // nothing fetched yet: rendered without images, and not cached
        service.download(report.id, null, ReportFormat.PDF).shouldBeRight()
        renderer.calls.last().size shouldBe 0
        store.rendition(1L, ReportFormat.PDF, "v1").shouldBeNull()

        images.fetchMissing(report.id, SOURCE)
        // the first download also started a background fetch; wait until both images are settled
        val settled = ExposedReportImageStore(database)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (settled.rows(report.id, listOf(imageKey(HARE), imageKey(GONE))).size < 2 && System.nanoTime() < deadline) kotlinx.coroutines.delay(50)
        service.download(report.id, null, ReportFormat.PDF).shouldBeRight()
        renderer.calls.last().keys.toList() shouldContainExactly listOf(imageKey(HARE))
        renderer.calls.last().values.single().contentType shouldBe "image/png"
        // the failed image settled it, so this file is cached and the next download renders nothing
        val calls = renderer.calls.size
        service.download(report.id, null, ReportFormat.PDF).shouldBeRight()
        renderer.calls.size shouldBe calls

        // plain text never takes images; Markdown is the source as written
        service.download(report.id, null, ReportFormat.TXT).shouldBeRight()
        renderer.calls.last().size shouldBe 0
        service.download(report.id, null, ReportFormat.MD).shouldBeRight().bytes.decodeToString() shouldBe SOURCE
    }

    test("the route serves the bytes with their type and a day of private caching; every other key is a 404") {
        val store = fresh()
        val (report, _) = store.create(ReportKind.DOCUMENT, "Salon", SOURCE, ReportStyle(), null)
        val (other, _) = store.create(ReportKind.DOCUMENT, "Other", "# Other", ReportStyle(), null)
        val fetcher = MapImageFetcher(mapOf(HARE to FetchedImage("image/png", png, 8, 6)))
        val images = testImages(database, fetcher)
        images.fetchMissing(report.id, SOURCE)
        val service = ReportService(store, RecordingRenderer(), ReportBlobs(reportsAppConfig(Files.createTempDirectory("img-routes"))), Clock.System, images)
        testApplication {
            application {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(ClarityJson) }
                install(Authentication) { bearer(AuthProviders.DEVICE) { authenticate { DevicePrincipal(DeviceId("d1"), null) } } }
                ReportRoutes(service, store, ExposedReportLinkStore(database, Clock.System), images).install(this)
            }
            val client = createClient { }
            suspend fun get(path: String, auth: Boolean = true) = client.get(path) { if (auth) header(HttpHeaders.Authorization, "Bearer device") }
            val path = "/api/v1/reports/${report.id}/images/${imageKey(HARE)}"

            get(path, auth = false).status shouldBe HttpStatusCode.Unauthorized
            val ok = get(path)
            ok.status shouldBe HttpStatusCode.OK
            ok.headers[HttpHeaders.ContentType] shouldBe "image/png"
            ok.headers[HttpHeaders.CacheControl] shouldBe "private, max-age=86400"
            ok.bodyAsBytes().toList() shouldBe png.toList()

            // fetch failed, never named, another report's, a malformed key, an unknown report
            get("/api/v1/reports/${report.id}/images/${imageKey(GONE)}").status shouldBe HttpStatusCode.NotFound
            get("/api/v1/reports/${report.id}/images/${imageKey("https://example.com/unknown.png")}").status shouldBe HttpStatusCode.NotFound
            get("/api/v1/reports/${other.id}/images/${imageKey(HARE)}").status shouldBe HttpStatusCode.NotFound
            get("/api/v1/reports/${report.id}/images/${imageKey(HARE).uppercase()}").status shouldBe HttpStatusCode.NotFound
            get("/api/v1/reports/${report.id}/images/not-a-key").status shouldBe HttpStatusCode.NotFound
            get("/api/v1/reports/00000000-0000-0000-0000-000000000000/images/${imageKey(HARE)}").status shouldBe HttpStatusCode.NotFound
            get("/api/v1/reports/not-an-id/images/${imageKey(HARE)}").status shouldBe HttpStatusCode.NotFound
        }
    }

    test("create_report schedules the fetch without waiting for it") {
        val store = fresh()
        val fetcher = MapImageFetcher(mapOf(HARE to FetchedImage("image/png", png, 8, 6)))
        val images = testImages(database, fetcher)
        val (report, revision) = store.create(ReportKind.DOCUMENT, "Salon", SOURCE, ReportStyle(), null)
        images.schedule(report.id, revision.source)
        // it runs in the background; wait for it
        val deadline = System.nanoTime() + 10_000_000_000L
        while (images.image(report.id, imageKey(HARE)) == null && System.nanoTime() < deadline) kotlinx.coroutines.delay(50)
        images.image(report.id, imageKey(HARE)).shouldNotBeNull()
        images.image(report.id, imageKey(HARE))!!.contentType shouldContain "image/png"
    }
})

private fun <A, B> Either<A, B>.shouldBeRight(): B = (this as? Either.Right)?.value ?: error("expected a result, got $this")
