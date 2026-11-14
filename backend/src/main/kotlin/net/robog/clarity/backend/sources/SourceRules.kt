package net.robog.clarity.backend.sources

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.backend.reports.fetchedPagesIn
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.UrlCitation
import net.robog.clarity.harness.citationsIn
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.ContactPayload
import net.robog.clarity.shared.noun.DocumentPayload
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Payload
import net.robog.clarity.shared.source.SourceAttachment
import net.robog.clarity.shared.source.SourceItem
import net.robog.clarity.shared.source.SourceKind
import net.robog.clarity.shared.source.SourceView
import net.robog.clarity.shared.text.oneLine
import net.robog.clarity.shared.source.dayLine
import net.robog.clarity.shared.source.momentLine
import net.robog.clarity.shared.text.stripInvisible

/** Most items one sources card lists. */
const val MAX_SOURCES = 8

private const val TITLE_CHARS = 120
private const val SUB_CHARS = 120
private const val LINE_CHARS = 300

/** The tools whose results are the person's own records: only an id one of these returned can be cited. */
val RECORD_TOOLS: Set<String> = setOf(
    "list_messages", "search_messages", "list_calendar_events", "search_contacts", "search_documents", "read_document",
    "read_result",
)

/**
 * What the runs behind a reply have read: the ids that stand in the result of a record tool (as whole
 * tokens, lower case) and the web pages they fetched or got as search citations. Only this counts as
 * having read something: what the model wrote itself, what the person said, a tool that merely echoes
 * an id (create_trigger, delegate) and a helper's prose never do.
 */
class RunReads(val ids: Set<String>, val pages: List<UrlCitation>) {
    operator fun plus(other: RunReads) = RunReads(ids + other.ids, (pages + other.pages).distinctBy { urlKey(it.url) })

    companion object { val NONE = RunReads(emptySet(), emptyList()) }
}

private fun isIdChar(c: Char) = c.isLetterOrDigit() || c == '-' || c == '_'

private const val MIN_ID_CHARS = 8

/** Pure: every whole token of [text] (runs of letters, digits, `-` and `_`) that is long enough to be an id, lower case. */
fun idTokens(text: String): Set<String> {
    val out = HashSet<String>()
    var start = -1
    for (i in 0..text.length) {
        val inToken = i < text.length && isIdChar(text[i])
        if (inToken && start < 0) start = i
        if (!inToken && start >= 0) {
            if (i - start >= MIN_ID_CHARS) out += text.substring(start, i).lowercase()
            start = -1
        }
    }
    return out
}

/** Pure: what one run's [messages] read. */
fun readsOf(messages: List<Message>): RunReads {
    val blocks = messages.flatMap(Message::content)
    val recordCalls = blocks.filterIsInstance<Block.ToolUse>().filter { it.name in RECORD_TOOLS }.map { it.id }.toSet()
    val ids = blocks.filterIsInstance<Block.Result>().filter { !it.isError && it.toolUseId in recordCalls }
        .fold(HashSet<String>()) { all, r -> all.apply { addAll(idTokens(r.content)) } }
    // Pages it fetched come with their own title; search citations fill in the rest.
    val fetched = fetchedPagesIn(messages).map { it.citation }
    return RunReads(ids, (fetched + citationsIn(messages)).distinctBy { urlKey(it.url) })
}

/** Pure: whether a run read the record [id]: it stands in the result of a record tool. */
fun idRead(id: String, reads: RunReads): Boolean = id.length >= MIN_ID_CHARS && id.lowercase() in reads.ids

/** Pure: a web address as compared (scheme and host in lower case, no fragment, no trailing slash). */
fun urlKey(url: String): String {
    val trimmed = url.trim().substringBefore('#')
    val scheme = trimmed.substringBefore("://", "")
    if (scheme.isEmpty()) return trimmed
    val rest = trimmed.substringAfter("://")
    val authority = rest.substringBefore('/').substringBefore('?')
    val tail = rest.removePrefix(authority)
    return scheme.lowercase() + "://" + authority.lowercase() + tail.trimEnd('/')
}

/** Pure: the page a run read at [url], if it did. */
fun pageRead(url: String, reads: RunReads): UrlCitation? = urlKey(url).let { key -> reads.pages.firstOrNull { urlKey(it.url) == key } }

/** Pure: the host of [url] as lower case punycode, so look-alike letters show; null when it has none. java.net.URI leaves the host null for non-ASCII letters, so it is read from the authority. */
fun asciiHost(url: String): String? = runCatching {
    val uri = java.net.URI(url.trim())
    val host = uri.host ?: uri.authority?.substringAfterLast('@')?.substringBefore(':').orEmpty()
    java.net.IDN.toASCII(host.trimEnd('.')).lowercase().ifEmpty { null }
}.getOrNull()

