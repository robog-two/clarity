package net.robog.clarity.backend.attachments

import kotlin.time.Duration
import kotlin.time.Instant
import net.robog.clarity.shared.fetch.FetchRequest

/** Pure decisions of the attachment fetch: what is a valid part, what a name may be, what the cache keeps. */

private val PART_PATH = Regex("[1-9][0-9]{0,3}(\\.[1-9][0-9]{0,3}){0,7}")

/** Whether [part] is a part path (`2`, `1.3`) or the request for the list of parts. */
fun validPart(part: String): Boolean = part == FetchRequest.LIST_PARTS || PART_PATH.matches(part)

/** Whether [part] addresses one file (a path, not the list). */
fun isFilePart(part: String): Boolean = part != FetchRequest.LIST_PARTS && PART_PATH.matches(part)

/** How a declared or actual size compares to the cap. */
enum class SizeVerdict { OK, TOO_LARGE }

/** [size] (null when unknown) against [maxBytes]: only a known size over the cap is refused here. */
fun sizeVerdict(size: Long?, maxBytes: Long): SizeVerdict =
    if (size != null && size > maxBytes) SizeVerdict.TOO_LARGE else SizeVerdict.OK

private const val MAX_NAME_CHARS = 120

/**
 * A file name that is safe to write into the sandbox and to offer as a download: no path parts, no
 * control characters, no leading dots, at most [MAX_NAME_CHARS] characters with the extension kept.
 * A missing or empty name becomes `attachment-<part>` plus an extension from [mimeType].
 */
fun sanitizeFilename(name: String?, part: String, mimeType: String): String {
    val base = name.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        .map { c -> if (c.isISOControl() || c in "<>:\"|?*") '_' else c }.joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim().trimStart('.').trim()
    if (base.isEmpty() || base.all { it == '_' || it == '.' }) return "attachment-${part.replace('.', '-')}${extensionFor(mimeType)}"
    if (base.length <= MAX_NAME_CHARS) return base
    val dot = base.lastIndexOf('.')
    val extension = if (dot > 0 && base.length - dot <= 12) base.substring(dot) else ""
    return base.take(MAX_NAME_CHARS - extension.length) + extension
}

/** The extension (with its dot) usually used for [mimeType], or none. */
fun extensionFor(mimeType: String): String = when (mimeType.substringBefore(';').trim().lowercase()) {
    "application/pdf" -> ".pdf"
    "image/png" -> ".png"
    "image/jpeg" -> ".jpg"
    "image/gif" -> ".gif"
    "image/webp" -> ".webp"
    "text/plain" -> ".txt"
    "text/html" -> ".html"
    "text/csv" -> ".csv"
    "application/json" -> ".json"
    "application/zip" -> ".zip"
    "message/rfc822" -> ".eml"
    "application/msword" -> ".doc"
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx"
    "application/vnd.ms-excel" -> ".xls"
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx"
    else -> ""
}

/** Where `save_attachment` puts a message's attachment in the sandbox workspace: `attachments/<first 8 of the id>/<name>`. */
fun sandboxAttachmentPath(entityId: String, filename: String): String =
    "attachments/${entityId.filter { it.isLetterOrDigit() }.take(8)}/$filename"

/** Where a fetch row is in its life. */
enum class FetchStatus(val wire: String) {
    PENDING("pending"), LEASED("leased"), DONE("done"), FAILED("failed");

    companion object {
        fun fromWire(wire: String): FetchStatus = entries.first { it.wire == wire }
    }
}

/** What the fetcher knows of an existing row for the same message, part and version. */
data class RowState(val status: FetchStatus, val doneAt: Instant?, val leaseUntil: Instant?)

/** What to do about a request given the row that may already exist. */
enum class Plan {
    /** Nothing exists: queue it. */
    ENQUEUE,
    /** The bytes are cached and fresh. */
    SERVE,
    /** A request is already out; wait for its answer. */
    WAIT,
    /** The old row is spent (expired bytes, a failure, a lost lease): queue it again. */
    REQUEUE,
}

fun planFor(row: RowState?, now: Instant, ttl: Duration): Plan = when {
    row == null -> Plan.ENQUEUE
    row.status == FetchStatus.PENDING -> Plan.WAIT
    row.status == FetchStatus.LEASED -> if (row.leaseUntil != null && row.leaseUntil > now) Plan.WAIT else Plan.REQUEUE
    row.status == FetchStatus.DONE -> if (isFresh(row.doneAt, now, ttl)) Plan.SERVE else Plan.REQUEUE
    else -> Plan.REQUEUE
}

/** Whether bytes cached at [doneAt] are still within [ttl] of [now]. */
fun isFresh(doneAt: Instant?, now: Instant, ttl: Duration): Boolean = doneAt != null && now - doneAt < ttl

/** A cached file, for deciding what the total cap evicts. */
data class CacheEntry(val id: String, val size: Long, val doneAt: Instant)

/** The ids to drop so the cache fits [maxBytes]: oldest first, only as many as needed. */
fun evictionsFor(entries: List<CacheEntry>, maxBytes: Long): List<String> {
    var total = entries.sumOf { it.size }
    val out = mutableListOf<String>()
    for (e in entries.sortedBy { it.doneAt }) {
        if (total <= maxBytes) break
        out += e.id
        total -= e.size
    }
    return out
}
