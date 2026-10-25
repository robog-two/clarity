package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.uuid.Uuid
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.persistence.wire
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** Wakes the lead when something lands in the inbox. Conflated: a signal sent while nobody waits is not lost. */
@Inject
@SingleIn(AppScope::class)
class InboxSignals {
    private val channel = Channel<Unit>(Channel.CONFLATED)
    fun signal() { channel.trySend(Unit) }

    /** Suspends until signalled or [timeout] passes. */
    suspend fun await(timeout: Duration) { withTimeoutOrNull(timeout) { channel.receive() } }
}

/** The durable inbox (`inbox_item`). Enqueueing is idempotent by [dedupKey]. */
interface InboxStore {
    /** Adds [item]; false if an item with the same non-null [dedupKey] already exists. */
    suspend fun enqueue(item: InboxItem, dedupKey: String? = null): Boolean

    /** Unconsumed items in arrival order. */
    suspend fun pending(limit: Int = 500): List<PendingItem>

    /** Marks items consumed; call only after the turn carrying them is journaled. */
    suspend fun markConsumed(seqs: Collection<Long>)
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExposedInboxStore(
    private val database: Database,
    private val clock: Clock,
    private val signals: InboxSignals,
) : InboxStore {
    override suspend fun enqueue(item: InboxItem, dedupKey: String?): Boolean {
        val inserted = database.tx { enqueueTx(item, dedupKey) }
        if (inserted) signals.signal()
        return inserted
    }

    /** [enqueue] inside the caller's transaction (the scheduler enqueues atomically with advancing a job). */
    fun JdbcTransaction.enqueueTx(item: InboxItem, dedupKey: String?): Boolean =
        InboxItems.insertIgnore {
            it[id] = Uuid.random()
            it[priority] = item.priority.wire()
            it[InboxItems.item] = item
            it[InboxItems.dedupKey] = dedupKey
            it[createdAt] = clock.now()
        }.insertedCount > 0

    /** Wakes the lead after a transaction that enqueued work committed. */
    fun wake() = signals.signal()

    override suspend fun pending(limit: Int): List<PendingItem> = database.tx {
        InboxItems.selectAll().where { InboxItems.consumedAt.isNull() }.orderBy(InboxItems.seq).limit(limit)
            .map { PendingItem(it[InboxItems.seq], it[InboxItems.item], it[InboxItems.createdAt]) }
    }

    override suspend fun markConsumed(seqs: Collection<Long>) {
        if (seqs.isEmpty()) return
        val now = clock.now()
        database.tx { InboxItems.update({ InboxItems.seq inList seqs.toList() }) { it[consumedAt] = now } }
    }
}
