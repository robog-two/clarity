package net.robog.clarity.app.ui.onboarding

import net.robog.clarity.app.ui.theme.Space
import net.robog.clarity.app.ui.components.ClarityScreen
import net.robog.clarity.app.ui.components.PhaseSwap
import net.robog.clarity.app.ui.components.ScreenLevel

import net.robog.clarity.app.ui.components.ScreenGutter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.data.UrlError
import net.robog.clarity.app.ui.components.BackLink
import net.robog.clarity.app.ui.components.ClarityField
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.PrimaryButton
import net.robog.clarity.app.ui.components.ReadingText
import net.robog.clarity.app.ui.components.ScreenTitle
import net.robog.clarity.app.ui.components.StepBar
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.app.ui.theme.clarityText

/** The whole onboarding flow: welcome, server, model, connectors, values, review, schedule, done. */
@Composable
fun OnboardingFlow(flow: OnboardingFlowViewModel = metroViewModel()) {
    val state by flow.state.collectAsStateWithLifecycle()
    if (!state.ready) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {}
        return
    }
    val step = state.step
    BackHandler(enabled = step.previous() != null) { flow.back() }

    val connectorsVm: ConnectorsStepViewModel = metroViewModel()
    val setupVm: ConnectorSetupViewModel = metroViewModel()
    val valuesVm: ValuesStepViewModel = metroViewModel()
    var openConnector by rememberSaveable { mutableStateOf<String?>(null) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        PhaseSwap(step, label = "onboarding step") { shown ->
        when (shown) {
            OnboardingStep.Welcome -> WelcomeScreen(onBegin = { flow.go(OnboardingStep.Server) })
            OnboardingStep.Server -> ServerScreen(onNext = flow::next, onBack = flow::back)
            OnboardingStep.Model -> ModelScreen(onNext = flow::next, onBack = flow::back)
            OnboardingStep.Connectors -> {
                LaunchedEffect(Unit) { connectorsVm.refresh() }
                ConnectorsScreen(
                    viewModel = connectorsVm,
                    onOpen = { item ->
                        openConnector = item.row.key
                        setupVm.open(item.row, (item.test as? TestState.Failed)?.reason, item.test)
                        flow.go(OnboardingStep.ConnectorSetup)
                    },
                    onContinue = flow::next,
                    onBack = flow::back,
                )
            }
            OnboardingStep.ConnectorSetup -> ConnectorSetupScreen(
                viewModel = setupVm,
                onSaved = {
                    val key = openConnector
                    if (key != null) connectorsVm.afterSetup(key, retest = setupVm.wasFailed())
                    flow.go(OnboardingStep.Connectors)
                },
                onBack = flow::back,
            )
            OnboardingStep.ValuesIntro -> ValuesIntroScreen(
                onStart = { flow.go(OnboardingStep.Values) },
                onBack = flow::back,
            )
            OnboardingStep.Values -> {
                LaunchedEffect(Unit) { valuesVm.begin() }
                ValuesScreen(
                    viewModel = valuesVm,
                    onFinished = { flow.go(OnboardingStep.Constitution) },
                    onSkip = { flow.go(OnboardingStep.Schedule) },
                )
            }
            OnboardingStep.Constitution -> ConstitutionScreen(
                onRedo = {
                    valuesVm.restart()
                    flow.go(OnboardingStep.Values)
                },
                onNext = flow::next,
            )
            OnboardingStep.Schedule -> ScheduleScreen(onFinished = { flow.go(OnboardingStep.Done) })
            OnboardingStep.Done -> DoneScreen()
        }
        }
    }
}

/**
 * The shape shared by the step screens: progress bar, optional back link, kicker, heading, a
 * scrolling body and a pinned footer with the buttons. It is [ClarityScreen] with the step bar on.
 */
@Composable
fun StepScaffold(
    step: OnboardingStep?,
    modifier: Modifier = Modifier,
    backLabel: String? = null,
    onBack: (() -> Unit)? = null,
    kicker: String? = step?.kicker,
    title: String? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    ClarityScreen(
        title = title,
        modifier = modifier,
        kicker = kicker,
        backLabel = backLabel,
        onBack = onBack,
        stepSegments = step?.segments,
        footer = footer,
        body = body,
    )
}

/** [StepScaffold] for a body with phases (loading, ready): see [ClarityScreen]. */
@Composable
fun <P> StepScaffold(
    step: OnboardingStep?,
    phase: P,
    modifier: Modifier = Modifier,
    backLabel: String? = null,
    onBack: (() -> Unit)? = null,
    kicker: String? = step?.kicker,
    title: String? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    body: @Composable (P) -> Unit,
) {
    ClarityScreen(
        title = title,
        modifier = modifier,
        kicker = kicker,
        backLabel = backLabel,
        onBack = onBack,
        stepSegments = step?.segments,
        phase = phase,
        footer = footer,
        body = body,
    )
}

// --- welcome ---

