package net.robog.clarity.app.ui.call

import net.robog.clarity.app.ui.theme.Space
import net.robog.clarity.app.ui.components.CardGroup

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
import net.robog.clarity.app.ui.components.BackLink
import net.robog.clarity.app.ui.components.ChatLayout
import net.robog.clarity.app.ui.components.ClarityScreen
import net.robog.clarity.app.ui.components.ScreenLevel
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
    ClarityScreen(
        title = "Clarity",
        level = ScreenLevel.Top,
        kicker = "Incoming call",
        horizontalAlignment = Alignment.CenterHorizontally,
        footer = { SlideToAnswer(onAnswer = onAnswer, onDecline = onDecline, enabled = !state.working) },
    ) {
        Hint("About 8 minutes")
        RingingBell(Modifier.padding(vertical = Space.Large))
        state.message?.let { ErrorBox(it) }
        Hint("Replies appear on screen")
    }
}

/** The ringing bell: a few quick swings from the handle, then a pause, like a hand bell being rung. */
@Composable
private fun RingingBell(modifier: Modifier = Modifier) {
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
        modifier.size(180.dp).graphicsLayer {
            transformOrigin = TransformOrigin(0.5f, 0.15f)
            rotationZ = angle
        },
    )
}

@Composable
private fun SnoozedScreen(state: CallUiState, onAnswer: () -> Unit) {
    ClarityScreen(
        title = state.snoozedUntil?.let { "Calling again at $it" } ?: "Calling again in 15 minutes",
        kicker = "Not now",
        footer = { PrimaryButton("Call now", onAnswer, Modifier.fillMaxWidth(), enabled = !state.working) },
    ) {
        Hint("Yesterday’s plan stays")
        state.message?.let { ErrorBox(it) }
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

    ChatLayout(
        header = {
            Row(
                Modifier.fillMaxWidth().padding(start = ScreenGutter, end = ScreenGutter, top = Space.Medium, bottom = Space.Medium),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.mic == MicChipState.Off) Spacer(Modifier) else MicStateChip(state.mic)
                Kicker("Morning meeting, $elapsed", Modifier.semantics { contentDescription = "Morning meeting, ${spokenElapsed(elapsed)}" })
            }
            StageSegments(state.stage, Modifier.padding(horizontal = ScreenGutter).padding(bottom = Space.Medium))
        },
        footer = {
            if (!mic.granted) {
                MicRationale(mic.refused, onAllow = mic.request, onOpenSettings = mic.openSettings, modifier = Modifier.padding(horizontal = ScreenGutter, vertical = Space.Small))
            }
            state.message?.let { ErrorBox(it, modifier = Modifier.padding(horizontal = ScreenGutter, vertical = Space.Small)) }
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
                Modifier.fillMaxWidth().padding(start = ScreenGutter, end = ScreenGutter, bottom = Space.Large).heightIn(min = 56.dp),
                enabled = state.canAdvance,
            )
        },
    ) { transcript -> SessionFeed(state, transcript) }
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

/** The four stage segments, each with its one-word label: done and current filled, the rest muted. */
@Composable
fun StageSegments(stage: CallStage?, modifier: Modifier = Modifier, ended: Boolean = false) {
    val description = stageDescription(stage, ended)
    Row(
        modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
        horizontalArrangement = Arrangement.spacedBy(Space.Small),
    ) {
        stageSegments(stage, ended).forEachIndexed { i, segment -> Segment(StageLabels[i], segment) }
    }
}

@Composable
private fun RowScope.Segment(label: String, state: SegmentState) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
        Box(
            Modifier.fillMaxWidth().height(4.dp).background(
                when (state) {
                    // Each at least 3:1 on the page, and different from the others: grey, brand, outline grey.
                    SegmentState.Done -> scheme.onSurfaceVariant
                    SegmentState.Current -> scheme.primary
                    SegmentState.Todo -> scheme.outline
                },
                MaterialTheme.shapes.extraSmall,
            ),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (state == SegmentState.Current) scheme.onSurface else scheme.onSurfaceVariant,
        )
    }
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
    ClarityScreen(
        title = "Your morning",
        level = ScreenLevel.Top,
        kicker = if (elapsed == null) "Call ended" else "Call ended after $elapsed",
        footer = {
            PrimaryButton("Open app", onOpenApp, Modifier.fillMaxWidth())
            SecondaryButton("Replay call", onReplay, Modifier.fillMaxWidth())
        },
    ) {
        if (state.plan.isNotEmpty()) PlanHero(state.plan)
        TodayLabel("Setting up", Modifier.padding(top = Space.Medium))
        if (state.setup.isEmpty()) {
            Hint("Nothing set up")
        } else {
            CardGroup(state.setup) { task, shape, _ ->
                // No Review button in the call, but a task holding something for review keeps its shape.
                DoingRowView(DoingRow(task, review = null), shape, onReview = {}, needsYou = task.heldActionId != null)
            }
        }
    }
}

/** "Replay the call": the session's items again, read only. */
@Composable
private fun ReplayScreen(state: CallUiState, onClose: () -> Unit) {
    ChatLayout(
        header = { BackLink("Your morning", onClose, Modifier.padding(horizontal = ScreenGutter, vertical = Space.Small)) },
        footer = {},
    ) { transcript -> SessionFeed(state.copy(live = null), transcript) }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun IncomingPreview() = ClarityTheme {
    CallContent(
        CallUiState(screen = CallScreen.Incoming, callTime = "9:00"),
        MicAccess(true, false, {}, {}), {}, {}, {}, {}, {}, {}, {},
    )
}
