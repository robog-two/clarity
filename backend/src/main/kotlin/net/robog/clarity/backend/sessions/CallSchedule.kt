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
import net.robog.clarity.shared.time.ScheduleClock
import net.robog.clarity.shared.time.WeeklyTime

/** How long before the call the lead prepares when the heads-up is off. */
val DEFAULT_PREP_LEAD: Duration = 10.minutes

/** Pure: how long before the call the prep job runs (the heads-up time, else ten minutes). */
fun prepLead(call: Settings.MorningCall): Duration = (call.headsUpMinutes ?: 10).minutes

/**
 * Pure: the first moment strictly after [after] at which the call is due (its time on one of its days,
 * on the clock of the home zone [zone]); null when the call is off or has no days.
 */
fun nextCallAfter(call: Settings.MorningCall, after: Instant, zone: TimeZone): Instant? =
    firstAfter(call, after, zone, Duration.ZERO)

/** Pure: the first prep time strictly after [after]: [prepLead] before a call on one of its days. */
fun nextPrepAfter(call: Settings.MorningCall, after: Instant, zone: TimeZone): Instant? =
    firstAfter(call, after, zone, prepLead(call))

/** How long before the call prep the MCP sweep runs. */
val MCP_SWEEP_LEAD: Duration = 30.minutes

/** Pure: the first MCP sweep time strictly after [after]: [MCP_SWEEP_LEAD] before a prep on one of the call's days. */
fun nextSweepAfter(call: Settings.MorningCall, after: Instant, zone: TimeZone): Instant? =
    firstAfter(call, after, zone, prepLead(call) + MCP_SWEEP_LEAD)

private fun firstAfter(call: Settings.MorningCall, after: Instant, zone: TimeZone, lead: Duration): Instant? =
    if (!call.enabled) null else ScheduleClock.nextOccurrence(WeeklyTime(call.at, call.days), zone, after, lead)
