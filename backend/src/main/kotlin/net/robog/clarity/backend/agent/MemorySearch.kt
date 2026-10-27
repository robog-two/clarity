package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.ResultSet
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlin.time.toKotlinInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.robog.clarity.backend.governance.zoneOrUtc
import net.robog.clarity.backend.persistence.tx
import org.jetbrains.exposed.v1.jdbc.Database

private val log = KotlinLogging.logger {}

/**
 * Something memory search covers, as SQL fragments over its table: [from] (with alias `t`), [id],
 * [at] (a timestamptz), optional [day] (a date shown instead of [at]), [body] (the searchable text),
 * [filter] (live rows), [stamp] (changes when the row does, for re-embedding) and [untrusted]
 * (an SQL boolean: the text came from outside).
 */
enum class MemorySource(
    val wire: String,
    val label: String,
    val from: String,
    val id: String,
    val at: String,
    val body: String,
    val filter: String,
    val stamp: String,
    val untrusted: String,
    val day: String = "NULL",
    /** The full-text vector; null means `to_tsvector('english', body)` computed on the fly. */
    val tsv: String? = null,
) {
    JOURNAL("journal", "journal", "memory_journal t", "t.id", "t.created_at", "t.text", "true", "t.created_at", "false", day = "t.date::text", tsv = "t.tsv"),
    NOTE(
        "note", "note", "memory_note t JOIN memory_collection c ON c.id = t.collection_id", "t.id", "t.updated_at",
        "c.name || ': ' || t.text", "true", "t.updated_at", "false", tsv = "t.tsv",
    ),
    CONVERSATION("conversation", "conversation", "conversation_chunk t", "t.id", "t.started_at", "t.text", "true", "t.ended_at", "t.untrusted", tsv = "t.tsv"),
    FEED("feed", "feed", "feed_item t", "t.id", "t.at", "t.cards::text", "t.batch_state IS NULL AND NOT t.streaming", "t.at", "false"),
    MESSAGE(
        "message", "message", "message t", "t.id", "t.sent_at", "coalesce(t.subject, '') || E'\\n' || t.body", "t.deleted_at IS NULL",
        "t.updated_at", "true",
    ),
    EVENT(
        "event", "event", "calendar_event t", "t.id", "t.start_at",
        "t.title || coalesce(E'\\n' || t.location, '') || coalesce(E'\\n' || t.description, '')", "t.deleted_at IS NULL", "t.updated_at", "true",
    ),
    HELPER(
        "helper", "helper result", "agent_run t", "t.id", "coalesce(t.finished_at, t.started_at)",
        "'Task: ' || coalesce(t.task #>> '{}', '') || E'\\nResult: ' || t.result", "t.reason = 'helper' AND t.result IS NOT NULL",
        "t.finished_at", "true",
    ),
    ;

    val vector: String get() = tsv ?: "to_tsvector('english', $body)"

    /** The selected columns: source, ref, at, day, body, untrusted. */
    val columns: String get() = "'$wire', $id::text, $at, $day, $body, $untrusted"

    /** Pure: [raw] (the [body] column) as text a person or a model reads. */
    fun readable(raw: String): String = if (this == FEED) feedText(raw) else raw

    companion object {
        fun of(wire: String): MemorySource? = entries.firstOrNull { it.wire == wire }
    }
}

/** A search or read result. [at] is already formatted (home time, or the journal's day). */
data class MemoryHit(val key: MemoryKey, val at: String, val text: String, val untrusted: Boolean) {
    val source: String get() = key.source
}

/** Pure: the text values of a feed item's cards (JSON), without type tags and ids. */
fun feedText(cardsJson: String): String {
    val element = runCatching { Json.parseToJsonElement(cardsJson) }.getOrNull() ?: return cardsJson
    val out = mutableListOf<String>()
    fun walk(e: JsonElement, key: String?) {
        when (e) {
            is JsonArray -> e.forEach { walk(it, key) }
            is JsonObject -> e.forEach { (k, v) -> if (k !in FEED_SKIP_KEYS) walk(v, k) }
            is JsonPrimitive -> if (e.isString && key != null) e.contentOrNull?.takeIf { it.isNotBlank() }?.let(out::add)
        }
    }
    walk(element, null)
    return out.joinToString(" · ")
}

private val FEED_SKIP_KEYS = setOf("type", "id", "heldActionId", "requestId", "taskIds", "sessionId")

/** Pure: [text] cut to [max] characters, closing an `<untrusted …>` quote the cut left open. */
fun truncateQuoted(text: String, max: Int): String {
    if (text.length <= max) return text
    val cut = text.take(max).trimEnd() + "…"
    val opens = Regex("<untrusted[ >]").findAll(cut).count()
    val closes = Regex("</untrusted>").findAll(cut).count()
    return if (opens > closes) "$cut</untrusted>" else cut
}

