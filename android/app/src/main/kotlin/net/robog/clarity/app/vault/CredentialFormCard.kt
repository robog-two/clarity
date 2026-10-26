package net.robog.clarity.app.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.ui.cards.NoteBox
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.InfoBox
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.PrimaryButton
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.shared.vault.CredentialRequest
import net.robog.clarity.shared.vault.CredentialRequestId
import net.robog.clarity.shared.vault.CredentialRequestStatus
import net.robog.clarity.shared.vault.FieldSpec
import net.robog.clarity.shared.vault.FieldType

/** Where the form card gets its request and saves to. Null (previews, tests) shows a plain note. */
val LocalVaultRepository = compositionLocalOf<VaultRepository?> { null }

/** What the card shows. */
sealed interface FormCardState {
    data object Loading : FormCardState
    data class Failed(val message: String) : FormCardState
    data class Editing(val form: CredentialForm, val saving: Boolean = false, val error: String? = null) : FormCardState
    /** Saved now, or already answered earlier. */
    data class Done(val line: String) : FormCardState
}

/** Drives one form card: load the request, edit, validate, seal and upload. Secrets stay in [state] until saved. */
class CredentialFormModel(
    private val requestId: CredentialRequestId,
    private val vault: VaultRepository,
    private val now: () -> Pair<Int, Int> = ::currentYearMonth,
) {
    var state: FormCardState by mutableStateOf(FormCardState.Loading)
        private set

    suspend fun load() {
        state = when (val r = vault.request(requestId)) {
            is ApiResult.Failure -> FormCardState.Failed(r.error.sentence())
            is ApiResult.Success -> {
                val request = r.value
                when (request.status) {
                    CredentialRequestStatus.OPEN -> FormCardState.Editing(CredentialForm(request, now().first, now().second))
                    CredentialRequestStatus.FULFILLED -> FormCardState.Done("Saved. I can use it now.")
                    CredentialRequestStatus.CANCELLED -> FormCardState.Done("This request was cancelled.")
                }
            }
        }
    }

    fun edit(field: FieldSpec, value: String) {
        val s = state as? FormCardState.Editing ?: return
        state = s.copy(form = s.form.with(field, value), error = null)
    }

    fun touch(field: FieldSpec) {
        val s = state as? FormCardState.Editing ?: return
        state = s.copy(form = s.form.touch(field))
    }

    suspend fun save() {
        val s = state as? FormCardState.Editing ?: return
        if (s.saving) return
        if (!s.form.valid) {
            state = s.copy(form = s.form.touchAll())
            return
        }
        state = s.copy(saving = true, error = null)
        state = when (val outcome = vault.save(s.form.request, s.form.normalized())) {
            // Dropping the editing state drops the typed secrets with it.
            is SaveOutcome.Saved -> FormCardState.Done("Saved. “${credentialTitle(outcome.meta.name)}” is locked on your phone and I can use it now.")
            is SaveOutcome.Failed -> s.copy(saving = false, error = outcome.message)
        }
    }
}

private fun currentYearMonth(): Pair<Int, Int> {
    val d = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return d.year to d.monthNumber
}

/** The form card for a credential the agent asked for (`Card.CredentialRequest`). */
@Composable
fun CredentialFormCard(requestId: CredentialRequestId, modifier: Modifier = Modifier) {
    val vault = LocalVaultRepository.current
    if (vault == null) {
        NoteBox("I need a login or card from you. Open the vault to add it.", modifier)
        return
    }
    val model = remember(requestId, vault) { CredentialFormModel(requestId, vault) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.load() }
    CredentialFormContent(model.state, model::edit, model::touch, { scope.launch { model.save() } }, modifier, onRetry = { scope.launch { model.load() } })
}

@Composable
fun CredentialFormContent(
    state: FormCardState,
    onEdit: (FieldSpec, String) -> Unit,
    onTouch: (FieldSpec) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    InfoBox(modifier, border = MaterialTheme.colorScheme.primary) {
        when (state) {
            FormCardState.Loading -> { Kicker("for you to fill in"); Hint("Loading the form…") }
            is FormCardState.Failed -> {
                ErrorBox("Couldn’t load the form", state.message)
                TextButton(onClick = onRetry) { Text("Try again") }
            }
            is FormCardState.Done -> { Kicker("saved"); Text(state.line, style = MaterialTheme.clarityText.chat) }
            is FormCardState.Editing -> EditingForm(state, onEdit, onTouch, onSave)
        }
    }
}

@Composable
private fun EditingForm(state: FormCardState.Editing, onEdit: (FieldSpec, String) -> Unit, onTouch: (FieldSpec) -> Unit, onSave: () -> Unit) {
    DisableAutofill()
    PrivateKeyboard { FormBody(state, onEdit, onTouch, onSave) }
}

@Composable
private fun FormBody(state: FormCardState.Editing, onEdit: (FieldSpec, String) -> Unit, onTouch: (FieldSpec) -> Unit, onSave: () -> Unit) {
    val request = state.form.request
    Kicker("for you to fill in · ${request.site}")
    Text(request.purpose, style = MaterialTheme.typography.titleMedium)
    Hint("It’s locked on your phone before it leaves it. I never see it; I only get to use it when you approve.")
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        request.fields.forEach { field ->
            FieldRow(field, state.form.valueOf(field), state.form.shownError(field), enabled = !state.saving, onEdit, onTouch)
        }
    }
    state.error?.let { ErrorBox(it) }
    PrimaryButton(
        if (state.saving) "Locking it up…" else "Save",
        onSave,
        Modifier.fillMaxWidth().padding(top = 8.dp),
        enabled = !state.saving,
    )
}

@Composable
private fun FieldRow(
    field: FieldSpec,
    value: String,
    error: String?,
    enabled: Boolean,
    onEdit: (FieldSpec, String) -> Unit,
    onTouch: (FieldSpec) -> Unit,
) {
    val secret = field.type.isSecret()
    var shown by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { onEdit(field, it) },
        modifier = Modifier.fillMaxWidth().onFocusChanged {
            if (focused && !it.isFocused) onTouch(field)
            focused = it.isFocused
        },
        enabled = enabled,
        label = { Text(field.label + if (field.required) "" else " (optional)") },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        textStyle = MaterialTheme.clarityText.chat,
        shape = MaterialTheme.shapes.extraSmall,
        visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = secretKeyboard(keyboardFor(field.type)),
        trailingIcon = if (secret) ({ TextButton(onClick = { shown = !shown }) { Text(if (shown) "Hide" else "Show") } }) else null,
    )
}

/** Keyboard per field; secrets use the private variants so keyboards do not suggest or remember them. */
internal fun keyboardFor(type: FieldType): KeyboardType = when (type) {
    FieldType.PASSWORD, FieldType.OTP -> KeyboardType.Password
    FieldType.CARD_NUMBER, FieldType.CARD_CVC -> KeyboardType.NumberPassword
    FieldType.CARD_EXPIRY -> KeyboardType.Number
    FieldType.EMAIL -> KeyboardType.Email
    FieldType.PHONE -> KeyboardType.Phone
    FieldType.USERNAME, FieldType.NAME, FieldType.ADDRESS, FieldType.TEXT -> KeyboardType.Text
}
