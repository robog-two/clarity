package net.robog.clarity.app.ui.held

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import net.robog.clarity.app.vault.DisableAutofill
import net.robog.clarity.app.vault.PrivateKeyboard
import net.robog.clarity.app.vault.secretKeyboard
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.ui.components.BackLink
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.InfoBox
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.ReadingText
import net.robog.clarity.app.ui.components.SlideToConfirm
import net.robog.clarity.app.ui.cards.slideLabel
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.shared.governance.HeldActionId

@Composable
fun DraftReviewScreen(
    heldId: HeldActionId,
    onBack: () -> Unit,
    viewModel: DraftReviewViewModel = metroViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(heldId) { viewModel.load(heldId) }
    LaunchedEffect(state.finished) { if (state.finished) onBack() }
    BackHandler { onBack() }
    DraftReviewContent(
        state = state,
        onBack = onBack,
        onEdit = viewModel::edit,
        onApprove = { viewModel.approve() },
        onDecline = viewModel::decline,
        onAlways = viewModel::approveAlways,
        onCode = viewModel::editCode,
    )
}

/**
 * The one approval screen: what was asked, what the agent thinks, the draft or code when there is
 * one, the slider, and "not now" (deny, or approve automatically from now on with an edited rule).
 */
@Composable
fun DraftReviewContent(
    state: DraftReviewState,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    /** "approve automatically from now on" with the (edited) rule text and reason. */
    onAlways: (String, String) -> Unit = { _, _ -> },
    onCode: (String) -> Unit = {},
    extras: HeldExtras = LocalHeldExtras.current,
) {
    val held = state.held
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackLink("← back", onBack)
        if (held == null) {
            if (!state.loading) ErrorBox("Can’t open this one", state.message)
            else Hint("Loading…")
            return@Column
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val email = state.email
            Kicker("needs your ok · ${held.action.summary}")
            Text(email?.subject ?: held.action.summary, style = MaterialTheme.typography.headlineSmall)

            if (held.context.isNotEmpty()) {
                Kicker(email?.let { recipientName(it) }?.let { "what $it asked" } ?: "what prompted this", Modifier.padding(top = 4.dp))
                held.context.forEach { item ->
                    Column(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Kicker(item.tag)
                        Hint(item.title)
                    }
                }
            }

            Kicker("what I think", Modifier.padding(top = 4.dp))
            ReadingText(held.agentView)
            Hint(held.verdict.rationale)

            if (email != null) {
                Kicker("draft", Modifier.padding(top = 4.dp))
                OutlinedTextField(
                    value = state.body,
                    onValueChange = onEdit,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.decided,
                    textStyle = MaterialTheme.clarityText.reading,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    minLines = 5,
                )
            } else {
                InfoBox { Text(held.action.summary, style = MaterialTheme.typography.titleMedium) }
                if (held.needsCode) CodeField(state.code, onCode, enabled = !state.decided && !state.busy)
            }
            Kicker(checkedLine(held))
            extras(held)

            state.message?.let { ErrorBox(it) }
        }
        if (state.decided) {
            Hint("This one is settled.")
        } else {
            SlideToConfirm(
                slideLabel(held.action.kind),
                onApprove,
                enabled = !state.busy && (!held.needsCode || state.code.isNotBlank()),
                resetKey = state.message,
            )
            NotNow(held, onDeny = onDecline, onAlways = onAlways, enabled = !state.busy)
        }
    }
}

/** The one-field form for a two-factor code: digits keyboard, no autofill, no learning, never saved. */
@Composable
fun CodeField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    DisableAutofill()
    PrivateKeyboard {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text("code") },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = secretKeyboard(KeyboardType.Password),
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
    )
    }
}
