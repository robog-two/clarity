package net.robog.clarity.backend.feed

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
 * replayed from the store. An appended item at or below the replayed position is already known. A
 * finished item always goes out: it changes an item the client already has (usually one it was
 * just sent), and clients apply it idempotently.
 */
fun isNewerThan(event: FeedEvent, replayedSeq: Long): Boolean = when (event) {
    is FeedEvent.ItemAppended -> event.item.seq > replayedSeq
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
                    // A tap on a choices card is checked first. A retry of the very same tap (same client id) skips the
                    // check: it was accepted already, and the idempotent writes below heal whatever the first try left undone.
                    if (input.inReplyTo != null && feed.byClientInput(input.clientId) == null) {
                        when (val check = checkChoice(feed.get(input.inReplyTo!!), input.text)) {
                            ChoiceCheck.NotChoices -> return@post call.respondProblem(EdgeProblem.BadRequest("inReplyTo is not a card with choices"))
                            ChoiceCheck.NotAnOption -> return@post call.respondProblem(EdgeProblem.BadRequest("text is not one of the options"))
                            // Answered already: only a retry of the very request that answered it (it crashed before queueing) goes on.
                            // Any other tap, from any device and even with the same option, gets the conflict and the app shows the answer.
                            is ChoiceCheck.Answered ->
                                if (check.by != input.clientId.value) return@post call.respond(HttpStatusCode.Conflict, InputAccepted(check.item))
                            ChoiceCheck.Open -> Unit
                        }
                    }
                    // A tap on a choices card: the answer is recorded on the card (once, first tap wins) and shown on every device.
                    // Two taps at once can both pass the check; the second loses here and gets the conflict, nothing is queued for it.
                    if (input.inReplyTo != null && feed.chooseOption(input.inReplyTo!!, input.text, input.clientId) == null) {
                        // Lost the race, or a retry of the request that won: only the latter goes on.
                        val current = feed.get(input.inReplyTo!!)
                        val mine = (checkChoice(current, input.text) as? ChoiceCheck.Answered)?.by == input.clientId.value
                        if (!mine && current != null) return@post call.respond(HttpStatusCode.Conflict, InputAccepted(current))
                    }
                    // The card, the person's message and the lead's inbox are three stores with no shared transaction, so
                    // a failure after the card was marked takes the mark back: the card must not read answered on every
                    // device while the lead never hears it. (A crash that skips this is healed by the same client's retry.)
                    val appended = try {
                        val saved = feed.append(
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
                        saved
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        withContext(NonCancellable) { input.inReplyTo?.let { feed.unchooseOption(it, input.clientId) } }
                        throw e
                    } catch (e: Exception) {
                        input.inReplyTo?.let { feed.unchooseOption(it, input.clientId) }
                        throw e
                    }
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
