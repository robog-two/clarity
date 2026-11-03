package net.robog.clarity.app.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.InfoBox
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.PrimaryButton
import net.robog.clarity.app.ui.components.SecondaryButton
import net.robog.clarity.app.ui.components.ToggleRow
import net.robog.clarity.app.ui.today.SectionTitle
import net.robog.clarity.app.voice.ModelStatus
import net.robog.clarity.app.voice.TierChoice
import net.robog.clarity.app.voice.VoiceCatalog
import net.robog.clarity.app.voice.VoiceLogEntry
import net.robog.clarity.app.voice.VoiceModels
import net.robog.clarity.app.voice.VoiceTier
import net.robog.clarity.app.voice.VoiceUiState
import net.robog.clarity.app.voice.formatBytes

@Inject
@ViewModelKey(VoiceModelsViewModel::class)
@ContributesIntoMap(AppScope::class)
class VoiceModelsViewModel(private val voice: VoiceModels) : ViewModel() {
    val state: StateFlow<VoiceUiState> = voice.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceUiState())
    val log: StateFlow<List<VoiceLogEntry>> = voice.log.entries

    val actions = VoiceModelActions(
        onClearLog = { voice.log.clear() },
        onDownload = { voice.download() },
        onCancel = { voice.cancelDownload() },
        onChoice = { voice.setChoice(it) },
        onUseBuiltIn = { voice.setUseBuiltIn(it) },
        onMobileData = { voice.setMobileData(it) },
        onDelete = { voice.deleteModels() },
    )
}

/** Callbacks of the speech-model section, so previews can pass no-ops. */
data class VoiceModelActions(
    val onDownload: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onChoice: (TierChoice) -> Unit = {},
    val onUseBuiltIn: (Boolean) -> Unit = {},
    val onMobileData: (Boolean) -> Unit = {},
    val onDelete: () -> Unit = {},
    val onClearLog: () -> Unit = {},
)

@Composable
fun VoiceModelsSection(viewModel: VoiceModelsViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    VoiceModelsContent(state, viewModel.actions, log = log)
}

fun tierTitle(tier: VoiceTier): String = when (tier) {
    VoiceTier.Accurate -> "Parakeet 0.6B"
    VoiceTier.Light -> "Parakeet 110M"
}

/** Settings → Voice: the on-phone speech models. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VoiceModelsContent(
    state: VoiceUiState,
    actions: VoiceModelActions,
    modifier: Modifier = Modifier,
    log: List<VoiceLogEntry> = emptyList(),
    zone: ZoneId = ZoneId.systemDefault(),
    logOpen: Boolean = false,
) {
    Column(modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Speech recognition")
        InfoBox {
            when (val status = state.status) {
                ModelStatus.Ready -> {
                    Text(
                        if (state.usingOwn) "On this phone · ${tierTitle(state.tier)}" else "Models ready · not in use",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Hint(
                        when {
                            state.engineFailed -> "The models couldn’t start on this phone, so the phone’s own recognizer listens instead."
                            state.useBuiltIn -> "The phone’s own recognizer listens, as you chose below."
                            else -> "Live words as you talk, then a more accurate pass on each phrase. Your voice never leaves this phone."
                        },
                    )
                }
                is ModelStatus.NotDownloaded -> {
                    Text("Download speech models", style = MaterialTheme.typography.titleMedium)
                    Hint(
                        "More accurate speech to text that runs entirely on this phone: no chime, no restarts between " +
                            "sentences, and the audio never leaves the phone. One download of ${formatBytes(status.bytes)}" +
                            if (state.mobileData) "." else ", over Wi-Fi.",
                    )
                    Hint("Until then, the phone’s own recognizer listens.")
                    PrimaryButton("download ${formatBytes(status.bytes)}", actions.onDownload, Modifier.padding(top = 4.dp))
                }
                is ModelStatus.Downloading -> {
                    Text("Downloading speech models", style = MaterialTheme.typography.titleMedium)
                    LinearWavyProgressIndicator(progress = { status.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Kicker("${formatBytes(status.doneBytes)} of ${formatBytes(status.totalBytes)}", Modifier.weight(1f))
                        SecondaryButton("cancel", actions.onCancel)
                    }
                }
                is ModelStatus.WaitingForNetwork -> {
                    Text(if (state.mobileData) "Waiting for a connection" else "Waiting for Wi-Fi", style = MaterialTheme.typography.titleMedium)
                    Hint(
                        if (status.doneBytes > 0) "${formatBytes(status.doneBytes)} of ${formatBytes(status.totalBytes)} so far; the rest follows on its own."
                        else "The download of ${formatBytes(status.totalBytes)} starts on its own.",
                    )
                    SecondaryButton("cancel", actions.onCancel, Modifier.padding(top = 4.dp))
                }
                is ModelStatus.Failed -> {
                    ErrorBox("The download stopped", status.problem)
                    PrimaryButton("try again", actions.onDownload, Modifier.padding(top = 4.dp))
                }
            }
        }

        Kicker("Model size")
        TierPicker(state, actions.onChoice)
        Hint(tierCaption(state))

        ToggleRow(
            "Download over mobile data", if (state.mobileData) "Any connection" else "Wi-Fi only",
            state.mobileData, actions.onMobileData,
        )
        ToggleRow(
            "Use the phone’s built-in recognizer instead", if (state.useBuiltIn) "The downloaded models aren’t used" else "The downloaded models listen once they’re ready",
            state.useBuiltIn, actions.onUseBuiltIn,
        )
        if (state.status == ModelStatus.Ready) {
            SecondaryButton("delete speech models", actions.onDelete, Modifier.padding(top = 4.dp))
        }
        if (state.status == ModelStatus.Ready || log.isNotEmpty()) VoiceLogPanel(log, actions.onClearLog, zone, logOpen)
    }
}

/**
 * The voice log: what on-phone recognition did lately, newest first, with the words each pass heard,
 * so a problem can be read off the phone. Kept in memory only.
 */
