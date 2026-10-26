package net.robog.clarity.app.push

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.robog.clarity.R
import net.robog.clarity.app.ClarityApp
import net.robog.clarity.app.MainActivity
import net.robog.clarity.app.data.SessionStore
import net.robog.clarity.app.device.DeviceCapabilities
import net.robog.clarity.app.di.AppGraph
import net.robog.clarity.app.nav.toUri

/**
 * Keeps the feed connection open while the person has push on, so urgent things arrive in seconds
 * without Google services. It owns nothing itself: it holds the shared feed connection and the
 * phone connector's action loop, turns arrivals into notifications, reconnects when the network
 * changes, and stops itself when push is turned off or the server is forgotten.
 *
 * Declared as a `remoteMessaging` foreground service: the app relays the person's agent's messages
 * to this phone over a long-lived connection, which is what that type is for.
 */
class FeedService : Service() {
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
        enterForeground(PushNotifications.connection(this, "Connecting…"))
        if (job == null) job = scope.launch { runPush(graph) }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        scope.cancel()
        super.onDestroy()
    }

    private fun enterForeground(notification: Notification) {
        try {
            ServiceCompat.startForeground(
                this, PushNotifications.CONNECTION_ID, notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0,
            )
        } catch (e: Exception) {
            // Not allowed to run in the foreground right now (a background start on a strict phone).
            Log.w(TAG, "could not enter the foreground", e)
            stopSelf()
        }
    }

    private suspend fun runPush(graph: AppGraph) {
        pushConditions(graph.sessionStore, graph.onboardingFlag, graph.pushPrefs).collectLatest { conditions ->
            if (!conditions.shouldRun) {
                stopSelf()
                return@collectLatest
            }
            coroutineScope {
                launch { graph.connection.holdFeed() }
                launch { graph.connection.holdActions() }
                launch { graph.pushListener.run() }
                val reconnector = NetworkReconnector({ graph.feedSync.state.value }, graph.feedSync::reconnectNow)
                launch { networkEvents(this@FeedService).collect(reconnector::onEvent) }
                launch { keepStatusCurrent(graph.feedSync.state, graph.sessionStore) }
            }
        }
    }

    private suspend fun keepStatusCurrent(state: Flow<net.robog.clarity.app.data.feed.SyncState>, sessions: SessionStore) {
        val manager = getSystemService(NotificationManager::class.java)
        combine(state, sessions.session) { s, session -> statusText(s, hostOf(session?.instanceUrl)) }.collect { text ->
            manager.notify(PushNotifications.CONNECTION_ID, PushNotifications.connection(this, text))
        }
    }

    companion object {
        private const val TAG = "FeedService"
        internal val running = AtomicBoolean(false)

        fun isRunning(): Boolean = running.get()

        fun intent(context: Context): Intent = Intent(context, FeedService::class.java)
    }
}

/** "clarity.example.com" for the line in the persistent notification. */
fun hostOf(instanceUrl: String?): String =
    instanceUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: "your server"

/** The default network, as events: a new network id when it changes, `Lost` when there is none. */
private fun networkEvents(context: Context): Flow<NetworkEvent> = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(NetworkEvent.Available(network.toString())) }
        override fun onLost(network: Network) { trySend(NetworkEvent.Lost) }
    }
    manager.registerDefaultNetworkCallback(callback)
    awaitClose { manager.unregisterNetworkCallback(callback) }
}

/** Notification building shared by the service and the notifier. */
object PushNotifications {
    const val CONNECTION_ID = 4101
    const val CHANNEL_CONNECTION = "push-connection"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A quiet note that Clarity is connected, so urgent things reach you right away"
                setShowBadge(false)
            },
        )
        PushChannel.entries.forEach { channel ->
            manager.createNotificationChannel(
                NotificationChannel(channel.id, channel.label, importanceOf(channel).toAndroid()).apply {
                    description = channel.description
                },
            )
        }
    }

    private fun ChannelImportance.toAndroid(): Int = when (this) {
        ChannelImportance.High -> NotificationManager.IMPORTANCE_HIGH
        ChannelImportance.Default -> NotificationManager.IMPORTANCE_DEFAULT
        ChannelImportance.Low -> NotificationManager.IMPORTANCE_LOW
    }

    fun connection(context: Context, text: String): Notification {
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Clarity")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
    }
}

/** Posts the notifications [decideNotification] asks for. Tapping one opens its deep link. */
@SuppressLint("MissingPermission") // the decision already required notifications to be enabled
class AndroidPushNotifier(private val context: Context) : PushNotifier {
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** The conversation shown in the message notification, oldest first: (text, from the person). */
    private val conversation = ArrayDeque<Pair<String, Boolean>>()
    private var lastMessage: NotificationPlan? = null
    private val me = Person.Builder().setName("You").build()
    private val agent = Person.Builder().setName("Clarity").setBot(true).build()

