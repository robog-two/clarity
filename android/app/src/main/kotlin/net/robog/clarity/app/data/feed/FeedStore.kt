package net.robog.clarity.app.data.feed

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.FeedItemId
import net.robog.clarity.shared.governance.HeldAction

/**
 * Local cache of what the person sees, so the app opens instantly. The synchronizer is the only
 * writer; screens observe the flows. Items are keyed by their server `seq`, so replays are idempotent.
 */
interface FeedStore {
    /** All cached items, oldest first. */
    val items: Flow<List<FeedItem>>
    val tasks: Flow<List<AgentTask>>
    val held: Flow<List<HeldAction>>

    /** Highest cached seq, or 0 when empty (the server's seqs start above 0). */
    suspend fun lastSeq(): Long

    /** The newest [limit] cached items, oldest first. */
    suspend fun recentItems(limit: Int): List<FeedItem> = items.first().takeLast(limit)
    suspend fun item(id: FeedItemId): FeedItem?
    suspend fun itemAt(seq: Long): FeedItem?
    suspend fun upsertItem(item: FeedItem)
    suspend fun upsertTask(task: AgentTask)
    suspend fun replaceHeld(held: List<HeldAction>)
    suspend fun clear()
}
