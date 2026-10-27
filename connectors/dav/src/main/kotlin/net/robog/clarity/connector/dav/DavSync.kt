package net.robog.clarity.connector.dav

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.Url
import java.security.MessageDigest
import java.time.Duration
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import net.robog.clarity.connector.sdk.StateStore
import net.robog.clarity.connector.sdk.getAs
import net.robog.clarity.connector.sdk.putAs
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.noun.DocumentPayload
import net.robog.clarity.shared.noun.Noun

private val log = KotlinLogging.logger {}

/**
 * Recurring events are expanded from this far back to this far ahead. Plain events are reported
 * whatever their date. Occurrences that later fall behind the window are kept (history stays).
 */
val WINDOW_BEFORE: Duration = Duration.ofDays(365)
val WINDOW_AFTER: Duration = Duration.ofDays(180)

/** A recurring event is re-read when the window has moved on by more than this since it was expanded. */
private val REEXPAND_AFTER: Duration = Duration.ofDays(7)

/**
 * Version of the calendar part of [CollectionState]. A collection stored with an older version has
 * every resource refetched and re-expanded once (source ids do not change, so nothing duplicates).
 * 2: plain events of any age, series from a year back, [CollectionState.recurring], history kept.
 */
// 3: refetch once so objects that failed to parse before the bad-line fix are read again.
const val CAL_STATE_VERSION = 3

private const val CHUNK = 100
private const val MAX_FILES = 5000
private const val MAX_DEPTH = 6

/**
 * What the last sync of one collection knew: the server's sync token, ETags per resource, and for
 * calendars which occurrence keys were reported per resource (to tombstone them when they go away)
 * and which resources are recurring series (re-expanded as the window moves, even if they have
 * produced nothing yet).
 */
@Serializable
data class CollectionState(
    val token: String? = null,
    val etags: Map<String, String> = emptyMap(),
    val occurrences: Map<String, List<String>> = emptyMap(),
    val expandedThrough: Long = 0,
    val recurring: Set<String> = emptySet(),
    val version: Int = 0,
)

