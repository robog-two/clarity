package net.robog.clarity.app.ui.onboarding

import net.robog.clarity.app.ui.theme.Space

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import net.robog.clarity.app.connector.PresetEdit
import net.robog.clarity.app.ui.components.ClarityDropdown
import net.robog.clarity.app.ui.components.ClarityField
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.InfoBox
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.SecondaryButton
import net.robog.clarity.app.ui.components.ToggleRow
import net.robog.clarity.app.ui.mcp.MCP_HOST
import net.robog.clarity.shared.connector.PresetInput
import net.robog.clarity.shared.connector.PresetInputError
import net.robog.clarity.shared.connector.PresetInputType
import net.robog.clarity.shared.connector.PresetInputs

/** What one collection item is called on screen: a mail account, an MCP server. */
data class ItemNoun(
    val one: String,
    val many: String,
    val chooserTitle: String,
    val chooserHint: String,
    val otherTitle: String,
    val otherCaption: String,
    val removeText: String,
    /** The same, for a removal that is saved at once. */
    val removeNowText: String,
) {
    companion object {
        val Account = ItemNoun(
            one = "account", many = "accounts", chooserTitle = "Which kind?",
            chooserHint = "Pick a provider to fill in its settings.",
            otherTitle = "Other provider", otherCaption = "Enter the server settings yourself",
            removeText = "Stops syncing on save. Saved password is deleted.",
            removeNowText = "Stops syncing. Saved password is deleted.",
        )
        val Server = ItemNoun(
            one = "server", many = "servers", chooserTitle = "Which server?",
            chooserHint = "Pick one to fill in its settings.",
            otherTitle = "Another server", otherCaption = "Enter its package or address yourself",
            removeText = "Stops being used on save. Saved logins are deleted.",
            removeNowText = "Stops being used. Saved logins are deleted.",
        )
    }
}

/** The items of `mcp-host` are servers; every other connector's are accounts. */
fun itemNounFor(connectorKey: String): ItemNoun = if (connectorKey == MCP_HOST) ItemNoun.Server else ItemNoun.Account

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
            // Nothing stored yet (an item saved before the field existed): show the default the connector uses.
            selected = values.text(field.key).ifEmpty { field.defaultChoice.orEmpty() },
            options = (if (field.required || field.defaultChoice != null) emptyList() else listOf("" to "not set")) +
                field.options.map { it.value to it.title },
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

const val SAVED_SECRET = "Saved. Type to replace it."

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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        entries.forEachIndexed { i, entry ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(entry, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal))
                TextButton(
                    onClick = { onChange(entries.filterIndexed { j, _ -> j != i }) },
                    modifier = Modifier.semantics { contentDescription = "Remove $entry" },
                ) { Text("Remove") }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.Small), verticalAlignment = Alignment.Top) {
            ClarityField(
                label = "Add one",
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = placeholder,
                error = error,
                supporting = if (error == null) supporting else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
            )
            SecondaryButton("add", { add() }, Modifier.padding(top = Space.Small), enabled = draft.isNotBlank())
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
    noun: ItemNoun = ItemNoun.Account,
) {
    val items = values.items(field.key)
    var removing by remember { mutableStateOf<ItemValues?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.Medium)) {
        Kicker(field.label, heading = true)
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
        error?.let { Hint(it.message(noun), color = MaterialTheme.colorScheme.error) }
        SecondaryButton("add ${noun.one}", { actions.onAdd(field.key) }, Modifier.fillMaxWidth())
    }
    removing?.let { item ->
        val title = form.itemTitle(field, item)
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove $title?") },
            text = { Text(noun.removeText) },
            confirmButton = {
                TextButton(onClick = { removing = null; actions.onRemove(field.key, item.id) }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
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
            TextButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = "Remove $title" }) { Text("Remove") }
            TextButton(onClick = onEdit, modifier = Modifier.semantics { contentDescription = "Edit $title" }) { Text("Edit") }
        }
    }
}