@Composable
private fun VoiceLogPanel(log: List<VoiceLogEntry>, onClear: () -> Unit, zone: ZoneId, startOpen: Boolean) {
    var open by rememberSaveable { mutableStateOf(startOpen) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Voice log", style = MaterialTheme.typography.titleMedium)
                Hint("The last ${log.size} steps, with the words heard. Only in this phone’s memory: never saved or sent.")
            }
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
        }
        if (open) {
            InfoBox {
                if (log.isEmpty()) {
                    Hint("Nothing yet. Talk with the mic on, then look here.")
                } else {
                    log.asReversed().forEach { entry ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                logTime(entry.atMs, zone),
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(entry.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            if (log.isNotEmpty()) SecondaryButton("clear log", onClear)
        }
    }
}

/** "14:52:21" in the phone's time zone. Pure. */
fun logTime(atMs: Long, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).format(Instant.ofEpochMilli(atMs).atZone(zone))

@Composable
private fun TierPicker(state: VoiceUiState, onChoice: (TierChoice) -> Unit) {
    val options = TierChoice.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, choice ->
            SegmentedButton(
                selected = state.choice == choice,
                onClick = { onChoice(choice) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) {
                Text(
                    when (choice) {
                        TierChoice.Auto -> "Auto"
                        TierChoice.Accurate -> "Accurate"
                        TierChoice.Light -> "Light"
                    },
                )
            }
        }
    }
}

/** Pure: the line under the size picker. */
fun tierCaption(state: VoiceUiState): String {
    val accurate = formatBytes(VoiceCatalog.downloadBytes(VoiceTier.Accurate))
    val light = formatBytes(VoiceCatalog.downloadBytes(VoiceTier.Light))
    return when (state.choice) {
        TierChoice.Auto -> when {
            state.benchmarkedDown -> "Auto: ${tierTitle(VoiceTier.Light)} ($light). The larger model was too slow on this phone."
            else -> "Auto: ${tierTitle(state.proposed)} (${if (state.proposed == VoiceTier.Accurate) accurate else light}), from this phone’s memory; " +
                "it steps down if a test decode is too slow."
        }
        TierChoice.Accurate -> "${tierTitle(VoiceTier.Accurate)}: the most accurate, $accurate. Needs a fast phone with 8 GB of memory."
        TierChoice.Light -> "${tierTitle(VoiceTier.Light)}: lighter and quicker, $light."
    }
}
