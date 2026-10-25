package net.robog.clarity.app.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.connector.ConfigForm
import net.robog.clarity.app.connector.FieldError
import net.robog.clarity.app.connector.FieldKind
import net.robog.clarity.app.connector.FormField
import net.robog.clarity.app.connector.PhoneConnectorKind
import net.robog.clarity.app.ui.components.ClarityField
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.InfoBox
import net.robog.clarity.app.ui.components.PrimaryButton
import net.robog.clarity.app.ui.components.SecondaryButton
import net.robog.clarity.app.ui.components.SelectRow
import net.robog.clarity.app.ui.components.ToggleRow
import net.robog.clarity.app.ui.theme.ClarityTheme

// --- connectors list ---

@Composable
fun ConnectorsScreen(
    viewModel: ConnectorsStepViewModel,
    onOpen: (ConnectorItem) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ConnectorsContent(state, onOpen, viewModel::syncAll, onContinue, onBack)
}

@Composable
fun ConnectorsContent(
    state: ConnectorsState,
    onOpen: (ConnectorItem) -> Unit,
    onSyncAll: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    StepScaffold(
        step = OnboardingStep.Connectors,
        backLabel = "← model",
        onBack = onBack,
        title = "Where Your Day Lives",
        footer = {
            Text(
                state.summaryLine,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            if (state.allSynced) {
                PrimaryButton("continue →", onContinue, Modifier.fillMaxWidth())
            } else {
                PrimaryButton(if (state.syncing) "syncing…" else "sync all", onSyncAll, Modifier.fillMaxWidth(), enabled = state.canSync)
            }
        },
    ) {
        Hint("Tap each one to set it up. Then sync, and I’ll test them all.")
        if (state.unavailable) Hint("This server can’t list its connectors yet. Only the ones on this phone are shown.")
        state.error?.let { ErrorBox("Could not load the connectors.", "Check your connection and come back to this step.") }
        Column {
            state.items.forEach { item ->
                SelectRow(
                    title = item.row.title.lowercase(),
                    caption = item.caption,
                    status = item.status,
                    statusAlpha = if (item.saved || item.test != null) 1f else 0.72f,
                    onClick = { onOpen(item) },
                    statusColor = if (item.test is TestState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// --- one connector ---

@Composable
fun ConnectorSetupScreen(viewModel: ConnectorSetupViewModel, onSaved: () -> Unit, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.consumeSaved()
            onSaved()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onPermissionResult(it)
    }
    ConnectorSetupContent(
        state = state,
        onValueChanged = viewModel::onValueChanged,
        onAllow = { state.phone?.let { permission.launch(it.permission) } },
        onSave = viewModel::save,
        onBack = onBack,
    )
}

@Composable
fun ConnectorSetupContent(
    state: ConnectorSetupState,
    onValueChanged: (String, String) -> Unit,
    onAllow: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    StepScaffold(
        step = OnboardingStep.ConnectorSetup,
        backLabel = "← connectors",
        onBack = onBack,
        kicker = state.proto,
        title = state.title,
        footer = {
            PrimaryButton(state.saveLabel, onSave, Modifier.fillMaxWidth(), enabled = state.canSave)
        },
    ) {
        state.failedReason?.let {
            ErrorBox("sync failed · $it", if (state.form.fields.any { f -> f.secret }) "Tap the password to enter it again." else null)
        }
        Hint(state.blurb)
        state.problem?.let { ErrorBox(it.message()) }
        val errors = state.errors
        state.form.fields.forEach { field ->
            FieldInput(field, state.values[field.key].orEmpty(), errors[field.key]) { onValueChanged(field.key, it) }
        }
        if (state.phone != null) {
            InfoBox {
                Text("Allow access to ${state.title.lowercase()}?", style = MaterialTheme.typography.titleMedium)
                Hint("Read only. You can turn this off in settings.")
                if (state.allowed) {
                    StatusLine("● allowed")
                } else {
                    SecondaryButton("allow", onAllow)
                }
            }
        }
    }
}

@Composable
private fun FieldInput(field: FormField, value: String, error: FieldError?, onChange: (String) -> Unit) {
    val label = if (field.required) field.label else "${field.label} (optional)"
    when (field.kind) {
        FieldKind.BOOLEAN -> ToggleRow(field.label, field.description.orEmpty(), value == "true", onCheckedChange = { onChange(it.toString()) })
        else -> ClarityField(
            label = label,
            value = value,
            onValueChange = onChange,
            supporting = field.description,
            error = error?.message(),
            visualTransformation = if (field.kind == FieldKind.PASSWORD) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when (field.kind) {
                    FieldKind.PASSWORD -> KeyboardType.Password
                    FieldKind.URL -> KeyboardType.Uri
                    FieldKind.EMAIL -> KeyboardType.Email
                    FieldKind.NUMBER -> if (field.integer) KeyboardType.Number else KeyboardType.Decimal
                    else -> KeyboardType.Text
                },
                imeAction = ImeAction.Next,
            ),
        )
    }
}

private fun FieldError.message(): String = when (this) {
    FieldError.Required -> "Required."
    FieldError.InvalidUrl -> "Enter a full address, like https://example.com."
    FieldError.InvalidEmail -> "Enter an email address."
    FieldError.NotANumber -> "Enter a number."
    FieldError.NotAWholeNumber -> "Enter a whole number."
    is FieldError.TooSmall -> "At least ${trim(minimum)}."
    is FieldError.TooLarge -> "At most ${trim(maximum)}."
}

private fun trim(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

private fun SetupProblem.message(): String = when (this) {
    SetupProblem.NoSealingKey -> "This connector hasn’t published its encryption key yet, so I can’t send the password safely. Try again in a minute."
    SetupProblem.NotAvailable -> "This server can’t store connector settings yet."
    SetupProblem.Unreachable -> "Could not reach the server."
    SetupProblem.Other -> "The server did not accept the settings."
}

// --- previews ---

@Preview(showBackground = true, widthDp = 390, heightDp = 780)
@Composable
private fun ConnectorSetupPreview() = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
        val form = ConfigForm(
            listOf(
                FormField("host", "imap host", kind = FieldKind.TEXT, required = true),
                FormField("user", "username", kind = FieldKind.EMAIL, required = true),
                FormField("password", "password", kind = FieldKind.PASSWORD, required = true),
                FormField("tls", "use tls", kind = FieldKind.BOOLEAN),
            ),
        )
        ConnectorSetupContent(
            ConnectorSetupState(
                key = "mail", title = "Mail", proto = "imap · on your server", blurb = "I read your inbox and save drafts.",
                form = form, values = mapOf("host" to "imap.home.local:993", "tls" to "true"), showErrors = true,
                failedReason = "password rejected (401)",
            ),
            { _, _ -> }, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780)
@Composable
private fun PhoneConnectorPreview() = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
        ConnectorSetupContent(
            ConnectorSetupState(key = "phone-messages", title = "Messages", proto = "on this phone", blurb = PhoneConnectorKind.Messages.blurb, phone = PhoneConnectorKind.Messages),
            { _, _ -> }, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780)
@Composable
private fun ConnectorsPreview() = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
        ConnectorsContent(
            ConnectorsState(
                loading = false,
                items = listOf(
                    ConnectorItem(ConnectorRow.Phone(PhoneConnectorKind.Messages), saved = true, test = TestState.Ok("86 threads")),
                ),
            ),
            {}, {}, {}, {},
        )
    }
}
