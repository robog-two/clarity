package net.robog.clarity.backend.reports

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import net.robog.clarity.harness.imageProxyFromEnv

private val log = KotlinLogging.logger {}

/** Most images fetched for one report; the rest of its image links stay captions. */
const val MAX_IMAGES_PER_REPORT = 20

/** Largest image, enforced while it streams. */
const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

/** Redirects followed for one image; each is checked as http or https again. */
const val MAX_IMAGE_REDIRECTS = 3

/** One fetch, redirects included. */
val IMAGE_FETCH_TIMEOUT = 15.seconds

const val IMAGE_USER_AGENT = "Clarity-Goods/1.0 (+image fetch for a document)"

/** The types an image may have: the response says one of these and the first bytes agree. */
val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

/** Pure: the key a URL has everywhere (the table, the route, the renderer): lowercase hex SHA-256 of the trimmed URL. */
fun imageKey(url: String): String =
    MessageDigest.getInstance("SHA-256").digest(url.trim().encodeToByteArray()).joinToString("") { "%02x".format(it) }

private val IMAGE_LINK = Regex(
    """!\[(?:[^\[\]\\]|\\.|\[[^\[\]]*])*]\(\s*(<[^<>\r\n]*>|(?:[^\s()\\<]|\\.|\([^\s()]*\))+)(?:\s+(?:"[^"]*"|'[^']*'|\([^)]*\)))?\s*\)""",
)
private val CODE_FENCE = Regex("""(?ms)^ {0,3}(`{3,}|~{3,}).*?(?:^ {0,3}\1[`~]*[ \t]*$|\z)""")

/**
 * Pure: every image address in [markdown] as written (trimmed, `<...>` removed), once each and in
 * order, leaving out fenced code. Reference-style images (`![a][ref]`) are not found; they stay captions.
 */
fun extractImageUrls(markdown: String): List<String> =
    IMAGE_LINK.findAll(CODE_FENCE.replace(markdown, "")).map { it.groupValues[1].trim().removeSurrounding("<", ">").trim() }
        .filter { it.isNotEmpty() }.distinct().toList()

/** Pure: whether [url] may be fetched at all: absolute http or https with a host and no login in it. */
fun fetchableImageUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
}

/** Pure: the addresses to fetch for [markdown]: fetchable ones, the first [MAX_IMAGES_PER_REPORT]. */
fun imagesToFetch(markdown: String): List<String> = extractImageUrls(markdown).filter(::fetchableImageUrl).take(MAX_IMAGES_PER_REPORT)

/** Pure: the type the first bytes show (png, jpeg, gif, webp), or null. SVG and everything else is null. */
fun sniffImageType(b: ByteArray): String? = when {
    b.size >= 8 && b.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
    b.size >= 3 && b.startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
    b.size >= 6 && (b.startsWith('G'.code, 'I'.code, 'F'.code, '8'.code, '7'.code, 'a'.code) || b.startsWith('G'.code, 'I'.code, 'F'.code, '8'.code, '9'.code, 'a'.code)) -> "image/gif"
    b.size >= 12 && b.startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) && b[8].toInt() == 'W'.code && b[9].toInt() == 'E'.code &&
        b[10].toInt() == 'B'.code && b[11].toInt() == 'P'.code -> "image/webp"
    else -> null
}

private fun ByteArray.startsWith(vararg prefix: Int): Boolean = prefix.indices.all { this[it].toInt() and 0xFF == prefix[it] }
private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
private fun ByteArray.le16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
private fun ByteArray.le24(i: Int): Int = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
private fun ByteArray.be16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
private fun ByteArray.be32(i: Int): Int = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)

/** Pure: (width, height) read from the header bytes of an image of [type], or null when they cannot be found. Nothing is decoded. */
fun imageSize(b: ByteArray, type: String): Pair<Int, Int>? = try {
    when (type) {
        "image/png" -> if (b.size >= 24 && b.be32(8) == 13 && b[12].toInt() == 'I'.code) b.be32(16) to b.be32(20) else null
        "image/gif" -> if (b.size >= 10) b.le16(6) to b.le16(8) else null
        "image/jpeg" -> jpegSize(b)
        "image/webp" -> webpSize(b)
        else -> null
    }?.takeIf { (w, h) -> w in 1..MAX_IMAGE_SIDE && h in 1..MAX_IMAGE_SIDE }
} catch (_: IndexOutOfBoundsException) {
    null
}

private const val MAX_IMAGE_SIDE = 100_000

private fun jpegSize(b: ByteArray): Pair<Int, Int>? {
    var i = 2
    while (i + 3 < b.size) {
        if (b.u8(i) != 0xFF) { i++; continue }
        val marker = b.u8(i + 1)
        if (marker == 0xFF) { i++; continue }
        if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
        if (marker == 0xD9 || marker == 0xDA) return null
        val length = b.be16(i + 2)
        if (length < 2) return null
        // start-of-frame markers (not DHT 0xC4, JPG 0xC8, DAC 0xCC)
        if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
            return if (i + 9 <= b.size) b.be16(i + 7) to b.be16(i + 5) else null
        }
        i += 2 + length
    }
    return null
}

private fun webpSize(b: ByteArray): Pair<Int, Int>? {
    if (b.size < 30) return null
    return when (b.decodeToString(12, 16)) {
        "VP8 " -> (b.le16(26) and 0x3FFF) to (b.le16(28) and 0x3FFF)
        "VP8L" -> {
            val bits = b.u8(21) or (b.u8(22) shl 8) or (b.u8(23) shl 16) or (b.u8(24) shl 24)
            ((bits and 0x3FFF) + 1) to (((bits ushr 14) and 0x3FFF) + 1)
        }
        "VP8X" -> (b.le24(24) + 1) to (b.le24(27) + 1)
        else -> null
    }
}

