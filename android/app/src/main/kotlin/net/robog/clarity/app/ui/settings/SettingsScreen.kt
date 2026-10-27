package net.robog.clarity.app.ui.settings

import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.app.ui.components.ScreenGutter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import net.robog.clarity.R
import androidx.annotation.DrawableRes
import net.robog.clarity.app.ui.components.Drawing
import net.robog.clarity.app.ui.components.BackLink
import net.robog.clarity.app.ui.components.sentenceCase
import android.Manifest
import android.content.Context
import android.content.res.Configuration
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import net.robog.clarity.app.ui.components.ToggleRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.tooling.preview.Preview
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
import net.robog.clarity.app.ui.components.LoadingSpinner
import net.robog.clarity.app.ui.components.PillChoice
import net.robog.clarity.app.ui.components.ScreenTitle
import net.robog.clarity.app.ui.components.SecondaryButton
import net.robog.clarity.app.ui.components.SelectRow
import net.robog.clarity.app.ui.onboarding.DayOrder
import net.robog.clarity.app.ui.onboarding.dayInitial
import net.robog.clarity.app.ui.onboarding.dayName
import net.robog.clarity.app.ui.onboarding.formatTime
import net.robog.clarity.app.ui.today.SectionTitle
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.app.vault.CredentialRow
import net.robog.clarity.app.vault.VaultKeyStatus
import net.robog.clarity.app.vault.VaultSection
import net.robog.clarity.app.vault.VaultSectionContent
import net.robog.clarity.app.vault.VaultUiState
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.session.Settings.MorningCall
import net.robog.clarity.shared.vault.CredentialId

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
        vault = { VaultSection(showTitle = false) },
        models = { ModelsSection(showTitle = false) },
    )
}

private fun Context.openSystem(action: String, withPackage: Boolean = false, extras: Intent.() -> Unit = {}) {
    val intent = Intent(action).apply {
        if (withPackage) data = Uri.fromParts("package", packageName, null)
        extras()
    }
    runCatching { startActivity(intent) }
}

/** The pages of Settings: a home page of groups, and one page for each group's controls. */
/** [drawing] is an optional hand-inked header for the page (its rows keep their Material icons on the home page). */
enum class SettingsPage(val title: String, @DrawableRes val drawing: Int? = null) {
    Home("Settings"),
    MorningCall("Morning call", R.drawable.drawing_bell),
    Voice("Voice"),
    HeldActions("Held actions"),
    Push("Stay connected"),
    Vault("Vault"),
    Sources("Sources"),
    Models("Models"),
    Server("Server"),
}

