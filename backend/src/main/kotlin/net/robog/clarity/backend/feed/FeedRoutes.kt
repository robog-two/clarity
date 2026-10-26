package net.robog.clarity.backend.feed

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.InboxStore
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.DevicePrincipal
import net.robog.clarity.backend.http.EdgeProblem
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.respondProblem
import net.robog.clarity.backend.scheduler.RELEASE_RESEND
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedEvent
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.InputAccepted
import net.robog.clarity.shared.feed.UserInput

private const val RELEASED_PREFIX = "released:"

/** How often an idle SSE connection gets a comment so proxies keep it open. */
val SSE_HEARTBEAT: Duration = 20.seconds

/** Pure: the SSE `event:` name of a feed event (its wire type). */
fun eventName(event: FeedEvent): String = when (event) {
    is FeedEvent.ItemAppended -> "item"
    is FeedEvent.TextDelta -> "delta"
    is FeedEvent.ItemFinal -> "item_final"
    is FeedEvent.TaskChanged -> "task"
    is FeedEvent.HeldChanged -> "held"
    is FeedEvent.ConstitutionChanged -> "constitution"
    is FeedEvent.SessionChanged -> "session"
    is FeedEvent.Busy -> "busy"
}

/**
 * Pure: whether a live [event] should still be sent after the items up to [replayedSeq] were
 * replayed from the store. Item events at or below the replayed position are already known, except
 * a released hold: it changes an old item, and the phone notifies on that change.
 */
fun isNewerThan(event: FeedEvent, replayedSeq: Long): Boolean = when (event) {
    is FeedEvent.ItemAppended -> event.item.seq > replayedSeq
    is FeedEvent.ItemFinal -> event.item.seq > replayedSeq || event.item.notify.startsWith(RELEASED_PREFIX)
    else -> true
}

/**
 * The app's feed: `GET /api/v1/feed?after=` (paging), `GET /api/v1/events?after=` (SSE: replay the
 * items after `after` and the holds released lately, then live events; a heartbeat comment keeps it
 * open; closed when the JWT expires so the client reconnects with a fresh one) and `POST /api/v1/input`.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class FeedRoutes(
    private val feed: FeedService,
    private val hub: FeedHub,
    private val inbox: InboxStore,
    private val clock: Clock,
) : HttpModule {
    /** Overridable in tests. */
    internal var heartbeat: Duration = SSE_HEARTBEAT

    override fun install(application: Application) {
        application.install(SSE)
        application.routing {
            authenticate(AuthProviders.DEVICE) {
                get(ApiPaths.FEED) {
                    val after = call.request.queryParameters["after"]?.let { it.toLongOrNull() ?: return@get call.respondProblem(EdgeProblem.BadRequest("after must be an integer")) } ?: 0L
                    val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PAGE).coerceIn(1, MAX_PAGE)
                    call.respond(feed.page(after, limit))
                }

                post(ApiPaths.INPUT) {
                    val input = call.receive<UserInput>()
                    if (input.text.isBlank()) return@post call.respondProblem(EdgeProblem.BadRequest("text must not be empty"))
                    val appended = feed.append(
                        FeedDraft(
                            author = FeedItem.Author.USER,
                            cards = listOf(Card.Said(input.text, input.source)),
                            sessionId = input.sessionId,
                            clientInputId = input.clientId,
                        ),
                    )
                    // Always (the key makes it idempotent): heals a crash between the two writes of the first attempt.
                    inbox.enqueue(
                        InboxItem.UserInput(input.clientId, input.text, input.source, input.sessionId),
                        dedupKey = "user:${input.clientId.value}",
                    )
                    call.respond(InputAccepted(appended.item))
                }

                sse(ApiPaths.EVENTS) {
                    val after = call.request.queryParameters["after"]?.toLongOrNull()
                        ?: call.request.headers["Last-Event-ID"]?.toLongOrNull()
                        ?: 0L
                    val expiresAt = call.principal<DevicePrincipal>()?.expiresAt
                    val lifetime = expiresAt?.let { it - clock.now() }
                    if (lifetime == null) stream(after) else withTimeoutOrNull(lifetime.coerceAtLeast(Duration.ZERO)) { stream(after) }
                }
            }
        }
    }

    private suspend fun ServerSSESession.stream(after: Long) = coroutineScope {
        // Ktor sends the response headers with the first event. Without this, a client that is caught
        // up waits for the first heartbeat, and the reverse proxy in front drops the upstream request.
        send(ServerSentEvent(comments = "open"))
        // Subscribe before replaying so nothing published during the replay is lost.
        val live = Channel<FeedEvent>(Channel.UNLIMITED)
        launch(start = CoroutineStart.UNDISPATCHED) { hub.events.collect { live.send(it) } }
        launch {
            while (true) {
                delay(heartbeat)
                send(ServerSentEvent(comments = "heartbeat"))
            }
        }

        var replayed = after
        while (true) {
            val page = feed.page(replayed, PAGE)
            page.items.forEach { item ->
                send(frame(FeedEvent.ItemAppended(item)))
                replayed = item.seq
            }
            if (!page.hasMore) break
        }
        // Holds released lately on items the client already has: it notifies once and remembers it.
        feed.releasedSince(clock.now() - RELEASE_RESEND).filter { it.seq <= after }.forEach { send(frame(FeedEvent.ItemFinal(it))) }
        for (event in live) {
            if (!isNewerThan(event, replayed)) continue
            send(frame(event))
            if (event is FeedEvent.ItemAppended) replayed = event.item.seq
        }
    }

    private fun frame(event: FeedEvent): ServerSentEvent {
        val seq = when (event) {
            is FeedEvent.ItemAppended -> event.item.seq.toString()
            is FeedEvent.ItemFinal -> event.item.seq.toString()
            else -> null
        }
        return ServerSentEvent(data = ClarityJson.encodeToString(FeedEvent.serializer(), event), event = eventName(event), id = seq)
    }

    private companion object {
        const val DEFAULT_PAGE = 100
        const val MAX_PAGE = 500
        const val PAGE = 200
    }
}
