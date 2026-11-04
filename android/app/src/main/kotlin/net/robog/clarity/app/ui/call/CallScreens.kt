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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import net.robog.clarity.app.ui.components.ChatComposer
import net.robog.clarity.app.ui.components.ChatTranscript
import net.robog.clarity.app.ui.components.ComposerMic
import net.robog.clarity.app.ui.components.chatEntries
import net.robog.clarity.app.ui.components.groupShape
import net.robog.clarity.app.ui.components.springIn
import net.robog.clarity.app.ui.today.DoingRow
import net.robog.clarity.app.ui.today.DoingRowView
import net.robog.clarity.app.ui.today.PlanHero
import net.robog.clarity.app.ui.today.TodayLabel
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import net.robog.clarity.R
import net.robog.clarity.app.ui.components.Drawing
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Clock
import kotlinx.coroutines.delay
import net.robog.clarity.app.ui.cards.CardEnv
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.PrimaryButton
import net.robog.clarity.app.ui.components.SecondaryButton
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.app.voice.MicAccess
import net.robog.clarity.app.voice.MicChipState
import net.robog.clarity.app.voice.MicRationale
import net.robog.clarity.app.voice.MicStateChip
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
    onDraft: (String) -> Unit = {},
    onSend: () -> Unit = {},
) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        when (state.screen) {
            CallScreen.Loading -> Unit
            CallScreen.Incoming -> IncomingScreen(state, onAnswer, onDecline)
            CallScreen.Snoozed -> SnoozedScreen(state, onAnswer)
            CallScreen.InCall -> InCallScreen(state, mic, onAdvance, onToggleMute, onDraft, onSend)
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
        Kicker("Incoming call")
        Text("Clarity", style = MaterialTheme.clarityText.heroTitle.copy(fontSize = 34.sp, lineHeight = 40.sp), textAlign = TextAlign.Center)
        Hint("About 8 minutes")
        Spacer(Modifier.weight(1f))
        RingingBell()
        Spacer(Modifier.weight(1f))
        state.message?.let { ErrorBox(it) }
        Hint("Replies appear on screen", Modifier.padding(bottom = 24.dp))
        SlideToAnswer(onAnswer = onAnswer, onDecline = onDecline, enabled = !state.working)
    }
}

/** The ringing bell: a few quick swings from the handle, then a pause, like a hand bell being rung. */
@Composable
private fun RingingBell() {
    val angle = if (LocalInspectionMode.current) 0f else {
        val ring = rememberInfiniteTransition(label = "bell")
        ring.animateFloat(
            initialValue = 0f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                keyframes {
                    durationMillis = 2_000
                    0f at 0
                    14f at 120
                    -12f at 260
                    9f at 400
                    -6f at 540
                    3f at 680
                    0f at 820
                },
            ),
            label = "swing",
        ).value
    }
    Drawing(
        R.drawable.drawing_bell_ringing,
        Modifier.size(180.dp).graphicsLayer {
            transformOrigin = TransformOrigin(0.5f, 0.15f)
            rotationZ = angle
        },
    )
}

@Composable
private fun SnoozedScreen(state: CallUiState, onAnswer: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 120.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Kicker("Not now")
        Text(
            state.snoozedUntil?.let { "Calling again at $it" } ?: "Calling again in 15 minutes",
            style = MaterialTheme.typography.headlineMedium,
        )
        Hint("Yesterday’s plan stays")
        state.message?.let { ErrorBox(it) }
        Spacer(Modifier.weight(1f))
        PrimaryButton("Call now", onAnswer, Modifier.fillMaxWidth(), enabled = !state.working)
    }
}

