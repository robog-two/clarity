package net.robog.clarity.connector.sdk

import arrow.core.Either
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.InputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.robog.clarity.shared.fetch.FetchFailure
import net.robog.clarity.shared.fetch.FetchRequest

private val log = KotlinLogging.logger {}

/** What a connector found for a [FetchRequest]. */
sealed interface FetchOutcome {
    /** The part's bytes (at most the request's `maxBytes`), with its name and media type. */
    class Found(val bytes: ByteArray, val filename: String?, val mimeType: String = "application/octet-stream") : FetchOutcome

    data class Failed(val code: FetchFailure.Code, val message: String) : FetchOutcome

    companion object {
        fun notFound(message: String) = Failed(FetchFailure.Code.NOT_FOUND, message)
        fun tooLarge(message: String) = Failed(FetchFailure.Code.TOO_LARGE, message)
    }
}

/**
 * The connector's answer to fetch requests (the bytes of one part of a record it keeps, such as an
 * email attachment). Read-only: it must not change anything upstream.
 *
 * ```kotlin
 * val fetches = fetchHandlers { onFetch { request -> FetchOutcome.Found(bytes, "a.pdf", "application/pdf") } }
 * ```
 *
 * A handler must stop reading at `request.maxBytes` (see [readCapped]); an exception is reported as a failure.
 */
class FetchHandlers internal constructor(private val handler: (suspend (FetchRequest) -> FetchOutcome)?) {
    val isEmpty: Boolean get() = handler == null

    internal suspend fun handle(request: FetchRequest): FetchOutcome =
        handler?.invoke(request) ?: FetchOutcome.Failed(FetchFailure.Code.FAILED, "this connector does not serve fetches")

    companion object {
        val None = FetchHandlers(null)
    }
}

class FetchHandlersBuilder {
    private var handler: (suspend (FetchRequest) -> FetchOutcome)? = null

    fun onFetch(block: suspend (FetchRequest) -> FetchOutcome) {
        handler = block
    }

    internal fun build() = FetchHandlers(handler)
}

fun fetchHandlers(block: FetchHandlersBuilder.() -> Unit): FetchHandlers = FetchHandlersBuilder().apply(block).build()

/** Pure: whether a part of [declared] bytes (null when the source does not say) may be read at all under [maxBytes]. */
fun withinCap(declared: Long?, maxBytes: Long): Boolean = declared == null || declared <= maxBytes

/**
 * Reads [this] to its end, but never holds more than [maxBytes]: null as soon as there is more than
 * that (the rest is not read). The caller closes the stream.
 */
fun InputStream.readCapped(maxBytes: Long): ByteArray? {
    require(maxBytes >= 0) { "maxBytes must not be negative" }
    val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024L).toInt())
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) return out.toByteArray()
        total += n
        if (total > maxBytes) return null
        out.write(buffer, 0, n)
    }
}

/** Fetches served at the same time; a big download does not hold up the next request. */
private const val CONCURRENT_FETCHES = 3

/**
 * One round of the fetch loop: long-poll for [wait], answer what arrives (a few at a time) and report
 * each answer. Returns the poll error, if any, so the caller can back off.
 */
internal suspend fun pollAndFetch(client: ClarityClient, handlers: FetchHandlers, wait: Duration): ClientError? {
    val poll = when (val r = client.pollFetches(wait)) {
        is Either.Left -> return r.value
        is Either.Right -> r.value
    }
    val permits = Semaphore(CONCURRENT_FETCHES)
    coroutineScope {
        poll.fetches.map { request -> async { permits.withPermit { answer(client, handlers, request) } } }.awaitAll()
    }
    return null
}

private suspend fun answer(client: ClarityClient, handlers: FetchHandlers, request: FetchRequest) {
    val outcome = try {
        handlers.handle(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "fetch ${request.id} failed" }
        FetchOutcome.Failed(FetchFailure.Code.FAILED, e.message ?: e::class.simpleName ?: "fetch failed")
    }
    val sent = when (outcome) {
        is FetchOutcome.Found ->
            if (outcome.bytes.size > request.maxBytes) {
                client.postFetchError(request.id, FetchFailure(FetchFailure.Code.TOO_LARGE, "the part is larger than the limit"))
            } else {
                client.putFetched(request.id, outcome.bytes, outcome.filename, outcome.mimeType)
            }
        is FetchOutcome.Failed -> client.postFetchError(request.id, FetchFailure(outcome.code, outcome.message))
    }
    sent.onLeft { log.warn { "could not answer fetch ${request.id}: ${it.message}" } }
}

/** Runs [pollAndFetch] forever, backing off (up to 30 s) while the backend is unreachable. */
internal suspend fun runFetchLoop(client: ClarityClient, handlers: FetchHandlers, wait: Duration = 25.seconds) {
    var backoff = 1.seconds
    while (true) {
        val error = pollAndFetch(client, handlers, wait)
        if (error == null) {
            backoff = 1.seconds
        } else {
            log.warn { "fetch poll failed: ${error.message}; retrying in $backoff" }
            delay(backoff)
            backoff = minOf(backoff * 2, 30.seconds)
        }
    }
}
