package net.robog.clarity.backend.reports

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import net.robog.clarity.shared.report.ReportFormat
import net.robog.clarity.shared.report.ReportStyle

/** The largest report source accepted (characters). */
const val MAX_REPORT_SOURCE_CHARS = 400_000

/** The longest title (characters). */
const val MAX_REPORT_TITLE_CHARS = 200

/**
 * Pure: [source] with the one occurrence of [old] replaced by [new], like a text editor's
 * `str_replace`. Left (a message for the model) when [old] is empty, does not occur, or occurs more
 * than once, when nothing would change, or when the result is too long.
 */
fun replaceUnique(source: String, old: String, new: String): Either<String, String> {
    if (old.isEmpty()) return "`old` must not be empty; copy the exact text to replace from read_report.".left()
    if (old == new) return "`old` and `new` are the same; nothing to change.".left()
    val first = source.indexOf(old)
    if (first < 0) return "No match for `old` in the latest revision. Copy the text exactly as read_report shows it (spaces and line breaks included).".left()
    val count = occurrences(source, old)
    if (count > 1) return "`old` matches $count places in the latest revision; include more of the surrounding text so it matches exactly one.".left()
    val result = source.substring(0, first) + new + source.substring(first + old.length)
    if (result.length > MAX_REPORT_SOURCE_CHARS) return "The report would be longer than $MAX_REPORT_SOURCE_CHARS characters.".left()
    return result.right()
}

/** Pure: how many times [needle] occurs in [haystack], overlapping occurrences included. */
fun occurrences(haystack: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var from = haystack.indexOf(needle)
    while (from >= 0) {
        count++
        from = haystack.indexOf(needle, from + 1)
    }
    return count
}

/** Pure: the lines of [source] from [fromLine] (1-based) for at most [maxChars], with a header that says where it is. */
fun clipSource(source: String, fromLine: Int, maxChars: Int): String {
    val lines = source.lines()
    val start = (fromLine - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
    val out = StringBuilder()
    var end = start
    while (end < lines.size) {
        val line = lines[end]
        if (out.isNotEmpty() && out.length + line.length + 1 > maxChars) break
        if (out.isNotEmpty()) out.append('\n')
        out.append(if (line.length > maxChars) line.take(maxChars) + "…" else line)
        end++
    }
    val note = if (end < lines.size) "\n[lines ${start + 1}-$end of ${lines.size}; call read_report again with fromLine=${end + 1} for the rest]"
    else "\n[lines ${start + 1}-${lines.size} of ${lines.size}, end of the report]"
    return out.toString() + note
}

/** Pure: the style spec as one line for the model. */
fun describeStyle(style: ReportStyle): String = buildString {
    append(style.fontFamily).append(' ').append(pt(style.fontSizePt)).append(" pt")
    append(", line spacing ").append(pt(style.lineSpacing))
    append(", margins ").append(pt(style.marginIn)).append(" in")
    if (style.paragraphIndentIn > 0) append(", first-line indent ").append(pt(style.paragraphIndentIn)).append(" in")
    append(", paragraph spacing ").append(pt(style.paragraphSpacingPt)).append(" pt")
    style.headingFontFamily?.let { append(", headings in ").append(it) }
    append(", heading sizes ").append(style.headingSizesPt.joinToString("/") { pt(it) }).append(" pt")
    append(", title ").append(pt(style.titleSizePt)).append(" pt")
    append(", ").append(style.align).append(", ").append(style.pageSize)
    if (style.pageNumbers) append(", page numbers")
    if (style.titlePage) append(", title page")
    style.citationStyle?.let { append(", citations ").append(it.uppercase()) }
}

private fun pt(value: Double): String = if (value == Math.floor(value)) value.toLong().toString() else value.toString()

/**
 * Pure: a filename for a download: the title made safe (letters, digits, spaces, `-`, `_`; at most
 * 80 characters), `-rev<n>` when it is not the latest revision, and the format's extension.
 */
fun downloadFilename(title: String, revision: Int, latest: Boolean, format: ReportFormat): String {
    val safe = title.map { c -> if (c.isLetterOrDigit() || c == ' ' || c == '-' || c == '_') c else ' ' }
        .joinToString("").trim().replace(Regex("\\s+"), " ").take(80).trim().ifEmpty { "report" }
    return safe + (if (latest) "" else "-rev$revision") + "." + format.extension
}

/**
 * Pure: the `Content-Disposition` value for [filename]: an ASCII `filename` (other characters become
 * `_`) and the exact name as RFC 5987 `filename*`.
 */
fun contentDisposition(filename: String): String {
    val ascii = filename.map { c -> if (c.code in 0x20..0x7e && c != '"' && c != '\\') c else '_' }.joinToString("")
    val encoded = filename.encodeToByteArray().joinToString("") { byte ->
        val b = byte.toInt() and 0xff
        val c = b.toChar()
        if (c.isLetterOrDigit() && b < 0x80 || c in "-._~") c.toString() else "%" + b.toString(16).uppercase().padStart(2, '0')
    }
    return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
}
