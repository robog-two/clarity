package net.robog.clarity.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.ui.cards.LiveBubble
import net.robog.clarity.app.ui.cards.SaidBubble
import net.robog.clarity.app.ui.components.ClarityField
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.InfoBox
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.PrimaryButton
import net.robog.clarity.app.ui.components.ReadingText
import net.robog.clarity.app.ui.components.SecondaryButton
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.app.voice.MicChipState
import net.robog.clarity.app.voice.MicPermissionGate
import net.robog.clarity.app.voice.MicStateChip
import net.robog.clarity.shared.governance.Line
import net.robog.clarity.shared.governance.LineId
import net.robog.clarity.shared.governance.Section

// --- values intro ---

@Composable
fun ValuesIntroScreen(onStart: () -> Unit, onBack: () -> Unit) {
    StepScaffold(
        step = OnboardingStep.ValuesIntro,
        backLabel = "← connectors",
        onBack = onBack,
        title = "How You Work",
        footer = { PrimaryButton("start →", onStart, Modifier.fillMaxWidth()) },
    ) {
        ReadingText("Seven questions, about six minutes.")
        Hint("I’ll ask about real moments, good and bad, rather than abstract rules. Stories show what matters more reliably than guesses about it.")
        Hint("Each answer becomes a line in your constitution, with your reason kept beside it. The reason is what lets me use judgment when something comes up that no rule covers.")
        Hint("A few things become hard lines I never cross. Everything else, you can amend any time by talking.")
    }
}

// --- the interview ---

@Composable
fun ValuesScreen(viewModel: ValuesStepViewModel, onFinished: () -> Unit, onSkip: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.phase) {
        if (state.phase == InterviewPhase.Finished) onFinished()
    }
    DisposableEffect(Unit) { onDispose { viewModel.stopListening() } }
    ValuesContent(
        state = state,
        onReword = viewModel::reword,
        onKeep = viewModel::keep,
        onRetry = viewModel::restart,
        onSkip = onSkip,
        onSendTyped = viewModel::sendTyped,
        onMute = viewModel::mute,
        onUnmute = viewModel::unmute,
        onMicReady = viewModel::startListening,
    )
}

@Composable
fun ValuesContent(
    state: ValuesState,
    onReword: () -> Unit,
    onKeep: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    onSendTyped: (String) -> Unit,
    onMute: () -> Unit,
    onUnmute: () -> Unit,
    onMicReady: () -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    StepScaffold(
        step = OnboardingStep.Values,
        kicker = null,
        footer = {
            if (state.phase == InterviewPhase.Active) {
                // Pinned with the buttons, so it stays in reach (and above the keyboard) however long the question is.
                ClarityField(
                    label = "or type your answer",
                    value = typed,
                    onValueChange = { typed = it },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        onSendTyped(typed)
                        typed = ""
                    }),
                )
                Hint("Just talk. The mic stays on until you move on.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("reword", onReword, Modifier.weight(1f), enabled = state.canDecide)
                    PrimaryButton("keep →", onKeep, Modifier.weight(1f), enabled = state.canDecide)
                }
            }
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Kicker("step 2 of 4 · ${state.position}".trimEnd(' ', '·'))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MicStateChip(state.mic)
                if (state.mic == MicChipState.Muted) {
                    TextButton(onClick = onUnmute) { Text("unmute") }
                } else if (state.mic != MicChipState.Off) {
                    TextButton(onClick = onMute) { Text("mute") }
                }
            }
        }
        when (val phase = state.phase) {
            InterviewPhase.Idle, InterviewPhase.Starting -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(4.dp))
                Hint("Getting the first question ready…")
            }
            InterviewPhase.Unavailable -> {
                Hint("This server can’t run the values interview yet. You can skip it for now and do it later.")
                SecondaryButton("skip for now", onSkip, Modifier.fillMaxWidth())
            }
            is InterviewPhase.Failed -> {
                ErrorBox("The interview stopped.", "Check your connection and try again.")
                SecondaryButton("start again", onRetry, Modifier.fillMaxWidth())
            }
            InterviewPhase.Finished -> Hint("All seven answered.")
            InterviewPhase.Active -> InterviewBody(state, onMicReady)
        }
    }
}

