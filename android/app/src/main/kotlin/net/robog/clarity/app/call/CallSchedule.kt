package net.robog.clarity.app.call

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.time.ScheduleClock
import net.robog.clarity.shared.time.WeeklyTime
import net.robog.clarity.shared.time.zoneOrUtc

/** When the next morning call rings and when its heads-up notification shows. */
data class CallPlan(val callAt: Instant, val headsUpAt: Instant?)

/**
 * The next call after [now] under the stored [settings]: its UTC time on one of its UTC days, moved by
 * daylight saving in the home zone (see [ScheduleClock]). Null when the call is off or no day is
 * selected. The heads-up is omitted when it is already past (the call is less than the heads-up
 * window away).
 */
fun nextCallPlan(settings: Settings, now: Instant): CallPlan? {
    val call = settings.morningCall
    if (!call.enabled) return null
    val callAt = ScheduleClock.nextOccurrence(WeeklyTime(call.at, call.days), zoneOrUtc(settings.homeZone), now) ?: return null
    val headsUpAt = call.headsUpMinutes?.let { callAt - it.minutes }?.takeIf { it > now }
    return CallPlan(callAt, headsUpAt)
}

/** Snoozed calls ring again [minutes] after the person declined. */
fun snoozedCallAt(now: Instant, minutes: Int): Instant = now + minutes.minutes

/** What kind of alarm the phone lets us use. */
enum class AlarmKind { Exact, Inexact }

fun alarmKind(canScheduleExact: Boolean): AlarmKind = if (canScheduleExact) AlarmKind.Exact else AlarmKind.Inexact

/**
 * Whether the app should ask the person to allow exact alarms. Only worth asking when a call is
 * actually scheduled and the permission is missing.
 */
fun shouldAskForExactAlarms(settings: Settings, canScheduleExact: Boolean): Boolean =
    !canScheduleExact && settings.morningCall.enabled && settings.morningCall.days.isNotEmpty()
