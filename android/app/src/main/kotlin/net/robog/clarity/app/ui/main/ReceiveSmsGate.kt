package net.robog.clarity.app.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import net.robog.clarity.app.connector.receivePermissionsToAsk

private const val PREFS = "receive_sms_gate"
private const val ASKED_VERSION = "asked_version"

/**
 * Installs from before texts arrived live have READ_SMS but not RECEIVE_SMS / RECEIVE_MMS. They are
 * in the same permission group, so asking for just those two is granted without a dialog. Asked at
 * most once per app version, and nothing is shown.
 */
@Composable
fun ReceiveSmsGate() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        val version = appVersion(context)
        val toAsk = receivePermissionsToAsk(
            granted = { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED },
            askedVersion = askedVersion(context),
            currentVersion = version,
        )
        if (toAsk.isNotEmpty()) {
            rememberAsked(context, version)
            launcher.launch(toAsk.toTypedArray())
        }
    }
}

private fun appVersion(context: Context): Long = try {
    PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
} catch (_: Exception) {
    0L
}

private fun askedVersion(context: Context): Long? = try {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).takeIf { it.contains(ASKED_VERSION) }?.getLong(ASKED_VERSION, 0L)
} catch (_: Exception) {
    null
}

private fun rememberAsked(context: Context, version: Long) {
    try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(ASKED_VERSION, version).apply()
    } catch (_: Exception) {
    }
}
