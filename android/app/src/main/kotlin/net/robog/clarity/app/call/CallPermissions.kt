package net.robog.clarity.app.call

import net.robog.clarity.shared.session.Settings

/** What the morning call needs from the phone, in the order the app asks for it. */
enum class CallPermission {
    /** Posting notifications: the heads-up and the call itself (a runtime prompt on Android 13+). */
    Notifications,
    /** Opening the call over the lock screen (Android 14+: a settings page; granted at install before). */
    FullScreen,
    /** Ringing at the minute set ("Alarms & reminders", a settings page). */
    ExactAlarms,
}

/** What is missing for [settings]' morning call, in asking order; empty when it can ring as it should. */
fun missingCallPermissions(
    settings: Settings,
    notificationsOn: Boolean,
    canUseFullScreenIntent: Boolean,
    canScheduleExact: Boolean,
): List<CallPermission> = buildList {
    // Asked even with no call set: urgent holds and the agent's answers notify too.
    if (!notificationsOn) add(CallPermission.Notifications)
    val call = settings.morningCall
    if (!call.enabled || call.days.isEmpty()) return@buildList
    if (shouldAskFullScreen(call.mode, canUseFullScreenIntent)) add(CallPermission.FullScreen)
    if (shouldAskForExactAlarms(settings, canScheduleExact)) add(CallPermission.ExactAlarms)
}

/**
 * The one to explain now: the first missing one not asked yet this time the app is open. "Not now"
 * ([declined]) stops the asking until the app is opened again, and one already sent to its prompt or
 * settings page is not asked again either, whatever the person chose there.
 */
fun nextCallPermission(missing: List<CallPermission>, asked: Set<CallPermission>, declined: Boolean): CallPermission? =
    if (declined) null else missing.firstOrNull { it !in asked }
