package net.robog.clarity.app.call

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.net.toUri
import net.robog.clarity.R
import net.robog.clarity.app.MainActivity
import net.robog.clarity.app.nav.DeepLink
import net.robog.clarity.app.nav.toUri
import net.robog.clarity.shared.session.Session

/**
 * Posts the heads-up and the incoming call, and builds the ongoing-call notification.
 *
 * The incoming call rings like a phone call: a `CallStyle` notification (answer / not now, category
 * CALL) on a channel with the phone's ringtone and a vibration, insistent (it keeps ringing) for
 * [RING_TIMEOUT], then it turns into a missed call. It always carries a full-screen intent: with the
 * permission, the call screen opens over the lock screen; without it (Android 14+), Android shows a
 * sticky ringing heads-up instead. A `CallStyle` with neither a full-screen intent nor a foreground
 * service is refused outright on Android 12+, so the intent is what makes it a call at all.
 */
@SuppressLint("MissingPermission") // POST_NOTIFICATIONS is checked through DeviceCapabilities before posting
class AndroidCallNotifier(private val context: Context) : CallNotifier {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val agent = Person.Builder().setName(AGENT_NAME).setImportant(true).build()

    init {
        CallChannels.RETIRED.forEach(manager::deleteNotificationChannel)
        CallChannels.ALL.forEach { manager.createNotificationChannel(it.toChannel()) }
    }

    override fun headsUp(title: String, body: String) {
        val notification = NotificationCompat.Builder(context, CallChannels.HEADS_UP)
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
        val style = incomingStyle(presentation) ?: return
        manager.cancel(ID_MISSED)
        if (!style.callStyle) {
            manager.notify(
                ID_INCOMING,
                NotificationCompat.Builder(context, style.channel)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Time for your morning meeting")
                    .setContentText("Open the app when you’re ready.")
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(mainScreen(DeepLink.Today, code = 2))
                    .build(),
            )
            return
        }
        val builder = NotificationCompat.Builder(context, style.channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentText("morning meeting · about 8 min")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(callScreen(answer = false, code = 3))
            // Unanswered (the timeout) or swiped away: a missed call. Answering and "not now" cancel it
            // from the app, which sends nothing.
            .setDeleteIntent(action(CallActionReceiver.ACTION_RING_ENDED, code = 10))
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(agent, decline(), callScreen(answer = true, code = 4)))
        style.timeout?.let { builder.setTimeoutAfter(it.inWholeMilliseconds) }
        if (style.fullScreenIntent) builder.setFullScreenIntent(callScreen(answer = false, code = 5), true)
        val notification = builder.build()
        if (style.insistent) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        try {
            manager.notify(ID_INCOMING, notification)
        } catch (e: IllegalArgumentException) {
            // Android refused the call style (it should not, with the full-screen intent): ring anyway,
            // as a notification with the same two buttons.
            Log.w(TAG, "call style refused, ringing without it", e)
            val plain = builder.setStyle(null)
                .addAction(0, "Not now", decline())
                .addAction(0, "Answer", callScreen(answer = true, code = 4))
                .build()
            if (style.insistent) plain.flags = plain.flags or Notification.FLAG_INSISTENT
            manager.notify(ID_INCOMING, plain)
        }
    }

    override fun missed() {
        val notification = NotificationCompat.Builder(context, CallChannels.HEADS_UP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Missed call from $AGENT_NAME")
            .setContentText("morning meeting · call back when you’re ready")
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(callScreen(answer = true, code = 11))
            .addAction(0, "Call back", callScreen(answer = true, code = 12))
            .addAction(0, "Ring in $SNOOZE_MINUTES min", decline())
            .build()
        manager.notify(ID_MISSED, notification)
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
        val builder = NotificationCompat.Builder(context, CallChannels.ONGOING)
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
    override fun cancelIncoming() {
        manager.cancel(ID_INCOMING)
        manager.cancel(ID_MISSED)
    }

    private fun decline(): PendingIntent = action(CallActionReceiver.ACTION_DECLINE, code = 8)

    private fun action(name: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code,
        Intent(context, CallActionReceiver::class.java).setAction(name),
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
        private const val TAG = "CallNotifier"
        private const val AGENT_NAME = "Personal Agent"
        private const val ID_HEADS_UP = 9101
        private const val ID_INCOMING = 9102
        private const val ID_MISSED = 9104
    }
}

private fun ChannelSpec.toChannel(): NotificationChannel {
    val importance = when (importance) {
        ChannelSpec.Importance.LOW -> NotificationManager.IMPORTANCE_LOW
        ChannelSpec.Importance.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT
        ChannelSpec.Importance.HIGH -> NotificationManager.IMPORTANCE_HIGH
    }
    val spec = this
    return NotificationChannel(id, name, importance).apply {
        spec.description?.let { description = it }
        if (spec.publicOnLockScreen) lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        if (spec.ringtone) {
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        spec.vibration?.let {
            enableVibration(true)
            vibrationPattern = it
        }
    }
}
