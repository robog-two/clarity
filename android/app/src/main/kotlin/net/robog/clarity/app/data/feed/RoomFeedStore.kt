package net.robog.clarity.app.data.feed

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.FeedItemId
import net.robog.clarity.shared.governance.HeldAction

// Room stores each shared DTO as its wire JSON: the schema never has to follow the contracts,
// and a contract change only needs the cache to be cleared (it is rebuilt from the server).

@Entity(tableName = "feed_item", indices = [Index("id", unique = true)])
data class FeedItemRow(@PrimaryKey val seq: Long, val id: String, val json: String)

@Entity(tableName = "agent_task")
data class TaskRow(@PrimaryKey val id: String, val updatedAt: Long, val json: String)

@Entity(tableName = "held_action")
data class HeldRow(@PrimaryKey val id: String, val createdAt: Long, val json: String)

@Dao
interface FeedDao {
    @Query("SELECT json FROM feed_item ORDER BY seq ASC")
    fun items(): Flow<List<String>>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM feed_item")
    suspend fun lastSeq(): Long

    @Query("SELECT json FROM feed_item ORDER BY seq DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<String>

    @Query("SELECT json FROM feed_item WHERE id = :id")
    suspend fun itemById(id: String): String?

    @Query("SELECT json FROM feed_item WHERE seq = :seq")
    suspend fun itemBySeq(seq: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: FeedItemRow)

    @Query("SELECT json FROM agent_task ORDER BY updatedAt DESC")
    fun tasks(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: TaskRow)

    @Query("SELECT json FROM held_action ORDER BY createdAt DESC")
    fun held(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHeld(rows: List<HeldRow>)

    @Query("DELETE FROM held_action")
    suspend fun clearHeld()

    @Transaction
    suspend fun replaceHeld(rows: List<HeldRow>) {
        clearHeld()
        insertHeld(rows)
    }

    @Query("DELETE FROM feed_item")
    suspend fun clearItems()

    @Query("DELETE FROM agent_task")
    suspend fun clearTasks()

    @Transaction
    suspend fun clearAll() {
        clearItems()
        clearTasks()
        clearHeld()
    }
}

@Database(entities = [FeedItemRow::class, TaskRow::class, HeldRow::class], version = 1, exportSchema = false)
abstract class ClarityDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
}

/**
 * The feed cache. Every table change re-reads and re-decodes the whole list, which during a streamed
 * reply happens many times a second, so decoding runs on [decode] (a background pool) and only the
 * newest list is kept when the screen falls behind.
 */
class RoomFeedStore(
    private val dao: FeedDao,
    private val decode: CoroutineDispatcher = Dispatchers.Default,
) : FeedStore {
    private val json = ClarityJson

    override val items: Flow<List<FeedItem>> =
        dao.items().map { rows -> rows.map { json.decodeFromString(FeedItem.serializer(), it) } }.offMain()
    override val tasks: Flow<List<AgentTask>> =
        dao.tasks().map { rows -> rows.map { json.decodeFromString(AgentTask.serializer(), it) } }.offMain()
    override val held: Flow<List<HeldAction>> =
        dao.held().map { rows -> rows.map { json.decodeFromString(HeldAction.serializer(), it) } }.offMain()

    private fun <T> Flow<T>.offMain(): Flow<T> = flowOn(decode).conflate()

    override suspend fun lastSeq(): Long = dao.lastSeq()

    override suspend fun recentItems(limit: Int): List<FeedItem> =
        withContext(decode) { dao.recent(limit).map { json.decodeFromString(FeedItem.serializer(), it) }.reversed() }

    override suspend fun item(id: FeedItemId): FeedItem? =
        dao.itemById(id.value)?.let { json.decodeFromString(FeedItem.serializer(), it) }

    override suspend fun itemAt(seq: Long): FeedItem? =
        dao.itemBySeq(seq)?.let { json.decodeFromString(FeedItem.serializer(), it) }

    override suspend fun upsertItem(item: FeedItem) =
        dao.upsert(FeedItemRow(item.seq, item.id.value, json.encodeToString(FeedItem.serializer(), item)))

    override suspend fun upsertTask(task: AgentTask) =
        dao.upsert(TaskRow(task.id.value, task.updatedAt.toEpochMilliseconds(), json.encodeToString(AgentTask.serializer(), task)))

    override suspend fun replaceHeld(held: List<HeldAction>) =
        dao.replaceHeld(held.map {
            HeldRow(it.id.value, it.createdAt.toEpochMilliseconds(), json.encodeToString(HeldAction.serializer(), it))
        })

    override suspend fun clear() = dao.clearAll()
}
