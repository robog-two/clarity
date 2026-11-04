package net.robog.clarity.app.ui.settings

import net.robog.clarity.app.ui.theme.Space
import net.robog.clarity.app.ui.components.CardGroup
import net.robog.clarity.app.ui.components.CardGroupScope
import net.robog.clarity.app.ui.components.SegmentCard
import net.robog.clarity.app.ui.components.ClarityScreen
import net.robog.clarity.app.ui.components.PhaseSwap
import net.robog.clarity.app.ui.components.ScreenLevel
import net.robog.clarity.app.ui.components.springSwap
import androidx.compose.ui.graphics.Shape
import net.robog.clarity.app.ui.components.groupShape

import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.app.ui.components.ScreenGutter
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.Extension
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
    /** Settings → MCP servers. */
    val onOpenMcp: () -> Unit = {},
    val onAllowNotifications: () -> Unit = {},
    val onAllowExactAlarms: () -> Unit = {},
    val onAllowFullScreen: () -> Unit = {},
    val onAllowSms: () -> Unit = {},
    /** Texts are readable but the server refuses them: grant them there. */
    val onAllowSmsOnServer: () -> Unit = {},
    val onAllowDnd: () -> Unit = {},
    val onRedoOnboarding: () -> Unit = {},
    val onForget: () -> Unit = {},
)

