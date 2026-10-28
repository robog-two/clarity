package net.robog.clarity.backend.reports

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.robog.clarity.backend.config.AppConfig
import net.robog.clarity.backend.config.ReportsConfig
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.report.ReportFormat
import net.robog.clarity.shared.report.ReportStyle

private val log = KotlinLogging.logger {}

/** Why a render did not produce a file. */
sealed interface RenderError {
    val message: String

    /** The render service could not be reached or is not working. */
    data class Unavailable(override val message: String) : RenderError

    /** It refused or could not convert this source (bad input). */
    data class Failed(override val message: String) : RenderError

    data class TimedOut(override val message: String) : RenderError
}

/** A rendered file and the renderer version that made it (the cache key). */
data class Rendered(val bytes: ByteArray, val rendererVersion: String)

/** Turns a revision into a file. The `render` service in production; a fake in tests. */
interface Renderer {
    /** The renderer's version (pandoc, Typst and the server script), or null when it cannot be reached. */
    suspend fun version(): String?

    suspend fun render(source: String, style: ReportStyle, format: ReportFormat): Either<RenderError, Rendered>
}

/** `POST /render`'s body. */
@Serializable
data class RenderRequest(val source: String, val style: ReportStyle, val format: String)

/** Pure: the JSON body of a render request. */
fun renderRequestBody(source: String, style: ReportStyle, format: ReportFormat): String =
    ClarityJson.encodeToString(RenderRequest.serializer(), RenderRequest(source, style, format.wireName))

/** Pure: the renderer version in a `/healthz` answer (`{"status":"ok","version":"..."}`), else null. */
fun parseRenderVersion(body: String): String? {
    val json = runCatching { ClarityJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    return (json["version"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

/**
 * The `render` service over plain HTTP on the internal network (never through the egress proxy), with
 * the bearer key from [ReportsConfig.renderKeyFile]. The version is asked for once and kept for
 * [VERSION_TTL]; a render answer's `X-Render-Version` refreshes it.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class HttpRenderClient(config: AppConfig, private val clock: Clock) : Renderer {
    private val settings: ReportsConfig = config.reports
    private val base: String = settings.renderUrl.trimEnd('/')

    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .proxy(HttpClient.Builder.NO_PROXY)
        .connectTimeout(CONNECT_TIMEOUT.toJavaDuration())
        .build()

    private val cachedVersion = AtomicReference<Pair<String, Instant>?>(null)

    private fun key(): String? = settings.renderKeyFile?.let { file ->
        runCatching { Files.readString(Path.of(file)).trim() }.getOrNull()?.takeIf(String::isNotEmpty)
    }

    private fun HttpRequest.Builder.auth(): HttpRequest.Builder = apply { key()?.let { header("Authorization", "Bearer $it") } }

    override suspend fun version(): String? {
        cachedVersion.get()?.let { (v, at) -> if (clock.now() - at < VERSION_TTL) return v }
        val request = HttpRequest.newBuilder(URI.create("$base/healthz")).timeout(HEALTH_TIMEOUT.toJavaDuration()).auth().GET().build()
        val version = try {
            runInterruptible(Dispatchers.IO) {
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) parseRenderVersion(response.body()) else null
            }
        } catch (e: IOException) {
            log.debug { "render service unreachable: ${e::class.simpleName}" }
            null
        }
        version?.let { cachedVersion.set(it to clock.now()) }
        return version
    }

    override suspend fun render(source: String, style: ReportStyle, format: ReportFormat): Either<RenderError, Rendered> {
        val request = HttpRequest.newBuilder(URI.create("$base/render"))
            // A little longer than the server's own limit, so its timeout answer arrives first.
            .timeout((settings.renderTimeout + 10.seconds).toJavaDuration())
            .header("Content-Type", "application/json")
            .auth()
            .POST(HttpRequest.BodyPublishers.ofString(renderRequestBody(source, style, format)))
            .build()
        val response = try {
            runInterruptible(Dispatchers.IO) { http.send(request, HttpResponse.BodyHandlers.ofByteArray()) }
        } catch (e: HttpTimeoutException) {
            return RenderError.TimedOut("the render service did not answer in time").left()
        } catch (e: IOException) {
            return RenderError.Unavailable("the render service cannot be reached (${e::class.simpleName})").left()
        }
        val status = response.statusCode()
        val body = response.body()
        return when {
            status == 200 -> {
                val version = response.headers().firstValue(VERSION_HEADER).orElse(null)?.takeIf(String::isNotBlank)
                    ?: version() ?: UNKNOWN_VERSION
                cachedVersion.set(version to clock.now())
                Rendered(body, version).right()
            }
            status == 504 -> RenderError.TimedOut(errorText(body)).left()
            status == 400 || status == 422 -> RenderError.Failed(errorText(body)).left()
            else -> RenderError.Unavailable("the render service answered $status: ${errorText(body)}").left()
        }
    }

    private fun errorText(body: ByteArray): String {
        val text = body.decodeToString().take(1_000)
        val json = runCatching { ClarityJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        return ((json?.get("error") as? JsonPrimitive)?.contentOrNull ?: text).take(300).ifBlank { "no details" }
    }

    companion object {
        const val VERSION_HEADER = "X-Render-Version"
        const val UNKNOWN_VERSION = "unknown"
        private val CONNECT_TIMEOUT: Duration = 5.seconds
        private val HEALTH_TIMEOUT: Duration = 5.seconds
        val VERSION_TTL: Duration = 5.minutes
    }
}
