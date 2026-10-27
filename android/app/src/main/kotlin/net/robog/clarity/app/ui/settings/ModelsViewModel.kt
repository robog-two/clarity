package net.robog.clarity.app.ui.settings

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
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.ModelsRepository
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.shared.api.FieldError
import net.robog.clarity.shared.session.ModelRoles
import net.robog.clarity.shared.session.ModelSettingsView
import net.robog.clarity.shared.session.RoleModelOverride
import net.robog.clarity.shared.session.RoleModelView
import net.robog.clarity.shared.session.SettingSource

/** One role in the "Models" section. */
data class RoleModelRow(
    val role: String,
    val title: String,
    val caption: String,
    /** An OpenRouter model id (`vendor/model`). */
    val model: String,
    val effort: String,
    /** Nothing set in the app: the server's defaults. */
    val isDefault: Boolean,
    val suggestions: List<String>,
    /** A plain line under the role (the lead's "next turn"). */
    val note: String? = null,
    /** Why the last change was not saved. */
    val error: String? = null,
)

data class ModelsUiState(
    val loading: Boolean = true,
    /** False on a server without the models route: the section is hidden. */
    val available: Boolean = true,
    val rows: List<RoleModelRow> = emptyList(),
    val efforts: List<String> = ModelRoles.EFFORTS,
    val message: String? = null,
)

fun roleTitle(role: String): String = when (role) {
    ModelRoles.LEAD -> "Lead"
    ModelRoles.HELPER -> "Helpers"
    ModelRoles.GUARD -> "Guard"
    ModelRoles.TRIAGE -> "Browser check"
    else -> role
}

fun roleCaption(role: String): String = when (role) {
    ModelRoles.LEAD -> "Plans your day and talks with you"
    ModelRoles.HELPER -> "Do longer tasks for the lead"
    ModelRoles.GUARD -> "Checks each action against your rules"
    ModelRoles.TRIAGE -> "Looks at each step a helper takes on the web"
    else -> ""
}

fun effortLabel(effort: String): String = when (effort) {
    "xhigh" -> "Extra high"
    else -> effort.replaceFirstChar { it.titlecase() }
}

/**
 * Model ids to offer (OpenRouter's names: every model runs there); any other id can be typed. The
 * server's defaults first, then Claude.
 */
val MODEL_SUGGESTIONS: List<String> = listOf(
    "xiaomi/mimo-v2.6-pro",
    "deepseek/deepseek-v4.1-flash-1017",
    "anthropic/claude-haiku-5.5",
    "anthropic/claude-sonnet-5.5",
    "anthropic/claude-opus-5.5",
)

/** Pure: what the app has set for [view] now (only the overridden fields). Never a provider: there is only OpenRouter. */
fun overrideOf(view: RoleModelView): RoleModelOverride {
    fun <T> set(field: String, value: T): T? = value.takeIf { view.sources[field] == SettingSource.OVERRIDE }
    val e = view.effective
    return RoleModelOverride(
        model = set("model", e.model),
        effort = set("effort", e.effort),
        maxTokens = set("maxTokens", e.maxTokens),
        cacheTtl = set("cacheTtl", e.cacheTtl),
        compaction = set("compaction", e.compaction),
        compactAtTokens = set("compactAtTokens", e.compactAtTokens),
    )
}

/**
 * Pure: the overrides after a change on the screen. A value equal to the server's default is left
 * unset, so the role keeps following the default.
 */
fun changedOverride(view: RoleModelView, model: String? = null, effort: String? = null): RoleModelOverride {
    val d = view.default
    val current = overrideOf(view)
    val nextModel = model ?: view.effective.model
    val nextEffort = effort ?: view.effective.effort
    return current.copy(
        model = nextModel.takeIf { it != d.model },
        effort = nextEffort.takeIf { it != d.effort },
    )
}

/** Under the lead: when a change applies, and what happens to a long thread. */
const val LEAD_NOTE = "Changes apply from its next turn. It summarizes its own thread when the thread gets long."

/** Pure: the rows the section shows. */
fun modelRows(view: ModelSettingsView, errors: Map<String, String> = emptyMap()): List<RoleModelRow> = view.roles.map { r ->
    RoleModelRow(
        role = r.role,
        title = roleTitle(r.role),
        caption = roleCaption(r.role),
        model = r.effective.model,
        effort = r.effective.effort,
        isDefault = r.sources.filterKeys { it != "provider" }.values.none { it == SettingSource.OVERRIDE },
        suggestions = MODEL_SUGGESTIONS,
        note = if (r.role == ModelRoles.LEAD) LEAD_NOTE else null,
        error = errors[r.role],
    )
}

/** Pure: the person-facing reason a change was refused (the server names each field). */
fun refusal(error: ApiError): String {
    val problem = (error as? ApiError.Http)?.problem
    val fields: List<FieldError> = problem?.errors.orEmpty()
    return if (fields.isNotEmpty()) fields.joinToString(" ") { it.message.replaceFirstChar { c -> c.titlecase() }.trimEnd('.') + "." } else error.sentence()
}

/** "Models" in Settings: which model each role runs on, saved on the server and in effect at once. */
@Inject
@ViewModelKey(ModelsViewModel::class)
@ContributesIntoMap(AppScope::class)
class ModelsViewModel(private val models: ModelsRepository) : ViewModel() {
    private val _state = MutableStateFlow(ModelsUiState())
    val state: StateFlow<ModelsUiState> = _state.asStateFlow()

    /** What the server last said. */
    private var view: ModelSettingsView? = null
    private val errors = mutableMapOf<String, String>()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        when (val r = models.load()) {
            is ApiResult.Success -> show(r.value)
            is ApiResult.Failure -> _state.update {
                it.copy(loading = false, available = r.error !is ApiError.NotFound, message = r.error.takeUnless { e -> e is ApiError.NotFound }?.sentence())
            }
        }
    }

    private fun show(next: ModelSettingsView) {
        view = next
        _state.update {
            it.copy(
                loading = false, available = true, message = null,
                rows = modelRows(next, errors), efforts = next.efforts,
            )
        }
    }

    fun setModel(role: String, model: String) = change(role) { changedOverride(it, model = model.trim()) }
    fun setEffort(role: String, effort: String) = change(role) { changedOverride(it, effort = effort) }

    fun reset(role: String) {
        errors.remove(role)
        viewModelScope.launch { finish(role, models.reset(role)) }
    }

    private fun change(role: String, update: (RoleModelView) -> RoleModelOverride) {
        val current = view ?: return
        val roleView = current.roles.firstOrNull { it.role == role } ?: return
        val next = update(roleView)
        if (next == overrideOf(roleView)) return
        errors.remove(role)
        // Shown at once; the server's answer replaces it (or puts it back with the reason).
        _state.update { s ->
            s.copy(rows = s.rows.map { row ->
                if (row.role != role) row else row.copy(
                    model = next.model ?: roleView.default.model,
                    effort = next.effort ?: roleView.default.effort,
                    error = null,
                )
            })
        }
        viewModelScope.launch { finish(role, models.save(role, next)) }
    }

    private fun finish(role: String, result: ApiResult<ModelSettingsView>) {
        when (result) {
            is ApiResult.Success -> show(result.value)
            is ApiResult.Failure -> {
                errors[role] = refusal(result.error)
                view?.let(::show)
            }
        }
    }
}