@Composable
fun SettingsTab(
    onOpenConstitution: () -> Unit,
    onOpenConnector: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenMcp: () -> Unit = {},
    viewModel: SettingsViewModel = metroViewModel(),
) {
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
    val sms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            viewModel.onSmsGranted()
        } else {
            viewModel.refreshDevice()
            // Denied for good ("don't ask again"): the system no longer shows the dialog, so open the app's page.
            val activity = context as? android.app.Activity
            if (activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS)) {
                context.openSystem(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, withPackage = true)
            }
        }
    }
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
            onOpenMcp = onOpenMcp,
            onAllowNotifications = {
                if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.openSystem(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS) { putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName) }
            },
            onAllowExactAlarms = { context.openSystem(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, withPackage = true) },
            onAllowFullScreen = {
                if (Build.VERSION.SDK_INT >= 34) context.openSystem(AndroidSettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, withPackage = true)
            },
            onAllowSms = { sms.launch(Manifest.permission.READ_SMS) },
            onAllowSmsOnServer = viewModel::onSmsGranted,
            onAllowDnd = { context.openSystem(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
            onRedoOnboarding = viewModel::redoOnboarding,
            onForget = viewModel::forgetInstance,
        ),
        modifier = modifier,
        vault = { VaultSection(showTitle = false) },
        models = { ModelsSection(showTitle = false) },
        voiceModels = { VoiceModelsSection() },
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
    /** The on-phone speech models, shown on the Voice page. */
    voiceModels: @Composable () -> Unit = {},
    /** The page to open on; previews use it to show a sub-page. */
    startPage: SettingsPage = SettingsPage.Home,
) {
    var page by rememberSaveable { mutableStateOf(startPage) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf<String?>(null) }
    val settings = state.settings

    BackHandler(enabled = page != SettingsPage.Home) { page = SettingsPage.Home }

    PhaseSwap(page, modifier.fillMaxSize(), label = "settings page") { shown ->
        if (shown == SettingsPage.Home) {
            SettingsHome(state, open = { page = it }, onOpenConstitution = actions.onOpenConstitution, onOpenMcp = actions.onOpenMcp)
        } else {
            SettingsSubPage(shown.title, shown.drawing, onBack = { page = SettingsPage.Home }) {
                when (shown) {
                    SettingsPage.Home -> Unit
                    SettingsPage.MorningCall -> settings?.let { MorningCallSettings(state, it, actions) { pickingTime = TIME_CALL } } ?: LoadingSpinner()
                    SettingsPage.Voice -> settings?.let { VoiceSettings(it, actions, voiceModels) } ?: LoadingSpinner()
                    SettingsPage.HeldActions -> settings?.let { HeldActionsSettings(it, actions) { pickingTime = TIME_EVENING } } ?: LoadingSpinner()
                    SettingsPage.Push -> PushSettings(state, actions)
                    SettingsPage.Vault -> vault()
                    SettingsPage.Sources -> SourcesSettings(state, actions)
                    SettingsPage.Models -> models()
                    SettingsPage.Server -> ServerSettings(state, actions) { confirming = true }
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
            text = { Text("Removes this phone’s key and saved feed. Setup starts over.") },
            confirmButton = { TextButton(onClick = { confirming = false; actions.onForget() }) { Text("Forget", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

// --- home ---

@Composable
private fun SettingsHome(state: SettingsUiState, open: (SettingsPage) -> Unit, onOpenConstitution: () -> Unit, onOpenMcp: () -> Unit) {
    val settings = state.settings
    ClarityScreen(
        title = "Settings",
        level = ScreenLevel.Top,
        art = { Drawing(R.drawable.settings_coffee, Modifier.size(88.dp), tint = MaterialTheme.colorScheme.primary) },
    ) {
        // One state at a time: a problem outranks the local-only note, and either outranks the spinner.
        when {
            state.message != null -> ErrorBox(state.message)
            state.localOnly -> Hint("Server can’t save settings. Changes stay on this phone.")
            settings == null -> LoadingSpinner()
        }

        SettingsGroup("Your day") {
            val call = settings?.morningCall
            val callWarning = when {
                !state.notificationsOn -> "Notifications off. Calls can’t reach you."
                state.needsExactAlarms -> "Exact timing off. Calls may be late."
                !state.fullScreenOk && call?.mode == MorningCall.Mode.CALL -> "Call can’t show over the lock screen"
                else -> null
            }
            groupRow(
                Icons.Outlined.Alarm, "Morning call",
                callWarning ?: call?.let(::callTimeSummary),
                enabled = settings != null, warning = callWarning != null,
            ) { open(SettingsPage.MorningCall) }
            groupRow(
                Icons.Outlined.Mic, "Voice",
                settings?.let { if (it.micAlwaysOn) "Mic on during calls" else "Push to talk" },
                enabled = settings != null,
            ) { open(SettingsPage.Voice) }
            groupRow(
                Icons.Outlined.PauseCircleOutline, "Held actions",
                settings?.let { if (it.batchLowUrgency) "Low-urgency items wait until ${formatTime(it.eveningSummaryAt)}" else "Shown right away" },
                enabled = settings != null,
            ) { open(SettingsPage.HeldActions) }
        }

        SettingsGroup("Behavior") {
            groupRow(Icons.Outlined.Gavel, "Constitution", state.constitution?.let(::sentenceCase) ?: "Not written yet", onClick = onOpenConstitution)
            groupRow(Icons.Outlined.Memory, "Models", "Model for each job") { open(SettingsPage.Models) }
        }

        val mcpTitle = stringResource(R.string.mcp_title)
        val mcpSummary = stringResource(if (state.mcpRunning == false) R.string.mcp_row_not_running else R.string.mcp_row_summary)
        SettingsGroup("Connections") {
            val failing = state.serverSources.count { it.sync.contains("fail", ignoreCase = true) }
            groupRow(
                Icons.Outlined.Hub, "Sources",
                if (failing > 0) "$failing ${if (failing == 1) "source isn’t" else "sources aren’t"} syncing" else sourcesSummary(state),
                warning = failing > 0,
            ) { open(SettingsPage.Sources) }
            groupRow(
                Icons.Outlined.Extension, mcpTitle, mcpSummary,
                onClick = onOpenMcp,
            )
            val batteryWarning = state.pushOn && !state.batteryUnrestricted
            groupRow(
                Icons.Outlined.NotificationsActive, "Stay connected",
                when {
                    !state.pushOn -> "Off. Checks every 15 minutes."
                    batteryWarning -> "On. Battery limits may delay alerts."
                    else -> "On. Urgent items arrive at once."
                },
                warning = batteryWarning,
            ) { open(SettingsPage.Push) }
            groupRow(Icons.Outlined.Lock, "Vault", "Logins and cards, locked on this phone") { open(SettingsPage.Vault) }
        }

        SettingsGroup("This phone") {
            groupRow(Icons.Outlined.Dns, "Server", state.instanceUrl.removePrefix("https://").ifEmpty { "Not connected" }) { open(SettingsPage.Server) }
        }
    }
}

/** "3 on your server. Texts synced." */
private fun sourcesSummary(state: SettingsUiState): String {
    val server = when (state.serverSources.size) {
        0 -> "None on your server yet"
        1 -> "1 on your server"
        else -> "${state.serverSources.size} on your server"
    }
    val textsOk = state.smsGranted && state.sms.ok
    return if (textsOk) "$server. Texts synced." else server
}

/** A titled group of cards, the same grouped style as Today's task list. */
@Composable
private fun SettingsGroup(title: String, modifier: Modifier = Modifier, build: CardGroupScope.() -> Unit) {
    // The screen's gap is Medium; a group sits Large (Medium plus this) from the one above.
    Column(modifier.padding(top = Space.Medium), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
        Text(title, Modifier.padding(start = Space.Medium).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        CardGroup(build = build)
    }
}

/** One segment of a group: an icon, a name and a one-line summary (none while loading). Opens a page. */
private fun CardGroupScope.groupRow(
    icon: ImageVector,
    title: String,
    summary: String?,
    enabled: Boolean = true,
    warning: Boolean = false,
    onClick: () -> Unit,
) = segment { shape ->
    SegmentCard(
        shape,
        Modifier.semantics { if (warning) stateDescription = "Needs attention" },
        onClick = onClick, enabled = enabled, minHeight = 56.dp,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.Medium), verticalAlignment = Alignment.CenterVertically) {
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
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal))
                if (summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// --- the pages ---

@Composable
private fun SettingsSubPage(title: String, @DrawableRes drawing: Int?, onBack: () -> Unit, content: @Composable () -> Unit) {
    ClarityScreen(
        title = title,
        backLabel = "settings",
        onBack = onBack,
        art = drawing?.let { res -> { Drawing(res, Modifier.size(88.dp), tint = MaterialTheme.colorScheme.secondary) } },
    ) {
        content()
    }
}

@Composable
private fun MorningCallSettings(state: SettingsUiState, settings: Settings, actions: SettingsActions, onPickTime: () -> Unit) {
    val calling = settings.morningCall.mode == MorningCall.Mode.CALL
    CardGroup {
        segment { SelectRow("time", callTimeSummary(settings.morningCall), "change", onPickTime, shape = it) }
        segment {
            ToggleRow(
                "Heads-up 10 min before", "A notification, then the call",
                settings.morningCall.headsUpMinutes != null, { actions.onToggleHeadsUp() }, shape = it,
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
        Kicker("days", Modifier.padding(start = Space.Medium), heading = true)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.Small), modifier = Modifier.fillMaxWidth()) {
            DayOrder.forEach { day ->
                PillChoice(dayInitial(day), day in settings.morningCall.days, { actions.onToggleDay(day) }, description = dayName(day))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
        Kicker("contact method", Modifier.padding(start = Space.Medium), heading = true)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(calling, { if (!calling) actions.onToggleMode() }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Call") }
            SegmentedButton(!calling, { if (calling) actions.onToggleMode() }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Notify") }
        }
    }
    if (!state.notificationsOn || state.needsExactAlarms || (!state.fullScreenOk && calling)) {
        CardGroup {
            if (!state.notificationsOn) segment { SelectRow("notifications", "off. Calls can’t reach you", "allow", actions.onAllowNotifications, shape = it) }
            if (state.needsExactAlarms) segment { SelectRow("exact timing", "calls may ring late", "allow", actions.onAllowExactAlarms, shape = it) }
            if (!state.fullScreenOk && calling) {
                segment { SelectRow("show the call over the lock screen", "off. Calls show as a banner", "allow", actions.onAllowFullScreen, shape = it) }
            }
        }
    }
}

@Composable
private fun VoiceSettings(settings: Settings, actions: SettingsActions, voiceModels: @Composable () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.Medium)) {
        ToggleRow(
            "Mic on during calls", if (settings.micAlwaysOn) "Always listening" else "Push to talk",
            settings.micAlwaysOn, { actions.onToggleMic() },
        )
        Hint("Replies appear as text.", Modifier.padding(start = Space.Medium))
    }
    voiceModels()
}

@Composable
private fun HeldActionsSettings(settings: Settings, actions: SettingsActions, onPickTime: () -> Unit) {
    CardGroup {
        segment {
            ToggleRow(
                "Hold low-urgency items until evening", if (settings.batchLowUrgency) "Reviewed together in the evening" else "Shown right away",
                settings.batchLowUrgency, { actions.onToggleBatch() }, shape = it,
            )
        }
        if (settings.batchLowUrgency) {
            segment { SelectRow("evening summary", "when the batch arrives", formatTime(settings.eveningSummaryAt), onPickTime, shape = it) }
        }
        segment {
            ToggleRow(
                "Notify about urgent holds", "A notification as it happens",
                settings.pushUrgent, { actions.onTogglePush() }, shape = it,
            )
        }
    }
}

@Composable
private fun PushSettings(state: SettingsUiState, actions: SettingsActions) {
    CardGroup {
        segment {
            ToggleRow(
                "Stay connected", "Keeps a notification showing so urgent items arrive at once",
                state.pushOn, { actions.onTogglePushService() }, shape = it,
            )
        }
        if (state.pushOn && !state.batteryUnrestricted) {
            segment { SelectRow("battery", "without it your phone may delay alerts", "allow", actions.onAllowBattery, shape = it) }
        }
    }
    if (state.pushOn) {
        if (state.batteryUnrestricted) Hint("Battery use unrestricted. Alerts arrive at once.", Modifier.padding(start = Space.Medium))
    } else {
        Hint("Updates when you open the app and every 15 minutes.", Modifier.padding(start = Space.Medium))
    }
}

@Composable
private fun SourcesSettings(state: SettingsUiState, actions: SettingsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
        SectionTitle("On your server", Modifier.padding(start = Space.Medium).semantics { heading() })
        if (state.serverSources.isEmpty()) Hint("No sources connected yet.", Modifier.padding(start = Space.Medium))
        CardGroup(state.serverSources) { row, shape, _ -> SourceItem(row, shape) { actions.onOpenConnector(row.id) } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
        SectionTitle("On this phone", Modifier.padding(start = Space.Medium).semantics { heading() })
        CardGroup {
            segment {
                SelectRow(
                    "messages",
                    state.sms.sub,
                    state.sms.trailing,
                    {
                        when {
                            !state.smsGranted -> actions.onAllowSms()
                            state.sms.needsAllow -> actions.onAllowSmsOnServer()
                        }
                    },
                    shape = it,
                )
            }
            segment {
                SelectRow(
                    "do not disturb",
                    if (state.dndGranted) "Can mute your phone on request" else "allow to mute your phone",
                    if (state.dndGranted) "on" else "allow",
                    { if (!state.dndGranted) actions.onAllowDnd() },
                    shape = it,
                )
            }
        }
    }
}

@Composable
private fun ServerSettings(state: SettingsUiState, actions: SettingsActions, onForget: () -> Unit) {
    SegmentCard(groupShape(0, 1)) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
            Kicker("connected to", heading = true)
            Text(state.instanceUrl.ifEmpty { "Not connected" }, style = MaterialTheme.typography.bodyLarge)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.Medium)) {
        SecondaryButton("set up again", actions.onRedoOnboarding, Modifier.fillMaxWidth())
        OutlinedButton(
            onClick = onForget,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        ) { Text("Forget this server", style = MaterialTheme.typography.labelLarge) }
    }
}

private const val TIME_CALL = "call"
private const val TIME_EVENING = "evening"

@Composable
private fun SourceItem(row: SourceRow, shape: Shape, onClick: () -> Unit) {
    val failed = row.sync.contains("fail", ignoreCase = true)
    SelectRow(
        row.title, row.sub, row.sync, onClick, shape = shape,
        statusColor = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        stateDescription = if (failed) "Needs attention" else null,
    )
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
    settings = Settings(), constitution = "Version 3, 14 lines, amended yesterday",
    serverSources = listOf(
        SourceRow("mail", "2 accounts", "4 min ago", "mail"),
        SourceRow("calendar and contacts", "Password rejected", "Failed", "dav"),
        SourceRow("files", "", "never", "files"),
    ),
    smsGranted = true, sms = SmsRowText("1,204 texts synced with your server", "just now", ok = true), notificationsOn = false, needsExactAlarms = true, batteryUnrestricted = false,
)

private val previewVault = VaultUiState(
    VaultKeyStatus.READY,
    listOf(
        CredentialRow(CredentialId("c1"), "chase_checking", "login", "secure.chase.com", "alex.morgan@work-example.com"),
        CredentialRow(CredentialId("c2"), "visa_personal", "card", "", "Visa ending 4242, expires 03"),
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
    previewState.copy(message = "Server unreachable. Changes save when you’re back online.", localOnly = true),
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
