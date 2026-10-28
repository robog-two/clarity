package net.robog.clarity.app.ui.goods

/*
 * A small Markdown reader for the Goods viewer: the subset a report uses (pandoc's Markdown as the
 * agent writes it). Headings, paragraphs, bullet and numbered lists (one level of nesting), block
 * quotes, fenced code, rules and tables (kept as text), and inline emphasis, strong, code and links.
 * Pure, so it is tested without Compose; [MarkdownDocument] draws the result.
 */

/** A document: the YAML front matter's title block (when there is one) and the blocks. */
data class MdDocument(val meta: MdMeta, val blocks: List<MdBlock>)

data class MdMeta(val title: String? = null, val author: String? = null, val date: String? = null)

sealed interface MdBlock {
    data class Heading(val level: Int, val text: List<MdInline>) : MdBlock
    data class Paragraph(val text: List<MdInline>) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock

    /** [start] is the first number of a numbered list, null for bullets. */
    data class ListBlock(val start: Int?, val items: List<MdListItem>) : MdBlock
    data class Code(val text: String) : MdBlock

    /** A table, as its rows of cells (the header first). */
    data class Table(val rows: List<List<List<MdInline>>>) : MdBlock
    data object Rule : MdBlock
}

data class MdListItem(val text: List<MdInline>, val children: List<MdBlock> = emptyList())

sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Emphasis(val children: List<MdInline>) : MdInline
    data class Strong(val children: List<MdInline>) : MdInline
    data class Code(val text: String) : MdInline
    data class Link(val children: List<MdInline>, val url: String) : MdInline
}

private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val NUMBERED = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
private val FENCE = Regex("^\\s{0,3}(```|~~~)")
private val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

/** Pure: [source] as a document. Never throws; anything it does not understand is a paragraph. */
fun parseMarkdown(source: String): MdDocument {
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val (meta, start) = frontMatter(lines)
    return MdDocument(meta, blocks(lines.subList(start, lines.size)))
}

/** The `---` YAML block at the very top: its title, author and date (the only keys a report uses). */
private fun frontMatter(lines: List<String>): Pair<MdMeta, Int> {
    if (lines.firstOrNull()?.trim() != "---") return MdMeta() to 0
    val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" || lines[it].trim() == "..." } ?: return MdMeta() to 0
    val values = lines.subList(1, end).mapNotNull { line ->
        val colon = line.indexOf(':')
        if (colon <= 0 || line.first().isWhitespace()) null
        else line.substring(0, colon).trim().lowercase() to unquote(line.substring(colon + 1).trim())
    }.toMap()
    return MdMeta(values["title"]?.ifBlank { null }, values["author"]?.ifBlank { null }, values["date"]?.ifBlank { null }) to end + 1
}

private fun unquote(value: String): String =
    if (value.length >= 2 && (value.first() == '"' && value.last() == '"' || value.first() == '\'' && value.last() == '\'')) value.substring(1, value.length - 1)
    else value

private fun blocks(lines: List<String>): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.isBlank() -> i++
            FENCE.containsMatchIn(line) -> {
                val fence = FENCE.find(line)!!.groupValues[1]
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence)) body += lines[i++]
                i++ // the closing fence (or the end)
                out += MdBlock.Code(body.joinToString("\n"))
            }
            HEADING.matches(line) -> {
                val m = HEADING.find(line)!!
                out += MdBlock.Heading(m.groupValues[1].length, parseInline(m.groupValues[2]))
                i++
            }
            RULE.matches(line) -> {
                out += MdBlock.Rule
                i++
            }
            line.trimStart().startsWith(">") -> {
                val quoted = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank() && (lines[i].trimStart().startsWith(">") || quoted.isNotEmpty() && !startsBlock(lines[i]))) {
                    quoted += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                    i++
                }
                out += MdBlock.Quote(blocks(quoted))
            }
            BULLET.matches(line) || NUMBERED.matches(line) -> {
                val (list, next) = listAt(lines, i)
                out += list
                i = next
            }
            line.trimStart().startsWith("|") && i + 1 < lines.size && TABLE_SEPARATOR.matches(lines[i + 1]) -> {
                val rows = mutableListOf(cells(line))
                i += 2
                while (i < lines.size && lines[i].trimStart().startsWith("|")) rows += cells(lines[i++])
                out += MdBlock.Table(rows)
            }
            else -> {
                val para = mutableListOf(line.trim())
                i++
                while (i < lines.size && lines[i].isNotBlank() && !startsBlock(lines[i])) para += lines[i++].trim()
                out += MdBlock.Paragraph(parseInline(para.joinToString(" ")))
            }
        }
    }
    return out
}