private fun isWebUrl(url: String) = (url.startsWith("https://") || url.startsWith("http://")) && !url.any { it.isWhitespace() || it.isISOControl() } && asciiHost(url) != null

/** The label of an item in an error message. */
fun SourceItem.label(): String = when (this) {
    is SourceItem.Entity -> id
    is SourceItem.Web -> url
    SourceItem.Unknown -> ""
}

/**
 * Pure: the items as asked, cleaned: trimmed, a repeat of one record or page dropped, never more than
 * [MAX_SOURCES]. Left is the one error to give the model.
 */
fun requestedSources(items: List<SourceItem>): Either<String, List<SourceItem>> {
    if (items.isEmpty()) return "A sources card needs at least one item: {\"id\": \"<record id>\"} or {\"url\": \"https://...\"}.".left()
    val cleaned = items.map {
        when (it) {
            is SourceItem.Entity -> SourceItem.Entity(it.id.trim())
            is SourceItem.Web -> SourceItem.Web(it.url.trim())
            SourceItem.Unknown -> SourceItem.Unknown
        }
    }
    if (SourceItem.Unknown in cleaned) return "Every source is {\"id\": \"<record id>\"} or {\"url\": \"https://...\"}.".left()
    val bad = cleaned.filter { it is SourceItem.Entity && it.id.isEmpty() || it is SourceItem.Web && !isWebUrl(it.url) }
    if (bad.isNotEmpty()) return "Not a record id or an http(s) address: ${bad.joinToString { "\"${oneLine(it.label(), 80)}\"" }}.".left()
    val unique = cleaned.distinctBy { if (it is SourceItem.Web) "u:" + urlKey(it.url) else "e:" + it.label().lowercase() }
    if (unique.size > MAX_SOURCES) return "A sources card lists at most $MAX_SOURCES items; keep the ones the reply rests on most.".left()
    return unique.right()
}

/** Pure: the items of [wanted] that no run read, by the rule in [idRead] and [pageRead]. */
fun unreadSources(wanted: List<SourceItem>, reads: RunReads): List<SourceItem> = wanted.filter {
    when (it) {
        is SourceItem.Entity -> !idRead(it.id, reads)
        is SourceItem.Web -> pageRead(it.url, reads) == null
        SourceItem.Unknown -> true
    }
}

/** Pure: the one error that names what cannot be cited, so the model fixes the card. */
fun refusal(unread: List<SourceItem>, missing: List<SourceItem> = emptyList()): String = buildString {
    if (unread.isNotEmpty()) {
        append("Not cited, you have not read these in this conversation: ")
        append(unread.joinToString { oneLine(it.label(), 100) })
        append(". Cite only records a search or list tool returned (use their id) and pages you or a helper fetched. A read and the card that cites it cannot share one step: read first, then send the sources card in a later step.")
    }
    if (missing.isNotEmpty()) {
        if (isNotEmpty()) append(' ')
        append("No such record: ").append(missing.joinToString { oneLine(it.label(), 100) }).append('.')
    }
}

/** Pure: how a person is named: `Name <address>`, or just the address. */
fun handleLabel(handle: Handle): String =
    oneLine(handle.displayName?.takeIf(String::isNotBlank)?.let { "$it <${handle.value}>" } ?: handle.value, LINE_CHARS)

private fun shortName(handle: Handle): String = oneLine(handle.displayName?.takeIf(String::isNotBlank) ?: handle.value, 60)

/** The title of a message: its subject, or fixed words when it has none (the card and the viewer agree). */
fun messageTitle(subject: String?): String = oneLine(subject?.takeIf(String::isNotBlank) ?: "(no subject)", TITLE_CHARS)

/**
 * Pure: the card item for a record, with its title and sub line made here from what is stored; null for
 * a kind with no view. The sub line is in words on the person's clock ([zone]); the raw time travels too
 * so the device can show it on its own clock (an all-day event only as a date, never shifted).
 */
