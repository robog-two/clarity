package net.robog.clarity.app.ui.settings

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import net.robog.clarity.app.ui.onboarding.dayName
import net.robog.clarity.app.ui.onboarding.formatTime
import net.robog.clarity.shared.session.Settings.MorningCall

/** "2 min ago", "1 hr ago", "3 days ago"; "never" when there is no time. */
fun relativeTime(then: Instant?, now: Instant): String {
    if (then == null) return "never"
    val ago = now - then
    return when {
        ago < 1.minutes -> "just now"
        ago < 1.hours -> "${ago.inWholeMinutes} min ago"
        ago < 1.days -> "${ago.inWholeHours} hr ago"
        else -> ago.inWholeDays.let { if (it == 1L) "yesterday" else "$it days ago" }
    }
}

/** "weekdays", "every day", "weekends" or the day names ("Mon, Wed"). */
fun daysSummary(days: Set<Int>): String = when {
    days.isEmpty() -> "no days"
    days == setOf(1, 2, 3, 4, 5) -> "weekdays"
    days == (1..7).toSet() -> "every day"
    days == setOf(6, 7) -> "weekends"
    else -> days.sorted().joinToString(", ") { dayName(it).take(3) }
}

/** "9:00 · weekdays" */
fun callTimeSummary(call: MorningCall): String = "${formatTime(call.at)} · ${daysSummary(call.days)}"