@Composable
private fun InCallScreen(
    state: CallUiState,
    mic: MicAccess,
    onAdvance: () -> Unit,
    onToggleMute: () -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
) {
    // The screen stays on while the person talks to the agent.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    // Previews and screenshots show a fixed timer, so their images do not change with the clock.
    val inspection = LocalInspectionMode.current
    val now by produceState(Clock.System.now()) {
        if (!inspection) while (true) { delay(1_000); value = Clock.System.now() }
    }
    val elapsed = if (inspection) "4:12" else state.startedAt?.let { formatElapsed(now - it) } ?: "0:00"

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = ScreenGutter, end = ScreenGutter, top = 20.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.mic == MicChipState.Off) Spacer(Modifier) else MicStateChip(state.mic)
            Kicker("Morning meeting, $elapsed", Modifier.semantics { contentDescription = "Morning meeting, ${spokenElapsed(elapsed)}" })
        }
        StageSegments(state.stage, Modifier.padding(horizontal = ScreenGutter).padding(bottom = 14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SessionFeed(state, Modifier.weight(1f))
        if (!mic.granted) {
            MicRationale(mic.refused, onAllow = mic.request, onOpenSettings = mic.openSettings, modifier = Modifier.padding(horizontal = ScreenGutter, vertical = 8.dp))
        }
        state.message?.let { ErrorBox(it, modifier = Modifier.padding(horizontal = ScreenGutter, vertical = 8.dp)) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ChatComposer(
            draft = state.draft,
            onDraft = onDraft,
            onSend = onSend,
            mic = callMic(state),
            onMic = onToggleMute,
        )
        PrimaryButton(
            primaryLabel(state.stage),
            onAdvance,
            Modifier.fillMaxWidth().padding(start = ScreenGutter, end = ScreenGutter, bottom = 24.dp).heightIn(min = 56.dp),
            enabled = state.canAdvance,
        )
    }
}

/** The composer's mic in a call: live while listening, crossed out when muted, "Talk" when push to talk. */
private fun callMic(state: CallUiState) = ComposerMic(
    live = state.mic == MicChipState.Listening || state.mic == MicChipState.MicOn,
    muted = state.muted,
    description = "Microphone",
    state = when {
        state.pushToTalk -> if (state.mic == MicChipState.Listening) "Talking" else "Off"
        state.muted -> "Muted"
        else -> "On"
    },
    action = when {
        state.pushToTalk -> "Talk"
        state.muted -> "Unmute"
        else -> "Mute"
    },
)

/** The four stage segments: done and current filled, the rest muted. No labels, TalkBack says "Step 2 of 4". */
@Composable
fun StageSegments(stage: CallStage?, modifier: Modifier = Modifier, ended: Boolean = false) {
    val description = stageDescription(stage, ended)
    Row(
        modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        stageSegments(stage, ended).forEach { segment -> Segment(segment) }
    }
}

@Composable
private fun RowScope.Segment(state: SegmentState) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.weight(1f).height(4.dp).background(
            when (state) {
                // Each at least 3:1 on the page, and different from the others: grey, brand, outline grey.
                SegmentState.Done -> scheme.onSurfaceVariant
                SegmentState.Current -> scheme.primary
                SegmentState.Todo -> scheme.outline
            },
            MaterialTheme.shapes.extraSmall,
        ),
    )
}

/** This call's feed items with the live bubble last (at the bottom); follows the newest line. */
@Composable
private fun SessionFeed(state: CallUiState, modifier: Modifier = Modifier) {
    val entries = remember(state.items) { chatEntries(state.items) }
    ChatTranscript(entries, state.live, CardEnv(held = state.held, tasks = state.tasks), modifier, liveConfirmed = state.liveConfirmed)
}

/**
 * "Your Morning", right after the call: the same plan card and task group as Today, so what was just
 * agreed looks exactly like what Today will show.
 */
@Composable
private fun EndedScreen(state: CallUiState, onOpenApp: () -> Unit, onReplay: () -> Unit) {
    val elapsed = state.session?.let { s -> s.endedAt?.let { formatElapsed(it - s.startedAt) } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 48.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            var order = 0
            Kicker(if (elapsed == null) "Call ended" else "Call ended, $elapsed")
            Text("Your morning", style = MaterialTheme.clarityText.heroTitle.copy(fontSize = 28.sp, lineHeight = 32.sp))
            if (state.plan.isNotEmpty()) PlanHero(state.plan, Modifier.springIn(order++))
            TodayLabel("Setting up", Modifier.padding(top = 10.dp).springIn(order++))
            if (state.setup.isEmpty()) {
                Hint("Nothing set up")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    state.setup.forEachIndexed { i, task ->
                        // No Review button in the call, but a task holding something for review keeps its shape.
                        DoingRowView(DoingRow(task, review = null), groupShape(i, state.setup.size), onReview = {}, Modifier.springIn(order++), needsYou = task.heldActionId != null)
                    }
                }
            }
        }
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("Open app", onOpenApp, Modifier.fillMaxWidth())
            SecondaryButton("Replay call", onReplay, Modifier.fillMaxWidth())
        }
    }
}

/** "Replay the call": the session's items again, read only. */
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