@Composable
private fun InterviewBody(
    state: ValuesState,
    onMicReady: () -> Unit,
) {
    state.question?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
    if (state.live != null) LiveBubble(state.live, Modifier.padding(start = 32.dp))
    if (state.live == null && state.said != null) SaidBubble(state.said, Modifier.padding(start = 32.dp))
    state.captured?.let { CapturedLine(it) }
    MicPermissionGate {
        LaunchedEffect(Unit) { onMicReady() }
    }
}

/** "captured as": the line written from the answer, in its reason's words. */
@Composable
fun CapturedLine(line: Line, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Kicker("captured as", Modifier.padding(top = 4.dp))
        InfoBox(
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            border = null,
        ) {
            Kicker(sectionLabel(line.section), color = MaterialTheme.colorScheme.onPrimaryContainer)
            ReadingText(line.text)
            line.because?.let { Hint("because $it", italic = true, color = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
    }
}

// --- constitution review ---

@Composable
fun ConstitutionScreen(
    onRedo: () -> Unit,
    onNext: () -> Unit,
    viewModel: ConstitutionStepViewModel = metroViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.load() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ConstitutionContent(state, onRedo, onNext, viewModel::load)
}

@Composable
fun ConstitutionContent(state: ConstitutionUi, onRedo: () -> Unit, onNext: () -> Unit, onRetry: () -> Unit) {
    val version = (state as? ConstitutionUi.Loaded)?.version
    StepScaffold(
        step = OnboardingStep.Constitution,
        kicker = "step 3 of 4 · your constitution" + (version?.let { " · v$it" } ?: ""),
        title = "How I Want to Work",
        footer = {
            when (state) {
                is ConstitutionUi.Loaded -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("redo", onRedo, Modifier.weight(1f))
                    PrimaryButton("looks right →", onNext, Modifier.weight(1f))
                }
                ConstitutionUi.Unavailable -> PrimaryButton("continue →", onNext, Modifier.fillMaxWidth())
                is ConstitutionUi.Failed -> SecondaryButton("try again", onRetry, Modifier.fillMaxWidth())
                ConstitutionUi.Loading -> Unit
            }
        },
    ) {
        when (state) {
            ConstitutionUi.Loading -> CircularProgressIndicator(strokeWidth = 2.dp)
            ConstitutionUi.Unavailable -> Hint("This server can’t show the constitution yet. You can read it in settings once it can.")
            is ConstitutionUi.Failed -> ErrorBox("Could not load the constitution.", "Check your connection and try again.")
            is ConstitutionUi.Loaded -> {
                state.sections.forEach { section ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Kicker(section.title, Modifier.padding(top = 4.dp))
                        section.entries.forEach { entry ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                ReadingText(entry.text)
                                entry.because?.let { Hint("because $it", italic = true) }
                            }
                        }
                    }
                }
                Hint("Every action I take is checked against this page first. Hard lines are fixed. Everything else is judgment, guided by your reasons. Amend any of it by talking.")
            }
        }
    }
}

// --- previews ---

private val sampleLine = Line(
    LineId("l1"), Section.HARD_LINES, "Never speak for me to a group without my review.",
    because = "being misrepresented costs more than any time saved.", hard = true,
)

@Preview(showBackground = true, widthDp = 390, heightDp = 800)
@Composable
private fun InterviewPreview() = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
        ValuesContent(
            ValuesState(
                phase = InterviewPhase.Active, index = 1, count = 7,
                question = "Now a time help made things worse. What crossed the line?",
                said = "A scheduling tool accepted a meeting in my focus block, then emailed my whole team about it.",
                captured = sampleLine, mic = MicChipState.MicOn,
            ),
            {}, {}, {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 800)
@Composable
private fun ConstitutionPreview() = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
        ConstitutionContent(
            ConstitutionUi.Loaded(
                1,
                listOf(
                    ConstitutionSectionView("what I value", listOf(ConstitutionEntry("Keep 9 to 12 for deep work.", "that’s when the work gets the best of me."))),
                    ConstitutionSectionView("hard lines · never crossed", listOf(ConstitutionEntry(sampleLine.text, sampleLine.because))),
                ),
            ),
            {}, {}, {},
        )
    }
}