@Composable
fun WelcomeScreen(onBegin: () -> Unit) {
    ClarityScreen(
        title = "You set the priorities.",
        level = ScreenLevel.Top,
        kicker = "Personal agent",
        footer = { PrimaryButton("begin", onBegin, Modifier.fillMaxWidth()) },
    ) {
        ReadingText(
            "Clarity handles small tasks for you.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The four steps ahead, so the screen says what "ten minutes" is made of.
        Kicker("What we’ll do", Modifier.padding(top = Space.Medium))
        WELCOME_STEPS.forEachIndexed { i, (title, caption) -> WelcomeStep(i + 1, title, caption) }
    }
}

private val WELCOME_STEPS = listOf(
    "Connect" to "Server, model key, connectors",
    "Values" to "Seven questions about how you like to work",
    "Review" to "Review your constitution",
    "Morning call" to "Pick a call time",
)

@Composable
private fun WelcomeStep(number: Int, title: String, caption: String) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(Space.Medium),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Text("$number", color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Hint(caption)
        }
    }
}

// --- server ---

@Composable
fun ServerScreen(onNext: () -> Unit, onBack: () -> Unit, viewModel: ServerStepViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ServerContent(state, viewModel::onUrlChanged, viewModel::onCodeChanged, viewModel::check, onNext, onBack)
}

@Composable
fun ServerContent(
    state: ServerState,
    onUrlChanged: (String) -> Unit,
    onCodeChanged: (String) -> Unit,
    onCheck: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    StepScaffold(
        step = OnboardingStep.Server,
        backLabel = "welcome",
        onBack = onBack,
        title = "Your server",
        footer = {
            if (state.enrolled) {
                PrimaryButton("next", onNext, Modifier.fillMaxWidth())
            } else {
                PrimaryButton("check server", onCheck, Modifier.fillMaxWidth(), enabled = state.canCheck, busy = state.checking)
            }
        },
    ) {
        Hint("Your data stays on your server.")
        val urlError = (state.error as? ServerError.InvalidUrl)?.reason
        if (state.enrolled) {
            // Already connected: the address reads as plain text, not a faint disabled field.
            Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                Kicker("Server address")
                Text(state.url, style = MaterialTheme.typography.titleMedium)
            }
        } else {
            ClarityField(
                label = "Server address",
                value = state.url,
                onValueChange = onUrlChanged,
                placeholder = "https://clarity.example.com",
                error = urlError?.message(),
                enabled = !state.checking,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            ClarityField(
                label = "Setup code",
                value = state.code,
                onValueChange = onCodeChanged,
                supporting = "Optional",
                error = if (state.error is ServerError.CodeRequired) "This server needs a valid setup code." else null,
                enabled = !state.checking,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            )
        }
        when {
            state.checking -> StatusLine("Checking")
            state.probe != null -> StatusLine(
                if (state.probe.tls) "Server reachable. Connection secure." else "Server reachable. Connection not secure.",
            )
            state.enrolled -> StatusLine("Connected")
        }
        state.error?.takeIf { it !is ServerError.InvalidUrl && it !is ServerError.CodeRequired }?.let {
            ErrorBox(it.message())
        }
    }
}

@Composable
fun StatusLine(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
}

private fun UrlError.message(): String = when (this) {
    UrlError.Empty -> "Enter the address of your server."
    UrlError.Malformed -> "That address doesn’t look right."
    UrlError.InsecureScheme -> "Start the address with https://"
    UrlError.UnsupportedScheme -> "Start the address with https://"
}

private fun ServerError.message(): String = when (this) {
    is ServerError.InvalidUrl -> reason.message()
    ServerError.Unreachable -> "Can’t reach server. Check the address."
    ServerError.CodeRequired -> "This server needs a valid setup code."
    is ServerError.Server -> "Server error $status. Try again later."
    ServerError.Unexpected -> "Not a Clarity server. Check the address."
}

// --- model ---

@Composable
fun ModelScreen(onNext: () -> Unit, onBack: () -> Unit, viewModel: ModelStepViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ModelContent(state, viewModel::check, onNext, onBack)
}

@Composable
fun ModelContent(state: ModelState, onCheck: () -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    StepScaffold(
        step = OnboardingStep.Model,
        backLabel = "server",
        onBack = onBack,
        title = "Your model",
        footer = {
            if (state.canContinue) {
                PrimaryButton("next", onNext, Modifier.fillMaxWidth())
            } else {
                PrimaryButton("check again", onCheck, Modifier.fillMaxWidth(), enabled = state.canCheck, busy = state.checking)
            }
        },
    ) {
        Hint("Uses OpenRouter with a key on your server. You pay OpenRouter for usage.")
        Hint("A small model sorts mail. A larger one writes for you.")
        when (state.result) {
            ModelResult.Works -> StatusLine("Models ready")
            ModelResult.Missing -> ErrorBox(
                "Your server needs an OpenRouter key.",
                "Add the key to your server settings. Restart the server and check again.",
                raw = "The server reads the key from its openrouter_api_key secret or OPENROUTER_API_KEY.",
            )
            ModelResult.ServerNotReady -> Hint("Server setup can’t be checked. You can continue.")
            null -> if (state.checking) StatusLine("Checking")
        }
        Spacer(Modifier.height(Space.Small))
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780)
@Composable
private fun ServerPreview() = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
        ServerContent(
            ServerState(url = "https://clarity.home.example", probe = net.robog.clarity.app.data.ServerProbe(18, true), enrolled = true),
            {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780)
@Composable
private fun ModelPreview() = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
        ModelContent(ModelState(result = ModelResult.Works), {}, {}, {})
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780)
@Composable
private fun WelcomePreview() = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) { WelcomeScreen {} }
}
