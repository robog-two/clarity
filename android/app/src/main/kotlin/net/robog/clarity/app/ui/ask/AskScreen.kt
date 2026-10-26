package net.robog.clarity.app.ui.ask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.data.feed.SyncState
import net.robog.clarity.app.ui.cards.CardEnv
import net.robog.clarity.app.ui.cards.FeedItemView
import net.robog.clarity.app.ui.cards.LiveBubble
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.PrimaryButton
import net.robog.clarity.app.ui.components.SecondaryButton
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.app.voice.MicAccess
import net.robog.clarity.app.voice.MicChipState
import net.robog.clarity.app.voice.MicRationale
import net.robog.clarity.app.voice.MicStateChip
import net.robog.clarity.app.voice.rememberMicAccess
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.HeldDecision

@Composable
fun AskScreen(modifier: Modifier = Modifier, viewModel: AskViewModel = metroViewModel()) {
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
        modifier = modifier,
    )
}

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
) {
    val env = CardEnv(held = state.held, tasks = state.tasks, onDecision = onDecision)
    val listState = rememberLazyListState()
    LaunchedEffect(state.items.size, state.items.lastOrNull()?.cards?.size, state.live) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    Column(modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Ask", style = MaterialTheme.typography.headlineSmall)
            if (state.mic == MicChipState.Listening || state.mic == MicChipState.MicOn) MicStateChip(state.mic)
            else Kicker(if (state.busy) "working…" else "replies in text")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.items.isEmpty() && state.live == null) {
                item { Hint("Ask me anything about your day. I’ll check your sources and answer here.") }
            }
            items(state.items, key = { it.seq }) { FeedItemView(it, env = env) }
            state.live?.let { live -> item(key = "live") { LiveBubble(live) } }
            if (state.suggestions.isNotEmpty()) {
                item(key = "suggestions") {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }
        if (state.sync == SyncState.Unauthorized) {
            ErrorBox("Your server doesn’t accept this phone", "Set it up again in settings.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        state.message?.let { ErrorBox(it, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        if (mic.refused && !mic.granted) {
            MicRationale(mic.refused, mic.request, mic.openSettings, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = state.draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "ask anything" },
                placeholder = { Text("type, or tap talk", style = MaterialTheme.clarityText.reading) },
                textStyle = MaterialTheme.clarityText.reading,
                shape = MaterialTheme.shapes.extraLarge,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSubmit() }),
                maxLines = 4,
            )
            if (state.canSend) PrimaryButton("send", onSubmit) else SecondaryButton(
                if (state.mic == MicChipState.Off) "talk" else "stop",
                onTalk,
            )
        }
    }
}
