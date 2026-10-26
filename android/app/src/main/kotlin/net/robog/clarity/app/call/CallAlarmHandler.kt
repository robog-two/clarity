package net.robog.clarity.app.call

import kotlinx.coroutines.flow.first
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.app.data.seenSettings
import net.robog.clarity.app.data.feed.FeedStore
import net.robog.clarity.app.device.DeviceCapabilities
import net.robog.clarity.app.ui.onboarding.formatTime
import net.robog.clarity.shared.session.Settings

/** What the call shows on the phone. */
interface CallNotifier {
    fun headsUp(title: String, body: String)
    fun incoming(presentation: IncomingPresentation, callTime: String)
    fun ongoing(sessionId: String)
    fun cancelHeadsUp()
    fun cancelIncoming()
    fun cancelOngoing()
}

/** Reacts to the alarms: posts the heads-up or the call, then arms the next alarm. */
class CallAlarmHandler(
    private val cache: CallSettingsCache,
    private val feed: FeedStore,
    private val notifier: CallNotifier,
    private val capabilities: DeviceCapabilities,
    private val scheduler: CallScheduler,
    private val clock: AppClock,
) {
    suspend fun onAlarm(slot: AlarmSlot) {
        val settings = cache.load() ?: Settings()
        val call = settings.morningCall
        // What the person reads: the call time on the phone's clock.
        val seenAt = seenSettings(settings, clock.zone(), clock.now()).morningCall.at
        when (slot) {
            AlarmSlot.HeadsUp -> {
                if (capabilities.notificationsEnabled()) {
                    val items = feed.items.first()
                    notifier.headsUp(
                        headsUpTitle(call.headsUpMinutes ?: 10),
                        headsUpBody(seenAt, items, clock.now()),
                    )
                }
            }
            AlarmSlot.Call, AlarmSlot.Snooze -> {
                val presentation = incomingPresentation(
                    call.mode, capabilities.canUseFullScreenIntent(), capabilities.notificationsEnabled(),
                )
                if (presentation != IncomingPresentation.None) notifier.incoming(presentation, formatTime(seenAt))
                notifier.cancelHeadsUp()
            }
        }
        // Arm the next occurrence (a fired Call alarm is past, so this picks the next day).
        if (slot != AlarmSlot.Snooze) scheduler.apply(settings)
    }
}