/** A fetched, checked image. */
class FetchedImage(val contentType: String, val bytes: ByteArray, val width: Int?, val height: Int?)

/** Why an image was not kept (shown nowhere but the log and the table). */
sealed interface ImageOutcome {
    class Fetched(val image: FetchedImage) : ImageOutcome

    data class Failed(val reason: String) : ImageOutcome
}

/** One HTTP answer, for [fetchImage]. Closing it releases the connection. */
class ImageResponse(val status: Int, val contentType: String?, val location: String?, val body: InputStream?) : Closeable {
    override fun close() {
        runCatching { body?.close() }
    }
}

/** Sends one GET and does not follow redirects. */
fun interface ImageTransport {
    @Throws(IOException::class)
    fun get(uri: URI): ImageResponse
}

/**
 * Fetches [url] through [transport]: http and https only, at most [MAX_IMAGE_REDIRECTS] redirects each
 * checked again, a response of at most [MAX_IMAGE_BYTES] (counted while it streams), a PNG, JPEG, GIF or
 * WebP by both Content-Type and first bytes. Blocking; the caller sets the time limit.
 */
fun fetchImage(url: String, transport: ImageTransport): ImageOutcome {
    var current = url.trim()
    var redirects = 0
    while (true) {
        if (!fetchableImageUrl(current)) return ImageOutcome.Failed("only http and https addresses are fetched")
        val response = try {
            transport.get(URI(current))
        } catch (e: IOException) {
            return ImageOutcome.Failed("could not fetch (${e::class.simpleName})")
        }
        response.use { r ->
            if (r.status in REDIRECTS) {
                val next = r.location?.takeIf { it.isNotBlank() } ?: return ImageOutcome.Failed("redirect without a location")
                if (++redirects > MAX_IMAGE_REDIRECTS) return ImageOutcome.Failed("too many redirects")
                current = runCatching { URI(current).resolve(next).toString() }.getOrNull() ?: return ImageOutcome.Failed("bad redirect")
                return@use
            }
            if (r.status != 200) return ImageOutcome.Failed("answered ${r.status}")
            val declared = r.contentType?.substringBefore(';')?.trim()?.lowercase()?.let { if (it == "image/jpg") "image/jpeg" else it }
            if (declared == null || declared !in IMAGE_TYPES) return ImageOutcome.Failed("not a PNG, JPEG, GIF or WebP (Content-Type ${declared ?: "missing"})")
            val bytes = readLimited(r.body ?: return ImageOutcome.Failed("no body")) ?: return ImageOutcome.Failed("larger than ${MAX_IMAGE_BYTES / (1024 * 1024)} MB")
            val sniffed = sniffImageType(bytes) ?: return ImageOutcome.Failed("the bytes are not a PNG, JPEG, GIF or WebP")
            if (sniffed != declared) return ImageOutcome.Failed("Content-Type $declared but the bytes are $sniffed")
            val size = imageSize(bytes, sniffed)
            return ImageOutcome.Fetched(FetchedImage(sniffed, bytes, size?.first, size?.second))
        }
    }
}

private val REDIRECTS = setOf(301, 302, 303, 307, 308)

/** The whole stream, or null once it passes [MAX_IMAGE_BYTES]. */
private fun readLimited(stream: InputStream): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val n = stream.read(buffer)
        if (n < 0) return out.toByteArray()
        if (out.size() + n > MAX_IMAGE_BYTES) return null
        out.write(buffer, 0, n)
    }
}

/** Fetches one image for a report. A fake in tests. */
interface ImageFetcher {
    suspend fun fetch(url: String): ImageOutcome
}

/**
 * The real fetcher: through the image listener of the egress proxy (`CLARITY_IMAGE_PROXY`; the backend
 * has no other route out), without cookies, redirects or HTTP/2 upgrades of its own, and one fixed user
 * agent. With no proxy (local development) it connects directly but refuses private addresses.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class HttpImageFetcher : ImageFetcher {
    private val proxy: Proxy? = imageProxyFromEnv(System.getenv())

    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(IMAGE_FETCH_TIMEOUT.toJavaDuration())
        .apply {
            if (proxy != null) proxy(object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> = listOf(proxy)
                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
            }) else proxy(HttpClient.Builder.NO_PROXY)
        }
        .build()

    private val transport = ImageTransport { uri ->
        if (proxy == null) refusePrivate(uri.host)
        val request = HttpRequest.newBuilder(uri)
            .timeout(IMAGE_FETCH_TIMEOUT.toJavaDuration())
            .header("User-Agent", IMAGE_USER_AGENT)
            .header("Accept", "image/png,image/jpeg,image/gif,image/webp")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        ImageResponse(
            response.statusCode(),
            response.headers().firstValue("Content-Type").orElse(null),
            response.headers().firstValue("Location").orElse(null),
            response.body(),
        )
    }

    override suspend fun fetch(url: String): ImageOutcome = try {
        withTimeout(IMAGE_FETCH_TIMEOUT) { runInterruptible(Dispatchers.IO) { fetchImage(url, transport) } }
    } catch (_: TimeoutCancellationException) {
        ImageOutcome.Failed("timed out")
    }

    private fun refusePrivate(host: String) {
        val addresses = InetAddress.getAllByName(host)
        if (addresses.any { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress || (it is Inet6Address && (it.address[0].toInt() and 0xFE) == 0xFC) }) {
            throw IOException("private address")
        }
    }
}

internal fun logFetchFailure(url: String, reason: String) {
    log.info { "report image not kept (${url.substringBefore('?').take(120)}): $reason" }
}
