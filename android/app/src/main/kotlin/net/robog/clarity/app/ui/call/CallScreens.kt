package net.robog.clarity.app.ui.call

import net.robog.clarity.app.ui.components.ScreenGutter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Clock
import kotlinx.coroutines.delay
import net.robog.clarity.app.ui.cards.CardEnv
import net.robog.clarity.app.ui.cards.FeedItemView
import net.robog.clarity.app.ui.cards.LiveBubble
import net.robog.clarity.app.ui.cards.taskStatusLabel
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.InfoBox
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.PrimaryButton
import net.robog.clarity.app.ui.components.ReadingText
import net.robog.clarity.app.ui.components.SecondaryButton
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.app.voice.MicAccess
import net.robog.clarity.app.voice.MicChipState
import net.robog.clarity.app.voice.MicRationale
import net.robog.clarity.app.voice.MicStateChip
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.session.CallStage

/** Everything `Call.dc.html` shows after the lock screen: incoming, snoozed, in call, ended, replay. */
@Composable
fun CallContent(
    state: CallUiState,
    mic: MicAccess,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onAdvance: () -> Unit,
    onToggleMute: () -> Unit,
    onReplay: () -> Unit,
    onCloseReplay: () -> Unit,
    onOpenApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        when (state.screen) {
            CallScreen.Loading -> Unit
            CallScreen.Incoming -> IncomingScreen(state, onAnswer, onDecline)
            CallScreen.Snoozed -> SnoozedScreen(state, onAnswer)
            CallScreen.InCall -> InCallScreen(state, mic, onAdvance, onToggleMute)
            CallScreen.Ended -> EndedScreen(state, onOpenApp, onReplay)
            CallScreen.Replay -> ReplayScreen(state, onCloseReplay)
        }
    }
}

@Composable
private fun IncomingScreen(state: CallUiState, onAnswer: () -> Unit, onDecline: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 120.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Kicker(if (state.callTime.isEmpty()) "incoming call" else "incoming call · ${state.callTime}")
        Text("Personal Agent", style = MaterialTheme.typography.displaySmall.copy(fontSize = 34.sp), textAlign = TextAlign.Center)
        Hint("Morning call · about 8 min")
        Spacer(Modifier.weight(1f))
        state.message?.let { ErrorBox(it) }
        Hint("You talk. I answer in text, on screen.", Modifier.padding(bottom = 24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("not now", onDecline, Modifier.weight(1f).heightIn(min = 64.dp), enabled = !state.working)
            PrimaryButton("answer", onAnswer, Modifier.weight(1f).heightIn(min = 64.dp), enabled = !state.working)
        }
    }
}

@Composable
private fun SnoozedScreen(state: CallUiState, onAnswer: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 120.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Kicker("snoozed")
        Text(
            state.snoozedUntil?.let { "I’ll call again at $it." } ?: "I’ll call again soon.",
            style = MaterialTheme.typography.headlineMedium,
        )
        Hint("If you skip today, I’ll keep yesterday’s plan and hold nothing new.")
        state.message?.let { ErrorBox(it) }
        Spacer(Modifier.weight(1f))
        PrimaryButton("call me now", onAnswer, Modifier.fillMaxWidth(), enabled = !state.working)
    }
}

@Composable
private fun InCallScreen(state: CallUiState, mic: MicAccess, onAdvance: () -> Unit, onToggleMute: () -> Unit) {
    // The screen stays on while the person talks to the agent.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val now by produceState(Clock.System.now()) {
        while (true) { delay(1_000); value = Clock.System.now() }
    }
    val elapsed = state.startedAt?.let { formatElapsed(now - it) } ?: "0:00"

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = ScreenGutter, end = ScreenGutter, top = 20.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.mic == MicChipState.Off) Spacer(Modifier) else MicStateChip(state.mic)
            Kicker("morning meeting · $elapsed")
        }
        StageSegments(state.stage, Modifier.padding(horizontal = ScreenGutter).padding(bottom = 14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SessionFeed(state, Modifier.weight(1f))
        if (!mic.granted) {
            MicRationale(mic.refused, onAllow = mic.request, onOpenSettings = mic.openSettings, modifier = Modifier.padding(horizontal = ScreenGutter, vertical = 8.dp))
        }
        state.message?.let { ErrorBox(it, modifier = Modifier.padding(horizontal = ScreenGutter, vertical = 8.dp)) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(start = ScreenGutter, end = ScreenGutter, top = 14.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SecondaryButton(
                when {
                    state.pushToTalk -> "talk"
                    state.muted -> "unmute"
                    else -> "mute"
                },
                onToggleMute,
                Modifier.weight(1f),
            )
            PrimaryButton(primaryLabel(state.stage), onAdvance, Modifier.weight(1f), enabled = state.canAdvance)
        }
    }
}

/** The four stage segments: done and current filled, the rest muted, each with its label. */
@Composable
fun StageSegments(stage: CallStage?, modifier: Modifier = Modifier, ended: Boolean = false) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        stageSegments(stage, ended).forEachIndexed { i, segment ->
            Segment(StageLabels[i], segment)
        }
    }
}

