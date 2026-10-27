package net.robog.clarity.app.ui.held

import net.robog.clarity.app.ui.components.LoadingSpinner
import net.robog.clarity.app.ui.components.ScreenGutter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.TextButton
import kotlinx.datetime.TimeZone
import net.robog.clarity.app.ui.call.formatClock
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
        onRetry = { viewModel.load(heldId) },
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
    onRetry: (() -> Unit)? = null,
) {
    val held = state.held
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()
            .padding(horizontal = ScreenGutter, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackLink("Back", onBack)
        if (held == null) {
            if (!state.loading) {
                ErrorBox("Can’t open this one", state.message)
                onRetry?.let { TextButton(onClick = it) { Text("Try again") } }
            } else {
                LoadingSpinner(Modifier.padding(top = 48.dp))
            }
            return@Column
        }
        val outcome = heldOutcome(held.status)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val email = state.email
            Kicker(outcome ?: "needs your OK · ${formatClock(held.createdAt, TimeZone.currentSystemDefault())}")
            Text(email?.subject ?: held.action.summary, style = MaterialTheme.typography.headlineSmall)

            if (email != null) {
                // Who it goes to comes first: it is sent as the person.
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RecipientLine("To", email.to.joinToString { handleLine(it) })
                    if (email.cc.isNotEmpty()) RecipientLine("Cc", email.cc.joinToString { handleLine(it) })
                }
                OutlinedTextField(
                    value = state.body,
                    onValueChange = onEdit,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    enabled = !state.decided,
                    label = { Text("Draft") },
                    textStyle = MaterialTheme.clarityText.chat,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    minLines = 5,
                )
            }
            extras(held)
            if (email == null && held.needsCode) CodeField(state.code, onCode, enabled = !state.decided && !state.busy)

            Kicker("why I’m asking", Modifier.padding(top = 8.dp))
            ReadingText(held.agentView)
            Hint(held.verdict.rationale)
            checkedLine(held)?.let { Kicker(it) }

            if (held.context.isNotEmpty()) {
                Kicker(email?.let { recipientName(it) }?.let { "what $it asked" } ?: "what prompted this", Modifier.padding(top = 8.dp))
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

            state.message?.let { ErrorBox(it) }
        }
        if (state.decided) {
            Text(outcome ?: "Settled", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        } else {
            SlideToConfirm(
                slideLabel(held.action.kind),
                onApprove,
                enabled = !state.busy && (!held.needsCode || state.code.isNotBlank()),
                resetKey = state.message,
                working = state.busy,
            )
            NotNow(held, onDeny = onDecline, onAlways = onAlways, enabled = !state.busy)
        }
    }
}

@Composable
private fun RecipientLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.clarityText.chatSmall)
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
        label = { Text("Code") },
        supportingText = { Text("Used once, never saved") },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = secretKeyboard(KeyboardType.Password),
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
    )
    }
}
