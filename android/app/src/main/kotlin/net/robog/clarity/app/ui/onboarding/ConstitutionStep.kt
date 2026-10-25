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
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.ConstitutionRepository
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.Section

/** One entry of the reviewed document: a line and, when the person gave one, the reason. */
data class ConstitutionEntry(val text: String, val because: String? = null)

data class ConstitutionSectionView(val title: String, val entries: List<ConstitutionEntry>)

private val sectionOrder = listOf(
    Section.VALUES to "what I value",
    Section.HARD_LINES to "hard lines · never crossed",
    Section.AUTONOMY to "how much I do alone",
    Section.COLLISIONS to "when things collide",
    Section.PEOPLE to "who gets through",
    Section.REPAIR to "when I get it wrong",
)

/** "do it, then tell me: holds on calendar, drafts." */
fun tierLabel(tier: AutonomyTier): String = when (tier) {
    AutonomyTier.DO_THEN_TELL -> "do it, then tell me"
    AutonomyTier.ASK_FIRST -> "ask me first"
    AutonomyTier.NEVER_ALONE -> "never alone"
}

fun actionKindLabel(kind: ActionKind): String = kind.name.lowercase().replace('_', ' ')

/**
 * The constitution grouped like the prototype's review screen. Sections without content are
 * left out; the autonomy section also lists the per-action tiers.
 */
fun constitutionSections(c: Constitution): List<ConstitutionSectionView> {
    val tiers = AutonomyTier.entries.mapNotNull { tier ->
        val kinds = c.autonomy.filterValues { it == tier }.keys.sortedBy { it.ordinal }
        if (kinds.isEmpty()) null else ConstitutionEntry("${tierLabel(tier)}: ${kinds.joinToString(", ") { actionKindLabel(it) }}.")
    }
    return sectionOrder.mapNotNull { (section, title) ->
        val lines = c.lines.filter { it.section == section }.map { ConstitutionEntry(it.text, it.because) }
        val entries = if (section == Section.AUTONOMY) lines + tiers else lines
        if (entries.isEmpty()) null else ConstitutionSectionView(title, entries)
    }
}

sealed interface ConstitutionUi {
    data object Loading : ConstitutionUi
    data class Loaded(val version: Int, val sections: List<ConstitutionSectionView>) : ConstitutionUi
    data object Unavailable : ConstitutionUi
    data class Failed(val error: ApiError) : ConstitutionUi
}

/** Step 3: read the constitution the interview produced. */
@Inject
@ViewModelKey(ConstitutionStepViewModel::class)
@ContributesIntoMap(AppScope::class)
class ConstitutionStepViewModel(private val constitution: ConstitutionRepository) : ViewModel() {
    private val _state = MutableStateFlow<ConstitutionUi>(ConstitutionUi.Loading)
    val state: StateFlow<ConstitutionUi> = _state.asStateFlow()

    fun load() {
        _state.value = ConstitutionUi.Loading
        viewModelScope.launch {
            _state.update {
                when (val r = constitution.current()) {
                    is ApiResult.Success -> ConstitutionUi.Loaded(r.value.version, constitutionSections(r.value))
                    is ApiResult.Failure ->
                        if (r.error is ApiError.NotFound) ConstitutionUi.Unavailable else ConstitutionUi.Failed(r.error)
                }
            }
        }
    }
}