/** Whether [line] begins a block of its own (so it ends the paragraph above it). */
private fun startsBlock(line: String): Boolean =
    HEADING.matches(line) || RULE.matches(line) || FENCE.containsMatchIn(line) || line.trimStart().startsWith(">") ||
        BULLET.matches(line) || NUMBERED.matches(line)

private data class ListLine(val indent: Int, val number: Int?, val text: String)

private fun listLine(line: String): ListLine? {
    BULLET.find(line)?.let { return ListLine(it.groupValues[1].length, null, it.groupValues[2]) }
    NUMBERED.find(line)?.let { return ListLine(it.groupValues[1].length, it.groupValues[2].toInt(), it.groupValues[3]) }
    return null
}

/**
 * The list starting at [from]: its items, each with its continuation lines and, indented under it,
 * nested blocks (a sub-list). A blank line between items keeps the list going; any other line ends it.
 */
private fun listAt(lines: List<String>, from: Int): Pair<MdBlock.ListBlock, Int> {
    val first = listLine(lines[from])!!
    val base = first.indent
    val items = mutableListOf<MdListItem>()
    var i = from
    while (i < lines.size) {
        val item = listLine(lines[i])
        if (item == null || item.indent > base + 1 || (item.number == null) != (first.number == null)) break
        val text = mutableListOf(item.text.trim())
        val nested = mutableListOf<String>()
        i++
        while (i < lines.size) {
            val next = lines[i]
            if (next.isBlank()) {
                val after = lines.getOrNull(i + 1)
                val sibling = after?.let(::listLine)?.let { it.indent >= base } == true
                val indented = after != null && nested.isNotEmpty() && leadingSpaces(after) >= base + 2
                if (sibling || indented) { i++; continue }
                break
            }
            val sub = listLine(next)
            when {
                sub != null && sub.indent >= base + 2 -> nested += dropSpaces(next, base + 2)
                sub != null -> break
                nested.isNotEmpty() && leadingSpaces(next) >= base + 2 -> nested += dropSpaces(next, base + 2)
                startsBlock(next) -> break
                else -> text += next.trim()
            }
            i++
        }
        items += MdListItem(parseInline(text.joinToString(" ")), if (nested.isEmpty()) emptyList() else blocks(nested))
    }
    return MdBlock.ListBlock(first.number, items) to i
}

private fun leadingSpaces(line: String): Int = line.takeWhile { it == ' ' }.length

private fun dropSpaces(line: String, count: Int): String = line.drop(minOf(count, leadingSpaces(line)))

private fun cells(line: String): List<List<MdInline>> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { parseInline(it.trim()) }

