package net.robog.clarity.app.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.robog.clarity.app.call.CallPermission
import net.robog.clarity.app.call.CallSettingsCache
import net.robog.clarity.app.call.missingCallPermissions
import net.robog.clarity.app.call.nextCallPermission
import net.robog.clarity.app.device.DeviceCapabilities
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.shared.session.Settings

data class CallPermissionsState(
    val missing: List<CallPermission> = emptyList(),
    /** Sent to its prompt or settings page while the app was open: not asked again until next time. */
    val asked: Set<CallPermission> = emptySet(),
    /** "Not now": nothing more is asked until the app is opened again. */
    val declined: Boolean = false,
) {
    val showing: CallPermission? get() = nextCallPermission(missing, asked, declined)
}

/**
 * When the app opens, what the morning call needs and the phone does not allow yet, explained one at
 * a time before the system prompt or settings page. It lives as long as the signed-in screen, so a
 * "not now" holds until the app is opened again.
 */
@Inject
@ViewModelKey(CallPermissionsViewModel::class)
@ContributesIntoMap(AppScope::class)
class CallPermissionsViewModel(
    private val cache: CallSettingsCache,
    private val device: DeviceCapabilities,
) : ViewModel() {
    /** Where the settings cache and the system are read; tests run it in place. */
    internal var io: CoroutineDispatcher = Dispatchers.Default

    private val _state = MutableStateFlow(CallPermissionsState())
    val state: StateFlow<CallPermissionsState> = _state.asStateFlow()

    /** Re-reads what is missing; call it when the app comes to the front (and back from a settings page). */
    fun refresh() {
        viewModelScope.launch {
            val missing = withContext(io) {
                missingCallPermissions(
                    cache.load() ?: Settings(),
                    notificationsOn = device.notificationsEnabled(),
                    canUseFullScreenIntent = device.canUseFullScreenIntent(),
                    canScheduleExact = device.canScheduleExactAlarms(),
                )
            }
            _state.update { it.copy(missing = missing) }
        }
    }

    /** The person tapped "allow": the prompt or settings page opens, and this one is not asked again now. */
    fun asked(permission: CallPermission) = _state.update { it.copy(asked = it.asked + permission) }

    fun notNow() = _state.update { it.copy(declined = true) }
}

/** The explanation, then the system prompt or settings page, for each missing call permission. */
@Composable
fun CallPermissionGate(viewModel: CallPermissionsViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val context = LocalContext.current
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refresh() }
    val showing = state.showing ?: return
    CallPermissionDialog(
        showing,
        onAllow = {
            viewModel.asked(showing)
            when (showing) {
                CallPermission.Notifications ->
                    if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else context.openSettings(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS) {
                        putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                CallPermission.FullScreen ->
                    if (Build.VERSION.SDK_INT >= 34) context.openSettings(AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, withPackage = true)
                CallPermission.ExactAlarms ->
                    context.openSettings(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, withPackage = true)
            }
        },
        onNotNow = viewModel::notNow,
    )
}

/** Why the call needs [permission], and what tapping "allow" does. */
data class CallPermissionCopy(val icon: ImageVector, val title: String, val body: String, val allow: String)

fun callPermissionCopy(permission: CallPermission): CallPermissionCopy = when (permission) {
    CallPermission.Notifications -> CallPermissionCopy(
        Icons.Outlined.NotificationsActive,
        "Let me reach you",
        "Your morning call, its heads-up and anything urgent arrive as notifications. Without them I can’t ring you.",
        "Allow",
    )
    CallPermission.FullScreen -> CallPermissionCopy(
        Icons.Outlined.PhoneInTalk,
        "Ring like a phone call",
        "So the morning call fills the screen and rings, even when your phone is locked. " +
            "Android keeps this switch in settings: turn on “Allow full screen notifications” for Clarity. " +
            "Without it the call rings as a banner at the top.",
        "Open settings",
    )
    CallPermission.ExactAlarms -> CallPermissionCopy(
        Icons.Outlined.Alarm,
        "Ring right on time",
        "Without “Alarms & reminders”, Android may hold the call back by a few minutes. Turn it on for Clarity in settings.",
        "Open settings",
    )
}

@Composable
fun CallPermissionDialog(permission: CallPermission, onAllow: () -> Unit, onNotNow: () -> Unit) {
    val copy = callPermissionCopy(permission)
    AlertDialog(
        onDismissRequest = onNotNow,
        icon = { Icon(copy.icon, contentDescription = null) },
        title = { Text(copy.title) },
        text = { Hint(copy.body) },
        confirmButton = { TextButton(onClick = onAllow) { Text(copy.allow) } },
        dismissButton = { TextButton(onClick = onNotNow) { Text("Not now") } },
    )
}

private fun Context.openSettings(action: String, withPackage: Boolean = false, extras: Intent.() -> Unit = {}) {
    val intent = Intent(action).apply {
        if (withPackage) data = Uri.fromParts("package", packageName, null)
        extras()
    }
    runCatching { startActivity(intent) }
}
