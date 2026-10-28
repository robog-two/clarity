package net.robog.clarity.app.ui.ask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.data.feed.SyncState
import net.robog.clarity.app.ui.cards.CardEnv
import net.robog.clarity.app.ui.components.ChatComposer
import net.robog.clarity.app.ui.components.ChatTranscript
import net.robog.clarity.app.ui.components.ComposerMic
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.ScreenGutter
import net.robog.clarity.app.ui.components.arrivingItem
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.app.voice.MicAccess
import net.robog.clarity.app.voice.MicChipState
import net.robog.clarity.app.voice.MicRationale
import net.robog.clarity.app.voice.MicStateChip
import net.robog.clarity.app.voice.rememberMicAccess
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.HeldDecision

@Composable
fun AskScreen(
    modifier: Modifier = Modifier,
    onReview: (HeldActionId) -> Unit = {},
    onOpenReport: ((String) -> Unit)? = null,
    viewModel: AskViewModel = metroViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mic = rememberMicAccess(viewModel::onMicPermission)
    AskContent(
        state = state,
        mic = mic,
        onDraft = viewModel::onDraft,
        onSubmit = viewModel::submit,
        onTalk = { if (mic.granted) viewModel.talk() else mic.request() },
        onSuggest = viewModel::suggest,
        onDecision = viewModel::decide,
        onReview = onReview,
        onAlways = viewModel::approveAlways,
        modifier = modifier,
        onOpenReport = onOpenReport,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AskContent(
    state: AskUiState,
    mic: MicAccess,
    onDraft: (String) -> Unit,
    onSubmit: () -> Unit,
    onTalk: () -> Unit,
    onSuggest: (String) -> Unit,
    onDecision: (HeldActionId, HeldDecision) -> Unit,
    modifier: Modifier = Modifier,
    onReview: (HeldActionId) -> Unit = {},
    onAlways: (HeldActionId, String, String) -> Unit = { _, _, _ -> },
    onOpenReport: ((String) -> Unit)? = null,
) {
    val env = CardEnv(held = state.held, tasks = state.tasks, onDecision = onDecision, onReview = onReview, onAlways = onAlways, resetKey = state.message, onOpenReport = onOpenReport)
    val listening = state.mic == MicChipState.Listening || state.mic == MicChipState.MicOn
    Column(modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = ScreenGutter, end = ScreenGutter, top = 24.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Ask", style = MaterialTheme.clarityText.heroTitle.copy(fontSize = 24.sp, lineHeight = 30.sp))
            if (listening) MicStateChip(state.mic)
            else if (state.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LoadingIndicator(Modifier.size(24.dp))
                Kicker("working…")
            }
            else Kicker("replies in text")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ChatTranscript(
            entries = state.entries,
            live = state.live,
            env = env,
            modifier = Modifier.weight(1f),
            newest = {
                if (state.suggestions.isNotEmpty()) {
                    item(key = "suggestions") {
                        Column(arrivingItem().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Kicker("try saying")
                            state.suggestions.forEach { s ->
                                OutlinedButton(
                                    onClick = { onSuggest(s) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    shape = MaterialTheme.shapes.extraLarge,
                                ) { Text("“$s”", style = MaterialTheme.clarityText.readingSmall) }
                            }
                        }
                    }
                }
            },
            oldest = {
                if (state.entries.isEmpty() && state.live == null) {
                    item(key = "empty") { Hint("Ask me anything about your day. I’ll check your sources and answer here.") }
                }
            },
        )
        if (state.sync == SyncState.Unauthorized) {
            ErrorBox("Your server doesn’t accept this phone", "Set it up again in settings.", Modifier.padding(horizontal = ScreenGutter, vertical = 8.dp))
        }
        state.message?.let { ErrorBox(it, modifier = Modifier.padding(horizontal = ScreenGutter, vertical = 8.dp)) }
        if (mic.refused && !mic.granted) {
            MicRationale(mic.refused, mic.request, mic.openSettings, Modifier.padding(horizontal = ScreenGutter, vertical = 8.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ChatComposer(
            draft = state.draft,
            onDraft = onDraft,
            onSend = onSubmit,
            mic = ComposerMic(live = listening, description = if (state.mic == MicChipState.Off) "talk" else "stop"),
            onMic = onTalk,
        )
    }
}