/** Pure: reciprocal rank fusion of ranked lists (best first): `sum 1 / (k + rank)`, best first. */
fun <T> reciprocalRankFusion(lists: List<List<T>>, k: Int = 60): List<T> {
    val scores = LinkedHashMap<T, Double>()
    lists.forEach { list -> list.forEachIndexed { rank, item -> scores[item] = (scores[item] ?: 0.0) + 1.0 / (k + rank + 1) } }
    return scores.entries.sortedByDescending { it.value }.map { it.key }
}

/** Pure: how a hit reads in a tool result: a header with its ref, untrusted text quoted. */
fun renderHit(hit: MemoryHit, label: String, maxChars: Int): String {
    val body = truncateQuoted(hit.text.trim(), maxChars)
    val shown = if (hit.untrusted && hit.key.source != MemorySource.CONVERSATION.wire) quoteUntrusted(UntrustedPart(hit.key.source, body)) else body
    return "[$label ${hit.at} · ${hit.key}]\n$shown"
}

/**
 * Memory search over every [MemorySource]: Postgres full-text and, when embeddings are available,
 * vector similarity, fused by reciprocal rank. Degrades to full-text only without embeddings.
 */
@Inject
@SingleIn(AppScope::class)
class MemorySearch(
    private val database: Database,
    private val embeddings: Embeddings,
    private val vectors: VectorIndex,
    private val settings: SettingsProvider,
) {
    /** Best matches first, at most [limit]; [sources] narrows (wire names). */
    suspend fun search(query: String, limit: Int, sources: Set<String>? = null): List<MemoryHit> {
        if (query.isBlank()) return emptyList()
        val n = limit.coerceIn(1, 50)
        val chosen = sources?.mapNotNull(MemorySource::of)?.toSet()?.takeIf { it.isNotEmpty() } ?: MemorySource.entries.toSet()
        val zone = zoneOrUtc(settings.current().homeZone)
        val keyword = keyword(query, chosen, CANDIDATES, zone)
        val semantic = semantic(query, chosen)
        if (semantic.isEmpty()) return keyword.take(n)
        val fused = reciprocalRankFusion(listOf(keyword.map { it.key }, semantic)).take(n)
        val known = keyword.associateBy { it.key }
        val fetched = fetch(fused.filter { it !in known }, zone).associateBy { it.key }
        return fused.mapNotNull { known[it] ?: fetched[it] }
    }

    private suspend fun semantic(query: String, sources: Set<MemorySource>): List<MemoryKey> {
        if (!embeddings.available) return emptyList()
        return try {
            val vector = embeddings.query(query) ?: return emptyList()
            vectors.refresh()
            vectors.nearest(vector, CANDIDATES, MIN_SIMILARITY, sources.mapTo(HashSet()) { it.wire }).map { it.first }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "semantic search failed, keyword only: ${e.message}" }
            emptyList()
        }
    }

    /** Full-text hits, best first. */
    suspend fun keyword(query: String, sources: Set<MemorySource>, limit: Int, zone: TimeZone): List<MemoryHit> {
        if (query.isBlank() || sources.isEmpty()) return emptyList()
        val sql = "WITH q AS (SELECT websearch_to_tsquery('english', ?) AS q) SELECT * FROM (" +
            sources.joinToString(" UNION ALL ") { s ->
                "SELECT ${s.columns}, ts_rank(${s.vector}, q.q) AS rank FROM ${s.from}, q WHERE ${s.filter} AND ${s.vector} @@ q.q"
            } + ") hits ORDER BY rank DESC LIMIT ?"
        return database.tx {
            jdbc().prepareStatement(sql).use { st ->
                st.setString(1, query)
                st.setInt(2, limit)
                st.executeQuery().use { rs -> rs.hits(zone) }
            }
        }
    }

    /** The records [keys] point at (live ones only), in no particular order. */
    suspend fun fetch(keys: List<MemoryKey>): List<MemoryHit> = fetch(keys, zoneOrUtc(settings.current().homeZone))

    private suspend fun fetch(keys: List<MemoryKey>, zone: TimeZone): List<MemoryHit> {
        val bySource = keys.groupBy { it.source }.mapKeys { MemorySource.of(it.key) }.filterKeys { it != null }
        if (bySource.isEmpty()) return emptyList()
        return database.tx {
            val c = jdbc()
            bySource.flatMap { (source, ks) ->
                c.prepareStatement("SELECT ${source!!.columns} FROM ${source.from} WHERE ${source.filter} AND ${source.id}::text = ANY(?)").use { st ->
                    st.setArray(1, c.createArrayOf("text", ks.map { it.ref }.toTypedArray()))
                    st.executeQuery().use { rs -> rs.hits(zone) }
                }
            }
        }
    }

    private fun ResultSet.hits(zone: TimeZone): List<MemoryHit> = buildList {
        while (next()) {
            val source = MemorySource.of(getString(1)) ?: continue
            val at = getString(4) ?: getTimestamp(3)?.let { formatAt(it.toInstant().toKotlinInstant(), zone) } ?: ""
            add(MemoryHit(MemoryKey(source.wire, getString(2)), at, source.readable(getString(5).orEmpty()), getBoolean(6)))
        }
    }

    companion object {
        /** Candidates taken from each ranking before fusion. */
        const val CANDIDATES = 30

        /** Vector hits below this cosine similarity are noise for this model. */
        const val MIN_SIMILARITY = 0.3f
    }
}

