package net.robog.clarity.app.data.feed

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.net.FeedBackend
import net.robog.clarity.app.net.FeedEventSource
import net.robog.clarity.app.net.FeedStreamException
import net.robog.clarity.shared.feed.FeedEvent
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.session.SessionId

/** Exponential backoff: [initial] doubling up to [max]. Pure. */
data class Backoff(val initial: Duration = 1.seconds, val max: Duration = 60.seconds) {
    fun delayFor(attempt: Int): Duration {
        var d = initial
        repeat(attempt.coerceAtLeast(0)) {
            d *= 2
            if (d >= max) return max
        }
        return minOf(d, max)
    }
}

enum class SyncState {
    /** Not started or between attempts. */
    Connecting,
    /** The stream is open. */
    Live,
    /** Network or server trouble; retrying. */
    Offline,
    /** The server rejected this device (revoked or JWT refused). */
    Unauthorized,
    /** The server has no feed route yet. */
    Unavailable,
}

/** Things other screens react to without caring about the feed itself. */
sealed interface SyncSignal {
    data class ConstitutionChanged(val version: Int) : SyncSignal
    data class SessionChanged(val sessionId: SessionId) : SyncSignal
}

/**
 * Something that reached this phone for the first time, or a held item the server just released:
 * the push service decides if it deserves a notification.
 */
sealed interface Arrival {
    data class Item(val item: FeedItem) : Arrival
    data class Held(val action: HeldAction) : Arrival
}

/**
 * Keeps [FeedStore] current: fills the gap after the last cached `seq` through `GET /feed?after=`,
 * then holds `GET /events` open and applies each [FeedEvent]. When the stream drops it reconnects
 * with backoff (each attempt mints a fresh device JWT through the HTTP client), fills the gap again
 * and resumes from the last `seq`. Duplicates are harmless: items are keyed by `seq`.
 */
