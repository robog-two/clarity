package net.robog.clarity.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import net.robog.clarity.app.connector.ConfigForm
import net.robog.clarity.app.connector.ConfigForms
import net.robog.clarity.app.connector.FieldError
import net.robog.clarity.app.connector.PhoneConnectorKind
import net.robog.clarity.app.connector.PhonePermissions
import net.robog.clarity.app.connector.storedFormValues
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ConnectorsRepository
import net.robog.clarity.app.data.SaveConnectorOutcome

enum class SetupProblem { NoSealingKey, NotAvailable, Unreachable, Other }

data class ConnectorSetupState(
    val key: String = "",
    val title: String = "",
    val proto: String = "",
    val blurb: String = "",
    val form: ConfigForm = ConfigForm(emptyList()),
    val values: Map<String, String> = emptyMap(),
    /** Errors are only shown once the person tried to save. */
    val showErrors: Boolean = false,
    /** The connector's last test failed: the person re-enters the password. */
    val failedReason: String? = null,
    val phone: PhoneConnectorKind? = null,
    val allowed: Boolean = false,
    val saving: Boolean = false,
    val problem: SetupProblem? = null,
    /** Set when saved; the screen leaves and calls [ConnectorSetupViewModel.consumeSaved]. */
    val saved: Boolean = false,
) {
    val errors: Map<String, FieldError> get() = if (showErrors) form.errors(values) else emptyMap()
    val canSave: Boolean
        get() = !saving && if (phone != null) allowed else form.errors(values).isEmpty()
    val saveLabel: String get() = if (failedReason != null) "save and retest" else "save"
}

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

    fun open(row: ConnectorRow, failedReason: String?) {
        current = row
        when (row) {
            is ConnectorRow.Phone -> _state.value = ConnectorSetupState(
                key = row.key, title = row.title, proto = row.proto, blurb = row.kind.blurb,
                phone = row.kind, allowed = phone.isGranted(row.kind), failedReason = failedReason,
            )
            is ConnectorRow.Server -> {
                val manifest = row.summary.manifest
                val form = manifest?.let { ConfigForms.parse(it.configSchema) } ?: ConfigForm(emptyList())
                val description = (manifest?.configSchema?.get("description") as? JsonPrimitive)?.content
                _state.value = ConnectorSetupState(
                    key = row.key, title = row.title, proto = row.proto,
                    blurb = description ?: "Connect ${row.title.lowercase()} so I can read it.",
                    form = form, values = form.initialValues(), failedReason = failedReason,
                )
                // Prefill what the server already has (secrets come back sealed, so they stay empty).
                viewModelScope.launch {
                    connectors.storedConfig(row.summary.id)?.let { stored ->
                        _state.update { s ->
                            if (s.key != row.key) s else s.copy(values = s.values + storedFormValues(form, stored.values))
                        }
                    }
                }
            }
        }
    }

    fun onValueChanged(key: String, value: String) =
        _state.update { it.copy(values = it.values + (key to value), problem = null) }

    fun onPermissionResult(granted: Boolean) = _state.update { it.copy(allowed = granted) }

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
