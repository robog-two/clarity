package net.robog.clarity.app.push

import kotlinx.coroutines.flow.Flow
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.app.data.feed.Arrival

/** Posts the notification (or removes it); the Android side is [AndroidPushNotifier]. */
interface PushNotifier {
    fun post(plan: NotificationPlan)
}

/** Turns what arrives from the feed into notifications, as [decideNotification] says. */
class PushListener(
    private val arrivals: Flow<Arrival>,
    private val front: () -> Front,
    private val canPost: () -> Boolean,
    private val clock: AppClock,
    private val notifier: PushNotifier,
) {
    /** Runs until cancelled. */
    suspend fun run() {
        arrivals.collect { arrival ->
            decideNotification(arrival, front(), canPost(), clock.now())?.let(notifier::post)
        }
    }
}
