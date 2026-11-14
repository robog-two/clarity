package net.robog.clarity.backend.attachments

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.ConnectorPrincipal
import net.robog.clarity.backend.config.AppConfig
import net.robog.clarity.backend.governance.MAX_WAIT
import net.robog.clarity.backend.governance.OutboxSignals
import net.robog.clarity.backend.http.EdgeProblem
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.ProblemException
import net.robog.clarity.backend.http.respondProblem
import net.robog.clarity.backend.reports.contentDisposition
import net.robog.clarity.backend.sources.CitedRecords
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.fetch.FetchFailure
import net.robog.clarity.shared.fetch.FetchPoll
import net.robog.clarity.shared.fetch.FetchRequest

/** How often a long poll looks again when nothing woke it. */
private val RECHECK: Duration = 2.seconds

/**
 * Attachments of messages:
 *  - `GET /api/v1/messages/{entityId}/attachments/{part}` (device): the file as a download, only for a message a
 *    Sources card cites. 200 with `Content-Type` and
 *    `Content-Disposition: attachment; filename=...`; 404 for an unknown message or part; 413 over the size limit;
 *    502 when the source could not provide it; 504 when the connector did not answer in time.
 *  - The fetch channel (connector, read-only): `GET /ingest/v1/fetches?wait=` (long poll for requests addressed to
 *    this connector), `PUT /ingest/v1/fetches/{id}?filename=&type=` (the bytes), `POST /ingest/v1/fetches/{id}/error`.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class AttachmentRoutes(
    private val fetcher: AttachmentFetcher,
    private val store: AttachmentFetchStore,
    private val signals: OutboxSignals,
    private val config: AppConfig,
    private val clock: Clock,
    private val citations: CitedRecords,
) : HttpModule {
    /** Uploads read into memory at once; each is up to the size limit. */
    private val uploads = Semaphore(3)

    override fun install(application: Application) {
        application.routing {
            authenticate(AuthProviders.DEVICE) {
                get("${ApiPaths.MESSAGES}/{entityId}/attachments/{part}") {
                    val entityId = call.parameters["entityId"].orEmpty()
                    // Like the sources route: only a message some Sources card cites, so this is not a way to browse mail.
                    if (entityId.isEmpty() || !citations.cited(entityId)) return@get call.respondProblem(EdgeProblem.NotFound("No such attachment."))
                    fetcher.fetch(EntityId(entityId), call.parameters["part"].orEmpty()).fold(
                        { call.respondProblem(it) },
                        { file ->
                            call.response.header(HttpHeaders.ContentDisposition, contentDisposition(file.filename))
                            call.response.header("X-Content-Type-Options", "nosniff")
                            call.respondBytes(file.bytes, runCatching { ContentType.parse(file.mimeType) }.getOrDefault(ContentType.Application.OctetStream))
                        },
                    )
                }
            }
            authenticate(AuthProviders.CONNECTOR) {
                route(ApiPaths.FETCHES) {
                    get {
                        val wait = (call.request.queryParameters["wait"]?.toLongOrNull() ?: 0L).coerceIn(0L, MAX_WAIT.inWholeSeconds).seconds
                        call.respond(FetchPoll(leaseOrWait(call.connector(), wait)))
                    }
                    put("{id}") {
                        val id = call.requestId() ?: return@put call.respondProblem(EdgeProblem.NotFound("No such request."))
                        val max = config.attachments.maxBytes
                        val declared = call.request.contentLength()
                        val bytes = if (sizeVerdict(declared, max) == SizeVerdict.TOO_LARGE) null else uploads.withPermit { readCapped(call, max) }
                        if (bytes == null) {
                            store.fail(call.connector(), id, FetchFailure(FetchFailure.Code.TOO_LARGE, "the file is larger than the limit"), clock.now())
                            return@put call.respond(HttpStatusCode.PayloadTooLarge)
                        }
                        val type = call.request.queryParameters["type"]?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                        val refused = store.complete(call.connector(), id, bytes, call.request.queryParameters["filename"], type, clock.now(), max, config.attachments.cacheMaxBytes)
                        call.respond(if (refused == null) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
                    }
                    post("{id}/error") {
                        val id = call.requestId() ?: return@post call.respondProblem(EdgeProblem.NotFound("No such request."))
                        val refused = store.fail(call.connector(), id, call.receive<FetchFailure>(), clock.now())
                        call.respond(if (refused == null) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
                    }
                }
            }
        }
    }

    /** Leases requests for [connector], waiting up to [wait] for one to be queued. */
    private suspend fun leaseOrWait(connector: ConnectorId, wait: Duration): List<FetchRequest> {
        val deadline = clock.now() + wait
        while (true) {
            val found = store.lease(connector, clock.now(), config.attachments.maxBytes)
            if (found.isNotEmpty()) return found
            val remaining = deadline - clock.now()
            if (remaining <= Duration.ZERO) return emptyList()
            signals.await(minOf(remaining, RECHECK))
        }
    }

    /** The body, or null when it is longer than [max] (the rest is not read). */
    private suspend fun readCapped(call: ApplicationCall, max: Long): ByteArray? = withContext(Dispatchers.IO) {
        call.receiveStream().use { stream ->
            val bytes = stream.readNBytes(max.toInt() + 1)
            if (bytes.size.toLong() > max) null else bytes
        }
    }

    private fun ApplicationCall.requestId(): Uuid? = Uuid.parseOrNull(parameters["id"].orEmpty())

    private fun ApplicationCall.connector(): ConnectorId =
        principal<ConnectorPrincipal>()?.connectorId ?: throw ProblemException(EdgeProblem.Unauthorized())
}
