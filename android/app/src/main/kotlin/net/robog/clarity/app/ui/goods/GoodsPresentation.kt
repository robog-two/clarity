package net.robog.clarity.app.ui.goods

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.app.ui.call.formatClock
import net.robog.clarity.shared.report.ReportDetail
import net.robog.clarity.shared.report.ReportKind
import net.robog.clarity.shared.report.ReportSummary

/** One row of the Goods tab. */
data class GoodsRow(val id: String, val title: String, val detail: String)

/** Pure: what a kind is called in the app. */
fun kindLabel(kind: ReportKind): String = when (kind) {
    ReportKind.DOCUMENT -> "Document"
    ReportKind.DECK -> "Slides"
    ReportKind.SHEET -> "Spreadsheet"
    ReportKind.SITE -> "Site"
}

/** Pure: when, the way a list of things says it: "Today, 9:41", "Yesterday", "Oct 26", "Oct 26, 2025". */
fun goodsWhen(at: Instant, now: Instant, zone: TimeZone): String {
    val day = at.toLocalDateTime(zone).date
    val today = now.toLocalDateTime(zone).date
    return when {
        day == today -> "Today, ${formatClock(at, zone)}"
        day == today.minus(1, kotlinx.datetime.DateTimeUnit.DAY) -> "Yesterday"
        day.year == today.year -> shortDate(day)
        else -> "${shortDate(day)}, ${day.year}"
    }
}

private fun shortDate(day: LocalDate): String =
    day.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3) + " " + day.day

/** Pure: the Goods tab's rows, the most recently changed first: "Document · Today, 9:41 · revision 2". */
fun goodsRows(reports: List<ReportSummary>, now: Instant, zone: TimeZone): List<GoodsRow> =
    reports.sortedByDescending { it.updatedAt }.map { r ->
        val revised = if (r.latestRevision > 1) " · revision ${r.latestRevision}" else ""
        GoodsRow(r.id, r.title, "${kindLabel(r.kind)} · ${goodsWhen(r.updatedAt, now, zone)}$revised")
    }

/** Pure: the line under the viewer's title: "Document · revision 3 · Today, 9:41", or when it was written. */
fun revisionLine(detail: ReportDetail, now: Instant, zone: TimeZone): String {
    val latest = detail.revisions.maxByOrNull { it.n }
    val kind = kindLabel(detail.kind)
    return if (latest == null || latest.n == 1) "$kind · ${goodsWhen(detail.createdAt, now, zone)}"
    else "$kind · revision ${latest.n} · ${goodsWhen(latest.createdAt, now, zone)}"
}

/** Pure: a source's site, for the line under its title ("cdc.gov"); the URL itself when it has none. */
fun sourceSite(url: String): String {
    val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@').substringBefore(':')
    return host.removePrefix("www.").ifEmpty { url }
}