class FeedSync(
    private val backend: FeedBackend,
    private val source: FeedEventSource,
    private val store: FeedStore,
    private val backoff: Backoff = Backoff(),
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
) {
    private val _state = MutableStateFlow(SyncState.Connecting)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    /** The agent is thinking or working. */
    val agentBusy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _signals = MutableSharedFlow<SyncSignal>(extraBufferCapacity = 16)
    val signals: SharedFlow<SyncSignal> = _signals.asSharedFlow()

    private val _arrivals = MutableSharedFlow<Arrival>(extraBufferCapacity = 64)
    /** New feed items (once complete), released holds and newly held actions, once each; the push service and its fallback listen. */
    val arrivals: SharedFlow<Arrival> = _arrivals.asSharedFlow()

    private val _caughtUp = MutableSharedFlow<List<Arrival>>(extraBufferCapacity = 8)
    /**
     * What one (re)connect's gap fill and held reload brought, in one list instead of one by one on
     * [arrivals]: after a long gap these are things missed, which notify as such (a summary rather
     * than a burst; see `decideMissed`).
     */
    val caughtUp: SharedFlow<List<Arrival>> = _caughtUp.asSharedFlow()

    private val nudges = Channel<Unit>(Channel.CONFLATED)

    /**
     * The network changed (see `reconnectOn`): drop a stream that may be dead and reconnect at once,
     * or cut a backoff wait short. The next connection still resumes from the last `seq`.
     */
    fun reconnectNow() {
        nudges.trySend(Unit)
    }

    /** The server was forgotten: what was known about its connection does not carry over. */
    fun forgetServer() {
        _busy.value = false
        _state.value = SyncState.Connecting
    }

    private class NetworkChanged : Exception()

    /** Waits out [delay]; returns true if [reconnectNow] cut it short. */
    private suspend fun pause(delay: Duration): Boolean = coroutineScope {
        val nudged = async { nudges.receive(); true }
        val timer = async { sleep(delay); false }
        val cut = select<Boolean> {
            nudged.onAwait { it }
            timer.onAwait { it }
        }
        nudged.cancel()
        timer.cancel()
        cut
    }

    /** Runs until cancelled. */
    suspend fun run() {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            _state.value = SyncState.Connecting
            try {
                val missed = mutableListOf<Arrival>()
                try {
                    fillGap(missed::add)
                    refreshHeld(missed::add)
                } finally {
                    // Also what arrived before a failure part way: it is cached now, so it would not come again.
                    if (missed.isNotEmpty()) _caughtUp.tryEmit(missed.toList())
                }
                coroutineScope {
                    val watcher = launch { nudges.receive(); throw NetworkChanged() }
                    try {
                        source.connect(
                            after = store.lastSeq(),
                            onOpen = { attempt = 0; _state.value = SyncState.Live },
                            onEvent = ::apply,
                        )
                    } finally {
                        watcher.cancel()
                    }
                }
                // The server closed the stream cleanly: reconnect (still with a small pause).
                _state.value = SyncState.Offline
            } catch (e: NetworkChanged) {
                // Reconnect straight away with a fresh backoff.
                attempt = 0
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: GapFailed) {
                _state.value = e.state
            } catch (e: FeedStreamException) {
                _state.value = when (e.status) {
                    401, 403 -> SyncState.Unauthorized
                    404 -> SyncState.Unavailable
                    else -> SyncState.Offline
                }
            } catch (_: Exception) {
                _state.value = SyncState.Offline
            }
            if (pause(backoff.delayFor(attempt))) attempt = 0 else attempt++
        }
    }

    private class GapFailed(val state: SyncState) : Exception()

    /** What one [catchUp] pass brought: the first-time arrivals, and whether every request got through. */
    data class CatchUp(val arrived: List<Arrival>, val complete: Boolean)

    /**
     * One pass without the stream, for the background fallback: fills the gap and reloads the held
     * list. The arrivals are returned (not sent on [arrivals]): the caller notifies them as missed.
     * A failure part way keeps what already arrived.
     */
    suspend fun catchUp(): CatchUp {
        val arrived = mutableListOf<Arrival>()
        return try {
            fillGap(arrived::add)
            refreshHeld(arrived::add)
            CatchUp(arrived, complete = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            CatchUp(arrived, complete = false)
        }
    }

    /**
     * Pages through the feed from [resumePoint] until the server says there is no more. Starting
     * before a cached item that is still streaming, or still held, fetches its final version or its
     * release if it was missed (the fallback has no stream to hear them on).
     */
    suspend fun fillGap(onArrival: ((Arrival) -> Unit)? = null) {
        var after = resumePoint()
        while (true) {
            when (val result = backend.feed(after)) {
                is ApiResult.Failure -> throw GapFailed(
                    when (result.error) {
                        is ApiError.Unauthorized, is ApiError.Forbidden -> SyncState.Unauthorized
                        is ApiError.NotFound -> SyncState.Unavailable
                        else -> SyncState.Offline
                    },
                )
                is ApiResult.Success -> {
                    val page = result.value
                    page.items.sortedBy { it.seq }.forEach { storeItem(it, final = true, onArrival) }
                    val next = page.items.maxOfOrNull { it.seq } ?: return
                    // No progress means nothing new; stop even if the server claims more.
                    if (!page.hasMore || next <= after) return
                    after = next
                }
            }
        }
    }

    /** The last cached seq, or just before the oldest recent item still streaming or held. */
    private suspend fun resumePoint(): Long {
        val last = store.lastSeq()
        val open = store.recentItems(STREAMING_LOOKBACK).filter { it.streaming || isHeld(it) }.minOfOrNull { it.seq } ?: return last
        return minOf(last, open - 1)
    }

    /**
     * Stores [item]; it arrives (for notifications) once, when the cache first holds it complete:
     * first seen already final, or a streaming copy being finished. Partials never arrive. A held
     * item arrives a second time, once, when the server releases it.
     */
    private suspend fun storeItem(item: FeedItem, final: Boolean, onArrival: ((Arrival) -> Unit)? = null) {
        val existing = store.itemAt(item.seq)
        mergeItem(existing, item, final)?.let {
            store.upsertItem(it)
            // A topic marker is a pill in the chat, never a notification.
            if (it.topic == null && (becameComplete(existing, it) || holdReleased(existing, it))) arrive(Arrival.Item(it), onArrival)
        }
    }

    /** Live on [arrivals], or to [onArrival] when a gap fill or catch-up gathers them. */
    private fun arrive(arrival: Arrival, onArrival: ((Arrival) -> Unit)?) {
        if (onArrival != null) onArrival(arrival) else _arrivals.tryEmit(arrival)
    }

    /** Reloads the held list; actions that are waiting and new to this phone arrive once. */
    private suspend fun refreshHeld(onArrival: ((Arrival) -> Unit)? = null) {
        val fresh = (backend.held() as? ApiResult.Success)?.value ?: return
        val known = store.held.first().filter { it.status == HeldAction.Status.HELD }.map { it.id }.toSet()
        store.replaceHeld(fresh)
        fresh.filter { it.status == HeldAction.Status.HELD && it.id !in known }.forEach { arrive(Arrival.Held(it), onArrival) }
    }

    internal suspend fun apply(event: FeedEvent) {
        when (event) {
            is FeedEvent.ItemAppended -> storeItem(event.item, final = false)
            is FeedEvent.ItemFinal -> storeItem(event.item, final = true)
            is FeedEvent.TextDelta -> {
                // A delta for an item we never saw is dropped; the gap fill on reconnect brings it.
                store.item(event.itemId)?.let { store.upsertItem(it.withDelta(event.text)) }
            }
            is FeedEvent.TaskChanged -> store.upsertTask(event.task)
            is FeedEvent.HeldChanged -> refreshHeld()
            is FeedEvent.ConstitutionChanged -> _signals.tryEmit(SyncSignal.ConstitutionChanged(event.version))
            is FeedEvent.SessionChanged -> _signals.tryEmit(SyncSignal.SessionChanged(event.sessionId))
            is FeedEvent.Busy -> _busy.value = event.busy
        }
    }
}

/** Whether storing [stored] over [existing] is the first time the cache holds that item complete. */
fun becameComplete(existing: FeedItem?, stored: FeedItem): Boolean =
    !stored.streaming && (existing == null || existing.streaming)

/** How far back [FeedSync.fillGap] looks for items left streaming (a reply cut off by a dropped stream). */
private const val STREAMING_LOOKBACK = 50
