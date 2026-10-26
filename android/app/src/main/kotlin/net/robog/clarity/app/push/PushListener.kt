package net.robog.clarity.app.push

import kotlinx.coroutines.flow.Flow
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.app.data.feed.Arrival

/** Posts the notification (or removes it); the Android side is [AndroidPushNotifier]. */
interface PushNotifier {
    fun post(plan: NotificationPlan)

    /** The person replied from the notification; [sent] says whether the server took it. */
    fun replied(text: String, sent: Boolean) {}

    /** The app came to the front: the conversation is read there, so the notification goes. */
    fun clearConversation() {}
}

/**
 * Turns what arrives from the feed into notifications, as [decideNotification] says. The service
 * runs it live; the background fallback hands it what a catch-up found. Both claim each arrival in
 * [ledger] first, so the same thing is never decided (or notified) twice.
 */
class PushListener(
    private val arrivals: Flow<Arrival>,
    private val front: () -> Front,
    private val canPost: () -> Boolean,
    private val clock: AppClock,
    private val notifier: PushNotifier,
    private val ledger: NotifiedLedger = NotifiedLedger.None,
) {
    /** Runs until cancelled. */
    suspend fun run() {
        arrivals.collect { deliver(it) }
    }

    /** Posts what [arrival] earns, unless either path already handled it. Returns whether it posted. */
    suspend fun deliver(arrival: Arrival): Boolean {
        if (!ledger.claim(arrival)) return false
        val plan = decideNotification(arrival, front(), canPost(), clock.now()) ?: return false
        notifier.post(plan)
        return true
    }
}
