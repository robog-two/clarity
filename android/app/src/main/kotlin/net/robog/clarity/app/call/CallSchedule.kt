package net.robog.clarity.app.call

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.shared.session.Settings

/** When the next morning call rings and when its heads-up notification shows. */
data class CallPlan(val callAt: Instant, val headsUpAt: Instant?)

/** The person's zone, falling back to the phone's when the stored id is unknown. */
fun zoneOf(settings: Settings): TimeZone =
    runCatching { TimeZone.of(settings.timeZone) }.getOrDefault(TimeZone.currentSystemDefault())

/**
 * The next call after [now] under [settings], evaluated in the person's zone (so DST shifts move
 * the instant, not the wall-clock time). Null when the call is off or no day is selected.
 * The heads-up is omitted when it is already past (the call is less than the heads-up window away).
 */
fun nextCallPlan(settings: Settings, now: Instant): CallPlan? {
    val call = settings.morningCall
    if (!call.enabled || call.days.isEmpty()) return null
    val zone = zoneOf(settings)
    val today = now.toLocalDateTime(zone).date
    for (offset in 0..7) {
        val date = today.plus(DatePeriod(days = offset))
        if (date.dayOfWeek.isoDayNumber !in call.days) continue
        val callAt = LocalDateTime(date, call.at).toInstant(zone)
        if (callAt <= now) continue
        val headsUpAt = call.headsUpMinutes?.let { callAt - it.minutes }?.takeIf { it > now }
        return CallPlan(callAt, headsUpAt)
    }
    return null
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