fun FieldError.message(noun: ItemNoun = ItemNoun.Account): String = when (this) {
    FieldError.Required -> "Fill this in."
    FieldError.InvalidUrl -> "Enter a full address starting with https://"
    FieldError.InvalidEmail -> "Enter an email address."
    FieldError.InvalidChoice -> "Pick one of the choices."
    FieldError.NotANumber -> "Enter a number."
    FieldError.NotAWholeNumber -> "Enter a whole number."
    is FieldError.TooSmall -> "At least ${trim(minimum)}."
    is FieldError.TooLarge -> "At most ${trim(maximum)}."
    is FieldError.TooFewItems -> if (minimum <= 1) "Add at least one ${noun.one}." else "Add at least $minimum ${noun.many}."
    FieldError.ItemInvalid -> "Finish the incomplete ${noun.one}."
}

private fun trim(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

// --- the short form of a preset ---

/** The preset's questions, in order: an address, a menu (with "Other"), a token. */
@Composable
fun PresetQuestions(edit: PresetEdit, values: FormValues, errors: Map<String, PresetInputError>, onAnswer: (String, String) -> Unit) {
    edit.form.inputs.forEach { input ->
        PresetQuestion(input, edit, values, errors[input.id], onAnswer)
    }
}

@Composable
private fun PresetQuestion(input: PresetInput, edit: PresetEdit, values: FormValues, error: PresetInputError?, onAnswer: (String, String) -> Unit) {
    val label = if (input.required) input.title else "${input.title} (optional)"
    val answer = edit.answerOf(input, values)
    when (input.type) {
        PresetInputType.CHOICE -> {
            val other = input.id in edit.others
            ClarityDropdown(
                label = label,
                selected = if (other) OTHER_CHOICE else answer,
                options = choiceOptions(input),
                onSelect = { onAnswer(input.id, it) },
                supporting = input.description,
                error = error?.takeIf { !other }?.message(),
            )
            if (other) {
                ClarityField(
                    label = input.otherTitle ?: input.title,
                    value = answer,
                    supporting = input.otherDescription,
                    onValueChange = { onAnswer(input.id, it) },
                    placeholder = input.placeholder ?: input.default,
                    error = error?.message(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                )
            }
        }
        else -> {
            val secret = input.type == PresetInputType.SECRET
            val stored = input.property?.let { it in values.storedSecrets } == true
            val supporting = when {
                stored && answer.isEmpty() -> SAVED_SECRET
                input.type == PresetInputType.URL -> (PresetInputs.normalizeUrl(answer, input.path) as? PresetInputs.Result.Ok)
                    ?.let { "Saved as ${it.value.url}" } ?: input.description
                else -> input.description
            }
            ClarityField(
                label = label,
                value = answer,
                onValueChange = { onAnswer(input.id, it) },
                placeholder = if (stored) SAVED_SECRET else input.placeholder,
                supporting = supporting,
                error = error?.message(),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = when (input.type) {
                        PresetInputType.SECRET -> KeyboardType.Password
                        PresetInputType.URL -> KeyboardType.Uri
                        else -> KeyboardType.Text
                    },
                    imeAction = ImeAction.Next,
                ),
            )
        }
    }
}

/** A choice input's menu: each choice with its value, then "Other" when other answers are taken. */
fun choiceOptions(input: PresetInput): List<Pair<String, String>> =
    input.choices.map { it.value to "${it.title}, ${it.value}" } + if (input.other) listOf(OTHER_CHOICE to "Other") else emptyList()

/** "Advanced settings": every field of the item, folded away under the questions. */
@Composable
fun AdvancedToggle(open: Boolean, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 0.dp, vertical = Space.Medium)) {
        Text("Advanced settings")
        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
    }
}

/** Hosts named by https addresses in the settings that the server may not reach, each with "Add". */
@Composable
fun UnreachableHosts(hosts: List<String>, onAdd: (String) -> Unit) {
    InfoBox(container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer, border = null) {
        Text("Blocked address", style = MaterialTheme.typography.titleSmall)
        Hint(
            "This server can’t reach ${hosts.joinToString()}. Add it to the allowed list.",
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.Small, Alignment.End)) {
            hosts.forEach { h -> TextButton(onClick = { onAdd(h) }) { Text("Add $h") } }
        }
    }
}

fun PresetInputError.message(): String = when (this) {
    PresetInputError.Required -> "Fill this in."
    PresetInputError.NotHttps -> "Use the https:// address or its name."
    PresetInputError.NotAnAddress -> "Enter its address, like canvas.school.edu."
    PresetInputError.WrongPort -> "Use the address without a port number."
    PresetInputError.NoMatch -> "That doesn’t look right. Check for typos."
    PresetInputError.NotAChoice -> "Pick one of the choices."
}
