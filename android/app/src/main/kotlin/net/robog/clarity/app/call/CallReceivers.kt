package net.robog.clarity.app.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.robog.clarity.app.ClarityApp
import net.robog.clarity.app.di.AppGraph

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

internal fun Context.graph(): AppGraph = (applicationContext as ClarityApp).graph

/** Runs [block] off the main thread and keeps the receiver alive until it is done. */
internal fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

/** The heads-up and call alarms. */
class CallAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slot = slotFor(intent.action) ?: return
        val graph = context.graph()
        async { graph.callAlarmHandler.onAlarm(slot) }
    }

    companion object {
        fun actionFor(slot: AlarmSlot): String = "net.robog.clarity.alarm.${slot.name}"
        fun slotFor(action: String?): AlarmSlot? = AlarmSlot.entries.firstOrNull { actionFor(it) == action }
    }
}

/** "Not now" on the incoming call, its ringing ending unanswered, and "hang up" on the ongoing call. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.graph()
        when (intent.action) {
            ACTION_DECLINE -> async { graph.callService.snooze() }
            ACTION_HANG_UP -> async { graph.callService.hangUp() }
            ACTION_RING_ENDED -> async { graph.callService.ringEnded() }
        }
    }

    companion object {
        const val ACTION_DECLINE = "net.robog.clarity.call.DECLINE"
        const val ACTION_HANG_UP = "net.robog.clarity.call.HANG_UP"
        const val ACTION_RING_ENDED = "net.robog.clarity.call.RING_ENDED"
    }
}

/** Boot, time zone and clock changes, and app updates all invalidate the alarms. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        val graph = context.graph()
        async {
            // Boot and app update may start the push service from the background; do it first.
            graph.pushController.reconcile()
            graph.callScheduler.refresh()
            graph.phoneConnector.schedule()
            graph.dndActions.endIfDue()
        }
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
