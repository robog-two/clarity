package net.robog.clarity.shared.time

import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
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
 * The server stores them on the clock of the person's *home zone* (the zone they were in when they
 * set them): the wall-clock time of day, and the weekdays, as they read there.
 * - The zone's rules turn them into moments, so 19:00 in New York stays 19:00 there all year.
 * - Moving the phone to another zone does not: the same moment shows as 16:00 in Los Angeles.
 * - A time skipped when clocks go forward happens that much later (02:30 becomes 03:30); a time that
 *   happens twice when they go back happens the first time.
 *
 * Everything here is pure; callers pass the zones and "now".
 */
object ScheduleClock {
    val EVERY_DAY: Set<Int> = (1..7).toSet()

    /** Stored → moment: when stored time of day [at] happens on [date] on the clock of [home]. */
    fun occurrence(date: LocalDate, at: LocalTime, home: TimeZone): Instant = date.atTime(at).toInstant(home)

    /**
     * Stored → moment: the first occurrence of [time] strictly after [after], [lead] early (a heads-up
     * or a prep before it). Null when it has no days.
     */
    fun nextOccurrence(time: WeeklyTime, home: TimeZone, after: Instant, lead: Duration = Duration.ZERO): Instant? {
        if (time.days.isEmpty()) return null
        // An occurrence [lead] early may sit on the previous date.
        val start = after.toLocalDateTime(home).date.minus(DatePeriod(days = 1))
        return (0..9).asSequence()
            .map { start.plus(DatePeriod(days = it)) }
            .filter { it.dayOfWeek.isoDayNumber in time.days }
            .map { occurrence(it, time.at, home) - lead }
            .firstOrNull { it > after }
    }

    /**
     * Seen → stored: [seen] as read on a clock in [zone], restated on the clock of [home]. Usually the
     * two are the same zone (the phone's, when the person sets a time) and nothing changes. Weekdays
     * move with the time when it crosses midnight between them. [near] picks the week.
     */
    fun toStored(seen: WeeklyTime, zone: TimeZone, home: TimeZone, near: Instant): WeeklyTime =
        if (zone.id == home.id) seen else restate(seen, near.toLocalDateTime(zone).date, from = zone, to = home)

    /** Stored → seen: [stored] on the clock of [home], as it reads on a clock in [zone] in the week around [near]. */
    fun toSeen(stored: WeeklyTime, zone: TimeZone, home: TimeZone, near: Instant): WeeklyTime =
        if (zone.id == home.id) stored else restate(stored, near.toLocalDateTime(home).date, from = home, to = zone)

    /** A daily time of day, [seen] in [zone] → stored. */
    fun timeToStored(seen: LocalTime, zone: TimeZone, home: TimeZone, near: Instant): LocalTime =
        toStored(WeeklyTime(seen, emptySet()), zone, home, near).at

    /** A daily stored time of day → as seen in [zone]. */
    fun timeToSeen(stored: LocalTime, zone: TimeZone, home: TimeZone, near: Instant): LocalTime =
        toSeen(WeeklyTime(stored, emptySet()), zone, home, near).at

    /** A protected block [seen] in [zone] → stored. A block ending at or before its start ends the next day. */
    fun blockToStored(seen: TimeBlock, zone: TimeZone, home: TimeZone, near: Instant): TimeBlock {
        val start = toStored(WeeklyTime(seen.start, seen.days), zone, home, near)
        return seen.copy(days = start.days, start = start.at, end = timeToStored(seen.end, zone, home, near))
    }

    /** A stored protected block → as seen in [zone]. */
    fun blockToSeen(stored: TimeBlock, zone: TimeZone, home: TimeZone, near: Instant): TimeBlock {
        val start = toSeen(WeeklyTime(stored.start, stored.days), zone, home, near)
        return stored.copy(days = start.days, start = start.at, end = timeToSeen(stored.end, zone, home, near))
    }

    /** The person's calendar date at [moment], in their home zone [home]. */
    fun homeDate(moment: Instant, home: TimeZone): LocalDate = moment.toLocalDateTime(home).date

    /** [time] on the clock of [from], read on the clock of [to], on its next days from [today] (a date in [from]). */
    private fun restate(time: WeeklyTime, today: LocalDate, from: TimeZone, to: TimeZone): WeeklyTime {
        fun on(date: LocalDate): LocalDateTime = occurrence(date, time.at, from).toLocalDateTime(to)
        if (time.days.isEmpty()) return WeeklyTime(on(today).time, emptySet())
        val each = time.days.map { day -> on(nextOn(today, day)) }
        return WeeklyTime(each.first().time, each.map { it.dayOfWeek.isoDayNumber }.toSet())
    }

    /** [from], or the first date after it, that falls on ISO weekday [isoDay]. */
    private fun nextOn(from: LocalDate, isoDay: Int): LocalDate =
        from.plus(DatePeriod(days = (isoDay - from.dayOfWeek.isoDayNumber + 7) % 7))
}

/** A zone id that falls back to UTC instead of throwing, for ids typed by people or stored long ago. */
fun zoneOrUtc(id: String): TimeZone = runCatching { TimeZone.of(id) }.getOrDefault(TimeZone.UTC)