/** Pure: the inline Markdown of [text]: `*em*`, `**strong**`, `` `code` ``, `[text](url)`, `<url>`, `\` escapes. */
fun parseInline(text: String): List<MdInline> {
    val out = mutableListOf<MdInline>()
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) {
            out += MdInline.Text(plain.toString())
            plain.clear()
        }
    }
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE -> {
                plain.append(text[i + 1])
                i += 2
            }
            c == '`' -> {
                val ticks = text.substring(i).takeWhile { it == '`' }.length
                val close = text.indexOf("`".repeat(ticks), i + ticks)
                if (close < 0) {
                    plain.append(text, i, i + ticks)
                    i += ticks
                } else {
                    flush()
                    out += MdInline.Code(text.substring(i + ticks, close).trim())
                    i = close + ticks
                }
            }
            c == '[' -> {
                val link = linkAt(text, i)
                if (link == null) {
                    plain.append(c)
                    i++
                } else {
                    flush()
                    out += MdInline.Link(parseInline(link.first), link.second)
                    i = link.third
                }
            }
            c == '<' && (text.startsWith("<http://", i) || text.startsWith("<https://", i)) && text.indexOf('>', i) > 0 -> {
                val close = text.indexOf('>', i)
                val url = text.substring(i + 1, close)
                flush()
                out += MdInline.Link(listOf(MdInline.Text(url)), url)
                i = close + 1
            }
            (c == '*' || c == '_') && canOpen(text, i) -> {
                val run = text.substring(i).takeWhile { it == c }.length.coerceAtMost(3)
                val match = closing(text, i + run, c, run)
                if (match == null) {
                    plain.append(text, i, i + run)
                    i += run
                } else {
                    flush()
                    val inner = parseInline(text.substring(i + run, match))
                    out += when (run) {
                        1 -> MdInline.Emphasis(inner)
                        2 -> MdInline.Strong(inner)
                        else -> MdInline.Strong(listOf(MdInline.Emphasis(inner)))
                    }
                    i = match + run
                }
            }
            else -> {
                plain.append(c)
                i++
            }
        }
    }
    flush()
    return out
}

private const val ESCAPABLE = "\\`*_{}[]()#+-.!|>~\""

/** An emphasis run at [i] can open when the next character is not a space (and `_` not inside a word). */
private fun canOpen(text: String, i: Int): Boolean {
    val run = text.substring(i).takeWhile { it == text[i] }.length
    val next = text.getOrNull(i + run) ?: return false
    if (next.isWhitespace()) return false
    if (text[i] == '_' && text.getOrNull(i - 1)?.isLetterOrDigit() == true) return false
    return true
}

/** Where the run of [count] [marker]s that closes one opened before [from] starts, or null. */
private fun closing(text: String, from: Int, marker: Char, count: Int): Int? {
    var i = from
    val token = marker.toString().repeat(count)
    while (i < text.length) {
        if (text[i] == '\\') { i += 2; continue }
        if (text[i] == '`') {
            val close = text.indexOf('`', i + 1)
            if (close > 0) { i = close + 1; continue }
        }
        if (text.startsWith(token, i) && !text[i - 1].isWhitespace() && text.getOrNull(i + count) != marker) {
            val after = text.getOrNull(i + count)
            if (marker == '_' && after?.isLetterOrDigit() == true) { i++; continue }
            return i
        }
        if (text[i] == marker) {
            // Skip a run of another length (a nested `*` inside `**`).
            i += text.substring(i).takeWhile { it == marker }.length
            continue
        }
        i++
    }
    return null
}

/** `[text](url)` at [at]: (text, url, the index after it), or null when it is not a link. */
private fun linkAt(text: String, at: Int): Triple<String, String, Int>? {
    var depth = 0
    var i = at
    while (i < text.length) {
        when (text[i]) {
            '\\' -> i++
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) break
            }
        }
        i++
    }
    if (i >= text.length || text.getOrNull(i + 1) != '(') return null
    val close = text.indexOf(')', i + 2)
    if (close < 0) return null
    val url = text.substring(i + 2, close).trim().substringBefore(' ').removeSurrounding("<", ">")
    if (url.isEmpty()) return null
    return Triple(text.substring(at + 1, i), url, close + 1)
}

/** Pure: the plain words of [inlines] (accessibility, previews of a title). */
fun plainText(inlines: List<MdInline>): String = buildString {
    inlines.forEach {
        when (it) {
            is MdInline.Text -> append(it.text)
            is MdInline.Code -> append(it.text)
            is MdInline.Emphasis -> append(plainText(it.children))
            is MdInline.Strong -> append(plainText(it.children))
            is MdInline.Link -> append(plainText(it.children))
        }
    }
}
