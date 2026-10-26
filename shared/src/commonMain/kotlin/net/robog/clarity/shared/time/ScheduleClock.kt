package net.robog.clarity.shared.time

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.shared.governance.TimeBlock

/** A repeating time: a time of day on ISO weekdays (1 = Monday). Which clock it is on depends on where it is. */
data class WeeklyTime(val at: LocalTime, val days: Set<Int>)

/**
 * The one place that converts repeating times (the morning call, the evening summary, protected
 * hours) between what a person sees and what the server stores.
 *
 * The server stores them in UTC: the UTC time of day, and the UTC weekdays, that they fall on at the
 * standard (winter) offset of their *home zone*, the zone the person was in when they set them.
 * - Daylight saving in the home zone moves the moment, so 8:30 in New York stays 8:30 there all year.
 * - Moving the phone to another zone does not: the same moment shows as 5:30 in Los Angeles.
 *
 * Everything here is pure; callers pass the zones and "now".
 */
object ScheduleClock {
    val EVERY_DAY: Set<Int> = (1..7).toSet()

    /** [rules]' standard offset in [year]: the smaller of its January and July offsets. */
    fun standardOffset(rules: TimeZone, year: Int): Duration {
        val january = LocalDateTime(year, 1, 15, 12, 0).toInstant(TimeZone.UTC)
        val july = LocalDateTime(year, 7, 15, 12, 0).toInstant(TimeZone.UTC)
        return minOf(rules.offsetAt(january).totalSeconds, rules.offsetAt(july).totalSeconds).seconds
    }

    /** How far daylight saving in [rules] has moved clocks at [moment] (one hour in a US summer, else zero). */
    fun daylightShift(rules: TimeZone, moment: Instant): Duration =
        rules.offsetAt(moment).totalSeconds.seconds - standardOffset(rules, moment.toLocalDateTime(TimeZone.UTC).year)

    /** Stored → moment: when a stored time of day [at] happens for UTC date [date]. */
    fun occurrence(date: LocalDate, at: LocalTime, rules: TimeZone): Instant {
        val standard = date.atTime(at).toInstant(TimeZone.UTC)
        return standard - daylightShift(rules, standard)
    }

    /**
     * Stored → moment: the first occurrence of [time] strictly after [after], [lead] early (a heads-up
     * or a prep before it). Null when it has no days.
     */
    fun nextOccurrence(time: WeeklyTime, rules: TimeZone, after: Instant, lead: Duration = Duration.ZERO): Instant? {
        if (time.days.isEmpty()) return null
        // An occurrence [lead] early, or moved by daylight saving, may sit on the previous UTC date.
        val start = after.toLocalDateTime(TimeZone.UTC).date.minus(DatePeriod(days = 1))
        return (0..9).asSequence()
            .map { start.plus(DatePeriod(days = it)) }
            .filter { it.dayOfWeek.isoDayNumber in time.days }
            .map { occurrence(it, time.at, rules) - lead }
            .firstOrNull { it > after }
    }

    /**
     * Seen → stored: [seen] as read on a clock in [zone], with daylight saving following [rules]
     * (usually the same zone: the phone's, when the person sets a time). Weekdays move with the time
     * when it crosses midnight in UTC. [near] picks the week the conversion is made in.
     */
    fun toStored(seen: WeeklyTime, zone: TimeZone, rules: TimeZone, near: Instant): WeeklyTime {
        val today = near.toLocalDateTime(zone).date
        fun stored(date: LocalDate): LocalDateTime {
            val moment = date.atTime(seen.at).toInstant(zone)
            return (moment + daylightShift(rules, moment)).toLocalDateTime(TimeZone.UTC)
        }
        if (seen.days.isEmpty()) return WeeklyTime(stored(today).time, emptySet())
        val each = seen.days.map { day -> stored(nextOn(today, day)) }
        return WeeklyTime(each.first().time, each.map { it.dayOfWeek.isoDayNumber }.toSet())
    }

    /** Stored → seen: [stored] as it reads on a clock in [zone], in the week around [near]. */
    fun toSeen(stored: WeeklyTime, zone: TimeZone, rules: TimeZone, near: Instant): WeeklyTime {
        val today = near.toLocalDateTime(TimeZone.UTC).date
        fun seen(date: LocalDate): LocalDateTime = occurrence(date, stored.at, rules).toLocalDateTime(zone)
        if (stored.days.isEmpty()) return WeeklyTime(seen(today).time, emptySet())
        val each = stored.days.map { day -> seen(nextOn(today, day)) }
        return WeeklyTime(each.first().time, each.map { it.dayOfWeek.isoDayNumber }.toSet())
    }

    /** A daily time of day, [seen] in [zone] → stored. */
    fun timeToStored(seen: LocalTime, zone: TimeZone, rules: TimeZone, near: Instant): LocalTime =
        toStored(WeeklyTime(seen, emptySet()), zone, rules, near).at

    /** A daily stored time of day → as seen in [zone]. */
    fun timeToSeen(stored: LocalTime, zone: TimeZone, rules: TimeZone, near: Instant): LocalTime =
        toSeen(WeeklyTime(stored, emptySet()), zone, rules, near).at

    /** A protected block [seen] in [zone] → stored. A block ending at or before its start ends the next day. */
    fun blockToStored(seen: TimeBlock, zone: TimeZone, rules: TimeZone, near: Instant): TimeBlock {
        val start = toStored(WeeklyTime(seen.start, seen.days), zone, rules, near)
        return seen.copy(days = start.days, start = start.at, end = timeToStored(seen.end, zone, rules, near))
    }

    /** A stored protected block → as seen in [zone]. */
    fun blockToSeen(stored: TimeBlock, zone: TimeZone, rules: TimeZone, near: Instant): TimeBlock {
        val start = toSeen(WeeklyTime(stored.start, stored.days), zone, rules, near)
        return stored.copy(days = start.days, start = start.at, end = timeToSeen(stored.end, zone, rules, near))
    }

    /** The person's calendar date at [moment], in their home zone [rules]. */
    fun homeDate(moment: Instant, rules: TimeZone): LocalDate = moment.toLocalDateTime(rules).date

    /** [from], or the first date after it, that falls on ISO weekday [isoDay]. */
    private fun nextOn(from: LocalDate, isoDay: Int): LocalDate =
        from.plus(DatePeriod(days = (isoDay - from.dayOfWeek.isoDayNumber + 7) % 7))
}

/** A zone id that falls back to UTC instead of throwing, for ids typed by people or stored long ago. */
fun zoneOrUtc(id: String): TimeZone = runCatching { TimeZone.of(id) }.getOrDefault(TimeZone.UTC)
