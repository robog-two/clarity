package net.robog.clarity.app.call

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.net.toUri
import net.robog.clarity.R
import net.robog.clarity.app.MainActivity
import net.robog.clarity.app.nav.DeepLink
import net.robog.clarity.app.nav.toUri
import net.robog.clarity.shared.session.Session

/** Posts the heads-up and the incoming call (`CallStyle`, full-screen intent), and builds the ongoing-call notification. */
@SuppressLint("MissingPermission") // POST_NOTIFICATIONS is checked through DeviceCapabilities before posting
class AndroidCallNotifier(private val context: Context) : CallNotifier {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val agent = Person.Builder().setName(AGENT_NAME).setImportant(true).build()

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_HEADS_UP, "Heads-up before the call", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL, "Morning call", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "The call that starts your morning meeting"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Call in progress", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun headsUp(title: String, body: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_HEADS_UP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(callScreen(answer = false, code = 1))
            .build()
        manager.notify(ID_HEADS_UP, notification)
    }

    override fun incoming(presentation: IncomingPresentation, callTime: String) {
        when (presentation) {
            IncomingPresentation.None -> Unit
            IncomingPresentation.Plain -> manager.notify(
                ID_INCOMING,
                NotificationCompat.Builder(context, CHANNEL_CALL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Time for your morning meeting")
                    .setContentText("Open the app when you’re ready.")
                    .setAutoCancel(true)
                    .setContentIntent(mainScreen(DeepLink.Today, code = 2))
                    .build(),
            )
            IncomingPresentation.FullScreenCall, IncomingPresentation.HeadsUpCall -> {
                val builder = NotificationCompat.Builder(context, CHANNEL_CALL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentText("morning meeting · about 8 min")
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setOngoing(true)
                    .setContentIntent(callScreen(answer = false, code = 3))
                    .setStyle(NotificationCompat.CallStyle.forIncomingCall(agent, decline(), callScreen(answer = true, code = 4)))
                if (presentation == IncomingPresentation.FullScreenCall) {
                    builder.setFullScreenIntent(callScreen(answer = false, code = 5), true)
                }
                manager.notify(ID_INCOMING, builder.build())
            }
        }
    }

    /**
     * The ongoing call, for [OngoingCallService] to run in the foreground with: a `CallStyle` that
     * Android shows as a call (the status-bar chip, pinned at the top of the shade), with the call's
     * running time, a tap that goes back to the call and a hang-up button.
     */
    fun ongoingNotification(session: Session?): Notification {
        val hangUp = PendingIntent.getBroadcast(
            context, 6,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_HANG_UP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentText(ongoingCallText(session?.stage))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // Back to the call; "answer" also picks it up from the incoming or snoozed screen.
            .setContentIntent(callScreen(answer = true, code = 7, fresh = false))
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(agent, hangUp))
        session?.startedAt?.let { builder.setWhen(it.toEpochMilliseconds()).setShowWhen(true).setUsesChronometer(true) }
        return builder.build()
    }

    override fun cancelHeadsUp() = manager.cancel(ID_HEADS_UP)
    override fun cancelIncoming() = manager.cancel(ID_INCOMING)

    private fun decline(): PendingIntent = PendingIntent.getBroadcast(
        context, 8,
        Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_DECLINE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** [fresh] starts the call screen from scratch (a new call); otherwise it resumes the one on screen. */
    private fun callScreen(answer: Boolean, code: Int, fresh: Boolean = true): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, DeepLink.Call(answer).toUri().toUri(), context, CallActivity::class.java)
            .addFlags(
                if (fresh) Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                else Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun mainScreen(link: DeepLink, code: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, link.toUri().toUri(), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        /** The ongoing call notification, which [OngoingCallService] runs in the foreground with. */
        const val ID_ONGOING = 9103
        private const val AGENT_NAME = "Personal Agent"
        private const val CHANNEL_HEADS_UP = "heads_up"
        private const val CHANNEL_CALL = "morning_call"
        private const val CHANNEL_ONGOING = "call_in_progress"
        private const val ID_HEADS_UP = 9101
        private const val ID_INCOMING = 9102
    }
}
