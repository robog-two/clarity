package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.sql.ResultSet
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import net.robog.clarity.backend.persistence.tx
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

data class JournalEntry(val id: Long, val date: LocalDate, val text: String)

data class ScratchNote(val id: Long, val text: String, val expiresAt: Instant)

/** A search hit from [MemoryStore.search]; [source] is `journal`, `feed` or `message`. */
data class MemoryHit(val source: String, val ref: String, val text: String)

/** Journal, scratch and full-text search: the memory tiers below the constitution (all in Postgres). */
interface MemoryStore {
    suspend fun writeJournal(date: LocalDate, text: String): Long

    /** Entries with `from <= date <= to`, oldest first. */
    suspend fun journal(from: LocalDate, to: LocalDate): List<JournalEntry>

    suspend fun setScratch(text: String, ttl: Duration): Long

    /** Notes that have not expired at [now]. */
    suspend fun liveScratch(now: Instant): List<ScratchNote>

    /** Deletes expired notes; returns how many. */
    suspend fun purgeScratch(now: Instant): Int

    /** Full-text search over the journal, the feed and messages, best matches first. */
    suspend fun search(query: String, limit: Int): List<MemoryHit>
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExposedMemoryStore(private val database: Database, private val clock: Clock) : MemoryStore {
    override suspend fun writeJournal(date: LocalDate, text: String): Long = database.tx {
        MemoryJournal.insert {
            it[MemoryJournal.date] = date
            it[MemoryJournal.text] = text
            it[createdAt] = clock.now()
        }[MemoryJournal.id]
    }

    override suspend fun journal(from: LocalDate, to: LocalDate): List<JournalEntry> = database.tx {
        MemoryJournal.selectAll().where { (MemoryJournal.date greaterEq from) and (MemoryJournal.date lessEq to) }
            .orderBy(MemoryJournal.date).orderBy(MemoryJournal.id)
            .map { JournalEntry(it[MemoryJournal.id], it[MemoryJournal.date], it[MemoryJournal.text]) }
    }

    override suspend fun setScratch(text: String, ttl: Duration): Long = database.tx {
        val now = clock.now()
        MemoryScratch.insert {
            it[MemoryScratch.text] = text
            it[expiresAt] = now + ttl
            it[createdAt] = now
        }[MemoryScratch.id]
    }

    override suspend fun liveScratch(now: Instant): List<ScratchNote> = database.tx {
        MemoryScratch.selectAll().where { MemoryScratch.expiresAt greaterEq now }.orderBy(MemoryScratch.id)
            .map { ScratchNote(it[MemoryScratch.id], it[MemoryScratch.text], it[MemoryScratch.expiresAt]) }
    }

    override suspend fun purgeScratch(now: Instant): Int = database.tx {
        MemoryScratch.deleteWhere { expiresAt less now }
    }

    override suspend fun search(query: String, limit: Int): List<MemoryHit> {
        if (query.isBlank()) return emptyList()
        return database.tx {
            val args = listOf(
                TextColumnType() to query, TextColumnType() to query, TextColumnType() to query,
                IntegerColumnType() to limit.coerceIn(1, 50),
            )
            exec(SEARCH_SQL, args) { rs: ResultSet ->
                buildList { while (rs.next()) add(MemoryHit(rs.getString(1), rs.getString(2), rs.getString(3).take(MAX_HIT_CHARS))) }
            }.orEmpty()
        }
    }

    private companion object {
        const val MAX_HIT_CHARS = 600

        val SEARCH_SQL = """
            SELECT source, ref, body FROM (
              SELECT 'journal' AS source, date::text AS ref, text AS body, ts_rank(tsv, q) AS rank
                FROM memory_journal, websearch_to_tsquery('english', ?) q WHERE tsv @@ q
              UNION ALL
              SELECT 'feed', to_char(at, 'YYYY-MM-DD"T"HH24:MI'), cards::text, ts_rank(to_tsvector('english', cards::text), q)
                FROM feed_item, websearch_to_tsquery('english', ?) q
                WHERE batch_state IS NULL AND to_tsvector('english', cards::text) @@ q
              UNION ALL
              SELECT 'message', id::text, coalesce(subject, '') || E'\n' || body,
                     ts_rank(to_tsvector('english', coalesce(subject, '') || ' ' || body), q)
                FROM message, websearch_to_tsquery('english', ?) q
                WHERE deleted_at IS NULL AND to_tsvector('english', coalesce(subject, '') || ' ' || body) @@ q
            ) hits ORDER BY rank DESC LIMIT ?
        """.trimIndent()
    }
}

/**
 * Pure: the "what you remember" system message: today's and yesterday's journal and the live
 * scratch notes. Null when there is nothing to say. Rendered only when it changed, so it never
 * churns the cache.
 */
fun renderMemoryContext(today: LocalDate, entries: List<JournalEntry>, scratch: List<ScratchNote>): String? {
    val yesterday = today.minus(DatePeriod(days = 1))
    val todays = entries.filter { it.date == today }
    val yesterdays = entries.filter { it.date == yesterday }
    if (todays.isEmpty() && yesterdays.isEmpty() && scratch.isEmpty()) return null
    return buildString {
        append("[memory, ").append(today).append(']')
        if (yesterdays.isNotEmpty()) {
            append("\nJournal, yesterday:")
            yesterdays.forEach { append("\n- ").append(it.text) }
        }
        if (todays.isNotEmpty()) {
            append("\nJournal, today:")
            todays.forEach { append("\n- ").append(it.text) }
        }
        if (scratch.isNotEmpty()) {
            append("\nScratch notes (they expire):")
            scratch.forEach { append("\n- ").append(it.text).append(" (until ").append(it.expiresAt).append(')') }
        }
    }
}