@Composable
fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    /** The credential vault, shown on its own page. */
    vault: @Composable () -> Unit = {},
    /** The model choices, shown on their own page. */
    models: @Composable () -> Unit = {},
    /** The page to open on; previews use it to show a sub-page. */
    startPage: SettingsPage = SettingsPage.Home,
) {
    var page by rememberSaveable { mutableStateOf(startPage) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf<String?>(null) }
    val settings = state.settings

    BackHandler(enabled = page != SettingsPage.Home) { page = SettingsPage.Home }

    AnimatedContent(
        targetState = page,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            val forward = targetState != SettingsPage.Home
            (fadeIn() + slideInHorizontally { w -> if (forward) w / 8 else -w / 8 }) togetherWith
                (fadeOut() + slideOutHorizontally { w -> if (forward) -w / 8 else w / 8 })
        },
        label = "settings page",
    ) { shown ->
        if (shown == SettingsPage.Home) {
            SettingsHome(state, open = { page = it }, onOpenConstitution = actions.onOpenConstitution)
        } else {
            SettingsSubPage(shown.title, shown.drawing, onBack = { page = SettingsPage.Home }) {
                when (shown) {
                    SettingsPage.Home -> Unit
                    SettingsPage.MorningCall -> settings?.let { MorningCallPage(state, it, actions) { pickingTime = TIME_CALL } } ?: LoadingSpinner()
                    SettingsPage.Voice -> settings?.let { VoicePage(it, actions) } ?: LoadingSpinner()
                    SettingsPage.HeldActions -> settings?.let { HeldActionsPage(it, actions) { pickingTime = TIME_EVENING } } ?: LoadingSpinner()
                    SettingsPage.Push -> PushPage(state, actions)
                    SettingsPage.Vault -> vault()
                    SettingsPage.Sources -> SourcesPage(state, actions)
                    SettingsPage.Models -> models()
                    SettingsPage.Server -> ServerPage(state, actions) { confirming = true }
                }
            }
        }
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
            confirmButton = { TextButton(onClick = { confirming = false; actions.onForget() }) { Text("Forget", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

// --- home ---

@Composable
private fun SettingsHome(state: SettingsUiState, open: (SettingsPage) -> Unit, onOpenConstitution: () -> Unit) {
    val settings = state.settings
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.settings_coffee), contentDescription = null, modifier = Modifier.size(144.dp))
            Text(
                "Settings",
                Modifier.padding(top = 20.dp),
                style = MaterialTheme.clarityText.heroTitle,
                textAlign = TextAlign.Center,
            )
        }
        // One state at a time: a problem outranks the local-only note, and either outranks the spinner.
        when {
            state.message != null -> ErrorBox(state.message)
            state.localOnly -> Hint("Your server can’t store settings yet, so changes stay on this phone for now.")
            settings == null -> LoadingSpinner()
        }

        SettingsGroup("Your day") {
            val call = settings?.morningCall
            val callWarning = when {
                !state.notificationsOn -> "Notifications are off, so I can’t reach you"
                state.needsExactAlarms -> "Exact timing is off, so the call may be late"
                !state.fullScreenOk && call?.mode == MorningCall.Mode.CALL -> "The call can’t show over the lock screen"
                else -> null
            }
            GroupRow(
                Icons.Outlined.Alarm, "Morning call",
                callWarning ?: call?.let(::callTimeSummary),
                enabled = settings != null, warning = callWarning != null,
            ) { open(SettingsPage.MorningCall) }
            GroupRow(
                Icons.Outlined.Mic, "Voice",
                settings?.let { if (it.micAlwaysOn) "Mic always on during calls" else "Push to talk" },
                enabled = settings != null,
            ) { open(SettingsPage.Voice) }
            GroupRow(
                Icons.Outlined.PauseCircleOutline, "Held actions",
                settings?.let { if (it.batchLowUrgency) "Low-urgency holds wait for ${formatTime(it.eveningSummaryAt)}" else "Shown right away" },
                enabled = settings != null,
            ) { open(SettingsPage.HeldActions) }
        }

        SettingsGroup("How I work") {
            GroupRow(Icons.Outlined.Gavel, "Constitution", state.constitution?.let(::sentenceCase) ?: "Not written yet", onClick = onOpenConstitution)
            GroupRow(Icons.Outlined.Memory, "Models", "Which model does each part of the work") { open(SettingsPage.Models) }
        }

        SettingsGroup("Connections") {
            val failing = state.serverSources.count { it.sync.contains("fail", ignoreCase = true) }
            GroupRow(
                Icons.Outlined.Hub, "Sources",
                if (failing > 0) "$failing ${if (failing == 1) "source isn’t" else "sources aren’t"} syncing" else sourcesSummary(state),
                warning = failing > 0,
            ) { open(SettingsPage.Sources) }
            val batteryWarning = state.pushOn && !state.batteryUnrestricted
            GroupRow(
                Icons.Outlined.NotificationsActive, "Stay connected",
                when {
                    !state.pushOn -> "Off, so I check in every 15 minutes or so"
                    batteryWarning -> "On, but your battery settings may delay pushes"
                    else -> "On, so urgent things arrive at once"
                },
                warning = batteryWarning,
            ) { open(SettingsPage.Push) }
            GroupRow(Icons.Outlined.Lock, "Vault", "Logins and cards, locked on this phone") { open(SettingsPage.Vault) }
        }

        SettingsGroup("This phone") {
            GroupRow(Icons.Outlined.Dns, "Server", state.instanceUrl.removePrefix("https://").ifEmpty { "Not connected" }) { open(SettingsPage.Server) }
        }
    }
}

