package net.robog.clarity.app.ui.settings

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import net.robog.clarity.app.connector.SmsSyncProblem
import net.robog.clarity.app.connector.SmsSyncStatus
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

/** "9:00 am, weekdays" */
fun callTimeSummary(call: MorningCall): String = "${formatTime(call.at)}, ${daysSummary(call.days)}"

/** The "messages" row on the phone: a line about the sync, the trailing word, and whether tapping it should ask. */
data class SmsRowText(val sub: String, val trailing: String, val needsAllow: Boolean = false, val ok: Boolean = false)

/** "1 text" / "1,204 texts" */
fun textsCount(n: Long): String = if (n == 1L) "1 text" else "%,d texts".format(java.util.Locale.US, n)

fun smsRowText(granted: Boolean, status: SmsSyncStatus, now: Instant): SmsRowText {
    if (!granted) return SmsRowText("allow reading texts", "allow", needsAllow = true)
    val synced = textsCount(status.sent) + " synced"
    return when (status.problem) {
        SmsSyncProblem.NOT_ALLOWED -> SmsRowText("server not accepting texts yet", "allow", needsAllow = true)
        SmsSyncProblem.UNREACHABLE -> SmsRowText("server unreachable. $synced", relativeTime(status.lastSyncAt, now))
        SmsSyncProblem.NOT_ACCEPTED -> SmsRowText("server rejected this phone", "failed")
        SmsSyncProblem.READ_FAILED -> SmsRowText("can’t read texts on this phone", "failed")
        SmsSyncProblem.SERVER_ERROR -> SmsRowText("server error. $synced", "failed")
        null -> if (status.lastSyncAt == null) SmsRowText("waiting for the first sync", "never")
        else SmsRowText("$synced with your server", relativeTime(status.lastSyncAt, now), ok = true)
    }
}
