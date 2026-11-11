package net.robog.clarity.shared.text

/** Characters that draw nothing or reorder text (zero width, bidirectional marks): dropped, never shown. */
private fun Char.invisible() = this in '\u200B'..'\u200F' || this in '\u202A'..'\u202E' || this in '\u2060'..'\u2064' || this in '\u2066'..'\u2069' || this == '\uFEFF'

/**
 * [text] as one short line for a place where a person reads it next to words that are not
 * the sender's (a card summary): control characters and runs of whitespace become one space,
 * invisible characters go, and anything longer than [max] is cut with an ellipsis.
 */
fun oneLine(text: String, max: Int): String {
    val flat = text.filterNot { it.invisible() }.map { if (it.isISOControl() || it.isWhitespace()) ' ' else it }
        .joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "\u2026"
}