/** Pure: `2026-10-02 14:05` on [zone]'s clock. */
fun formatAt(at: Instant, zone: TimeZone): String {
    val t = at.toLocalDateTime(zone)
    return "${t.date} ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}

/**
 * Embeds what is not embedded yet (new rows, edited rows, rows embedded with another model or size)
 * in batches, newest first, and drops the vectors of rows that are gone. Text is cleaned of secrets
 * ([SecretDetector.redact]) before it leaves. Idempotent: the work is derived from the tables.
 */
@Inject
@SingleIn(AppScope::class)
class EmbeddingIndexer(
    private val database: Database,
    private val embeddings: Embeddings,
    private val vectors: VectorIndex,
) {
    /** Embeds up to [maxRows] records; returns how many were written. */
    suspend fun run(maxRows: Int = 1000): Int {
        if (!embeddings.available) return 0
        dropOrphans()
        var written = 0
        for (source in MemorySource.entries) {
            while (written < maxRows) {
                val pending = pending(source, minOf(BATCH, maxRows - written))
                if (pending.isEmpty()) break
                val texts = pending.map { cleanForEmbedding(source.readable(it.body)) }
                val toSend = texts.withIndex().filter { it.value.isNotEmpty() }
                val vectors = embeddings.documents(toSend.map { it.value }) ?: return written
                val byIndex = toSend.map { it.index }.zip(vectors).toMap()
                store(source, pending.mapIndexed { i, row -> row to (byIndex[i] ?: FloatArray(0)) })
                written += pending.size
                if (pending.size < BATCH) break
            }
        }
        if (written > 0) vectors.refresh()
        return written
    }

    private class Pending(val ref: String, val body: String, val stamp: java.sql.Timestamp?)

    private suspend fun pending(source: MemorySource, limit: Int): List<Pending> = database.tx {
        jdbc().prepareStatement(
            "SELECT ${source.id}::text, ${source.body}, ${source.stamp} FROM ${source.from} " +
                "LEFT JOIN memory_embedding e ON e.source = '${source.wire}' AND e.ref = ${source.id}::text " +
                "WHERE ${source.filter} AND (e.ref IS NULL OR e.model <> ? OR e.dims <> ? OR e.source_at IS DISTINCT FROM ${source.stamp}) " +
                "ORDER BY ${source.at} DESC LIMIT ?",
        ).use { st ->
            st.setString(1, embeddings.model)
            st.setInt(2, embeddings.dims)
            st.setInt(3, limit)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(Pending(rs.getString(1), rs.getString(2).orEmpty(), rs.getTimestamp(3))) } }
        }
    }

    private suspend fun store(source: MemorySource, rows: List<Pair<Pending, FloatArray>>) = database.tx {
        jdbc().prepareStatement(
            "INSERT INTO memory_embedding (source, ref, model, dims, vector, source_at, embedded_at) VALUES (?, ?, ?, ?, ?, ?, clock_timestamp()) " +
                "ON CONFLICT (source, ref) DO UPDATE SET model = excluded.model, dims = excluded.dims, vector = excluded.vector, " +
                "source_at = excluded.source_at, embedded_at = excluded.embedded_at",
        ).use { st ->
            rows.forEach { (row, vector) ->
                st.setString(1, source.wire)
                st.setString(2, row.ref)
                st.setString(3, embeddings.model)
                st.setInt(4, embeddings.dims)
                st.setBytes(5, vectorBytes(vector))
                st.setTimestamp(6, row.stamp)
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    private suspend fun dropOrphans() {
        val gone = database.tx {
            val c = jdbc()
            MemorySource.entries.flatMap { source ->
                c.prepareStatement(
                    "DELETE FROM memory_embedding e WHERE e.source = '${source.wire}' AND NOT EXISTS " +
                        "(SELECT 1 FROM ${source.from} WHERE ${source.filter} AND ${source.id}::text = e.ref) RETURNING e.ref",
                ).use { st -> st.executeQuery().use { rs -> buildList { while (rs.next()) add(MemoryKey(source.wire, rs.getString(1))) } } }
            }
        }
        vectors.remove(gone)
    }

    companion object {
        const val BATCH = 32

        /** About 2K tokens: enough to place a long mail, cheap at any size. */
        const val MAX_EMBED_CHARS = 8000
    }
}

/** Pure: what may be sent for embedding: secrets redacted, whitespace squeezed, capped. */
fun cleanForEmbedding(text: String): String =
    SecretDetector.redact(text.replace(Regex("\\s+"), " ").trim()).take(EmbeddingIndexer.MAX_EMBED_CHARS)