@Composable
private fun RowScope.Segment(label: String, state: SegmentState) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().height(4.dp).background(
                when (state) {
                    SegmentState.Done -> scheme.primary.copy(alpha = .5f)
                    SegmentState.Current -> scheme.primary
                    SegmentState.Todo -> scheme.secondaryContainer
                },
                MaterialTheme.shapes.extraSmall,
            ),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp),
            color = if (state == SegmentState.Current) scheme.onSurface else scheme.onSurfaceVariant,
        )
    }
}

/** This call's feed items with the live bubble last; follows the newest line. */
@Composable
private fun SessionFeed(state: CallUiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val env = CardEnv(held = state.held, tasks = state.tasks)
    val count = state.items.size + if (state.live != null) 1 else 0
    LaunchedEffect(count, state.items.lastOrNull()?.cards?.size) {
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    LazyColumn(
        modifier.fillMaxWidth(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = ScreenGutter, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.items, key = { it.seq }) { FeedItemView(it, env = env) }
        state.live?.let { live -> item(key = "live") { LiveBubble(live) } }
    }
}

@Composable
private fun EndedScreen(state: CallUiState, onOpenApp: () -> Unit, onReplay: () -> Unit) {
    val elapsed = state.session?.let { s -> s.endedAt?.let { formatElapsed(it - s.startedAt) } }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Kicker(if (elapsed == null) "call ended" else "call ended · $elapsed")
        Text("Your Morning", style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, lineHeight = 32.sp))
        if (state.plan.isNotEmpty()) {
            InfoBox(container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer, border = null, shape = MaterialTheme.shapes.large) {
                Kicker("your plan", color = MaterialTheme.colorScheme.onPrimaryContainer)
                state.plan.forEach { Text(it.sentence(), style = MaterialTheme.clarityText.reading.copy(fontSize = 15.sp)) }
            }
        }
        Kicker("setting up", Modifier.padding(top = 4.dp))
        LazyColumn(Modifier.weight(1f)) {
            if (state.setup.isEmpty()) {
                item { Hint("Nothing to set up yet.") }
            }
            items(state.setup, key = { it.id.value }) { SetupRow(it) }
        }
        PrimaryButton("Open the app", onOpenApp, Modifier.fillMaxWidth())
        SecondaryButton("replay the call", onReplay, Modifier.fillMaxWidth())
    }
}

@Composable
private fun SetupRow(task: AgentTask) {
    val working = task.status == AgentTask.Status.WORKING
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(task.title, style = MaterialTheme.clarityText.chat)
            Kicker(task.detail)
        }
        Text(
            if (working) "working…" else taskStatusLabel(task.status),
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (working) .6f else 1f),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** "replay the call": the session's items again, read only. */
@Composable
private fun ReplayScreen(state: CallUiState, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 16.dp)) {
        Row(Modifier.padding(horizontal = ScreenGutter), verticalAlignment = Alignment.CenterVertically) {
            net.robog.clarity.app.ui.components.BackLink("Your morning", onClose)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SessionFeed(state.copy(live = null), Modifier.weight(1f))
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun IncomingPreview() = ClarityTheme {
    CallContent(
        CallUiState(screen = CallScreen.Incoming, callTime = "9:00"),
        MicAccess(true, false, {}, {}), {}, {}, {}, {}, {}, {}, {},
    )
}