/** "3 on your server · texts synced" */
private fun sourcesSummary(state: SettingsUiState): String {
    val server = when (state.serverSources.size) {
        0 -> "None on your server yet"
        1 -> "1 on your server"
        else -> "${state.serverSources.size} on your server"
    }
    return if (state.smsGranted) "$server · texts synced" else server
}

/** A titled group of rows on a raised surface. */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp,
            shadowElevation = 2.dp,
        ) {
            Column(Modifier.padding(vertical = 8.dp), content = content)
        }
    }
}

/** One row of a group: an icon, a name and a one-line summary (none while loading). Opens a page. */
@Composable
private fun GroupRow(
    icon: ImageVector,
    title: String,
    summary: String?,
    enabled: Boolean = true,
    warning: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box(
                Modifier.size(40.dp).background(
                    if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, contentDescription = null, modifier = Modifier.size(22.dp),
                    tint = if (warning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        },
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal)) },
        supportingContent = summary?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

// --- the pages ---

@Composable
private fun SettingsSubPage(title: String, @DrawableRes drawing: Int?, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BackLink("settings", onBack)
        drawing?.let { Drawing(it, Modifier.padding(top = 8.dp).size(112.dp), tint = MaterialTheme.colorScheme.secondary) }
        ScreenTitle(title, Modifier.padding(top = 8.dp, bottom = 16.dp))
        content()
    }
}

