package net.robog.clarity.app.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import net.robog.clarity.app.ui.cards.LiveBubble
import net.robog.clarity.app.ui.components.ScreenGutter
import net.robog.clarity.app.ui.settings.VoiceModelActions
import net.robog.clarity.app.ui.settings.VoiceModelsContent
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.app.voice.ModelStatus
import net.robog.clarity.app.voice.TierChoice
import net.robog.clarity.app.voice.VoiceCatalog
import net.robog.clarity.app.voice.VoiceLogEntry
import java.time.ZoneOffset
import net.robog.clarity.app.voice.VoiceTier
import net.robog.clarity.app.voice.VoiceUiState

@Composable
private fun VoiceFrame(content: @Composable () -> Unit) = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = ScreenGutter, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

private val accurateBytes = VoiceCatalog.downloadBytes(VoiceTier.Accurate)

@PreviewTest @Part @Composable
fun VoiceModelsOffer() = VoiceFrame {
    VoiceModelsContent(
        VoiceUiState(tier = VoiceTier.Accurate, proposed = VoiceTier.Accurate, status = ModelStatus.NotDownloaded(accurateBytes)),
        VoiceModelActions(),
    )
}

@PreviewTest @Part @Composable
fun VoiceModelsDownloading() = VoiceFrame {
    VoiceModelsContent(
        VoiceUiState(tier = VoiceTier.Accurate, proposed = VoiceTier.Accurate, status = ModelStatus.Downloading(312_000_000, accurateBytes)),
        VoiceModelActions(),
    )
}

@PreviewTest @Part @Composable
fun VoiceModelsReady() = VoiceFrame {
    VoiceModelsContent(
        VoiceUiState(tier = VoiceTier.Light, choice = TierChoice.Auto, proposed = VoiceTier.Accurate, benchmarkedDown = true, status = ModelStatus.Ready),
        VoiceModelActions(),
    )
}

/** Settings → Voice with the voice log open: what both passes heard, newest first. */
@PreviewTest @Part @Composable
fun VoiceLogOpen() = VoiceFrame {
    val at = 1_793_717_541_000L // 14:52:21 UTC
    val lines = listOf(
        "Live words and speech detection loaded in 1840 ms (Accurate tier)",
        "Mic on: first audio 120 ms after the start, first second at -41 dB",
        "Phrase 0.0 closed: 1.9 s, -34 dB; live “so the first thing is”",
        "Second pass loaded in 6210 ms (Accurate tier, beam search, 12 hotwords)",
        "Phrase 0.0: second pass “So the first thing is” (412 ms)",
        "Phrase 0.1 closed: 2.6 s, -36 dB; live “we should meet at some point”",
        "Phrase 0.1: second pass “we should meet at some point.” (388 ms)",
        "Message 0 sent: “So the first thing is we should meet at some point.”",
    )
    VoiceModelsContent(
        VoiceUiState(tier = VoiceTier.Accurate, choice = TierChoice.Auto, proposed = VoiceTier.Accurate, status = ModelStatus.Ready),
        VoiceModelActions(),
        log = lines.mapIndexed { i, text -> VoiceLogEntry(at + i * 900L, text) },
        zone = ZoneOffset.UTC,
        logOpen = true,
    )
}

/** The live bubble while two-pass recognition works: settled words first, still-live words after. */
@PreviewTest @Part @Composable
fun VoiceLiveBubbles() = VoiceFrame {
    LiveBubble("and sales start at", confirmed = 0)
    LiveBubble("And class starts at 9:30, and then", confirmed = "And class starts at 9:30,".length)
    LiveBubble("And class starts at 9:30, and then I have lunch with Klaus.", confirmed = "And class starts at 9:30, and then I have lunch with Klaus.".length)
}
