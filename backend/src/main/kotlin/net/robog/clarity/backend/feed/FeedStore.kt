package net.robog.clarity.backend.feed

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.uuid.Uuid
import net.robog.clarity.backend.agent.FeedItems
import net.robog.clarity.backend.persistence.parseWire
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.persistence.wire
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.NOTIFY_IN_APP
import net.robog.clarity.shared.feed.FeedItemId
import net.robog.clarity.shared.feed.FeedPage
import net.robog.clarity.shared.session.SessionId
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** An item to append. [batchPending] items stay out of the feed until the evening summary. */
data class FeedDraft(
    val author: FeedItem.Author,
    val cards: List<Card>,
    val sessionId: SessionId? = null,
    val streaming: Boolean = false,
    /** Set for user input: appending twice with the same id returns the first item. */
    val clientInputId: ClientInputId? = null,
    /** The notification decision (`push_now`, `in_app`, `hold_until:<iso>`, `evening_batch:<iso>`). */
    val notify: String? = null,
    val batchPending: Boolean = false,
)

data class Appended(val item: FeedItem, val created: Boolean)

/** The feed's storage (`feed_item`). Sequence numbers are assigned by the database and strictly increase. */
interface FeedStore {
    suspend fun append(draft: FeedDraft): Appended

    /** Visible items with `seq > after`, oldest first. */
    suspend fun page(after: Long, limit: Int): FeedPage

    suspend fun get(id: FeedItemId): FeedItem?

    /** Replaces the cards of an item and its streaming flag (a streaming agent message being completed). */
    suspend fun update(id: FeedItemId, cards: List<Card>, streaming: Boolean): FeedItem?

    /** Items waiting for the evening summary, oldest first. */
    suspend fun pendingBatch(): List<FeedItem>

    suspend fun markSummarized(ids: Collection<FeedItemId>)

    /** The recorded notification decision of an item. */
    suspend fun notifyOf(id: FeedItemId): String?
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExposedFeedStore(private val database: Database, private val clock: Clock) : FeedStore {
    override suspend fun append(draft: FeedDraft): Appended = database.tx {
        val id = Uuid.random()
        val inserted = FeedItems.insertIgnore {
            it[FeedItems.id] = id
            it[at] = clock.now()
            it[author] = draft.author.wire()
            it[sessionId] = draft.sessionId?.value
            it[cards] = draft.cards
            it[streaming] = draft.streaming
            it[clientInputId] = draft.clientInputId?.value
            it[notify] = draft.notify
            it[batchState] = if (draft.batchPending) PENDING else null
        }.insertedCount > 0
        if (inserted) {
            Appended(FeedItems.selectAll().where { FeedItems.id eq id }.single().toItem(), created = true)
        } else {
            // Lost a race on client_input_id: the first writer's item is the answer.
            val existing = FeedItems.selectAll().where { FeedItems.clientInputId eq draft.clientInputId?.value }.single()
            Appended(existing.toItem(), created = false)
        }
    }

    override suspend fun page(after: Long, limit: Int): FeedPage = database.tx {
        val rows = FeedItems.selectAll()
            .where { (FeedItems.seq greater after) and FeedItems.batchState.isNull() }
            .orderBy(FeedItems.seq).limit(limit + 1).map { it.toItem() }
        FeedPage(rows.take(limit), hasMore = rows.size > limit)
    }

    override suspend fun get(id: FeedItemId): FeedItem? {
        val uuid = runCatching { Uuid.parse(id.value) }.getOrNull() ?: return null
        return database.tx { FeedItems.selectAll().where { FeedItems.id eq uuid }.singleOrNull()?.toItem() }
    }

    override suspend fun update(id: FeedItemId, cards: List<Card>, streaming: Boolean): FeedItem? {
        val uuid = runCatching { Uuid.parse(id.value) }.getOrNull() ?: return null
        return database.tx {
            FeedItems.update({ FeedItems.id eq uuid }) {
                it[FeedItems.cards] = cards
                it[FeedItems.streaming] = streaming
            }
            FeedItems.selectAll().where { FeedItems.id eq uuid }.singleOrNull()?.toItem()
        }
    }

    override suspend fun pendingBatch(): List<FeedItem> = database.tx {
        FeedItems.selectAll().where { FeedItems.batchState eq PENDING }.orderBy(FeedItems.seq).map { it.toItem() }
    }

    override suspend fun markSummarized(ids: Collection<FeedItemId>) {
        val uuids = ids.mapNotNull { runCatching { Uuid.parse(it.value) }.getOrNull() }
        if (uuids.isEmpty()) return
        database.tx { FeedItems.update({ FeedItems.id inList uuids }) { it[batchState] = SUMMARIZED } }
    }

    override suspend fun notifyOf(id: FeedItemId): String? {
        val uuid = runCatching { Uuid.parse(id.value) }.getOrNull() ?: return null
        return database.tx { FeedItems.selectAll().where { FeedItems.id eq uuid }.singleOrNull()?.get(FeedItems.notify) }
    }

    private companion object {
        const val PENDING = "pending"
        const val SUMMARIZED = "summarized"
    }
}

private fun ResultRow.toItem() = FeedItem(
    id = FeedItemId(this[FeedItems.id].toString()),
    seq = this[FeedItems.seq],
    at = this[FeedItems.at],
    author = parseWire<FeedItem.Author>(this[FeedItems.author]) ?: FeedItem.Author.AGENT,
    sessionId = this[FeedItems.sessionId]?.let(::SessionId),
    cards = this[FeedItems.cards],
    streaming = this[FeedItems.streaming],
    notify = this[FeedItems.notify] ?: NOTIFY_IN_APP,
)
