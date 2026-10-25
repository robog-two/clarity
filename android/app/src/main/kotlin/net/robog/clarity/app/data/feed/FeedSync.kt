package net.robog.clarity.app.data.feed

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.net.FeedBackend
import net.robog.clarity.app.net.FeedEventSource
import net.robog.clarity.app.net.FeedStreamException
import net.robog.clarity.shared.feed.FeedEvent
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

    /** Runs until cancelled. */
    suspend fun run() {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            _state.value = SyncState.Connecting
            try {
                fillGap()
                source.connect(
                    after = store.lastSeq(),
                    onOpen = { attempt = 0; _state.value = SyncState.Live },
                    onEvent = ::apply,
                )
                // The server closed the stream cleanly: reconnect (still with a small pause).
                _state.value = SyncState.Offline
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
            sleep(backoff.delayFor(attempt))
            attempt++
        }
    }

    private class GapFailed(val state: SyncState) : Exception()

    /** Pages through the feed after the last cached seq until the server says there is no more. */
    suspend fun fillGap() {
        while (true) {
            val after = store.lastSeq()
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
                    page.items.sortedBy { it.seq }.forEach { storeItem(it, final = true) }
                    // No progress means nothing new; stop even if the server claims more.
                    if (!page.hasMore || page.items.isEmpty() || store.lastSeq() <= after) return
                }
            }
        }
    }

    private suspend fun storeItem(item: net.robog.clarity.shared.feed.FeedItem, final: Boolean) {
        mergeItem(store.itemAt(item.seq), item, final)?.let { store.upsertItem(it) }
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
            is FeedEvent.HeldChanged -> (backend.held() as? ApiResult.Success)?.let { store.replaceHeld(it.value) }
            is FeedEvent.ConstitutionChanged -> _signals.tryEmit(SyncSignal.ConstitutionChanged(event.version))
            is FeedEvent.SessionChanged -> _signals.tryEmit(SyncSignal.SessionChanged(event.sessionId))
            is FeedEvent.Busy -> _busy.value = event.busy
        }
    }
}
