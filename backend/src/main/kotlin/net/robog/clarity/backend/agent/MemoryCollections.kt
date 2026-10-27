package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import net.robog.clarity.backend.persistence.tx
import org.jetbrains.exposed.v1.jdbc.Database

data class MemoryCollection(val id: Long, val name: String, val description: String, val notes: Int, val updatedAt: Instant)

data class MemoryNote(val id: Long, val collection: String, val text: String, val source: String?, val updatedAt: Instant)

/** Named collections of notes: the agent's searchable long-term memory by topic. */
interface CollectionStore {
    /** All collections, by name. */
    suspend fun collections(): List<MemoryCollection>

    suspend fun find(name: String): MemoryCollection?

    /** Creates [name]; null when it exists already. */
    suspend fun create(name: String, description: String): MemoryCollection?

    suspend fun describe(name: String, description: String): Boolean

    suspend fun rename(name: String, newName: String): Boolean

    /** Deletes an empty collection; false when it is missing or still has notes. */
    suspend fun deleteEmpty(name: String): Boolean

    /** Moves every note of [from] into [into] (dropping exact duplicates) and deletes [from]; the number of notes moved. */
    suspend fun merge(from: String, into: String): Int

    suspend fun addNote(collectionId: Long, text: String, source: String?): Long

    suspend fun note(id: Long): MemoryNote?

    /** Replaces a note's text and, when [collectionId] is given, moves it there. */
    suspend fun editNote(id: Long, text: String, collectionId: Long? = null): Boolean

    suspend fun removeNote(id: Long): Boolean

    /** Notes of [collectionId]: matching [query] (full-text, else substring) best first, or the most recent. */
    suspend fun notes(collectionId: Long, query: String?, limit: Int): List<MemoryNote>
}

