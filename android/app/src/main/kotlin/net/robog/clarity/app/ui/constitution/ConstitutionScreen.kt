package net.robog.clarity.app.ui.constitution

import net.robog.clarity.app.ui.components.ScreenGutter
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
    ActionKind.SEND_EMAIL -> "Send email as me"
    ActionKind.CREATE_EVENT -> "Block focus time"
    ActionKind.MOVE_EVENT -> "Move events"
    ActionKind.RESPOND_TO_INVITE -> "Answer invites"
    ActionKind.CREATE_REMINDER -> "Set reminders"
    ActionKind.SET_DO_NOT_DISTURB -> "Mute my phone"
    ActionKind.READ_DOCUMENT -> "Read my files"
    ActionKind.PAYMENT -> "Anything with money"
    ActionKind.EXTERNAL_TOOL -> "Use other tools"
    ActionKind.USE_CREDENTIAL -> "Use a saved login"
    ActionKind.TWO_FACTOR_CODE -> "Enter a login code"
}

/** Kinds not listed ask first; payments never go out alone whatever the tiers say. */
fun autonomySummary(constitution: Constitution): AutonomySummary {
    fun tier(kind: ActionKind) =
        if (kind in Constitution.ALWAYS_NEVER_ALONE) AutonomyTier.NEVER_ALONE else constitution.autonomy[kind] ?: AutonomyTier.ASK_FIRST
    val (alone, review) = ActionKind.entries.partition { tier(it) == AutonomyTier.DO_THEN_TELL }
    return AutonomySummary(alone.map { it.phrase() }, review.map { it.phrase() })
}

data class ConstitutionUiState(
    val loading: Boolean = true,
    val constitution: Constitution? = null,
    val unavailable: Boolean = false,
    val error: String? = null,
)

/** "your constitution · v3" */
fun versionKicker(version: Int): String = "your constitution · v$version"

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
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "$number",
            Modifier.width(20.dp).alignByBaseline(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        Column(Modifier.weight(1f).alignByBaseline(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ReadingText(line.text)
            if (line.hard) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { label ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    Modifier.heightIn(min = 32.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackLink("Settings", onBack)
        val c = state.constitution
        if (c != null) Kicker(versionKicker(c.version))
        ScreenTitle("How I want to work")
        state.error?.let { ErrorBox(it) }
        when {
            state.loading -> Hint("Loading…")
            c == null -> Hint(if (state.unavailable) "Your server doesn’t have a constitution yet." else "Can’t load it right now.")
            else -> {
                Kicker("lines")
                c.lines.forEachIndexed { i, line -> ConstitutionLineRow(i + 1, line) }
                val summary = autonomySummary(c)
                if (summary.alone.isNotEmpty()) {
                    Kicker("may do alone", Modifier.padding(top = 12.dp))
                    PermissionChips(summary.alone, Icons.Outlined.Check)
                }
                if (summary.afterReview.isNotEmpty()) {
                    Kicker("always after review", Modifier.padding(top = 12.dp))
                    PermissionChips(summary.afterReview, Icons.Outlined.Visibility)
                }
                SecondaryButton("amend by talking", onAmend, Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        }
    }
}
