package net.robog.clarity.backend.sessions

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.shared.session.Settings

/** How long before the call the lead prepares when the heads-up is off. */
val DEFAULT_PREP_LEAD: Duration = 10.minutes

/** Pure: how long before the call the prep job runs (the heads-up time, else ten minutes). */
fun prepLead(call: Settings.MorningCall): Duration = (call.headsUpMinutes ?: 10).minutes

/**
 * Pure: the first moment strictly after [after] at which the call is due (its local time on one of
 * its days, in [zone]); null when the call is off or has no days. A local time that does not exist
 * on a DST change moves forward, one that exists twice takes the first.
 */
fun nextCallAfter(call: Settings.MorningCall, after: Instant, zone: TimeZone): Instant? =
    firstAfter(call, after, zone, Duration.ZERO)

/** Pure: the first prep time strictly after [after]: [prepLead] before a call on one of its days. */
fun nextPrepAfter(call: Settings.MorningCall, after: Instant, zone: TimeZone): Instant? =
    firstAfter(call, after, zone, prepLead(call))

private fun firstAfter(call: Settings.MorningCall, after: Instant, zone: TimeZone, lead: Duration): Instant? {
    if (!call.enabled || call.days.isEmpty()) return null
    // A prep before midnight belongs to the next day's call, so the scan starts a day early.
    val start = after.toLocalDateTime(zone).date.minus(DatePeriod(days = 1))
    return (0..8).asSequence()
        .map { start.plus(DatePeriod(days = it)) }
        .filter { it.dayOfWeek.isoDayNumber in call.days }
        .map { it.atTime(call.at).toInstant(zone) - lead }
        .firstOrNull { it > after }
}
