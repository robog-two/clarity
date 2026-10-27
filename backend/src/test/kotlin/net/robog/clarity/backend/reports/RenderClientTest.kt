package net.robog.clarity.backend.reports

import arrow.core.Either
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.AppConfig
import net.robog.clarity.backend.config.ConnectorsConfig
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.EnrollmentConfig
import net.robog.clarity.backend.config.EnrollmentMode
import net.robog.clarity.backend.config.HttpConfig
import net.robog.clarity.backend.config.InstanceConfig
import net.robog.clarity.backend.config.ReportsConfig
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.report.ReportFormat
import net.robog.clarity.shared.report.ReportStyle

/** A configuration for report tests: [dataDir] for the cache, [reports] for the render service. */
fun reportsAppConfig(dataDir: Path, reports: ReportsConfig = ReportsConfig()) = AppConfig(
    instance = InstanceConfig("Test"),
    http = HttpConfig(0),
    database = DatabaseConfig("jdbc:postgresql://unused/db", "clarity", null, maxPoolSize = 1),
    dataDir = dataDir,
    enrollment = EnrollmentConfig(EnrollmentMode.TOFU, token = null),
    connectors = ConnectorsConfig(autoGrant = false, secretsDir = dataDir.resolve("secrets")),
    agent = AgentConfig(reviewInterval = 2.hours),
    reports = reports,
)

/** One request the fake render service received. */
private data class Seen(val method: String, val path: String, val authorization: String?, val body: String)

/**
 * A stand-in for renderd on a loopback port: answers `/healthz` with a version and `/render` with
 * [status] and [answer]. Records every request.
 */
private class FakeRenderServer(var status: Int = 200, var answer: ByteArray = "%PDF-1.7 fake".encodeToByteArray(), var version: String = "pandoc 3.12.1; typst 0.15.1; renderd t1") {
    val seen = CopyOnWriteArrayList<Seen>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = exchange.requestBody.readAllBytes().decodeToString()
            seen += Seen(exchange.requestMethod, exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"), body)
            val (code, bytes) = when (exchange.requestURI.path) {
                "/healthz" -> 200 to """{"status":"ok","version":"$version"}""".encodeToByteArray()
                else -> status to answer
            }
            exchange.responseHeaders.add("X-Render-Version", version)
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val url: String get() = "http://127.0.0.1:${server.address.port}"
    fun stop() = server.stop(0)
}

/** The render client against a fake render service: the request it builds, the key, the answers it understands. */
class RenderClientTest : FunSpec({
    val dir = Files.createTempDirectory("clarity-render-client")
    val keyFile = dir.resolve("render_key").also { Files.writeString(it, "secret-key\n") }
    val fake = FakeRenderServer()

    fun client(url: String = fake.url, key: Path? = keyFile) =
        HttpRenderClient(reportsAppConfig(dir, ReportsConfig(renderUrl = url, renderKeyFile = key?.toString(), renderTimeout = 5.seconds)), Clock.System)

    afterSpec { fake.stop() }
    beforeTest {
        fake.seen.clear()
        fake.status = 200
        fake.answer = "%PDF-1.7 fake".encodeToByteArray()
    }

    test("a render posts the source, the style and the format with the bearer key, and returns the bytes and version") {
        val result = client().render("# Essay\n\nText.", ReportStyle.MLA, ReportFormat.PDF)
        val rendered = result.shouldBeInstanceOf<Either.Right<Rendered>>().value
        rendered.bytes.decodeToString() shouldBe "%PDF-1.7 fake"
        rendered.rendererVersion shouldBe "pandoc 3.12.1; typst 0.15.1; renderd t1"

        val request = fake.seen.single { it.path == "/render" }
        request.method shouldBe "POST"
        request.authorization shouldBe "Bearer secret-key"
        val body = ClarityJson.parseToJsonElement(request.body).jsonObject
        body["source"]!!.jsonPrimitive.content shouldBe "# Essay\n\nText."
        body["format"]!!.jsonPrimitive.content shouldBe "pdf"
        body["style"]!!.jsonObject["lineSpacing"]!!.jsonPrimitive.content shouldBe "2.0"
    }

    test("the version comes from /healthz and is kept") {
        val c = client()
        c.version() shouldBe "pandoc 3.12.1; typst 0.15.1; renderd t1"
        c.version() shouldBe "pandoc 3.12.1; typst 0.15.1; renderd t1"
        fake.seen.count { it.path == "/healthz" } shouldBe 1
    }

    test("a refused source is a failed render, a timeout answer a timeout, anything else unavailable") {
        fake.status = 422
        fake.answer = """{"error":"pandoc: bad input"}""".encodeToByteArray()
        client().render("x", ReportStyle(), ReportFormat.DOCX).shouldBeInstanceOf<Either.Left<RenderError>>().value
            .shouldBeInstanceOf<RenderError.Failed>().message shouldContain "bad input"
        fake.status = 504
        fake.answer = """{"error":"rendering took longer than 60 s"}""".encodeToByteArray()
        client().render("x", ReportStyle(), ReportFormat.PDF).shouldBeInstanceOf<Either.Left<RenderError>>().value
            .shouldBeInstanceOf<RenderError.TimedOut>()
        fake.status = 401
        fake.answer = """{"error":"a valid bearer key is required"}""".encodeToByteArray()
        client().render("x", ReportStyle(), ReportFormat.ODT).shouldBeInstanceOf<Either.Left<RenderError>>().value
            .shouldBeInstanceOf<RenderError.Unavailable>().message shouldContain "401"
    }

    test("no key file sends no key; an unreachable service is unavailable and has no version") {
        client(key = dir.resolve("missing")).render("x", ReportStyle(), ReportFormat.TXT)
        fake.seen.single { it.path == "/render" }.authorization shouldBe null

        val down = client(url = "http://127.0.0.1:9")
        down.version() shouldBe null
        down.render("x", ReportStyle(), ReportFormat.PDF).shouldBeInstanceOf<Either.Left<RenderError>>().value
            .shouldBeInstanceOf<RenderError.Unavailable>()
    }
})
