package net.robog.clarity.app.call

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlin.time.Instant

/** [CallAlarms] over `AlarmManager`: exact when the permission allows, otherwise inexact but still while idle. */
class AndroidCallAlarms(private val context: Context) : CallAlarms {
    private val manager = context.getSystemService(AlarmManager::class.java)

    override fun canScheduleExact(): Boolean = manager.canScheduleExactAlarms()

    override fun set(slot: AlarmSlot, at: Instant) {
        val pending = pendingIntent(slot)
        when (alarmKind(canScheduleExact())) {
            AlarmKind.Exact -> manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilliseconds(), pending)
            AlarmKind.Inexact -> manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilliseconds(), pending)
        }
    }

    override fun cancel(slot: AlarmSlot) {
        manager.cancel(pendingIntent(slot))
    }

    private fun pendingIntent(slot: AlarmSlot): PendingIntent = PendingIntent.getBroadcast(
        context,
        slot.requestCode,
        Intent(context, CallAlarmReceiver::class.java).setAction(CallAlarmReceiver.actionFor(slot)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
