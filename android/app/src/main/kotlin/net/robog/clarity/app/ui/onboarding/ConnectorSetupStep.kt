package net.robog.clarity.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import net.robog.clarity.app.connector.ConfigForm
import net.robog.clarity.app.connector.ConfigForms
import net.robog.clarity.app.connector.FieldError
import net.robog.clarity.app.connector.FormField
import net.robog.clarity.app.connector.FormValues
import net.robog.clarity.app.connector.ItemIds
import net.robog.clarity.app.connector.ItemPreset
import net.robog.clarity.app.connector.ItemValues
import net.robog.clarity.app.connector.PhoneConnectorKind
import net.robog.clarity.app.connector.PresetEdit
import net.robog.clarity.app.connector.PhonePermissions
import net.robog.clarity.app.connector.accountTestLine
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.ConnectorsRepository
import net.robog.clarity.app.data.SaveConnectorOutcome
import net.robog.clarity.app.ui.mcp.MCP_HOST
import net.robog.clarity.app.ui.mcp.isMcpHost
import net.robog.clarity.shared.connector.PresetInputError

/** The value a choice input's menu sends for "Other". */
const val OTHER_CHOICE = "\u0000other"

enum class SetupProblem { NoSealingKey, NotAvailable, Unreachable, Other }

/** The last test text of a connector, and whether it passed. Account lines are cut out of it. */
data class TestLine(val text: String, val ok: Boolean)

/** One collection item being added or edited, in its own screen. */
data class ItemEdit(
    val collection: String,
    val id: String,
    val isNew: Boolean,
    val values: FormValues,
    /** The preset's description ("Use an app-specific password from account.apple.com"). */
    val hint: String? = null,
    /** Errors are only shown once the person tried to save the item. */
    val showErrors: Boolean = false,
    /** Opened on its preset's short form; null: the full form only. */
    val simple: PresetEdit? = null,
)

/**
 * Opened for one item rather than the whole connector (from Settings → MCP servers): straight into
 * adding ([add], with an optional [preset] title and [label]) or editing [itemId]. Saving the item
 * saves the connector, and leaving the item leaves the screen.
 */
data class DirectEdit(
    val add: Boolean = false,
    val preset: String? = null,
    val label: String? = null,
    val itemId: String? = null,
)

data class ConnectorSetupState(
    val key: String = "",
    val title: String = "",
    val proto: String = "",
    val blurb: String = "",
    val form: ConfigForm = ConfigForm(emptyList()),
    val values: FormValues = FormValues(),
    /** Errors are only shown once the person tried to save. */
    val showErrors: Boolean = false,
    /** The connector's last test failed: the person re-enters the password. */
    val failedReason: String? = null,
    val lastTest: TestLine? = null,
    val phone: PhoneConnectorKind? = null,
    val allowed: Boolean = false,
    val saving: Boolean = false,
    val problem: SetupProblem? = null,
    /** Set when saved; the screen leaves and calls [ConnectorSetupViewModel.consumeSaved]. */
    val saved: Boolean = false,
    /** Looking the connector up (opened from settings). */
    val loading: Boolean = false,
    /** A server connector that has no manifest: its container is not running. */
    val notRunning: Boolean = false,
    /** For a connector that is not running: how the server's admin starts it (shown behind "How to fix"). */
    val adminHint: String? = null,
    /** The connector is not in the server's list at all. */
    val missing: Boolean = false,
    val editor: ItemEdit? = null,
    /** The collection whose presets are being offered, before the item form opens. */
    val choosing: String? = null,
    /** Opened for one item ([DirectEdit]): the item's screen is the whole screen. */
    val direct: Boolean = false,
) {
    /** What one item of this connector is called on screen. */
    val noun: ItemNoun get() = itemNounFor(key)

    val errors: Map<String, FieldError> get() = if (showErrors) form.errors(values) else emptyMap()
    val editorErrors: Map<String, FieldError>
        get() = editor?.takeIf { it.showErrors }?.let { e -> itemForm(e.collection)?.errors(e.values) } ?: emptyMap()

    /**
     * Problems with the short form's answers. An address that is not https says so at once; the
     * rest once the person tried to save, so a half-typed address is not called wrong.
     */
    val presetErrors: Map<String, PresetInputError>
        get() {
            val e = editor ?: return emptyMap()
            val all = e.simple?.errors(e.values).orEmpty()
            return if (e.showErrors) all else all.filterValues { it == PresetInputError.NotHttps || it == PresetInputError.WrongPort }
        }
    val canSave: Boolean
        get() = !saving && !notRunning && if (phone != null) allowed else form.errors(values).isEmpty()
    val saveLabel: String get() = if (failedReason != null) "save and retest" else "save"

    fun collectionField(collection: String): FormField? = form.fields.firstOrNull { it.key == collection }
    fun itemForm(collection: String): ConfigForm? = collectionField(collection)?.collection?.itemForm

    /** What the last test said about the account called [label], if it names it. */
    fun testLineFor(label: String): TestLine? =
        lastTest?.let { t -> accountTestLine(t.text, label)?.let { TestLine(it, t.ok || accountLineOk(it)) } }
}

