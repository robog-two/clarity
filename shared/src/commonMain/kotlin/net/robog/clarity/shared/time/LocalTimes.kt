package net.robog.clarity.shared.time

import kotlin.math.absoluteValue
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * The one way moments are shown to, and read from, the agents: on the person's own clock (their
 * home zone), never in UTC, so "today", "tonight" and weekdays line up with what the person means.
 *
 * Shown: ISO-8601 with the local offset and the weekday, `2026-10-26T19:00-04:00 (Mon)` (seconds only
 * when they are not zero); a day is `2026-10-26 (Mon)`. Still exact and machine-readable.
 *
 * Read ([parse]): an ISO instant with an offset or `Z` (taken as it is), a local date-time without
 * an offset (a wall-clock time in the zone, resolved like [ScheduleClock.occurrence]: a time
 * skipped when clocks go forward happens that much later, a time that happens twice happens the
 * first time), or a plain date. A trailing weekday like ` (Mon)` is ignored, so what is shown can be
 * passed back. Storage stays in instants; only the agent-facing text changes.
 */
object LocalTimes {
    private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** `Mon`…`Sun`. */
    fun weekday(day: DayOfWeek): String = WEEKDAYS[day.isoDayNumber - 1]

    /** [moment] on the clock of [zone]: `2026-10-26T19:00-04:00 (Mon)`. */
    fun format(moment: Instant, zone: TimeZone): String {
        val local = moment.toLocalDateTime(zone)
        val t = local.time
        return buildString {
            append(local.date).append('T').append(two(t.hour)).append(':').append(two(t.minute))
            if (t.second != 0) append(':').append(two(t.second))
            append(offset(zone.offsetAt(moment)))
            append(" (").append(weekday(local.date.dayOfWeek)).append(')')
        }
    }

    /** A day: `2026-10-26 (Mon)`. */
    fun formatDate(date: LocalDate): String = "$date (${weekday(date.dayOfWeek)})"

    /**
     * A span for summaries: `2026-10-26T19:00-04:00 (Mon) to 20:00` when both ends are on the same
     * date at the same offset, otherwise both ends in full.
     */
    fun formatRange(start: Instant, end: Instant, zone: TimeZone): String {
        val s = start.toLocalDateTime(zone)
        val e = end.toLocalDateTime(zone)
        val tail = if (s.date == e.date && zone.offsetAt(start) == zone.offsetAt(end)) {
            two(e.time.hour) + ":" + two(e.time.minute) + (if (e.time.second != 0) ":" + two(e.time.second) else "")
        } else {
            format(end, zone)
        }
        return "${format(start, zone)} to $tail"
    }

    /** The current moment for a prompt: `2026-10-26T19:03-04:00 (Mon), America/New_York`. */
    fun now(moment: Instant, zone: TimeZone): String = "${format(moment, zone)}, ${zone.id}"

    /**
     * The days an all-day event covers, first and last (inclusive). All-day events are stored as
     * midnights in UTC (a floating date read as UTC), so the dates are read there, not in the
     * person's zone; an end at or before the start is a one-day event.
     */
    fun allDayDates(start: Instant, end: Instant): Pair<LocalDate, LocalDate> {
        val first = start.toLocalDateTime(TimeZone.UTC).date
        val endLocal = end.toLocalDateTime(TimeZone.UTC)
        // An exclusive end at midnight belongs to the day before; one inside a day belongs to it.
        val last = if (endLocal.time == LocalTime(0, 0)) endLocal.date.minus(DatePeriod(days = 1)) else endLocal.date
        return first to maxOf(first, last)
    }

    /** What [parse] read: an exact moment, or a whole day. */
    sealed interface Parsed {
        /** A moment; [hadOffset] tells whether the text named its own offset. */
        data class At(val instant: Instant, val hadOffset: Boolean) : Parsed

        /** A plain date. [start] and [end] are the day's bounds in the zone it was read in (end exclusive). */
        data class Day(val date: LocalDate, val start: Instant, val end: Instant) : Parsed
    }

    private val PATTERN = Regex(
        """^(\d{4}-\d{2}-\d{2})(?:[Tt ](\d{1,2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?\s*(?:([Zz])|([+-])(\d{2})(?::?(\d{2}))?)?)?$""",
    )
    private val WEEKDAY_SUFFIX = Regex("""\s*\(\s*[A-Za-z]{2,9}\.?\s*\)\s*$""")

    /** Reads [raw] (see the class comment); local times and dates are read on the clock of [zone]. Null when it is not a time. */
    fun parse(raw: String, zone: TimeZone): Parsed? {
        val text = raw.trim().replace(WEEKDAY_SUFFIX, "")
        val m = PATTERN.matchEntire(text) ?: return null
        val g = m.groupValues
        val date = runCatching { LocalDate.parse(g[1]) }.getOrNull() ?: return null
        if (g[2].isEmpty()) {
            return Parsed.Day(date, date.atStartOfDayIn(zone), date.plus(DatePeriod(days = 1)).atStartOfDayIn(zone))
        }
        val hour = g[2].toInt()
        val nanos = g[5].takeIf { it.isNotEmpty() }?.padEnd(9, '0')?.toInt() ?: 0
        val time = runCatching { LocalTime(hour, g[3].toInt(), g[4].ifEmpty { "0" }.toInt(), nanos) }.getOrNull() ?: return null
        val offset = when {
            g[6].isNotEmpty() -> UtcOffset.ZERO
            g[7].isNotEmpty() -> {
                val sign = if (g[7] == "-") -1 else 1
                runCatching { UtcOffset(hours = sign * g[8].toInt(), minutes = sign * g[9].ifEmpty { "0" }.toInt()) }.getOrNull() ?: return null
            }
            else -> null
        }
        return if (offset != null) {
            Parsed.At(date.atTime(time).toInstant(offset), hadOffset = true)
        } else {
            Parsed.At(ScheduleClock.occurrence(date, time, zone), hadOffset = false)
        }
    }

    /** [raw] as a moment; a plain date is the start of that day in [zone]. Null when it is not a time. */
    fun parseMoment(raw: String, zone: TimeZone): Instant? = when (val p = parse(raw, zone)) {
        is Parsed.At -> p.instant
        is Parsed.Day -> p.start
        null -> null
    }

    /** [raw] as a calendar date (`2026-10-26`, optionally with its weekday). Null otherwise. */
    fun parseDate(raw: String): LocalDate? =
        (parse(raw, TimeZone.UTC) as? Parsed.Day)?.date

    private fun two(n: Int) = n.toString().padStart(2, '0')

    private fun offset(o: UtcOffset): String {
        val total = o.totalSeconds
        val sign = if (total < 0) '-' else '+'
        val abs = total.absoluteValue
        return "$sign${two(abs / 3600)}:${two(abs % 3600 / 60)}"
    }
}
