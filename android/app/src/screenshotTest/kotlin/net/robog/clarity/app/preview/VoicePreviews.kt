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

/** The live bubble while two-pass recognition works: settled words first, still-live words after. */
@PreviewTest @Part @Composable
fun VoiceLiveBubbles() = VoiceFrame {
    LiveBubble("and sales start at", confirmed = 0)
    LiveBubble("And class starts at 9:30, and then", confirmed = "And class starts at 9:30,".length)
    LiveBubble("And class starts at 9:30, and then I have lunch with Klaus.", confirmed = "And class starts at 9:30, and then I have lunch with Klaus.".length)
}
