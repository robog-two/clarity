package net.robog.clarity.app.ui.amend

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.ui.cards.AgentText
import net.robog.clarity.app.ui.cards.FeedItemView
import net.robog.clarity.app.ui.cards.LiveBubble
import net.robog.clarity.app.ui.components.BackLink
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.InfoBox
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.PrimaryButton
import net.robog.clarity.app.ui.components.ReadingText
import net.robog.clarity.app.ui.components.SecondaryButton
import net.robog.clarity.app.ui.held.heldWhen
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.app.voice.MicAccess
import net.robog.clarity.app.voice.MicRationale
import net.robog.clarity.app.voice.MicStateChip
import net.robog.clarity.app.voice.rememberMicAccess
import net.robog.clarity.shared.governance.HeldActionId

@Composable
fun AmendScreen(heldId: HeldActionId?, backLabel: String, onBack: () -> Unit, viewModel: AmendViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mic = rememberMicAccess { if (it) viewModel.listen() }
    LaunchedEffect(heldId) { viewModel.load(heldId) }
    LaunchedEffect(state.session?.id, mic.granted) { if (mic.granted) viewModel.listen() }
    LaunchedEffect(state.finished) { if (state.finished) onBack() }
    BackHandler { viewModel.close(); onBack() }
    AmendContent(
        state = state,
        mic = mic,
        backLabel = backLabel,
        onBack = { viewModel.close(); onBack() },
        onKeep = viewModel::keep,
        onSave = viewModel::save,
    )
}

@Composable
fun AmendContent(
    state: AmendUiState,
    mic: MicAccess,
    backLabel: String,
    onBack: () -> Unit,
    onKeep: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackLink(backLabel, onBack)
        Kicker("amend constitution")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.held?.let { held ->
                InfoBox {
                    Kicker(heldWhen(held, kotlinx.datetime.TimeZone.currentSystemDefault()))
                    ReadingText(held.action.summary)
                    Hint(held.verdict.rationale)
                }
            }
            if (state.settled) Hint("This one is settled. Nothing more to decide here.")
            else if (!agentHasSpoken(state.talk)) AgentText(fallbackQuestion(state.held != null))
            state.talk.forEach { FeedItemView(it) }
            state.live?.let { LiveBubble(it) }
            if (state.waitingAgent) Hint("working on it…", italic = true)
            state.proposal?.let { p ->
                Kicker("proposed change")
                InfoBox(border = MaterialTheme.colorScheme.primary) {
                    Kicker(proposalLabel(p))
                    p.old?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f),
                            style = MaterialTheme.clarityText.reading.copy(textDecoration = TextDecoration.LineThrough),
                        )
                    }
                    ReadingText(p.proposed.text)
                    p.proposed.because?.let { Hint("because $it") }
                }
            }
            state.message?.let { ErrorBox(it) }
        }
        if (!mic.granted) MicRationale(mic.refused, mic.request, mic.openSettings)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MicStateChip(state.mic)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("keep as is", onKeep, Modifier.weight(1f), enabled = state.canKeep)
            PrimaryButton("save rule", onSave, Modifier.weight(1f), enabled = state.canSave)
        }
    }
}
