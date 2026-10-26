package net.robog.clarity.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.datetime.LocalTime
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.PillChoice
import net.robog.clarity.app.ui.components.ScreenTitle
import net.robog.clarity.app.ui.components.SecondaryButton
import net.robog.clarity.app.ui.components.SelectRow
import net.robog.clarity.app.ui.onboarding.DayOrder
import net.robog.clarity.app.ui.onboarding.dayInitial
import net.robog.clarity.app.ui.onboarding.dayName
import net.robog.clarity.app.ui.onboarding.formatTime
import net.robog.clarity.app.ui.today.SectionTitle
import net.robog.clarity.app.vault.VaultSection
import net.robog.clarity.shared.session.Settings.MorningCall

/** Callbacks of the settings screen, grouped so previews and tests can pass no-ops. */
data class SettingsActions(
    val onCallTime: (LocalTime) -> Unit = {},
    val onToggleDay: (Int) -> Unit = {},
    val onToggleHeadsUp: () -> Unit = {},
    val onToggleMode: () -> Unit = {},
    val onToggleMic: () -> Unit = {},
    val onToggleBatch: () -> Unit = {},
    val onTogglePush: () -> Unit = {},
    val onTogglePushService: () -> Unit = {},
    val onAllowBattery: () -> Unit = {},
    val onEveningAt: (LocalTime) -> Unit = {},
    val onOpenConstitution: () -> Unit = {},
    val onOpenConnector: (String) -> Unit = {},
    val onAllowNotifications: () -> Unit = {},
    val onAllowExactAlarms: () -> Unit = {},
    val onAllowFullScreen: () -> Unit = {},
    val onAllowSms: () -> Unit = {},
    val onAllowDnd: () -> Unit = {},
    val onRedoOnboarding: () -> Unit = {},
    val onForget: () -> Unit = {},
)

@Composable
fun SettingsTab(onOpenConstitution: () -> Unit, onOpenConnector: (String) -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Coming back from a system settings page: re-read what is allowed now.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshDevice()
                viewModel.refreshSources()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshDevice() }
    val sms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) viewModel.onSmsGranted() else viewModel.refreshDevice() }
    SettingsContent(
        state = state,
        actions = SettingsActions(
            onCallTime = viewModel::setCallTime,
            onToggleDay = viewModel::toggleDay,
            onToggleHeadsUp = viewModel::toggleHeadsUp,
            onToggleMode = viewModel::toggleMode,
            onToggleMic = viewModel::toggleMic,
            onToggleBatch = viewModel::toggleBatch,
            onTogglePush = viewModel::togglePushUrgent,
            onTogglePushService = viewModel::togglePushService,
            onAllowBattery = {
                viewModel.batteryAsked()
                context.openSystem(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPackage = true)
            },
            onEveningAt = viewModel::setEveningAt,
            onOpenConstitution = onOpenConstitution,
            onOpenConnector = onOpenConnector,
            onAllowNotifications = {
                if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.openSystem(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS) { putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName) }
            },
            onAllowExactAlarms = { context.openSystem(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, withPackage = true) },
            onAllowFullScreen = {
                if (Build.VERSION.SDK_INT >= 34) context.openSystem(AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, withPackage = true)
            },
            onAllowSms = { sms.launch(Manifest.permission.READ_SMS) },
            onAllowDnd = { context.openSystem(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
            onRedoOnboarding = viewModel::redoOnboarding,
            onForget = viewModel::forgetInstance,
        ),
        modifier = modifier,
        vault = { VaultSection() },
    )
}

private fun Context.openSystem(action: String, withPackage: Boolean = false, extras: Intent.() -> Unit = {}) {
    val intent = Intent(action).apply {
        if (withPackage) data = Uri.fromParts("package", packageName, null)
        extras()
    }
    runCatching { startActivity(intent) }
}

