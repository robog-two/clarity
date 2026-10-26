package net.robog.clarity.app.device

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** What the phone currently allows the app to do; each is a runtime permission or special access. */
interface DeviceCapabilities {
    fun notificationsEnabled(): Boolean
    fun canUseFullScreenIntent(): Boolean
    fun canScheduleExactAlarms(): Boolean
    fun smsGranted(): Boolean
    /** "Do not disturb access" (notification policy access). */
    fun dndAccessGranted(): Boolean
    /** The system does not restrict the app's background work (battery optimization exemption). */
    fun batteryUnrestricted(): Boolean
}

class AndroidDeviceCapabilities(private val context: Context) : DeviceCapabilities {
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    override fun notificationsEnabled(): Boolean = notifications.areNotificationsEnabled()

    // Before Android 14 the full-screen permission is granted at install.
    override fun canUseFullScreenIntent(): Boolean =
        Build.VERSION.SDK_INT < 34 || notifications.canUseFullScreenIntent()

    override fun canScheduleExactAlarms(): Boolean =
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    override fun smsGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    override fun dndAccessGranted(): Boolean = notifications.isNotificationPolicyAccessGranted

    override fun batteryUnrestricted(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
}