class DavSync(
    private val server: DavServer,
    private val config: DavConfig,
    private val state: StateStore,
    private val clock: Clock = Clock.System,
) {
    data class Counts(var events: Int = 0, var contacts: Int = 0, var documents: Int = 0, var removed: Int = 0)

    /** Runs one sync of everything configured. A failing collection is logged and does not stop the others. */
    suspend fun syncAll(push: suspend (List<IngestRecord>) -> Unit): Counts {
        val counts = Counts()
        val discovery = server.discover()
        for (calendar in discovery.calendars.filter { selected(config.calendars, it) }) {
            guard("calendar ${calendar.name}") { syncCalendar(calendar, counts, push) }
        }
        for (book in discovery.addressBooks.filter { selected(config.addressBooks, it) }) {
            guard("address book ${book.name}") { syncAddressBook(book, counts, push) }
        }
        val base = config.webdavUrl
        if (base != null) for (folder in config.webdavFolders) {
            guard("folder $folder") { syncFolder(Url(base), folder, counts, push) }
        }
        return counts
    }

    private suspend fun guard(what: String, block: suspend () -> Unit) {
        try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { log.warn(e) { "sync of $what failed; will retry" } }
    }

    private fun selected(wanted: List<String>, c: DavCollectionInfo) =
        wanted.isEmpty() || wanted.any { w -> c.name.equals(w, ignoreCase = true) || c.url.encodedPath.trimEnd('/').endsWith(w.trim('/'), ignoreCase = true) }

    // ---- calendars -----------------------------------------------------------------------------

    private suspend fun syncCalendar(cal: DavCollectionInfo, counts: Counts, push: suspend (List<IngestRecord>) -> Unit) {
        val key = stateKey("cal", cal.url)
        val now = clock.now()
        val nowJ = java.time.Instant.ofEpochMilli(now.toEpochMilliseconds())
        val window = TimeWindow(nowJ.minus(WINDOW_BEFORE), nowJ.plus(WINDOW_AFTER))
        val old = state.getAs<CollectionState>(key) ?: CollectionState()
        val changes = server.changes(cal.url, old.etags, old.token, cal.supportsSync)

        // an older state version: refetch and re-expand everything once
        val upgrade = old.version < CAL_STATE_VERSION
        val stale = upgrade || nowJ.plus(WINDOW_AFTER).epochSecond - old.expandedThrough > REEXPAND_AFTER.seconds
        val reexpand = when {
            upgrade -> changes.etags.keys
            stale -> old.recurring
            else -> emptySet()
        }
        val toFetch = (changes.changed + reexpand).distinct().filter { it !in changes.removed }
        val objects = if (toFetch.isEmpty()) emptyMap() else server.calendarObjects(cal.url, toFetch)
        val changedAtSource = changes.changed.toSet()

        val occurrences = old.occurrences.toMutableMap()
        val recurring = old.recurring.toMutableSet()
        val records = mutableListOf<IngestRecord>()
        var failures = 0
        for ((path, pair) in objects) {
            val (etag, ics) = pair
            val version = etag ?: hash(ics)
            val expansion = try { Ical.expand(ics, cal.name, window, plainWindow = null, source = path) } catch (e: Exception) {
                log.warn(e) { "skipping unreadable calendar object $path" }
                failures++
                null
            }
            if (expansion == null) {
                // unreadable: keep what it produced before, and try again whenever the window moves
                recurring += path
                continue
            }
            failures += expansion.failures
            if (expansion.recurring) recurring += path else recurring -= path
            val ids = expansion.occurrences.associateBy { it.key.orEmpty() }
            val before = old.occurrences[path].orEmpty()
            // Occurrences no longer produced: gone at the source if they were inside the window
            // (or the plain event itself); older ones simply fell behind the window and stay.
            val (vanished, behind) = (before - ids.keys).partition { k -> k.isEmpty() || (k.toLongOrNull() ?: Long.MAX_VALUE) >= window.from.epochSecond }
            if (vanished.isNotEmpty() && path !in changedAtSource) log.info { "$path: ${vanished.size} occurrence(s) inside the window are no longer produced" }
            vanished.forEach { records += tombstone(sourceIdOf(config.id, path, it), now, Noun.CALENDAR_EVENT) }
            for ((k, occ) in ids) records += IngestRecord.Upsert(SourceId(sourceIdOf(config.id, path, k)), version, now, occ.payload)
            occurrences[path] = (behind + ids.keys).distinct()
            counts.events += ids.size
        }
        for (path in changes.removed) {
            old.occurrences[path].orEmpty().ifEmpty { listOf("") }.forEach { records += tombstone(sourceIdOf(config.id, path, it), now, Noun.CALENDAR_EVENT) }
            occurrences.remove(path)
            recurring.remove(path)
            counts.removed++
        }
        pushAll(records, push)
        val kept = occurrences.filterKeys { it in changes.etags }
        val series = recurring.filterTo(mutableSetOf()) { it in changes.etags }
        state.putAs(key, CollectionState(changes.syncToken, changes.etags, kept, window.to.epochSecond, series, CAL_STATE_VERSION))
        // INFO whenever the sync did something (always on the upgrade and the weekly re-expansion), DEBUG otherwise
        val busy = objects.isNotEmpty() || changes.removed.isNotEmpty() || failures > 0
        run {
            val line = {
                "calendar '${cal.name}' sync: resources=${changes.etags.size} recurring series=${series.size} " +
                    "occurrences=${kept.values.sumOf { it.size }} (resources with none=${changes.etags.keys.count { kept[it].isNullOrEmpty() }}) " +
                    "fetched=${objects.size} upserted=${records.count { it is IngestRecord.Upsert }} tombstoned=${records.count { it is IngestRecord.Tombstone }} " +
                    "expansion failures=$failures${if (upgrade) " (state upgraded to v$CAL_STATE_VERSION, full re-expansion)" else ""}"
            }
            if (busy) log.info(line) else log.debug(line)
        }
    }

    // ---- contacts ------------------------------------------------------------------------------

    private suspend fun syncAddressBook(book: DavCollectionInfo, counts: Counts, push: suspend (List<IngestRecord>) -> Unit) {
        val key = stateKey("card", book.url)
        val now = clock.now()
        val old = state.getAs<CollectionState>(key) ?: CollectionState()
        val changes = server.changes(book.url, old.etags, old.token, book.supportsSync)
        val cards = if (changes.changed.isEmpty()) emptyMap() else server.cards(book.url, changes.changed)
        val records = mutableListOf<IngestRecord>()
        for ((path, pair) in cards) {
            val (etag, vcf) = pair
            val contact = try { contactsFrom(vcf).firstOrNull() } catch (e: Exception) { log.warn(e) { "skipping unreadable vCard $path" }; null }
            if (contact != null) {
                records += IngestRecord.Upsert(SourceId(sourceIdOf(config.id, path)), etag ?: hash(vcf), now, contact)
                counts.contacts++
            }
        }
        for (path in changes.removed) { records += tombstone(sourceIdOf(config.id, path), now, Noun.CONTACT); counts.removed++ }
        pushAll(records, push)
        state.putAs(key, CollectionState(changes.syncToken, changes.etags))
    }

    // ---- files ---------------------------------------------------------------------------------

    private suspend fun syncFolder(base: Url, folder: String, counts: Counts, push: suspend (List<IngestRecord>) -> Unit) {
        val rel = folder.trim('/')
        val root = server.resolve(base, base.encodedPath.trimEnd('/') + "/" + rel + if (rel.isEmpty()) "" else "/")
        val key = stateKey("files", root)
        val now = clock.now()
        val old = state.getAs<CollectionState>(key) ?: CollectionState()
        val files = walk(root)
        val fingerprints = files.associate { it.path to (it.etag ?: "${it.length}-${it.modifiedAt}") }
        val store = "webdav:${base.toString().trimEnd('/')}"
        val basePath = base.encodedPath.trimEnd('/')
        val records = mutableListOf<IngestRecord>()
        for (entry in files) {
            if (old.etags[entry.path] == fingerprints.getValue(entry.path)) continue
            val relative = entry.path.removePrefix(basePath).ifEmpty { "/" }
            val name = java.net.URLDecoder.decode(entry.path.trimEnd('/').substringAfterLast('/'), Charsets.UTF_8)
            val mime = entry.contentType?.substringBefore(';')?.trim()?.takeIf { it != "application/octet-stream" } ?: mimeFor(name)
            val text = if ((entry.length ?: 0) <= MAX_EXTRACT_BYTES) {
                server.get(server.resolve(base, entry.path))?.second?.let { extractText(name, mime, it) }.orEmpty()
            } else ""
            records += IngestRecord.Upsert(
                SourceId(sourceIdOf(config.id, entry.path)), fingerprints.getValue(entry.path), now,
                DocumentPayload(
                    store = store, path = java.net.URLDecoder.decode(relative, Charsets.UTF_8), title = name, mimeType = mime,
                    size = entry.length, modifiedAt = entry.modifiedAt, excerpt = text.take(DocumentPayload.MAX_EXCERPT_BYTES),
                ),
            )
            counts.documents++
        }
        for (gone in old.etags.keys - fingerprints.keys) { records += tombstone(sourceIdOf(config.id, gone), now, Noun.DOCUMENT); counts.removed++ }
        pushAll(records, push)
        state.putAs(key, CollectionState(etags = fingerprints))
    }

    private suspend fun walk(root: Url): List<DavEntry> {
        val files = mutableListOf<DavEntry>()
        var level = listOf(root)
        for (depth in 0 until MAX_DEPTH) {
            val next = mutableListOf<Url>()
            for (dir in level) {
                val members = try { server.list(dir) } catch (e: Exception) { log.warn { "could not list $dir: ${e.message}" }; emptyList() }
                for (m in members) {
                    if (m.isCollection) next += server.resolve(dir, m.path) else files += m
                    if (files.size >= MAX_FILES) return files
                }
            }
            level = next
            if (level.isEmpty()) break
        }
        return files
    }

    // ---- helpers -------------------------------------------------------------------------------

    private suspend fun pushAll(records: List<IngestRecord>, push: suspend (List<IngestRecord>) -> Unit) =
        records.chunked(CHUNK).forEach { push(it) }

    private fun tombstone(id: String, now: Instant, noun: Noun) = IngestRecord.Tombstone(SourceId(id), "removed", now, noun)

    private fun stateKey(kind: String, url: Url) = "dav:$kind:" + hash(url.toString()).take(24)

    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * Source id of a resource of account [accountId]: `<accountId>:<path>` for a calendar object,
 * contact or file, and `<accountId>:<path>#<epoch seconds>` for one occurrence of a recurring event.
 */
fun sourceIdOf(accountId: String, path: String, occurrenceKey: String = ""): String =
    "$accountId:" + if (occurrenceKey.isEmpty()) path else "$path#$occurrenceKey"

/** A source id split into its account and the rest (`<path>` or `<path>#<occurrence>`). */
data class DavRef(val accountId: String, val path: String) {
    companion object {
        /** Parses [sourceId]; the old form, a bare path starting with `/`, belongs to account [DEFAULT_ACCOUNT]. */
        fun parse(sourceId: String): DavRef =
            if (sourceId.startsWith("/") || ':' !in sourceId) DavRef(DEFAULT_ACCOUNT, sourceId)
            else DavRef(sourceId.substringBefore(':'), sourceId.substringAfter(':'))
    }
}