@Composable
fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    /** A section between the held-action settings and the sources (the credential vault). */
    vault: @Composable () -> Unit = {},
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf<String?>(null) }
    val settings = state.settings

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenTitle("Settings")
        state.message?.let { ErrorBox(it) }
        if (state.localOnly) Hint("Your server can’t store settings yet, so changes stay on this phone for now.")
        if (settings == null) {
            Hint("Loading…")
        } else {
            SectionTitle("Morning Call")
            Column {
                SelectRow("time", callTimeSummary(settings.morningCall), "change", { pickingTime = TIME_CALL })
                Kicker("days", Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    DayOrder.forEach { day ->
                        PillChoice(dayInitial(day), day in settings.morningCall.days, { actions.onToggleDay(day) }, description = dayName(day))
                    }
                }
                SelectRow(
                    "heads-up 10 min before", "a notification, then the call",
                    if (settings.morningCall.headsUpMinutes != null) "on" else "off", actions.onToggleHeadsUp,
                )
                SelectRow(
                    "how I reach you", "tap to switch",
                    if (settings.morningCall.mode == MorningCall.Mode.CALL) "call me" else "notify", actions.onToggleMode,
                )
                if (!state.notificationsOn) SelectRow("notifications", "off, so I can’t reach you", "allow", actions.onAllowNotifications)
                if (state.needsExactAlarms) SelectRow("exact timing", "the call may ring a few minutes late", "allow", actions.onAllowExactAlarms)
                if (!state.fullScreenOk && settings.morningCall.mode == MorningCall.Mode.CALL) {
                    SelectRow("show the call over the lock screen", "off, so it arrives as a notification", "allow", actions.onAllowFullScreen)
                }
            }

            SectionTitle("Voice")
            Column {
                SelectRow(
                    "mic during calls", "always on, so you can jump in",
                    if (settings.micAlwaysOn) "on" else "push to talk", actions.onToggleMic,
                )
                SelectRow("my replies", "I answer in text, on screen", "always text", {})
            }

            SectionTitle("Constitution")
            SelectRow("How I Want to Work", state.constitution ?: "not written yet", "→", actions.onOpenConstitution)

            SectionTitle("Held Actions")
            Column {
                SelectRow(
                    "low-urgency holds", "reviewed together in the evening",
                    if (settings.batchLowUrgency) "evening" else "right away", actions.onToggleBatch,
                )
                if (settings.batchLowUrgency) {
                    SelectRow("evening summary", "when the batch arrives", formatTime(settings.eveningSummaryAt), { pickingTime = TIME_EVENING })
                }
                SelectRow(
                    "urgent holds", "a notification as it happens",
                    if (settings.pushUrgent) "on" else "off", actions.onTogglePush,
                )
            }
        }

        SectionTitle("Push")
        Column {
            SelectRow(
                "stay connected", "keeps a quiet note in your notifications so urgent things arrive at once",
                if (state.pushOn) "on" else "off", actions.onTogglePushService,
            )
            if (state.pushOn) {
                if (state.batteryUnrestricted) {
                    SelectRow("battery", "unrestricted, so pushes arrive right away", "on", {})
                } else {
                    SelectRow("battery", "without it your phone may delay pushes", "allow", actions.onAllowBattery)
                }
            } else {
                Hint("With this off I catch up when you open the app and every 15 minutes or so.")
            }
        }

        vault()

        SectionTitle("Sources · on your server")
        Column {
            if (state.serverSources.isEmpty()) Hint("No sources connected yet.")
            state.serverSources.forEach { row -> SourceItem(row) { actions.onOpenConnector(row.id) } }
        }

        SectionTitle("Sources · on this phone")
        Column {
            SelectRow(
                "messages",
                if (state.smsGranted) "synced with your server" else "allow reading texts to sync them",
                if (state.smsGranted) state.smsLastSync else "allow",
                { if (!state.smsGranted) actions.onAllowSms() },
            )
            SelectRow(
                "do not disturb",
                if (state.dndGranted) "I can mute your phone when you ask" else "allow it so I can mute your phone",
                if (state.dndGranted) "on" else "allow",
                { if (!state.dndGranted) actions.onAllowDnd() },
            )
        }

        SecondaryButton("redo onboarding", actions.onRedoOnboarding, Modifier.fillMaxWidth().padding(top = 12.dp))
        SecondaryButton("forget this server", { confirming = true }, Modifier.fillMaxWidth())
        Kicker(state.instanceUrl.ifEmpty { "not connected" } + if (state.deviceId.isNotEmpty()) " · ${state.deviceId}" else "", Modifier.padding(top = 4.dp))
    }

    pickingTime?.let { which ->
        val initial = if (which == TIME_CALL) settings?.morningCall?.at else settings?.eveningSummaryAt
        TimeDialog(initial ?: LocalTime(9, 0), onDismiss = { pickingTime = null }) { time ->
            pickingTime = null
            if (which == TIME_CALL) actions.onCallTime(time) else actions.onEveningAt(time)
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Forget this server?") },
            text = { Text("This removes the device key and the saved feed from this phone. You will need to set up again.") },
            confirmButton = { TextButton(onClick = { confirming = false; actions.onForget() }) { Text("forget") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("cancel") } },
        )
    }
}

private const val TIME_CALL = "call"
private const val TIME_EVENING = "evening"

@Composable
private fun SourceItem(row: SourceRow, onClick: () -> Unit) {
    SelectRow(row.title, row.sub, row.sync, onClick, statusColor = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val picker = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = picker) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime(picker.hour, picker.minute)) }) { Text("ok") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("cancel") } },
    )
}