fun entityItem(id: String, payload: Payload, zone: TimeZone): SourceItem.Entity? = when (payload) {
    is MessagePayload -> {
        val by = shortName(payload.sender)
        SourceItem.Entity(
            id, SourceKind.MESSAGE,
            title = messageTitle(payload.subject),
            sub = oneLine("$by · ${momentLine(payload.sentAt, zone)}", SUB_CHARS),
            attachments = payload.attachments.count { !it.inline },
            by = by,
            at = payload.sentAt,
        )
    }
    is CalendarEventPayload -> {
        val day = if (payload.allDay) payload.start.toLocalDateTime(TimeZone.UTC).date else null
        SourceItem.Entity(
            id, SourceKind.EVENT,
            title = oneLine(payload.title.ifBlank { "(untitled)" }, TITLE_CHARS),
            sub = if (day != null) dayLine(day) else momentLine(payload.start, zone),
            at = if (day == null) payload.start else null,
            day = day,
        )
    }
    is DocumentPayload -> SourceItem.Entity(id, SourceKind.DOCUMENT, title = oneLine(payload.title.ifBlank { payload.path }, TITLE_CHARS), sub = oneLine(payload.path, SUB_CHARS))
    is ContactPayload -> SourceItem.Entity(
        id, SourceKind.CONTACT,
        title = oneLine(payload.displayName, TITLE_CHARS),
        sub = oneLine(payload.organization ?: payload.handles.firstOrNull()?.value.orEmpty(), SUB_CHARS),
    )
}

/** Pure: the card item for a page: its host as punycode so look-alike letters show, and its title from what was fetched. */
fun webItem(url: String, title: String?): SourceItem.Web {
    val host = oneLine(asciiHost(url).orEmpty(), 80)
    return SourceItem.Web(url.trim(), title = oneLine(title?.takeIf(String::isNotBlank) ?: host, TITLE_CHARS), host = host)
}

/** Pure: the card for resolved [items], with the plain text for an app that does not know it. */
fun sourcesCard(items: List<SourceItem>): Card.Sources =
    Card.Sources(items, "Sources." + items.filter { it.title.isNotBlank() }.joinToString("") { " " + it.title.trimEnd('.') + "." })

/** Most characters of a record's long text the view carries. */
const val MAX_VIEW_TEXT = 50_000

/** Pure: [text] without reordering characters, cut to [max] characters (never inside a surrogate pair); the flag says it was cut. */
fun cappedText(text: String, max: Int = MAX_VIEW_TEXT): Pair<String, Boolean> {
    val clean = stripInvisible(text)
    if (clean.length <= max) return clean to false
    val end = if (clean[max - 1].isHighSurrogate()) max - 1 else max
    return clean.substring(0, end) to true
}

/** Pure: a record as the source view shows it. Header-like text is made one line; the body is as stored. */
fun sourceView(id: String, payload: Payload): SourceView = when (payload) {
    is MessagePayload -> {
        val (body, cut) = cappedText(payload.body)
        SourceView.Message(
            id = id,
            title = messageTitle(payload.subject),
            channel = payload.channel.name.lowercase(),
            from = handleLabel(payload.sender),
            to = payload.recipients.map(::handleLabel),
            sentAt = payload.sentAt,
            account = handleLabel(payload.account),
            body = body,
            attachments = payload.attachments.filter { !it.inline }.map {
                SourceAttachment(it.part, it.filename?.let { name -> oneLine(name, 200) }, it.mimeType, it.size)
            },
            truncated = cut,
        )
    }
    is CalendarEventPayload -> {
        val description = payload.description?.takeIf(String::isNotBlank)?.let(::cappedText)
        SourceView.Event(
            id = id,
            title = oneLine(payload.title.ifBlank { "(untitled)" }, LINE_CHARS),
            calendar = oneLine(payload.calendar, 100),
            start = payload.start,
            end = payload.end,
            allDay = payload.allDay,
            location = payload.location?.let { oneLine(it, LINE_CHARS) }?.ifEmpty { null },
            description = description?.first,
            link = payload.url?.takeIf(String::isNotBlank),
            repeats = payload.repeats?.let { oneLine(it, LINE_CHARS) },
            attendees = payload.attendees.map(::handleLabel),
            truncated = description?.second == true,
        )
    }
    is DocumentPayload -> {
        val (excerpt, cut) = cappedText(payload.excerpt)
        SourceView.Document(
            id = id,
            title = oneLine(payload.title.ifBlank { payload.path }, LINE_CHARS),
            path = oneLine(payload.path, 500),
            mimeType = payload.mimeType,
            size = payload.size,
            modifiedAt = payload.modifiedAt,
            excerpt = excerpt,
            truncated = cut,
        )
    }
    is ContactPayload -> {
        val notes = payload.notes?.takeIf(String::isNotBlank)?.let(::cappedText)
        SourceView.Contact(
            id = id,
            title = oneLine(payload.displayName, LINE_CHARS),
            organization = payload.organization?.let { oneLine(it, LINE_CHARS) },
            handles = payload.handles.map(::handleLabel),
            notes = notes?.first,
            truncated = notes?.second == true,
        )
    }
}
