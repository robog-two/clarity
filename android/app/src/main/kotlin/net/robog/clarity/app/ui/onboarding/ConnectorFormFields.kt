package net.robog.clarity.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import net.robog.clarity.app.connector.ConfigForm
import net.robog.clarity.app.connector.FieldError
import net.robog.clarity.app.connector.FieldKind
import net.robog.clarity.app.connector.FormField
import net.robog.clarity.app.connector.FormValues
import net.robog.clarity.app.connector.ItemValues
import net.robog.clarity.app.ui.components.ClarityDropdown
import net.robog.clarity.app.ui.components.ClarityField
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.InfoBox
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.SecondaryButton
import net.robog.clarity.app.ui.components.ToggleRow

/** What a collection (the accounts) can do on screen. */
data class CollectionActions(
    val onAdd: (collection: String) -> Unit = {},
    val onEdit: (collection: String, id: String) -> Unit = { _, _ -> },
    val onRemove: (collection: String, id: String) -> Unit = { _, _ -> },
)

/** One input of the generated form. Collections are drawn by [CollectionSection] through [collection]. */
@Composable
fun FieldInput(
    field: FormField,
    values: FormValues,
    error: FieldError?,
    onText: (String, String) -> Unit,
    onList: (String, List<String>) -> Unit,
    collection: @Composable (FormField) -> Unit = {},
) {
    val label = if (field.required) field.label else "${field.label} (optional)"
    when (field.kind) {
        FieldKind.BOOLEAN ->
            ToggleRow(field.label, field.description.orEmpty(), values.text(field.key) == "true", onCheckedChange = { onText(field.key, it.toString()) })
        FieldKind.ENUM -> ClarityDropdown(
            label = label,
            selected = values.text(field.key),
            options = (if (field.required) emptyList() else listOf("" to "not set")) + field.options.map { it.value to it.title },
            onSelect = { onText(field.key, it) },
            supporting = field.description,
            error = error?.message(),
        )
        FieldKind.STRING_LIST -> StringListEditor(
            label = label,
            entries = values.list(field.key),
            onChange = { onList(field.key, it) },
            placeholder = field.placeholder,
            supporting = field.description,
            error = error?.message(),
        )
        FieldKind.COLLECTION -> collection(field)
        else -> {
            val stored = field.secret && field.key in values.storedSecrets
            val typed = values.text(field.key)
            ClarityField(
                label = label,
                value = typed,
                onValueChange = { onText(field.key, it) },
                placeholder = if (stored) SAVED_SECRET else field.placeholder,
                supporting = if (stored && typed.isEmpty()) SAVED_SECRET else field.description,
                error = error?.message(),
                visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    // No autofill hints and no suggestions for secrets: they stay between the person and the connector.
                    autoCorrectEnabled = !field.secret,
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
}

const val SAVED_SECRET = "saved · type to replace"

/** A list of short strings (folders, calendars): each entry with remove, and a field to add one. */
@Composable
fun StringListEditor(
    label: String,
    entries: List<String>,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supporting: String? = null,
    error: String? = null,
) {
    var draft by remember { mutableStateOf("") }
    fun add() {
        val v = draft.trim()
        if (v.isNotEmpty() && v !in entries) onChange(entries + v)
        draft = ""
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        entries.forEachIndexed { i, entry ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(entry, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal))
                TextButton(onClick = { onChange(entries.filterIndexed { j, _ -> j != i }) }) { Text("remove") }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            ClarityField(
                label = "add one",
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = placeholder,
                error = error,
                supporting = if (error == null) supporting else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
            )
            SecondaryButton("add", { add() }, Modifier.padding(top = 8.dp), enabled = draft.isNotBlank())
        }
    }
}

/** The accounts: one card each, with edit and remove, and "add account". */
@Composable
fun CollectionSection(
    field: FormField,
    form: ConfigForm,
    values: FormValues,
    error: FieldError?,
    actions: CollectionActions,
    testLineFor: (String) -> TestLine?,
) {
    val items = values.items(field.key)
    var removing by remember { mutableStateOf<ItemValues?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Kicker(field.label)
        field.description?.let { Hint(it) }
        items.forEach { item ->
            val title = form.itemTitle(field, item)
            AccountCard(
                title = title,
                subtitle = form.itemSubtitle(field, item),
                test = testLineFor(title),
                onEdit = { actions.onEdit(field.key, item.id) },
                onRemove = { removing = item },
            )
        }
        error?.let { Hint(it.message(), color = MaterialTheme.colorScheme.error) }
        SecondaryButton("add account", { actions.onAdd(field.key) }, Modifier.fillMaxWidth())
    }
    removing?.let { item ->
        val title = form.itemTitle(field, item)
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove $title?") },
            text = { Text("It stops syncing once you save. Its saved password is deleted with it.") },
            confirmButton = {
                TextButton(onClick = { removing = null; actions.onRemove(field.key, item.id) }) { Text("remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("cancel") } },
        )
    }
}

@Composable
fun AccountCard(
    title: String,
    subtitle: String?,
    test: TestLine?,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InfoBox(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        subtitle?.let { Kicker(it) }
        test?.let {
            Hint(it.text, color = if (it.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onRemove) { Text("remove") }
            TextButton(onClick = onEdit) { Text("edit") }
        }
    }
}

fun FieldError.message(): String = when (this) {
    FieldError.Required -> "Required."
    FieldError.InvalidUrl -> "Enter a full address, like https://example.com."
    FieldError.InvalidEmail -> "Enter an email address."
    FieldError.InvalidChoice -> "Pick one of the choices."
    FieldError.NotANumber -> "Enter a number."
    FieldError.NotAWholeNumber -> "Enter a whole number."
    is FieldError.TooSmall -> "At least ${trim(minimum)}."
    is FieldError.TooLarge -> "At most ${trim(maximum)}."
    is FieldError.TooFewItems -> if (minimum <= 1) "Add at least one account." else "Add at least $minimum accounts."
    FieldError.ItemInvalid -> "One of the accounts is incomplete. Open it to fix it."
}

private fun trim(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
