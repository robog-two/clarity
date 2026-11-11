package net.robog.clarity.app.ui.constitution

import net.robog.clarity.app.ui.theme.Space
import net.robog.clarity.app.ui.components.ClarityScreen

import net.robog.clarity.app.ui.components.LoadingSpinner
import net.robog.clarity.app.ui.components.ScreenGutter
import net.robog.clarity.R
import net.robog.clarity.app.ui.components.Drawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.ConstitutionRepository
import net.robog.clarity.app.data.FeedRepository
import net.robog.clarity.app.data.feed.SyncSignal
import net.robog.clarity.app.ui.components.BackLink
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.ReadingText
import net.robog.clarity.app.ui.components.ScreenTitle
import net.robog.clarity.app.ui.components.SecondaryButton
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.Line

/** What the agent may do alone and what always waits for review, in the person's terms. */
data class AutonomySummary(val alone: List<String>, val afterReview: List<String>)

private fun ActionKind.phrase(): String = when (this) {
    ActionKind.SAVE_DRAFT -> "Draft anything"
    ActionKind.SEND_EMAIL -> "Send email as you"
    ActionKind.CREATE_EVENT -> "Block focus time"
    ActionKind.MOVE_EVENT -> "Move events"
    ActionKind.UPDATE_EVENT -> "Change events"
    ActionKind.CANCEL_EVENT -> "Cancel events"
    ActionKind.RESPOND_TO_INVITE -> "Answer invites"
    ActionKind.CREATE_REMINDER -> "Set reminders"
    ActionKind.SET_DO_NOT_DISTURB -> "Mute your phone"
    ActionKind.READ_DOCUMENT -> "Read your files"
    ActionKind.PAYMENT -> "Anything with money"
    ActionKind.EXTERNAL_TOOL -> "Use other tools"
    ActionKind.USE_CREDENTIAL -> "Use a saved login"
    ActionKind.TWO_FACTOR_CODE -> "Enter a login code"
}

/**
 * Kinds not listed ask first; payments never go out alone whatever the tiers say. Moving an event is
 * only listed as part of changing one: the agent no longer proposes it, and it still limits changing and cancelling.
 */
fun autonomySummary(constitution: Constitution): AutonomySummary {
    fun tier(kind: ActionKind) =
        if (kind in Constitution.ALWAYS_NEVER_ALONE) AutonomyTier.NEVER_ALONE else constitution.autonomyOf(kind)
    val (alone, review) = ActionKind.entries.filter { it != ActionKind.MOVE_EVENT }.partition { tier(it) == AutonomyTier.DO_THEN_TELL }
    return AutonomySummary(alone.map { it.phrase() }, review.map { it.phrase() })
}

data class ConstitutionUiState(
    val loading: Boolean = true,
    val constitution: Constitution? = null,
    val unavailable: Boolean = false,
    val error: String? = null,
)

/** "your constitution, version 3" */
fun versionKicker(version: Int): String = "your constitution, version $version"

@Inject
@ViewModelKey(ConstitutionViewModel::class)
@ContributesIntoMap(AppScope::class)
class ConstitutionViewModel(
    private val repository: ConstitutionRepository,
    feed: FeedRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ConstitutionUiState())
    val state: StateFlow<ConstitutionUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { reload() }
        viewModelScope.launch { feed.signals.filterIsInstance<SyncSignal.ConstitutionChanged>().collect { reload() } }
    }

    private suspend fun reload() {
        when (val r = repository.current()) {
            is ApiResult.Success -> _state.update { ConstitutionUiState(loading = false, constitution = r.value) }
            is ApiResult.Failure -> _state.update {
                it.copy(
                    loading = false,
                    unavailable = r.error is net.robog.clarity.app.data.ApiError.NotFound,
                    error = r.error.takeUnless { e -> e is net.robog.clarity.app.data.ApiError.NotFound }?.sentence(),
                )
            }
        }
    }
}

@Composable
fun ConstitutionScreen(onBack: () -> Unit, onAmend: () -> Unit, viewModel: ConstitutionViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ConstitutionContent(state, onBack, onAmend)
}

/** One numbered line; a hard line carries a lock and a "Hard line" label so it reads as non-negotiable. */
@Composable
private fun ConstitutionLineRow(number: Int, line: Line) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.Small).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(Space.Medium)) {
        Text(
            "$number",
            Modifier.width(20.dp).alignByBaseline(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        Column(Modifier.weight(1f).alignByBaseline(), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
            ReadingText(line.text)
            if (line.hard) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.Small)) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Hard line", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** Short, parallel phrases as quiet, non-interactive chips instead of a run-on sentence. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionChips(items: List<String>, icon: ImageVector) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.Small), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
        items.forEach { label ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    Modifier.heightIn(min = 32.dp).padding(horizontal = Space.Medium, vertical = Space.Small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.Small),
                ) {
                    Icon(icon, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun ConstitutionContent(state: ConstitutionUiState, onBack: () -> Unit, onAmend: () -> Unit) {
    val c = state.constitution
    ClarityScreen(
        title = "Constitution",
        kicker = c?.let { versionKicker(it.version) },
        backLabel = "Settings",
        onBack = onBack,
        art = { Drawing(R.drawable.drawing_quill_scroll, Modifier.size(88.dp)) },
        phase = state.loading,
    ) { loading ->
        state.error?.let { ErrorBox(it) }
        when {
            loading -> LoadingSpinner()
            c == null -> Hint(if (state.unavailable) "No constitution on this server yet." else "Can’t load it.")
            else -> {
                Kicker("lines", heading = true)
                c.lines.forEachIndexed { i, line -> ConstitutionLineRow(i + 1, line) }
                val summary = autonomySummary(c)
                if (summary.alone.isNotEmpty()) {
                    Kicker("does alone", Modifier.padding(top = Space.Medium), heading = true)
                    PermissionChips(summary.alone, Icons.Outlined.Check)
                }
                if (summary.afterReview.isNotEmpty()) {
                    Kicker("always after review", Modifier.padding(top = Space.Medium), heading = true)
                    PermissionChips(summary.afterReview, Icons.Outlined.Visibility)
                }
                SecondaryButton("amend by talking", onAmend, Modifier.fillMaxWidth().padding(top = Space.Medium))
            }
        }
    }
}