    override fun post(plan: NotificationPlan) {
        PushNotifications.ensureChannels(context)
        if (plan.style == PlanStyle.Message) {
            synchronized(conversation) {
                conversation.addLast(plan.text to false)
                lastMessage = plan
            }
            postConversation(alert = true)
            return
        }
        val open = openIntent(plan)
        val builder = NotificationCompat.Builder(context, plan.channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(plan.title)
            .setContentText(plan.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(plan.text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setCategory(if (plan.channel == PushChannel.Urgent) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_EMAIL)
        if (plan.channel == PushChannel.Urgent) builder.setPriority(NotificationCompat.PRIORITY_HIGH)
        manager.notify(plan.id, builder.build())
    }

    override fun replied(text: String, sent: Boolean) {
        synchronized(conversation) {
            conversation.addLast((if (sent) text else "Not sent: $text. Open Clarity to try again.") to true)
        }
        postConversation(alert = false)
    }

    override fun clearConversation() {
        synchronized(conversation) {
            conversation.clear()
            lastMessage = null
        }
        manager.cancel(MESSAGE_ID)
    }

    /** One chat-style notification with the recent messages and a reply box. */
    private fun postConversation(alert: Boolean) {
        val (plan, lines) = synchronized(conversation) {
            while (conversation.size > MAX_LINES) conversation.removeFirst()
            (lastMessage ?: return) to conversation.toList()
        }
        val style = NotificationCompat.MessagingStyle(me)
        lines.forEach { (text, mine) -> style.addMessage(text, System.currentTimeMillis(), if (mine) null else agent) }
        val replyIntent = PendingIntent.getBroadcast(
            context, MESSAGE_ID,
            Intent(context, NotificationReplyReceiver::class.java)
                .putExtra(NotificationReplyReceiver.EXTRA_SESSION, plan.sessionId?.value),
            // A reply box needs a mutable intent: the system adds the typed text to it.
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val reply = NotificationCompat.Action.Builder(R.drawable.ic_notification, "Reply", replyIntent)
            .addRemoteInput(RemoteInput.Builder(NotificationReplyReceiver.KEY_TEXT).setLabel("Reply to Clarity").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .build()
        val builder = NotificationCompat.Builder(context, plan.channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .addAction(reply)
            .setContentIntent(openIntent(plan))
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        manager.notify(MESSAGE_ID, builder.build())
    }

    private fun openIntent(plan: NotificationPlan): PendingIntent =
        PendingIntent.getActivity(
            context, plan.id,
            Intent(Intent.ACTION_VIEW, plan.link.toUri().toUri(), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private companion object {
        const val MAX_LINES = 8
    }
}

/** The reply box on the message notification: sends the text as the person's input. */
class NotificationReplyReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim()
        if (text.isNullOrEmpty()) return
        val session = intent.getStringExtra(EXTRA_SESSION)?.let { net.robog.clarity.shared.session.SessionId(it) }
        val graph = (context.applicationContext as ClarityApp).graph
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val result = graph.feedRepository.send(text, net.robog.clarity.shared.feed.InputSource.TYPED, session, null)
                graph.pushNotifier.replied(text, result is net.robog.clarity.app.data.ApiResult.Success)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val KEY_TEXT = "reply"
        const val EXTRA_SESSION = "session"
    }
}

/** Starts and stops [FeedService] to match [PushConditions]. */
class AndroidPushController(
    private val context: Context,
    private val sessions: SessionStore,
    private val flag: net.robog.clarity.app.data.OnboardingFlagStore,
    private val prefs: PushPrefs,
) : PushController {
    override val enabled = prefs.enabled
    override val batteryAsked = prefs.batteryAsked

    override suspend fun setEnabled(on: Boolean) {
        prefs.setEnabled(on)
        reconcile()
    }

    override suspend fun markBatteryAsked() = prefs.markBatteryAsked()

    override suspend fun reconcile() {
        val conditions = pushConditions(sessions, flag, prefs).first()
        when (serviceAction(conditions.shouldRun, FeedService.isRunning())) {
            ServiceAction.Start -> try {
                ContextCompat.startForegroundService(context, FeedService.intent(context))
            } catch (e: Exception) {
                // Background start refused (Android 12+): the next time the app opens it will try again.
                Log.w("Push", "could not start the push service", e)
            }
            ServiceAction.Stop -> context.stopService(FeedService.intent(context))
            ServiceAction.None -> Unit
        }
    }
}

/** What the phone allows for notifications, for [PushListener]. */
fun canPostNotifications(capabilities: DeviceCapabilities): Boolean = capabilities.notificationsEnabled()
