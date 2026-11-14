package net.robog.clarity.shared.source

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** `Tue Oct 27`: a day without a year, for a line under a title. */
fun dayLine(day: LocalDate): String =
    "${day.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)} ${MONTHS[day.month.ordinal]} ${day.day}"

/** `Tue Oct 27, 10:00 am`: a moment on the clock of [zone], for a line under a title. */
fun momentLine(at: Instant, zone: TimeZone): String {
    val local = at.toLocalDateTime(zone)
    val hour = local.hour % 12
    val clock = "${if (hour == 0) 12 else hour}:${local.minute.toString().padStart(2, '0')} ${if (local.hour < 12) "am" else "pm"}"
    return "${dayLine(local.date)}, $clock"
}

/** `Mon Oct 26 to Wed Oct 28`, or just the day when [endDay] is null or not after it. */
fun daySpanLine(day: LocalDate, endDay: LocalDate?): String =
    if (endDay != null && endDay > day) "${dayLine(day)} to ${dayLine(endDay)}" else dayLine(day)
