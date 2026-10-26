package net.robog.clarity.backend.scheduler

import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.shared.time.ScheduleClock
import net.robog.clarity.shared.time.WeeklyTime

/** When a job runs. Pure; times of day are on the person's home clock (see [ScheduleClock]). */
sealed interface Schedule {
    /** Every [interval], measured from the previous run (a late run does not trigger a burst of catch-up runs). */
    data class Every(val interval: Duration) : Schedule {
        init { require(interval.isPositive()) { "interval must be positive" } }
    }

    /** Once a day at [time] on the home clock, daylight saving included. */
    data class DailyAt(val time: LocalTime) : Schedule

    /** A schedule derived from the person's settings (for example the call prep, which follows the call's time and days). */
    class Computed(val next: (after: Instant, zone: TimeZone) -> Instant) : Schedule
}

/** The first run strictly after [after]. For [Schedule.Every] that is one interval later. */
fun Schedule.nextAfter(after: Instant, zone: TimeZone): Instant = when (this) {
    is Schedule.Every -> after + interval
    is Schedule.Computed -> next(after, zone)
    is Schedule.DailyAt -> checkNotNull(ScheduleClock.nextOccurrence(WeeklyTime(time, ScheduleClock.EVERY_DAY), zone, after))
}