@Composable
private fun MorningCallPage(state: SettingsUiState, settings: Settings, actions: SettingsActions, onPickTime: () -> Unit) {
    Column {
        SelectRow("time", callTimeSummary(settings.morningCall), "change", onPickTime)
        Kicker("days", Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            DayOrder.forEach { day ->
                PillChoice(dayInitial(day), day in settings.morningCall.days, { actions.onToggleDay(day) }, description = dayName(day))
            }
        }
        ToggleRow(
            "Heads-up 10 min before", "A notification, then the call",
            settings.morningCall.headsUpMinutes != null, { actions.onToggleHeadsUp() },
        )
        Kicker("how I reach you", Modifier.padding(top = 12.dp))
        val calling = settings.morningCall.mode == MorningCall.Mode.CALL
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            SegmentedButton(calling, { if (!calling) actions.onToggleMode() }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Call me") }
            SegmentedButton(!calling, { if (calling) actions.onToggleMode() }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Notify me") }
        }
        if (!state.notificationsOn) SelectRow("notifications", "off, so I can’t reach you", "allow", actions.onAllowNotifications)
        if (state.needsExactAlarms) SelectRow("exact timing", "the call may ring a few minutes late", "allow", actions.onAllowExactAlarms)
        if (!state.fullScreenOk && calling) {
            SelectRow("show the call over the lock screen", "off, so it arrives as a notification", "allow", actions.onAllowFullScreen)
        }
    }
}

@Composable
private fun VoicePage(settings: Settings, actions: SettingsActions) {
    Column {
        ToggleRow(
            "Mic always on during calls", if (settings.micAlwaysOn) "So you can jump in" else "Push to talk",
            settings.micAlwaysOn, { actions.onToggleMic() },
        )
        Hint("I always answer in text, on screen.", Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun HeldActionsPage(settings: Settings, actions: SettingsActions, onPickTime: () -> Unit) {
    Column {
        ToggleRow(
            "Save low-urgency holds for the evening", if (settings.batchLowUrgency) "Reviewed together in the evening" else "Shown right away",
            settings.batchLowUrgency, { actions.onToggleBatch() },
        )
        if (settings.batchLowUrgency) {
            SelectRow("evening summary", "when the batch arrives", formatTime(settings.eveningSummaryAt), onPickTime)
        }
        ToggleRow(
            "Notify me about urgent holds", "A notification as it happens",
            settings.pushUrgent, { actions.onTogglePush() },
        )
    }
}

@Composable
private fun PushPage(state: SettingsUiState, actions: SettingsActions) {
    Column {
        ToggleRow(
            "Stay connected", "Keeps a quiet note in your notifications so urgent things arrive at once",
            state.pushOn, { actions.onTogglePushService() },
        )
        if (state.pushOn) {
            if (state.batteryUnrestricted) {
                Hint("Battery use is unrestricted, so pushes arrive right away.", Modifier.padding(top = 8.dp))
            } else {
                SelectRow("battery", "without it your phone may delay pushes", "allow", actions.onAllowBattery)
            }
        } else {
            Hint("With this off I catch up when you open the app and every 15 minutes or so.", Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun SourcesPage(state: SettingsUiState, actions: SettingsActions) {
    SectionTitle("On your server")
    Column {
        if (state.serverSources.isEmpty()) Hint("No sources connected yet.")
        state.serverSources.forEach { row -> SourceItem(row) { actions.onOpenConnector(row.id) } }
    }
    SectionTitle("On this phone", Modifier.padding(top = 16.dp))
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
}

@Composable
private fun ServerPage(state: SettingsUiState, actions: SettingsActions, onForget: () -> Unit) {
    Kicker("connected to")
    Text(state.instanceUrl.ifEmpty { "Not connected" }, style = MaterialTheme.typography.bodyLarge)
    SecondaryButton("redo onboarding", actions.onRedoOnboarding, Modifier.fillMaxWidth().padding(top = 24.dp))
    OutlinedButton(
        onClick = onForget,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    ) { Text("Forget this server", style = MaterialTheme.typography.labelLarge) }
}

private const val TIME_CALL = "call"
private const val TIME_EVENING = "evening"

@Composable
private fun SourceItem(row: SourceRow, onClick: () -> Unit) {
    val failed = row.sync.contains("fail", ignoreCase = true)
    SelectRow(row.title, row.sub, row.sync, onClick, statusColor = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val picker = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = picker) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime(picker.hour, picker.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- previews ---

private val previewState = SettingsUiState(
    instanceUrl = "https://clarity.home.example.org", deviceId = "dev_8f3k2m9q", loading = false,
    settings = Settings(), constitution = "v3 · 14 lines · amended yesterday",
    serverSources = listOf(
        SourceRow("mail", "imap · 2 accounts", "4 min ago", "mail"),
        SourceRow("calendar and contacts", "caldav · password rejected", "failed", "dav"),
        SourceRow("files", "webdav", "never", "files"),
    ),
    smsGranted = true, smsLastSync = "just now", notificationsOn = false, needsExactAlarms = true, batteryUnrestricted = false,
)

private val previewVault = VaultUiState(
    VaultKeyStatus.READY,
    listOf(
        CredentialRow(CredentialId("c1"), "chase_checking", "login", "secure.chase.com", "alex.morgan@work-example.com"),
        CredentialRow(CredentialId("c2"), "visa_personal", "card", "", "Visa ·· 4242 expires 03"),
    ),
    loading = false,
)

@Composable
private fun PreviewFrame(state: SettingsUiState, page: SettingsPage = SettingsPage.Home) = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
        SettingsContent(
            state, SettingsActions(),
            vault = { VaultSectionContent(previewVault, {}, {}, showTitle = false) },
            startPage = page,
        )
    }
}

@Preview(name = "light", widthDp = 390, heightDp = 1200)
@Preview(name = "dark", widthDp = 390, heightDp = 1200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsHomePreview() = PreviewFrame(previewState)

@Preview(name = "loading", widthDp = 390, heightDp = 1200)
@Composable
private fun SettingsLoadingPreview() = PreviewFrame(SettingsUiState())

@Preview(name = "offline", widthDp = 390, heightDp = 1200)
@Composable
private fun SettingsOfflinePreview() = PreviewFrame(
    previewState.copy(message = "Could not reach your server. Changes will be saved when you’re back online.", localOnly = true),
)

@Preview(name = "morning call", widthDp = 390, heightDp = 780)
@Composable
private fun SettingsMorningCallPreview() = PreviewFrame(previewState, SettingsPage.MorningCall)

@Preview(name = "sources", widthDp = 390, heightDp = 780)
@Composable
private fun SettingsSourcesPreview() = PreviewFrame(previewState, SettingsPage.Sources)

@Preview(name = "vault", widthDp = 390, heightDp = 780)
@Composable
private fun SettingsVaultPreview() = PreviewFrame(previewState, SettingsPage.Vault)

@Preview(name = "server", widthDp = 390, heightDp = 780)
@Composable
private fun SettingsServerPreview() = PreviewFrame(previewState, SettingsPage.Server)
