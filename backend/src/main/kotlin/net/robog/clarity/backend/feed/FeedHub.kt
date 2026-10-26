package net.robog.clarity.backend.feed

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Instant
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedEvent
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.FeedItemId

/**
 * Hot, in-process fan-out of [FeedEvent]s to SSE subscribers. Publishing never suspends. A slow
 * subscriber loses its oldest events, which is safe: clients resume from their last item `seq`
 * (items are replayed from the store), and the other events are state hints that are re-fetched.
 */
@Inject
@SingleIn(AppScope::class)
class FeedHub {
    private val flow = MutableSharedFlow<FeedEvent>(extraBufferCapacity = CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<FeedEvent> = flow.asSharedFlow()

    fun publish(event: FeedEvent) {
        flow.tryEmit(event)
    }

    private companion object { const val CAPACITY = 512 }
}

/** Appends to the feed and tells subscribers. Items waiting for the evening summary are not announced. */
@Inject
@SingleIn(AppScope::class)
class FeedService(private val store: FeedStore, private val hub: FeedHub) {
    suspend fun append(draft: FeedDraft): Appended {
        val appended = store.append(draft)
        if (appended.created && !draft.batchPending) hub.publish(FeedEvent.ItemAppended(appended.item))
        return appended
    }

    /** Streams text into an item whose last card is text. */
    fun delta(itemId: FeedItemId, text: String) = hub.publish(FeedEvent.TextDelta(itemId, text))

    /** Finishes a streaming item with its final cards. */
    suspend fun finish(itemId: FeedItemId, cards: List<Card>): FeedItem? =
        store.update(itemId, cards, streaming = false)?.also { hub.publish(FeedEvent.ItemFinal(it)) }

    fun publish(event: FeedEvent) = hub.publish(event)

    /** Items whose held notification was released at or after [since] (resent to a reconnecting phone). */
    suspend fun releasedSince(since: Instant) = store.releasedSince(since)

    suspend fun page(after: Long, limit: Int) = store.page(after, limit)
}
