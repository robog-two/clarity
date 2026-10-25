package net.robog.clarity.connector.sdk

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.action.ActionId
import net.robog.clarity.shared.action.ActionPoll
import net.robog.clarity.shared.action.ActionResult
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.api.Problem
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.ingest.IngestBatch
import net.robog.clarity.shared.ingest.IngestResponse

private val log = KotlinLogging.logger {}

/** Result of polling `GET /ingest/v1/config`. */
sealed interface ConfigPoll {
    /** New or first config, with the `ETag` to send as `If-None-Match` next time. */
    data class Changed(val config: ConnectorConfig, val etag: String) : ConfigPoll

    /** HTTP 304: the config you already have is current. */
    data object NotModified : ConfigPoll
}

/**
 * Builds the SDK's HTTP client: CIO engine (override [engine] in tests), [ClarityJson],
 * bearer auth and sane timeouts. Redirects are not followed.
 */
fun clarityHttpClient(
    settings: ConnectorSettings,
    engine: HttpClientEngine = CIO.create(),
    extra: HttpClientConfig<*>.() -> Unit = {},
): HttpClient = HttpClient(engine) {
    expectSuccess = false
    followRedirects = false
    install(ContentNegotiation) { json(ClarityJson) }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 60_000
    }
    defaultRequest {
        url(settings.baseUrl)
        bearerAuth(settings.connectorKey)
    }
    extra()
}

/**
 * Typed client for the connector-facing `/ingest/v1` API. Every call retries transient
 * failures per [retry] and returns an [Either] rather than throwing; only cancellation propagates.
 *
 * Each in-flight HTTP attempt is shielded from cancellation so a graceful shutdown lets the
 * current request finish; backoff delays remain cancellable.
 */
class ClarityClient(
    private val http: HttpClient,
    private val retry: RetryPolicy = RetryPolicy.Default,
    private val jitter: () -> Double = Random.Default::nextDouble,
) {
    /** `PUT /ingest/v1/manifest`. */
    suspend fun putManifest(manifest: ConnectorManifest): Either<ClientError, Unit> =
        execute("put manifest", { http.put("${ApiPaths.INGEST}/manifest") { contentType(ContentType.Application.Json); setBody(manifest) } }) {
            Unit.right()
        }

    /** `GET /ingest/v1/config`, conditional on [etag] when given. */
    suspend fun pollConfig(etag: String?): Either<ClientError, ConfigPoll> =
        execute("poll config", { http.get("${ApiPaths.INGEST}/config") { etag?.let { header(HttpHeaders.IfNoneMatch, it) } } }) { response ->
            if (response.status == HttpStatusCode.NotModified) {
                ConfigPoll.NotModified.right()
            } else {
                decode<ConnectorConfig>(response).map { config ->
                    ConfigPoll.Changed(config, response.headers[HttpHeaders.ETag] ?: "\"${config.version}\"")
                }
            }
        }

    /** `POST /ingest/v1/batches` for a single batch (at most [IngestBatch.MAX_RECORDS] records). */
    suspend fun postBatch(batch: IngestBatch): Either<ClientError, IngestResponse> {
        require(batch.records.size <= IngestBatch.MAX_RECORDS) { "batch too large: ${batch.records.size}" }
        return execute("post batch", { http.post("${ApiPaths.INGEST}/batches") { contentType(ContentType.Application.Json); setBody(batch) } }) {
            decode<IngestResponse>(it)
        }
    }

    /**
     * `GET /ingest/v1/actions?wait=`: long-polls up to [wait] (the backend caps it at 30 s) for
     * actions addressed to this connector. Unlike other calls the request is cancellable, so a
     * shutdown does not wait for the poll to time out; an action leased to a cancelled poll is
     * simply re-delivered after its lease lapses.
     */
    suspend fun pollActions(wait: Duration): Either<ClientError, ActionPoll> =
        execute("poll actions", { http.get(ApiPaths.ACTIONS) { parameter("wait", wait.inWholeSeconds) } }, shielded = false) {
            decode<ActionPoll>(it)
        }

    /** `POST /ingest/v1/actions/{id}/result`. Idempotent on the backend: reporting twice is harmless. */
    suspend fun postResult(id: ActionId, result: ActionResult): Either<ClientError, Unit> =
        execute("post action result", { http.post("${ApiPaths.ACTIONS}/${id.value}/result") { contentType(ContentType.Application.Json); setBody(result) } }) {
            Unit.right()
        }

    private suspend fun <T> execute(
        op: String,
        request: suspend () -> HttpResponse,
        shielded: Boolean = true,
        handle: suspend (HttpResponse) -> Either<ClientError, T>,
    ): Either<ClientError, T> {
        var lastFailure = "no attempt made"
        for (attempt in 1..retry.maxAttempts) {
            val outcome = if (shielded) withContext(NonCancellable) { attemptOnce(request, handle) } else attemptOnce(request, handle)
            when (outcome) {
                is Either.Right -> return outcome.value
                is Either.Left -> {
                    lastFailure = outcome.value.description
                    if (attempt == retry.maxAttempts) break
                    val wait = retry.delayBefore(attempt, jitter(), outcome.value.retryAfter)
                    log.warn { "$op failed (attempt $attempt/${retry.maxAttempts}): $lastFailure; retrying in $wait" }
                    delay(wait)
                }
            }
        }
        return ClientError.Unavailable(retry.maxAttempts, lastFailure).left()
    }

    private suspend fun <T> attemptOnce(
        request: suspend () -> HttpResponse,
        handle: suspend (HttpResponse) -> Either<ClientError, T>,
    ): Either<TransientFailure, Either<ClientError, T>> {
        val response = try {
            request()
        } catch (e: IOException) {
            return TransientFailure("${e::class.simpleName}: ${e.message}", null).left()
        }
        return when (RetryPolicy.verdict(response.status.value)) {
            Verdict.SUCCESS -> handle(response).right()
            Verdict.RETRY -> TransientFailure(
                "HTTP ${response.status.value}",
                RetryPolicy.parseRetryAfter(response.headers[HttpHeaders.RetryAfter]),
            ).left()
            Verdict.FATAL -> ClientError.Rejected(response.status.value, problemOrNull(response)).left().right()
        }
    }

    private suspend fun problemOrNull(response: HttpResponse): Problem? =
        try { response.body<Problem>() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    private suspend inline fun <reified T> decode(response: HttpResponse): Either<ClientError, T> =
        try {
            response.body<T>().right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ClientError.InvalidResponse("${T::class.simpleName}: ${e.message}").left()
        }

    private data class TransientFailure(val description: String, val retryAfter: Duration?)
}
