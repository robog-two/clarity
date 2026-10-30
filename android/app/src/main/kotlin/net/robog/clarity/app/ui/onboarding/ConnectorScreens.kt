package net.robog.clarity.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.robog.clarity.app.connector.ConfigForm
import net.robog.clarity.app.connector.ConfigForms
import net.robog.clarity.app.connector.FieldKind
import net.robog.clarity.app.connector.FormValues
import net.robog.clarity.app.connector.ItemPreset
import net.robog.clarity.app.connector.ItemValues
import net.robog.clarity.app.connector.PhoneConnectorKind
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.InfoBox
import net.robog.clarity.app.ui.components.LoadingSpinner
import net.robog.clarity.app.ui.components.PrimaryButton
import net.robog.clarity.app.ui.components.SelectRow
import net.robog.clarity.app.ui.components.sentenceCase
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.connector.ConnectorSummary

// --- connectors list ---

@Composable
fun ConnectorsScreen(
    viewModel: ConnectorsStepViewModel,
    onOpen: (ConnectorItem) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ConnectorsContent(state, onOpen, viewModel::syncAll, onContinue, onBack, onRetry = { viewModel.refresh() })
}

@Composable
fun ConnectorsContent(
    state: ConnectorsState,
    onOpen: (ConnectorItem) -> Unit,
    onSyncAll: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit = {},
) {
    val nothingListed = state.items.isEmpty() && !state.loading
    StepScaffold(
        step = OnboardingStep.Connectors,
        backLabel = "← model",
        onBack = onBack,
        title = "Where your day lives",
        footer = {
            if (nothingListed) {
                // Nothing to count or sync: the only useful action is loading the list again.
                PrimaryButton("try again", onRetry, Modifier.fillMaxWidth())
                return@StepScaffold
            }
            Text(
                sentenceCase(state.summaryLine),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            if (state.allSynced) {
                PrimaryButton("continue", onContinue, Modifier.fillMaxWidth())
            } else {
                PrimaryButton(if (state.syncing) "syncing…" else "sync all", onSyncAll, Modifier.fillMaxWidth(), enabled = state.canSync)
            }
        },
    ) {
        if (!nothingListed) Hint("Tap each one to set it up. Then sync, and I’ll test them all.")
        if (state.notRunning.isNotEmpty()) {
            Hint(
                "${state.notRunning.joinToString { it.row.title }} isn’t running on your server. " +
                    "You can set it up later; it won’t stop you here.",
            )
        }
        if (state.unavailable) Hint("This server can’t list its connectors yet. Only the ones on this phone are shown.")
        state.error?.let { ErrorBox("Couldn’t load the connectors.", "Check your connection and try again.") }
        Column {
            state.items.forEach { item ->
                SelectRow(
                    title = item.row.title,
                    caption = item.caption,
                    status = item.status,
                    statusAlpha = if (item.saved || item.test != null) 1f else 0.72f,
                    onClick = { onOpen(item) },
                    statusColor = when {
                        item.notRunning -> MaterialTheme.colorScheme.onSurfaceVariant
                        item.test is TestState.Failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }
}

// --- one connector ---

/**
 * The screen of one connector. [step] is the onboarding step it sits in, or null when it is opened
 * from settings (then [backLabel] says where back goes).
 */
@Composable
fun ConnectorSetupScreen(
    viewModel: ConnectorSetupViewModel,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    step: OnboardingStep? = OnboardingStep.ConnectorSetup,
    backLabel: String = "← connectors",
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.consumeSaved()
            onSaved()
        }
    }
    // Back from an account or from the presets goes to the account list, not out of the connector;
    // opened for one item, the item is the whole screen and back leaves it.
    val leaveItem = if (state.direct) onBack else viewModel::cancelItem
    val leaveChooser = if (state.direct) onBack else viewModel::closeChooser
    BackHandler(enabled = state.editor != null) { leaveItem() }
    BackHandler(enabled = state.choosing != null) { leaveChooser() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.onPermissionResult(it)
    }
    ConnectorSetupContent(
        state = state,
        actions = SetupActions(
            onValueChanged = viewModel::onValueChanged,
            onListChanged = viewModel::onListChanged,
            onAllow = { state.phone?.let { permission.launch(it.permission) } },
            onSave = viewModel::save,
            onBack = onBack,
            onRecheck = viewModel::recheck,
            collections = CollectionActions(viewModel::startAdd, viewModel::editItem, viewModel::removeItem),
            onChoosePreset = viewModel::choosePreset,
            onCloseChooser = leaveChooser,
            onItemValueChanged = viewModel::onItemValueChanged,
            onItemListChanged = viewModel::onItemListChanged,
            onSaveItem = viewModel::saveItem,
            onCancelItem = leaveItem,
        ),
        step = step,
        backLabel = backLabel,
    )
}

/** Everything the connector screen can do, grouped so previews and tests can pass no-ops. */
data class SetupActions(
    val onValueChanged: (String, String) -> Unit = { _, _ -> },
    val onListChanged: (String, List<String>) -> Unit = { _, _ -> },
    val onAllow: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onRecheck: () -> Unit = {},
    val collections: CollectionActions = CollectionActions(),
    val onChoosePreset: (String, ItemPreset?) -> Unit = { _, _ -> },
    val onCloseChooser: () -> Unit = {},
    val onItemValueChanged: (String, String) -> Unit = { _, _ -> },
    val onItemListChanged: (String, List<String>) -> Unit = { _, _ -> },
    val onSaveItem: () -> Unit = {},
    val onCancelItem: () -> Unit = {},
)

@Composable
fun ConnectorSetupContent(
    state: ConnectorSetupState,
    actions: SetupActions,
    step: OnboardingStep? = OnboardingStep.ConnectorSetup,
    backLabel: String = "← connectors",
) {
    val editor = state.editor
    val choosing = state.choosing
    when {
        editor != null -> ItemEditorContent(state, editor, actions, step)
        choosing != null -> PresetChooserContent(state, choosing, actions, step)
        else -> ConnectorFormContent(state, actions, step, backLabel)
    }
}

@Composable
private fun ConnectorFormContent(state: ConnectorSetupState, actions: SetupActions, step: OnboardingStep?, backLabel: String) {
    StepScaffold(
        step = step,
        backLabel = backLabel,
        onBack = actions.onBack,
        kicker = state.proto.ifEmpty { null },
        title = state.title,
        footer = {
            when {
                state.loading || state.missing -> Unit
                state.notRunning -> PrimaryButton("check again", actions.onRecheck, Modifier.fillMaxWidth())
                // One step for a phone connector: the button asks for the permission, then saves.
                state.phone != null && !state.allowed -> PrimaryButton("allow access", actions.onAllow, Modifier.fillMaxWidth())
                else -> PrimaryButton(
                    state.saveLabel,
                    actions.onSave,
                    Modifier.fillMaxWidth(),
                    enabled = if (state.phone != null) state.canSave else !state.saving,
                    busy = state.saving,
                )
            }
        },
    ) {
        if (state.loading) LoadingSpinner()
        if (state.missing) ErrorBox("${state.title} isn’t on this server.", "Check that its container is part of your setup.")
        if (state.notRunning) {
            Hint(state.blurb)
            state.adminHint?.let { HowToFix(it) }
        } else if (!state.missing && !state.loading) {
            Hint(state.blurb)
        }
        // One error box: the failed sync and any problem saving go together.
        val problem = state.problem?.message()
        val failed = state.failedReason
        if (failed != null) {
            ErrorBox(
                "Sync failed · $failed",
                listOfNotNull(
                    if (state.form.hasSecrets) "Open the account, or tap the password, to enter it again." else null,
                    problem,
                ).joinToString("\n").ifEmpty { null },
            )
        } else {
            problem?.let { ErrorBox(it) }
        }
        val errors = state.errors
        state.form.fields.forEach { field ->
            FieldInput(
                field = field,
                values = state.values,
                error = errors[field.key],
                onText = actions.onValueChanged,
                onList = actions.onListChanged,
                collection = {
                    CollectionSection(
                        field = it,
                        form = state.form,
                        values = state.values,
                        error = errors[it.key],
                        actions = actions.collections,
                        testLineFor = state::testLineFor,
                        noun = state.noun,
                    )
                },
            )
        }
        if (state.form.fields.any { it.kind == FieldKind.COLLECTION }) {
            Hint("${sentenceCase(state.noun.many)} are stored when you tap ${state.saveLabel}.")
        }
        if (state.phone != null) {
            InfoBox {
                Text(if (state.allowed) "Access allowed" else "Allow access to ${state.title}?", style = MaterialTheme.typography.titleMedium)
                Hint("Read only. You can turn this off in settings.")
                if (state.allowed) StatusLine("● Allowed")
            }
        }
    }
}

/** "Add account": pick iCloud, Fastmail, ... or other; a choice prefills the account form. */
@Composable
private fun PresetChooserContent(state: ConnectorSetupState, collection: String, actions: SetupActions, step: OnboardingStep?) {
    val presets = state.collectionField(collection)?.collection?.presets.orEmpty()
    val noun = state.noun
    StepScaffold(
        step = step,
        backLabel = backTo(state),
        onBack = actions.onCloseChooser,
        kicker = "new ${noun.one}",
        title = noun.chooserTitle,
    ) {
        Hint(noun.chooserHint)
        Column {
            presets.forEach { p ->
                SelectRow(
                    p.title,
                    p.description.orEmpty().ifEmpty { "I fill in ${p.title}’s server settings" },
                    "→",
                    { actions.onChoosePreset(collection, p) },
                )
            }
            SelectRow(noun.otherTitle, noun.otherCaption, "→", { actions.onChoosePreset(collection, null) })
        }
    }
}

/** The admin-only instructions, folded away under a quiet "How to fix" toggle. */
@Composable
private fun HowToFix(detail: String) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Column {
        androidx.compose.material3.TextButton(
            onClick = { open = !open },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 12.dp),
        ) {
            Text("How to fix")
            androidx.compose.material3.Icon(
                if (open) androidx.compose.material.icons.Icons.Outlined.ExpandLess else androidx.compose.material.icons.Icons.Outlined.ExpandMore,
                contentDescription = null,
            )
        }
        if (open) Hint(detail)
    }
}

/** One account's fields in their own screen. Nothing is sent until the connector is saved. */
@Composable
private fun ItemEditorContent(state: ConnectorSetupState, editor: ItemEdit, actions: SetupActions, step: OnboardingStep?) {
    val form = state.itemForm(editor.collection) ?: ConfigForm(emptyList())
    val errors = state.editorErrors
    val field = state.collectionField(editor.collection)
    val title = field?.let { form.itemTitle(it, ItemValues(editor.id, editor.values)) }?.takeIf { !editor.isNew }
    val noun = state.noun
    var removing by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    StepScaffold(
        step = step,
        backLabel = backTo(state),
        onBack = actions.onCancelItem,
        kicker = if (editor.isNew) "new ${noun.one}" else "edit ${noun.one}",
        title = title ?: sentenceCase(if (editor.isNew) "new ${noun.one}" else noun.one),
        footer = {
            PrimaryButton("save ${noun.one}", actions.onSaveItem, Modifier.fillMaxWidth(), busy = state.saving)
            // Opened for this one item, removing it happens here too (and is saved at once).
            if (state.direct && !editor.isNew) {
                androidx.compose.material3.TextButton(onClick = { removing = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove ${noun.one}", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    ) {
        if (state.direct) state.problem?.let { ErrorBox(it.message()) }
        editor.hint?.let { InfoBox { Hint(it) } }
        form.fields.forEach { f ->
            FieldInput(f, editor.values, errors[f.key], actions.onItemValueChanged, actions.onItemListChanged)
        }
    }
    if (removing) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove ${title ?: noun.one}?") },
            text = { Text(noun.removeNowText) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    removing = false
                    actions.collections.onRemove(editor.collection, editor.id)
                }) { Text("Remove") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { removing = false }) { Text("Cancel") } },
        )
    }
}

/** Where back goes from an item: the connector, or (opened for one item) the screen that opened it. */
private fun backTo(state: ConnectorSetupState): String = if (state.direct && state.noun == ItemNoun.Server) "MCP servers" else state.title

private fun SetupProblem.message(): String = when (this) {
    SetupProblem.NoSealingKey -> "This connector hasn’t published its encryption key yet, so I can’t send the password safely. Try again in a minute."
    SetupProblem.NotAvailable -> "This server can’t store connector settings yet."
    SetupProblem.Unreachable -> "Could not reach the server."
    SetupProblem.Other -> "The server did not accept the settings."
}

// --- previews ---

private val PREVIEW_SCHEMA = Json.parseToJsonElement(
    """
    {"type":"object","required":["accounts"],"properties":{"accounts":{"type":"array","title":"Accounts","minItems":1,
      "x-item-title":"label","x-item-subtitle":"username",
      "x-presets":[{"title":"iCloud","description":"Use an app-specific password from account.apple.com","values":{"imapHost":"imap.mail.me.com","imapPort":993,"imapSecurity":"ssl"}}],
      "items":{"type":"object","required":["label","imapHost","username","password"],"properties":{
        "id":{"type":"string","readOnly":true},
        "label":{"type":"string","title":"Name","examples":["Work"]},
        "imapHost":{"type":"string","title":"IMAP host"},
        "imapPort":{"type":"integer","title":"IMAP port","default":993},
        "imapSecurity":{"type":"string","title":"IMAP security","enum":["ssl","starttls","none"],"x-enum-titles":["SSL","STARTTLS","None"],"default":"ssl"},
        "username":{"type":"string"},
        "password":{"type":"string","format":"password"},
        "folders":{"type":"array","items":{"type":"string"},"default":["INBOX"]}}}}}}
    """,
).jsonObject

private fun previewState(editing: Boolean = false, choosing: Boolean = false): ConnectorSetupState {
    val form = ConfigForms.parse(PREVIEW_SCHEMA)
    val itemForm = form.fields.first().collection!!.itemForm
    fun item(id: String, label: String, user: String) = ItemValues(
        id,
        itemForm.initialValues().withText("label", label).withText("username", user).withText("imapHost", "imap.mail.me.com"),
    )
    val values = FormValues().withItems("accounts", listOf(item("k3j9x2p1", "Work", "sam@work.example"), item("m4n8q7r2", "Personal", "sam@icloud.com")))
    return ConnectorSetupState(
        key = "mail", title = "Mail", proto = "imap · on your server", blurb = "I read your inbox and save drafts.",
        form = form, values = values, lastTest = TestLine("Work: 4,218 messages · Personal: password rejected", ok = false),
        editor = if (editing) ItemEdit("accounts", "k3j9x2p1", false, values.items("accounts").first().values, showErrors = true) else null,
        choosing = if (choosing) "accounts" else null,
    )
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780)
@Composable
private fun ConnectorSetupPreview() = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
        ConnectorSetupContent(previewState().copy(failedReason = "Personal: password rejected"), SetupActions())
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780)
@Composable
private fun AccountEditorPreview() = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
        ConnectorSetupContent(previewState(editing = true), SetupActions())
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780)
@Composable
private fun PresetChooserPreview() = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
        ConnectorSetupContent(previewState(choosing = true), SetupActions())
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780)
@Composable
private fun NotRunningPreview() = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
        ConnectorSetupContent(
            ConnectorSetupState(
                key = "dav", title = "Dav", proto = NOT_RUNNING, notRunning = true,
                blurb = "Dav hasn’t started on your server yet, so there’s nothing to set up. Once it’s running, tap Check again.",
                adminHint = "Whoever runs your server can start it: it’s the “dav” profile in the server’s compose setup.",
            ),
            SetupActions(),
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780)
@Composable
private fun PhoneConnectorPreview() = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
        ConnectorSetupContent(
            ConnectorSetupState(
                key = "phone-messages", title = "Messages", proto = "on this phone",
                blurb = PhoneConnectorKind.Messages.blurb, phone = PhoneConnectorKind.Messages,
            ),
            SetupActions(),
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
                    ConnectorItem(ConnectorRow.Server(ConnectorSummary(ConnectorId("dav"), null, emptyMap(), null)), saved = false, test = null),
                ),
            ),
            {}, {}, {}, {},
        )
    }
}
