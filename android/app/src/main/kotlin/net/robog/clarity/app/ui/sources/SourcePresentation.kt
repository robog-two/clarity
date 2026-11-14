package net.robog.clarity.app.ui.sources

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.app.ui.call.formatClock
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.source.SourceAttachment
import net.robog.clarity.shared.source.SourceItem
import net.robog.clarity.shared.source.SourceView

/** A labelled line in the viewer, such as "From" and who. */
data class Fact(val label: String, val value: String)

/** The viewer's long text: a message's body, an event's details, a file's excerpt, a contact's notes. */
data class SourceText(val label: String, val text: String)

/** Pure: what the viewer's top line says the record is. */
fun sourceKind(view: SourceView): String = when (view) {
    is SourceView.Message -> if (view.channel == "email") "Email" else "Message"
    is SourceView.Event -> "Event"
    is SourceView.Document -> "File"
    is SourceView.Contact -> "Contact"
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun dayText(day: LocalDate): String =
    "${day.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)} ${MONTHS[day.month.ordinal]} ${day.day}, ${day.year}"

/** Pure: a moment spelled out in full so it can be checked: "Mon Oct 26, 2026 at 10:00 am". */
fun momentText(at: Instant, zone: TimeZone): String = "${dayText(at.toLocalDateTime(zone).date)} at ${formatClock(at, zone)}"

/** Pure: when an event is: a span of days when all-day (stored with an exclusive end), else start and end. */
fun whenText(view: SourceView.Event, zone: TimeZone): String {
    if (view.allDay) {
        val first = view.start.toLocalDateTime(TimeZone.UTC).date
        val last = kotlinx.datetime.LocalDate.fromEpochDays((view.end.toLocalDateTime(TimeZone.UTC).date.toEpochDays() - 1).coerceAtLeast(first.toEpochDays()))
        return if (last == first) "${dayText(first)}, all day" else "${dayText(first)} to ${dayText(last)}"
    }
    val start = view.start.toLocalDateTime(zone)
    val end = view.end.toLocalDateTime(zone)
    return if (start.date == end.date) "${dayText(start.date)}, ${formatClock(view.start, zone)} to ${formatClock(view.end, zone)}"
    else "${momentText(view.start, zone)} to ${momentText(view.end, zone)}"
}

/** Pure: "1.2 MB", "340 KB", "12 bytes". */
fun fileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes bytes"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(java.util.Locale.US, bytes / (1024.0 * 1024.0))
}

/** Pure: a file type in a word: PDF, Image, Text, or the subtype in capitals. */
fun typeLabel(mimeType: String?): String {
    val type = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return when {
        type.isEmpty() || type == "application/octet-stream" -> "File"
        type == "application/pdf" -> "PDF"
        type.startsWith("image/") -> "Image"
        type.startsWith("text/") -> "Text"
        type.startsWith("audio/") -> "Audio"
        type.startsWith("video/") -> "Video"
        else -> type.substringAfter('/').substringAfterLast('.').substringAfterLast('+').take(8).uppercase().ifEmpty { "File" }
    }
}

/** Pure: the line under a file's name: "PDF, 1.2 MB". */
fun attachmentLine(attachment: SourceAttachment): String =
    listOfNotNull(typeLabel(attachment.mimeType), attachment.size?.let(::fileSize)).joinToString(", ")

/** Pure: a name for a file in the cache that cannot climb out of it or hide: no path, no control characters, never empty. */
fun safeFileName(name: String?, part: String): String {
    val cleaned = name.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        .filter { !it.isISOControl() && it !in "<>:\"|?*" }.trim().trimStart('.').take(120)
    return cleaned.ifEmpty { "attachment-" + part.filter { it.isLetterOrDigit() }.ifEmpty { "file" } }
}

/** Pure: the lines under the viewer's title. */
fun sourceFacts(view: SourceView, zone: TimeZone): List<Fact> = when (view) {
    is SourceView.Message -> buildList {
        add(Fact("From", view.from))
        if (view.to.isNotEmpty()) add(Fact("To", view.to.joinToString(", ")))
        add(Fact("Date", momentText(view.sentAt, zone)))
        add(Fact(if (view.channel == "email") "Account" else "Your number", view.account))
    }
    is SourceView.Event -> buildList {
        add(Fact("When", whenText(view, zone)))
        view.location?.let { add(Fact("Where", it)) }
        view.repeats?.let { add(Fact("Repeats", it)) }
        add(Fact("Calendar", view.calendar))
        if (view.attendees.isNotEmpty()) add(Fact("People", view.attendees.joinToString(", ")))
        view.link?.let { add(Fact("Link", it)) }
    }
    is SourceView.Document -> buildList {
        add(Fact("Path", view.path))
        view.mimeType?.let { add(Fact("Type", typeLabel(it))) }
        view.size?.let { add(Fact("Size", fileSize(it))) }
        view.modifiedAt?.let { add(Fact("Changed", momentText(it, zone))) }
    }
    is SourceView.Contact -> buildList {
        view.organization?.let { add(Fact("Company", it)) }
        view.handles.forEach { add(Fact(if ('@' in it) "Email" else "Number", it)) }
    }
}

/** Pure: the viewer's long text, or null when the record has none. */
fun sourceText(view: SourceView): SourceText? = when (view) {
    is SourceView.Message -> SourceText("Message", view.body)
    is SourceView.Event -> view.description?.let { SourceText("Details", it) }
    is SourceView.Document -> view.excerpt.takeIf(String::isNotBlank)?.let { SourceText("Excerpt", it) }
    is SourceView.Contact -> view.notes?.let { SourceText("Notes", it) }
}

/** Pure: the files of a message, as the viewer lists them. */
fun sourceAttachments(view: SourceView): List<SourceAttachment> = (view as? SourceView.Message)?.attachments.orEmpty()

/** Pure: whether [url] is something the browser may be handed: a web address and nothing else. */
fun isWebAddress(url: String): Boolean =
    (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)) && url.none { it.isWhitespace() || it.isISOControl() }

/** Pure: the line under an item's title on the card: sender and date, or the site. */
fun sourceItemLine(item: SourceItem): String = when (item) {
    is SourceItem.Entity -> listOfNotNull(
        item.sub.takeIf(String::isNotBlank),
        item.attachments.takeIf { it > 0 }?.let { if (it == 1) "1 file" else "$it files" },
    ).joinToString(", ")
    is SourceItem.Web -> item.host
}

/** Pure: the card's heading: "1 source", "3 sources". */
fun sourcesHeading(card: Card.Sources): String = if (card.items.size == 1) "1 source" else "${card.items.size} sources"