/** The screen of a server connector whose container has not started: it says so, and how to start it. */
fun notRunningState(key: String, title: String): ConnectorSetupState = ConnectorSetupState(
    key = key, title = title, proto = NOT_RUNNING, notRunning = true,
    blurb = "$title isn’t running on your server. Start it and check again.",
    adminHint = if (key == MCP_HOST) {
        "It starts with your server. Otherwise ask your admin to update Clarity."
    } else {
        "Ask your admin to start it. Profile “$key” in the compose setup."
    },
)

/** Pure: whether one account's line of a failed test still reads as fine ("Work · 4,218 messages"). */
fun accountLineOk(line: String): Boolean =
    !Regex("fail|reject|error|denied|invalid|couldn|can’t|can't|cannot|timed out|unreachable|refused", RegexOption.IGNORE_CASE).containsMatchIn(line)

/** The form of one connector, generated from its manifest's `configSchema` (or the phone permission step). */
@Inject
@ViewModelKey(ConnectorSetupViewModel::class)
@ContributesIntoMap(AppScope::class)
class ConnectorSetupViewModel(
    private val connectors: ConnectorsRepository,
    private val phone: PhonePermissions,
) : ViewModel() {
    private val _state = MutableStateFlow(ConnectorSetupState())
    val state: StateFlow<ConnectorSetupState> = _state.asStateFlow()

    private var current: ConnectorRow? = null
    private var random: Random = Random.Default

    /** For tests: a fixed source of item ids. */
    internal fun useRandom(r: Random) {
        random = r
    }

    /** Set by [openById] when the screen is opened for one item. */
    private var direct: DirectEdit? = null

    fun open(row: ConnectorRow, failedReason: String?, test: TestState? = null) {
        current = row
        when (row) {
            is ConnectorRow.Phone -> _state.value = ConnectorSetupState(
                key = row.key, title = row.title, proto = row.proto, blurb = row.kind.blurb,
                phone = row.kind, allowed = phone.isGranted(row.kind), failedReason = failedReason,
            )
            is ConnectorRow.Server -> {
                val manifest = row.summary.manifest
                if (manifest == null) {
                    // The container is not running, so there is no schema: say so instead of an empty form.
                    _state.value = notRunningState(row.key, row.title)
                    return
                }
                val form = ConfigForms.parse(manifest.configSchema)
                val description = (manifest.configSchema["description"] as? JsonPrimitive)?.content
                val last = when (test) {
                    is TestState.Ok -> test.summary?.let { TestLine(it, true) }
                    is TestState.Failed -> TestLine(test.reason, false)
                    else -> null
                } ?: row.summary.lastTest?.let { TestLine(it.message, it.ok) }
                _state.value = ConnectorSetupState(
                    key = row.key, title = row.title, proto = row.proto,
                    blurb = description ?: "Connect ${row.title}.",
                    form = form, values = form.initialValues(), failedReason = failedReason, lastTest = last,
                )
                // Prefill what the server already has (secrets come back sealed and are kept as they are).
                viewModelScope.launch {
                    connectors.storedConfig(row.summary.id)?.let { stored ->
                        _state.update { s ->
                            if (s.key != row.key) s else s.copy(values = s.values.overlay(form.valuesFromStored(stored.values)))
                        }
                    }
                    // Only now are the stored items known: the one to edit, and the ids already taken.
                    direct?.let(::begin)
                }
            }
        }
    }

    /** Opened from settings, where only the connector's id is known; [edit] opens one item directly. */
    fun openById(id: String, edit: DirectEdit? = null) {
        direct = edit
        _state.value = ConnectorSetupState(key = id, title = connectorDisplayName(id), loading = true)
        viewModelScope.launch {
            when (val r = connectors.list()) {
                is ApiResult.Success -> {
                    val summary = r.value.firstOrNull { it.id.value == id }
                        ?: r.value.takeIf { id == MCP_HOST }?.firstOrNull(::isMcpHost)
                    if (summary == null) {
                        _state.value = ConnectorSetupState(key = id, title = connectorDisplayName(id), missing = true)
                    } else {
                        open(ConnectorRow.Server(summary), failedReason = null)
                    }
                }
                is ApiResult.Failure -> _state.value = ConnectorSetupState(
                    key = id, title = connectorDisplayName(id),
                    problem = if (r.error is ApiError.NotFound) SetupProblem.NotAvailable else SetupProblem.Unreachable,
                )
            }
        }
    }

    /** "check again" on the not-running screen. */
    fun recheck() {
        val id = (current as? ConnectorRow.Server)?.summary?.id?.value ?: return
        openById(id, direct)
    }

    /** Opens the item [edit] asks for, in the connector's first collection. */
    private fun begin(edit: DirectEdit) {
        val collection = _state.value.form.fields.firstOrNull { it.collection != null }?.key ?: return
        _state.update { it.copy(direct = true) }
        if (edit.itemId != null) {
            editItem(collection, edit.itemId)
            return
        }
        val preset = edit.preset?.let { form(collection)?.preset(it) }
        when {
            preset != null -> beginItem(collection, preset)
            // A suggestion without a preset (or with one this connector does not offer): an empty form with its name.
            edit.label != null || edit.preset != null -> {
                beginItem(collection, null)
                edit.label?.let { label -> onItemValueChanged(form(collection)?.titleKey ?: return@let, label) }
            }
            else -> startAdd(collection)
        }
    }

    private fun form(collection: String) = _state.value.collectionField(collection)?.collection

    fun onValueChanged(key: String, value: String) =
        _state.update { it.copy(values = it.values.withText(key, value), problem = null) }

    fun onListChanged(key: String, value: List<String>) =
        _state.update { it.copy(values = it.values.withList(key, value), problem = null) }

    fun onPermissionResult(granted: Boolean) = _state.update { it.copy(allowed = granted) }

    // --- collections (one card per account) ---

    /** "Add account": the presets first when the collection has any, else straight to the empty form. */
    fun startAdd(collection: String) {
        val spec = _state.value.collectionField(collection)?.collection ?: return
        if (spec.presets.isEmpty()) beginItem(collection, null) else _state.update { it.copy(choosing = collection) }
    }

    /** A preset was chosen, or null for "other". */
    fun choosePreset(collection: String, preset: ItemPreset?) = beginItem(collection, preset)

    fun closeChooser() = _state.update { it.copy(choosing = null) }

    private fun beginItem(collection: String, preset: ItemPreset?) = _state.update { s ->
        val form = s.itemForm(collection) ?: return@update s
        val taken = s.values.items(collection).map { it.id }.toSet()
        val initial = form.initialValues()
        val values = if (preset != null) initial.overlay(preset.values) else initial
        // A preset with a short form opens on its questions, their defaults already written in.
        val (simple, start) = preset?.takeIf { it.simple != null }?.let { PresetEdit.start(it, values) } ?: (null to values)
        s.copy(
            choosing = null,
            editor = ItemEdit(
                collection = collection,
                id = ItemIds.generate(taken, random),
                isNew = true,
                values = start,
                hint = preset?.description,
                simple = simple,
            ),
        )
    }

    fun editItem(collection: String, id: String) = _state.update { s ->
        val item = s.values.items(collection).firstOrNull { it.id == id } ?: return@update s
        // Set up from a preset with a short form: reopen it. Anything else (or saved before presets were
        // recorded) opens the full form, as it always did.
        val preset = s.collectionField(collection)?.collection?.presetOf(item.values)
        s.copy(
            editor = ItemEdit(
                collection, id, isNew = false, values = item.values,
                hint = preset?.description, simple = preset?.let { PresetEdit.reopen(it, item.values) },
            ),
        )
    }

    // --- the short form of a preset ---

    /** A short-form answer typed or picked. [OTHER_CHOICE] picks "Other" for a choice input. */
    fun onPresetAnswer(inputId: String, value: String) = _state.update { s ->
        val e = s.editor ?: return@update s
        val simple = e.simple ?: return@update s
        val input = simple.form.inputs.firstOrNull { it.id == inputId } ?: return@update s
        val (next, values) = when {
            value == OTHER_CHOICE -> simple.chooseOther(input, true, e.values)
            input.id in simple.others && input.choices.any { it.value == value } ->
                simple.copy(others = simple.others - input.id).answer(input, value, e.values)
            else -> simple.answer(input, value, e.values)
        }
        s.copy(editor = e.copy(values = values, simple = next))
    }

    /** "Advanced settings": shows or hides every field of the item under the questions. */
    fun toggleAdvanced() = _state.update { s ->
        val e = s.editor ?: return@update s
        s.copy(editor = e.copy(simple = e.simple?.let { it.copy(advanced = !it.advanced) }))
    }

    fun onItemValueChanged(key: String, value: String) =
        _state.update { s -> s.copy(editor = s.editor?.let { it.copy(values = it.values.withText(key, value)) }) }

    fun onItemListChanged(key: String, value: List<String>) =
        _state.update { s -> s.copy(editor = s.editor?.let { it.copy(values = it.values.withList(key, value)) }) }

    /**
     * Keeps the item in the form when it is complete; the config is only sent by [save]. Opened for
     * one item, the item's screen stays up while the whole config is saved.
     */
    fun saveItem() {
        val s = _state.value
        val e = s.editor ?: return
        val form = s.itemForm(e.collection) ?: return
        val fieldErrors = form.errors(e.values)
        if (fieldErrors.isNotEmpty() || e.simple?.errors(e.values).orEmpty().isNotEmpty()) {
            // A field outside the questions needs fixing: open the full form so it can be seen.
            val hiddenProblem = e.simple != null && (fieldErrors.keys - e.simple.form.targets).isNotEmpty()
            _state.update { it.copy(editor = e.copy(showErrors = true, simple = e.simple?.let { p -> if (hiddenProblem) p.copy(advanced = true) else p })) }
            return
        }
        val values = s.values.withItem(e.collection, ItemValues(e.id, e.values))
        _state.update { it.copy(values = values, editor = if (it.direct) e else null, problem = null) }
        if (s.direct) save()
    }

    fun cancelItem() = _state.update { it.copy(editor = null) }

    fun removeItem(collection: String, id: String) {
        _state.update { it.copy(values = it.values.withoutItem(collection, id), problem = null) }
        if (_state.value.direct) save()
    }

    fun save() {
        val s = _state.value
        val row = current ?: return
        if (s.saving) return
        if (!s.canSave) {
            _state.update { it.copy(showErrors = true) }
            return
        }
        when (row) {
            // Nothing is sent for phone connectors yet: their grant step belongs to the phone connector workstream.
            is ConnectorRow.Phone -> _state.update { it.copy(saved = true) }
            is ConnectorRow.Server -> {
                _state.update { it.copy(saving = true, problem = null) }
                viewModelScope.launch {
                    val problem = when (val r = connectors.save(row.summary, s.values)) {
                        SaveConnectorOutcome.Saved -> null
                        SaveConnectorOutcome.NoSealingKey -> SetupProblem.NoSealingKey
                        is SaveConnectorOutcome.Failed -> when (r.error) {
                            is ApiError.NotFound -> SetupProblem.NotAvailable
                            is ApiError.Unreachable -> SetupProblem.Unreachable
                            else -> SetupProblem.Other
                        }
                    }
                    _state.update { it.copy(saving = false, problem = problem, saved = problem == null) }
                }
            }
        }
    }

    /** True if the connector had a failed test before this save (so it should be tested again). */
    fun wasFailed(): Boolean = _state.value.failedReason != null

    fun consumeSaved() = _state.update { it.copy(saved = false) }
}
