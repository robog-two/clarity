package net.robog.clarity.app.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalContext
import net.robog.clarity.app.ui.components.InfoBox
import net.robog.clarity.app.ui.components.SecondaryButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.datetime.LocalTime
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.PillChoice
import net.robog.clarity.app.ui.components.PrimaryButton
import net.robog.clarity.app.ui.components.ReadingText
import net.robog.clarity.app.ui.components.ToggleRow
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.session.Settings.MorningCall

// --- schedule ---

@Composable
fun ScheduleScreen(onFinished: () -> Unit, viewModel: ScheduleStepViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.consumeSaved()
            onFinished()
        }
    }
    ScheduleContent(
        state, viewModel::pickTime, viewModel::toggleDay, viewModel::toggleHeadsUp, viewModel::toggleMode, viewModel::finish,
    )
}

@Composable
fun ScheduleContent(
    state: ScheduleState,
    onPickTime: (LocalTime) -> Unit,
    onToggleDay: (Int) -> Unit,
    onToggleHeadsUp: () -> Unit,
    onToggleMode: () -> Unit,
    onFinish: () -> Unit,
) {
    StepScaffold(
        step = OnboardingStep.Schedule,
        title = "When should I call?",
        footer = { PrimaryButton("finish →", onFinish, Modifier.fillMaxWidth(), enabled = !state.loading && !state.saving) },
    ) {
        Hint("Eight minutes. You talk, I take notes, then I set things up.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.times.forEach { t ->
                PillChoice(formatTime(t), selected = t == state.call.at, onClick = { onPickTime(t) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DayOrder.forEach { d ->
                PillChoice(dayInitial(d), selected = d in state.call.days, onClick = { onToggleDay(d) }, description = dayName(d))
            }
        }
        Column(Modifier.padding(top = 8.dp)) {
            ToggleRow(
                title = "heads-up ${state.call.headsUpMinutes ?: 10} min before",
                caption = "a notification, then the call",
                checked = state.call.headsUpMinutes != null,
                onCheckedChange = { onToggleHeadsUp() },
            )
            ToggleRow(
                title = "how I reach you",
                caption = if (state.call.mode == MorningCall.Mode.CALL) "call me" else "notify",
                checked = state.call.mode == MorningCall.Mode.CALL,
                onCheckedChange = { onToggleMode() },
            )
        }
        state.error?.let { ErrorBox("Could not save the schedule.", "Check your connection and try again.") }
    }
}

// --- done ---

@Composable
fun DoneScreen(viewModel: DoneStepViewModel = metroViewModel()) {
    LaunchedEffect(Unit) { viewModel.load() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    DoneContent(
        state,
        viewModel::openApp,
        onAllowBattery = {
            viewModel.batteryAsked()
            runCatching {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null)),
                )
            }
        },
    )
}

@Composable
fun DoneContent(state: DoneState, onOpenApp: () -> Unit, onAllowBattery: () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 72.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            val connectors = if (state.connectors == 1) "1 connector" else "${state.connectors} connectors"
            Kicker("set up · $connectors" + (state.constitutionVersion?.let { " · constitution v$it" } ?: ""))
            Text(
                state.nextDay?.let { "Talk $it at ${formatTime(state.call.at)}." } ?: "You’re all set.",
                style = MaterialTheme.clarityText.hero.copy(fontSize = 36.sp),
            )
            ReadingText(doneLine(state.call), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.askBattery) {
                InfoBox {
                    Text("Let me stay awake in the background", style = MaterialTheme.typography.titleMedium)
                    Hint(
                        "To tell you about urgent things the moment they happen, I keep a quiet connection to your server open. " +
                            "Your phone may pause that to save battery. Allowing it costs very little. If you skip it, " +
                            "pushes still work but may be late. You can change this in settings.",
                    )
                    SecondaryButton("allow", onAllowBattery, Modifier.fillMaxWidth())
                }
            }
        }
        PrimaryButton("open the app", onOpenApp, Modifier.fillMaxWidth())
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780)
@Composable
private fun SchedulePreview() = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
        ScheduleContent(ScheduleState(loading = false, settings = Settings()), {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780)
@Composable
private fun DonePreview() = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
        DoneContent(DoneState(MorningCall(), connectors = 6, constitutionVersion = 1, nextDay = "tomorrow"), {})
    }
}