/** Pure: a collection name as stored: lowercase letters, digits and dashes, at most 40; null when nothing is left. */
fun collectionSlug(raw: String): String? =
    raw.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trimEnd('-').takeIf { it.isNotEmpty() }

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExposedCollectionStore(private val database: Database, private val clock: Clock) : CollectionStore {
    private fun now() = java.sql.Timestamp.from(clock.now().toJavaInstant())

    override suspend fun collections(): List<MemoryCollection> = database.tx {
        jdbc().prepareStatement(
            "SELECT c.id, c.name, c.description, count(n.id), c.updated_at FROM memory_collection c " +
                "LEFT JOIN memory_note n ON n.collection_id = c.id GROUP BY c.id ORDER BY c.name",
        ).use { st -> st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.collection()) } } }
    }

    override suspend fun find(name: String): MemoryCollection? = database.tx {
        jdbc().prepareStatement(
            "SELECT c.id, c.name, c.description, (SELECT count(*) FROM memory_note n WHERE n.collection_id = c.id), c.updated_at " +
                "FROM memory_collection c WHERE c.name = ?",
        ).use { st ->
            st.setString(1, name)
            st.executeQuery().use { rs -> if (rs.next()) rs.collection() else null }
        }
    }

    override suspend fun create(name: String, description: String): MemoryCollection? {
        val created = database.tx {
            jdbc().prepareStatement(
                "INSERT INTO memory_collection (name, description, created_at, updated_at) VALUES (?, ?, ?, ?) ON CONFLICT (name) DO NOTHING",
            ).use { st ->
                st.setString(1, name)
                st.setString(2, description)
                st.setTimestamp(3, now())
                st.setTimestamp(4, now())
                st.executeUpdate()
            }
        }
        return if (created == 1) find(name) else null
    }

    override suspend fun describe(name: String, description: String): Boolean =
        update("UPDATE memory_collection SET description = ?, updated_at = ? WHERE name = ?", description, now(), name) == 1

    override suspend fun rename(name: String, newName: String): Boolean = database.tx {
        jdbc().prepareStatement(
            "UPDATE memory_collection SET name = ?, updated_at = ? WHERE name = ? AND NOT EXISTS (SELECT 1 FROM memory_collection WHERE name = ?)",
        ).use { st ->
            st.setString(1, newName)
            st.setTimestamp(2, now())
            st.setString(3, name)
            st.setString(4, newName)
            st.executeUpdate() == 1
        }
    }

    override suspend fun deleteEmpty(name: String): Boolean =
        update("DELETE FROM memory_collection c WHERE c.name = ? AND NOT EXISTS (SELECT 1 FROM memory_note n WHERE n.collection_id = c.id)", name) == 1

    override suspend fun merge(from: String, into: String): Int = database.tx {
        val c = jdbc()
        fun id(name: String): Long? = c.prepareStatement("SELECT id FROM memory_collection WHERE name = ?").use { st ->
            st.setString(1, name)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }
        val source = id(from) ?: return@tx -1
        val target = id(into) ?: return@tx -1
        if (source == target) return@tx 0
        // exact duplicates of a note already in the target go away
        c.prepareStatement(
            "DELETE FROM memory_note s WHERE s.collection_id = ? AND EXISTS (SELECT 1 FROM memory_note t WHERE t.collection_id = ? AND lower(t.text) = lower(s.text))",
        ).use { st -> st.setLong(1, source); st.setLong(2, target); st.executeUpdate() }
        val moved = c.prepareStatement("UPDATE memory_note SET collection_id = ? WHERE collection_id = ?").use { st ->
            st.setLong(1, target); st.setLong(2, source); st.executeUpdate()
        }
        c.prepareStatement("DELETE FROM memory_collection WHERE id = ?").use { st -> st.setLong(1, source); st.executeUpdate() }
        c.prepareStatement("UPDATE memory_collection SET updated_at = ? WHERE id = ?").use { st -> st.setTimestamp(1, now()); st.setLong(2, target); st.executeUpdate() }
        moved
    }

    override suspend fun addNote(collectionId: Long, text: String, source: String?): Long = database.tx {
        val c = jdbc()
        c.prepareStatement("UPDATE memory_collection SET updated_at = ? WHERE id = ?").use { st -> st.setTimestamp(1, now()); st.setLong(2, collectionId); st.executeUpdate() }
        c.prepareStatement(
            "INSERT INTO memory_note (collection_id, text, source, created_at, updated_at) VALUES (?, ?, ?, ?, ?) RETURNING id",
        ).use { st ->
            st.setLong(1, collectionId)
            st.setString(2, text)
            st.setString(3, source)
            st.setTimestamp(4, now())
            st.setTimestamp(5, now())
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    override suspend fun note(id: Long): MemoryNote? = database.tx {
        jdbc().prepareStatement("$NOTE_SELECT WHERE n.id = ?").use { st ->
            st.setLong(1, id)
            st.executeQuery().use { rs -> if (rs.next()) rs.note() else null }
        }
    }

    override suspend fun editNote(id: Long, text: String, collectionId: Long?): Boolean =
        if (collectionId == null) update("UPDATE memory_note SET text = ?, updated_at = ? WHERE id = ?", text, now(), id) == 1
        else update("UPDATE memory_note SET text = ?, collection_id = ?, updated_at = ? WHERE id = ?", text, collectionId, now(), id) == 1

    override suspend fun removeNote(id: Long): Boolean = update("DELETE FROM memory_note WHERE id = ?", id) == 1

    override suspend fun notes(collectionId: Long, query: String?, limit: Int): List<MemoryNote> = database.tx {
        val c = jdbc()
        val n = limit.coerceIn(1, 100)
        fun run(sql: String, vararg args: Any): List<MemoryNote> = c.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.note()) } }
        }
        if (query.isNullOrBlank()) {
            run("$NOTE_SELECT WHERE n.collection_id = ? ORDER BY n.updated_at DESC, n.id DESC LIMIT ?", collectionId, n)
        } else {
            run(
                "$NOTE_SELECT, websearch_to_tsquery('english', ?) q WHERE n.collection_id = ? AND n.tsv @@ q ORDER BY ts_rank(n.tsv, q) DESC, n.id DESC LIMIT ?",
                query, collectionId, n,
            ).ifEmpty {
                run("$NOTE_SELECT WHERE n.collection_id = ? AND n.text ILIKE ? ORDER BY n.updated_at DESC LIMIT ?", collectionId, "%${likeEscape(query.trim())}%", n)
            }
        }
    }

    private suspend fun update(sql: String, vararg args: Any): Int = database.tx {
        jdbc().prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeUpdate()
        }
    }

    private fun java.sql.ResultSet.collection() =
        MemoryCollection(getLong(1), getString(2), getString(3), getInt(4), getTimestamp(5).toInstant().toKotlinInstant())

    private fun java.sql.ResultSet.note() =
        MemoryNote(getLong(1), getString(2), getString(3), getString(4), getTimestamp(5).toInstant().toKotlinInstant())

    private fun likeEscape(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private companion object {
        const val NOTE_SELECT = "SELECT n.id, c.name, n.text, n.source, n.updated_at FROM memory_note n JOIN memory_collection c ON c.id = n.collection_id"
    }
}

/**
 * Pure: the collection index for the lead's prompt, one line per collection (name: description),
 * at most [maxEntries] with descriptions cut to [maxDescription] characters. Null when there are
 * none. Note counts are left out on purpose: the index changes only when a collection is created,
 * renamed, merged or re-described, so it is rarely re-sent.
 */
fun renderCollectionIndex(collections: List<MemoryCollection>, maxEntries: Int = 40, maxDescription: Int = 90): String? {
    if (collections.isEmpty()) return null
    val sorted = collections.sortedBy { it.name }
    return buildString {
        append(COLLECTION_INDEX_HEADER)
        sorted.take(maxEntries).forEach { c ->
            val d = c.description.replace(Regex("\\s+"), " ").trim()
            append("\n- ").append(c.name).append(": ").append(if (d.length > maxDescription) d.take(maxDescription - 1).trimEnd() + "…" else d)
        }
        if (sorted.size > maxEntries) append("\n- … and ").append(sorted.size - maxEntries).append(" more (open_collection without a name lists all)")
    }
}

const val COLLECTION_INDEX_HEADER = "[memory collections] Your notes by topic; open_collection reads one, save_note adds to one:"
