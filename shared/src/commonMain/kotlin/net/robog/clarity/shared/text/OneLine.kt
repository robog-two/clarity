package net.robog.clarity.shared.text

/**
 * Code points that draw nothing or reorder text, dropped from anything shown next to words the
 * sender did not write: soft hyphen, Arabic letter mark, zero width space, left-to-right and
 * right-to-left marks, bidirectional embeddings and isolates, word joiner and invisible operators,
 * the byte order mark and tag characters. Zero width (non-)joiners stay: scripts such as Persian and
 * emoji sequences need them.
 */
private fun invisible(cp: Int) = cp == 0xAD || cp == 0x61C || cp == 0x200B || cp == 0x200E || cp == 0x200F ||
    cp in 0x202A..0x202E || cp in 0x2060..0x2064 || cp in 0x2066..0x2069 || cp == 0xFEFF || cp in 0xE0000..0xE007F

private fun isSpace(cp: Int) = cp < 0x10000 && (cp.toChar().isWhitespace() || cp.toChar().isISOControl())

/**
 * [text] as one short line for a place where a person reads it next to words that are not the
 * sender's (a card summary): control characters and runs of whitespace become one space, invisible
 * characters go, and anything longer than [max] characters is cut with an ellipsis, never inside a
 * surrogate pair.
 */
fun oneLine(text: String, max: Int): String {
    val out = StringBuilder()
    var pendingSpace = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val pair = c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
        val cp = if (pair) 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) else c.code
        i += if (pair) 2 else 1
        when {
            invisible(cp) -> Unit
            isSpace(cp) -> pendingSpace = out.isNotEmpty()
            else -> {
                if (pendingSpace) out.append(' ')
                pendingSpace = false
                out.append(c)
                if (pair) out.append(text[i - 1])
            }
        }
    }
    if (out.length <= max) return out.toString()
    var end = max - 1
    if (end > 0 && out[end - 1].isHighSurrogate()) end--
    return out.substring(0, end).trimEnd() + "\u2026"
}
