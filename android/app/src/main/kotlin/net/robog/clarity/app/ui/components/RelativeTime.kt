package net.robog.clarity.app.ui.components

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The one relative time of the app, always "N unit ago": "5 min ago", "2 h ago", "1 day ago",
 * "3 days ago". Under a minute is "Just now"; "never" when there is no time.
 */
fun relativeTime(then: Instant?, now: Instant): String {
    if (then == null) return "never"
    val ago = now - then
    return when {
        ago < 1.minutes -> "Just now"
        ago < 1.hours -> "${ago.inWholeMinutes} min ago"
        ago < 1.days -> "${ago.inWholeHours} h ago"
        else -> ago.inWholeDays.let { if (it == 1L) "1 day ago" else "$it days ago" }
    }
}
