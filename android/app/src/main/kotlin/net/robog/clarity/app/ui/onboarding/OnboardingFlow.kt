package net.robog.clarity.app.ui.onboarding

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
import net.robog.clarity.app.ui.components.MaskedKeyTransformation
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
        when (step) {
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

/**
 * The shape shared by the step screens: progress bar, optional back link, kicker, heading, a
 * scrolling body and a pinned footer with the buttons.
 */
@Composable
fun StepScaffold(
    step: OnboardingStep?,
    modifier: Modifier = Modifier,
    backLabel: String? = null,
    onBack: (() -> Unit)? = null,
    kicker: String? = step?.kicker,
    title: String? = null,
    footer: @Composable ColumnScope.() -> Unit = {},
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = ScreenGutter)
            .padding(top = 24.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (step != null) StepBar(step.segments)
        if (backLabel != null && onBack != null) BackLink(backLabel, onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (kicker != null) Kicker(kicker)
            if (title != null) ScreenTitle(title)
            body()
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { footer() }
    }
}

// --- welcome ---

@Composable
fun WelcomeScreen(onBegin: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = ScreenGutter)
            .padding(top = 72.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Personal agent", style = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
            Text("You set the priorities.", style = MaterialTheme.clarityText.hero)
            ReadingText(
                "I take small, scoped tasks and stay out of the way. Setup takes about ten minutes, mostly talking.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The four steps ahead, so the screen says what "ten minutes" is made of.
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Kicker("What we’ll do")
            WELCOME_STEPS.forEachIndexed { i, (title, caption) -> WelcomeStep(i + 1, title, caption) }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        PrimaryButton("begin", onBegin, Modifier.fillMaxWidth())
    }
}

private val WELCOME_STEPS = listOf(
    "Connect" to "Your server, your model key, and where your day lives",
    "Values" to "Seven questions about how you like to work",
    "Review" to "Check the lines I wrote down from your answers",
    "Morning call" to "Pick when I call to plan the day",
)

@Composable
private fun WelcomeStep(number: Int, title: String, caption: String) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
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
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
        backLabel = "← welcome",
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
        Hint("Your data stays here. I read from it over open standards and keep nothing elsewhere.")
        val urlError = (state.error as? ServerError.InvalidUrl)?.reason
        ClarityField(
            label = "Server address",
            value = state.url,
            onValueChange = onUrlChanged,
            placeholder = "https://clarity.example.com",
            error = urlError?.message(),
            enabled = !state.enrolled && !state.checking,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
        ClarityField(
            label = "Setup code (optional)",
            value = state.code,
            onValueChange = onCodeChanged,
            supporting = "Only needed if your server shows one.",
            error = if (state.error is ServerError.CodeRequired) "This server needs a valid setup code." else null,
            enabled = !state.enrolled && !state.checking,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        )
        when {
            state.checking -> StatusLine("checking…")
            state.probe != null -> StatusLine(
                if (state.probe.tls) "● Reachable, connected securely" else "● Reachable, but not over a secure connection",
            )
            state.enrolled -> StatusLine("● connected")
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
    UrlError.Malformed -> "That does not look like a valid address."
    UrlError.InsecureScheme -> "The address must start with https://."
    UrlError.UnsupportedScheme -> "The address must start with https://."
}

private fun ServerError.message(): String = when (this) {
    is ServerError.InvalidUrl -> reason.message()
    ServerError.Unreachable -> "Could not reach the server. Check the address and your connection."
    ServerError.CodeRequired -> "This server needs a valid setup code."
    is ServerError.Server -> "The server returned an error (HTTP $status)."
    ServerError.Unexpected -> "Unexpected response. Is this a Clarity server?"
}

// --- model ---

@Composable
fun ModelScreen(onNext: () -> Unit, onBack: () -> Unit, viewModel: ModelStepViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ModelContent(state, viewModel::onKeyChanged, viewModel::check, onNext, onBack)
}

@Composable
fun ModelContent(state: ModelState, onKeyChanged: (String) -> Unit, onCheck: () -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    StepScaffold(
        step = OnboardingStep.Model,
        backLabel = "← server",
        onBack = onBack,
        title = "Your model",
        footer = {
            if (state.canContinue) {
                PrimaryButton("next", onNext, Modifier.fillMaxWidth())
            } else {
                PrimaryButton("check key", onCheck, Modifier.fillMaxWidth(), enabled = state.canCheck, busy = state.checking)
            }
        },
    ) {
        Hint("I run on your own Anthropic API key. Usage is billed to your account, and requests go straight from your server.")
        ClarityField(
            label = "Anthropic API key",
            value = state.key,
            onValueChange = onKeyChanged,
            placeholder = "sk-ant-…",
            enabled = !state.checking,
            visualTransformation = MaskedKeyTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        )
        Hint("I pick the model size for each task: small for sorting mail, larger for drafting as you.")
        when (val r = state.result) {
            ModelResult.Works -> StatusLine("● key works")
            is ModelResult.Rejected -> ErrorBox(
                "Anthropic didn’t accept this key.",
                "Check that you copied the whole key and that it’s still active.",
                raw = r.detail,
            )
            ModelResult.ServerNotReady -> Hint("This server can’t take the key yet. You can continue and add it in settings later.")
            ModelResult.Failed -> ErrorBox("Could not check the key.", "Try again in a moment.")
            null -> if (state.checking) StatusLine("Checking…")
        }
        Spacer(Modifier.height(1.dp))
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
        ModelContent(ModelState(key = "sk-ant-api03-EXAMPLEEXAMPLEEXAMPLE", result = ModelResult.Works), {}, {}, {}, {})
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780)
@Composable
private fun WelcomePreview() = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) { WelcomeScreen {} }
}
