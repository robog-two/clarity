package net.robog.clarity.app.call

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import net.robog.clarity.app.ClarityApp
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.app.di.AppGraph
import net.robog.clarity.shared.session.Session

/**
 * Keeps the morning call showing as a call while it is going on ([liveCall]), whatever happens to the
 * call screen: swiped away, or left to look something up. It runs in the foreground with the ongoing
 * `CallStyle` notification (the status-bar call chip, a tap back into the call, hang up), and stops
 * when the call ends, is snoozed, or another session replaces it.
 *
 * While it runs it also holds the feed connection and the notifications, so the agent's answers keep
 * arriving and, with the call screen not in front, show as notifications to reply to, even with the
 * push service off. The microphone is not part of it: it listens only while the call screen is in front.
 *
 * Its type is `phoneCall`: it carries on a call the person is in (the app declares `MANAGE_OWN_CALLS`,
 * which Android 14+ asks of that type). A `CallStyle` notification has to come from a foreground
 * service (or carry a full-screen intent) for Android to show it as a call at all.
 */
class OngoingCallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running.set(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = (application as ClarityApp).graph
        // Must happen within seconds of being started.
        if (!enterForeground(graph)) return START_NOT_STICKY
        if (job == null) job = scope.launch { follow(graph) }
        // Brought back after the system stopped it: it asks the server whether the call still runs.
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        scope.cancel()
        super.onDestroy()
    }

    private fun enterForeground(graph: AppGraph): Boolean = try {
        ServiceCompat.startForeground(
            this, AndroidCallNotifier.ID_ONGOING, graph.androidCallNotifier.ongoingNotification(graph.callTracker.live()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
        )
        true
    } catch (e: Exception) {
        // Not allowed right now (a start from the background); the next time the app is open it tries again.
        Log.w(TAG, "could not show the ongoing call", e)
        stopSelf()
        false
    }

    private suspend fun follow(graph: AppGraph) {
        val tracker = graph.callTracker
        coroutineScope {
            launch { graph.connection.holdFeed() }
            launch { graph.connection.holdNotifications() }
            launch { tracker.follow(graph.feedSync.signals) }
            if (tracker.live() == null) tracker.refresh()
            val manager = getSystemService(NotificationManager::class.java)
            ongoingCalls(tracker, graph.appClock)
                .takeWhile { it != null }
                .collect { manager.notify(AndroidCallNotifier.ID_ONGOING, graph.androidCallNotifier.ongoingNotification(it)) }
            // The call is over (or snoozed, or from another day): the notification goes with the service.
            ServiceCompat.stopForeground(this@OngoingCallService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            cancel() // and the connection it held
        }
    }

    companion object {
        private const val TAG = "OngoingCall"
        private val running = AtomicBoolean(false)

        fun isRunning(): Boolean = running.get()

        fun intent(context: Context): Intent = Intent(context, OngoingCallService::class.java)
    }
}

/** How often the ongoing call checks the clock, so a call from yesterday does not linger. */
val ONGOING_RECHECK: Duration = 15.minutes

/** The call going on, as it changes: what the notification shows; null once there is none. */
fun ongoingCalls(tracker: CallTracker, clock: AppClock, every: Duration = ONGOING_RECHECK): Flow<Session?> =
    combine(tracker.session, ticks(every)) { session, _ -> liveCall(session, clock.now(), clock.zone()) }
        .distinctUntilChanged { a, b -> a?.id == b?.id && a?.stage == b?.stage && a?.startedAt == b?.startedAt }

private fun ticks(every: Duration): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(every)
    }
}

/**
 * Starts [OngoingCallService] whenever a call is going on, app-wide: the call screen answering or
 * rejoining a call, the app opening during one. The service stops itself when the call is over.
 */
class OngoingCallStarter(private val context: Context, private val tracker: CallTracker, private val clock: AppClock) {
    /** Runs for the life of the process. */
    suspend fun run() {
        tracker.session
            .map { liveCall(it, clock.now(), clock.zone())?.id }
            .distinctUntilChanged()
            .collect { if (it != null) start() }
    }

    /** Asks the server about the call and starts the service if one is going on; call it with the app in front. */
    suspend fun ensure() {
        tracker.refresh()
        if (tracker.live() != null) start()
    }

    private fun start() {
        if (OngoingCallService.isRunning()) return
        try {
            ContextCompat.startForegroundService(context, OngoingCallService.intent(context))
        } catch (e: Exception) {
            // Refused from the background (Android 12+): the call screen or the app opening starts it.
            Log.w("OngoingCall", "could not start the ongoing call", e)
        }
    }
}
