package net.robog.clarity.app.push

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.app.data.feed.Arrival
import net.robog.clarity.app.data.feed.FeedStore
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.Urgency
import net.robog.clarity.shared.session.SessionId

/** Posts the notification (or removes it); the Android side is [AndroidPushNotifier]. */
interface PushNotifier {
    fun post(plan: NotificationPlan)

    /** The person replied from the notification; [sent] says whether the server took it. */
    fun replied(text: String, sent: Boolean) {}

    /** The app came to the front: the conversation is read there, so the notification goes. */
    fun clearConversation() {}

    /** The server was forgotten: every notification about it goes (its links lead nowhere now). */
    fun clearAll() {}
}

/**
 * Turns what arrives from the feed into notifications, as [decideNotification] says. The service
 * runs it live; the background fallback hands it what a catch-up found. Both claim each arrival in
 * [ledger] first, so the same thing is never decided (or notified) twice.
 *
 * Both the push service and the ongoing call hold it through [ConnectionHolder.holdNotifications], so
 * it runs once however many of them want it.
 *
 * Besides the live [arrivals], [batches] are what a reconnect's gap fill brought in one go, and
 * [cached] is what the cache holds that might never have been claimed (stored while neither the
 * service nor the fallback was listening, such as with the app open on another screen while the
 * service was dead). Those go through [deliverMissed]: a longer age limit, and a summary instead of
 * a burst. Whether something was notified is the ledger's answer, never the cache's.
 */
class PushListener(
    private val arrivals: Flow<Arrival>,
    private val front: () -> Front,
    private val canPost: () -> Boolean,
    private val clock: AppClock,
    private val notifier: PushNotifier,
    private val ledger: NotifiedLedger = NotifiedLedger.None,
    private val batches: Flow<List<Arrival>> = emptyFlow(),
    private val cached: suspend () -> List<Arrival> = { emptyList() },
    /** The morning call still running, whose messages lead back to it (see [decideNotification]). */
    private val call: () -> SessionId? = { null },
) {
    /** Runs until cancelled. On start it posts what was missed while nothing was running. */
    suspend fun run() {
        coroutineScope {
            // Listen before looking back, so that nothing arriving in between falls through.
            launch(start = CoroutineStart.UNDISPATCHED) { batches.collect { deliverMissed(it) } }
            launch(start = CoroutineStart.UNDISPATCHED) { arrivals.collect { deliver(it) } }
            deliverMissed(emptyList())
        }
    }

    /** Posts what [arrival] earns, unless either path already handled it. Returns whether it posted. */
    suspend fun deliver(arrival: Arrival): Boolean {
        if (!ledger.claim(arrival)) return false
        val plan = decideNotification(arrival, front(), canPost(), clock.now(), call = call()) ?: return false
        notifier.post(plan)
        return true
    }

    /**
     * Posts what was missed among [fetched] and the [cached] items, as [decideMissed] says: each
     * unclaimed one is claimed, and what still deserves a notification posts on its own, or as one
     * summary when there are many. Returns how many notifications it posted.
     */
    suspend fun deliverMissed(fetched: List<Arrival>): Int {
        val candidates = (fetched + cached()).distinctBy(::arrivalKey)
        if (candidates.isEmpty()) return 0
        val fresh = ledger.claimAll(candidates)
        if (fresh.isEmpty()) return 0
        val plans = decideMissed(fresh, front(), canPost(), clock.now(), ledger.since(), call = call())
        plans.forEach(notifier::post)
        return plans.size
    }
}

/** Tells arrivals apart the way the ledger does: a release is not its hold. */
private fun arrivalKey(arrival: Arrival): Any = when (arrival) {
    is Arrival.Item -> arrival.item.seq to arrival.item.notify.startsWith("released:")
    is Arrival.Held -> arrival.action.id
}

/**
 * What the cache holds that could deserve a notification: the recent finished agent items and the
 * urgent held actions still waiting. The ledger then says which were never claimed.
 */
suspend fun FeedStore.notifiable(limit: Int = SEEN_LIMIT): List<Arrival> =
    recentItems(limit).filter { it.author == FeedItem.Author.AGENT && !it.streaming && it.topic == null }.map { Arrival.Item(it) } +
        held.first().filter { it.status == HeldAction.Status.HELD && it.urgency == Urgency.URGENT }.map { Arrival.Held(it) }
