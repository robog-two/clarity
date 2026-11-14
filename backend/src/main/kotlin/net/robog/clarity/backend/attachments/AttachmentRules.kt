package net.robog.clarity.backend.attachments

import kotlin.time.Duration
import kotlin.time.Instant
import net.robog.clarity.shared.fetch.FetchRequest
import net.robog.clarity.shared.noun.AttachmentMeta

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

/** The longest file name, in UTF-8 bytes (file systems cut at 255; the part suffix and the sandbox's own paths need room). */
const val MAX_NAME_BYTES = 200

private fun utf8Size(codePoint: Int): Int = when {
    codePoint < 0x80 -> 1
    codePoint < 0x800 -> 2
    codePoint < 0x10000 -> 3
    else -> 4
}

private fun String.utf8Bytes(): Int = codePoints().toArray().sumOf(::utf8Size)

/** [this] cut to [maxBytes] of UTF-8 on a character boundary. */
private fun String.takeBytes(maxBytes: Int): String {
    var used = 0
    val out = StringBuilder()
    for (cp in codePoints().toArray()) {
        used += utf8Size(cp)
        if (used > maxBytes) break
        out.appendCodePoint(cp)
    }
    return out.toString()
}

/** [name] cut to [MAX_NAME_BYTES] with its extension (when short) kept. */
internal fun capName(name: String): String {
    if (name.utf8Bytes() <= MAX_NAME_BYTES) return name
    val dot = name.lastIndexOf('.')
    val extension = if (dot > 0 && name.substring(dot).utf8Bytes() <= 24) name.substring(dot) else ""
    return name.substring(0, name.length - extension.length).takeBytes(MAX_NAME_BYTES - extension.utf8Bytes()) + extension
}

/**
 * A file name that is safe to write into the sandbox and to offer as a download: no path parts, no control or
 * invisible formatting characters (bidirectional overrides and the like, Unicode category Cf), no leading dots or
 * hyphens (an option to a shell command), at most [MAX_NAME_BYTES] bytes of UTF-8 with the extension kept.
 * A missing or empty name becomes `attachment-<part>` plus an extension from [mimeType].
 */
fun sanitizeFilename(name: String?, part: String, mimeType: String): String {
    val visible = name.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        .codePoints().toArray()
        .filter { Character.getType(it) != Character.FORMAT.toInt() }
        .joinToString("") { cp -> if (Character.isISOControl(cp) || cp.toChar() in "<>:\"|?*" && cp < 0x80) "_" else String(Character.toChars(cp)) }
    val base = visible.replace(Regex("\\s+"), " ").trim().trimStart('.', '-').trim()
    if (base.isEmpty() || base.all { it == '_' || it == '.' }) return "attachment-${part.replace('.', '-')}${extensionFor(mimeType)}"
    return capName(base)
}

/**
 * [name] made different from the names of [others] (the message's other attachments, by their own sanitized
 * names, ignoring case): two files called `scan.pdf` become `scan-p2.pdf` and `scan-p3.pdf`, so saving one
 * never overwrites the other. A name already unique is returned as it is.
 */
fun distinctFilename(name: String, part: String, others: List<AttachmentMeta>): String {
    val clash = others.any { it.part != part && sanitizeFilename(it.filename, it.part, it.mimeType).equals(name, ignoreCase = true) }
    if (!clash) return name
    val dot = name.lastIndexOf('.')
    val (stem, extension) = if (dot > 0 && name.length - dot <= 24) name.substring(0, dot) to name.substring(dot) else name to ""
    return capName("$stem-p${part.replace('.', '-')}$extension")
}

/**
 * Whether a message's source record of [sourceVersion] lists its attachments: mail records from version 2 on do, so
 * an empty list means none. Older ones (and sources with their own version scheme) may not, and are asked.
 */
fun attachmentsKnown(sourceVersion: String): Boolean = (sourceVersion.toLongOrNull() ?: 0L) >= 2L

/**
 * Whether a part the source says is [declared] bytes as stored (base64, the usual encoding of a file, is a third
 * bigger than the file) cannot fit [maxBytes] once decoded. The same estimate as the connector's, so a 20 MB
 * file of about 27 MB encoded passes under a 25 MB limit; the real size is checked when the bytes arrive.
 */
fun declaredTooLarge(declared: Long?, maxBytes: Long): Boolean = declared != null && declared / 10 * 7 > maxBytes

/** How long a fetch of [declared] bytes waits for the connector: [base], plus 3 s per MB, at most 120 s (never less than [base]). */
fun fetchWait(base: Duration, declared: Long?): Duration {
    val extra = ((declared ?: 0L) / (1024 * 1024)).coerceAtLeast(0) * 3
    return maxOf(base, minOf(base + Duration.parse("${extra}s"), Duration.parse("120s")))
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
