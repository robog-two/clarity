package net.robog.clarity.app.ui.constitution

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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

/** What the agent may do alone and what always waits for review, in the person's terms. */
data class AutonomySummary(val alone: List<String>, val afterReview: List<String>)

private fun ActionKind.phrase(): String = when (this) {
    ActionKind.SAVE_DRAFT -> "Draft anything"
    ActionKind.SEND_EMAIL -> "Sending as me"
    ActionKind.CREATE_EVENT -> "Block focus time"
    ActionKind.MOVE_EVENT -> "Moving events"
    ActionKind.RESPOND_TO_INVITE -> "Answering invites"
    ActionKind.CREATE_REMINDER -> "Set reminders"
    ActionKind.SET_DO_NOT_DISTURB -> "Muting my phone"
    ActionKind.READ_DOCUMENT -> "Reading my files"
    ActionKind.PAYMENT -> "Anything with money"
    ActionKind.EXTERNAL_TOOL -> "Using other tools"
    ActionKind.USE_CREDENTIAL -> "Using a saved login"
    ActionKind.TWO_FACTOR_CODE -> "Entering a login code"
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

@Composable
fun ConstitutionContent(state: ConstitutionUiState, onBack: () -> Unit, onAmend: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
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
                c.lines.forEachIndexed { i, line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${i + 1}", Modifier.width(16.dp), style = MaterialTheme.typography.labelLarge)
                        ReadingText(line.text, Modifier.weight(1f))
                    }
                }
                val summary = autonomySummary(c)
                if (summary.alone.isNotEmpty()) {
                    Kicker("may do alone", Modifier.padding(top = 6.dp))
                    ReadingText(summary.alone.joinToString(". ") + ".")
                }
                if (summary.afterReview.isNotEmpty()) {
                    Kicker("always after review", Modifier.padding(top = 6.dp))
                    ReadingText(summary.afterReview.joinToString(". ") + ".")
                }
                SecondaryButton("amend by talking", onAmend, Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        }
    }
}
