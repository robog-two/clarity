package net.robog.clarity.app.ui.held

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
import kotlinx.datetime.TimeZone
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.FeedRepository
import net.robog.clarity.app.data.HeldRepository
import net.robog.clarity.app.ui.call.formatClock
import net.robog.clarity.app.ui.today.heldLine
import net.robog.clarity.shared.governance.ActivityEntry
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId

data class HeldRow(
    val id: HeldActionId,
    val whenText: String,
    val title: String,
    val status: String,
    val waiting: Boolean,
    /** Only a yes signed on this phone approves it (opens the review screen, not the talk-through). */
    val phone: Boolean = false,
)

data class AllowedRow(val title: String, val whenText: String, val ok: Boolean)

data class HeldUiState(
    val rows: List<HeldRow> = emptyList(),
    val waiting: Int = 0,
    /** Number of actions taken alone; null until the server answered. */
    val allowedCount: Int? = null,
    val allowed: List<AllowedRow> = emptyList(),
    val showAllowed: Boolean = false,
) {
    /** "2 held today · 14 allowed" */
    val kicker: String get() = heldLine(waiting) + (allowedCount?.let { " · $it allowed" } ?: "")
}

fun heldRows(held: List<HeldAction>, zone: TimeZone): List<HeldRow> =
    orderedHeld(held).map { HeldRow(it.id, heldWhen(it, zone), it.action.summary, heldRowStatus(it.status), it.waiting, it.waiting && it.needsPhoneApproval) }

fun allowedRows(entries: List<ActivityEntry>, zone: TimeZone): List<AllowedRow> =
    entries.sortedByDescending { it.at }.map { AllowedRow(it.summary, formatClock(it.at, zone), it.ok) }

/** Held: things the agent tried that hit one of the person's lines, plus what it did alone. */
@Inject
@ViewModelKey(HeldViewModel::class)
@ContributesIntoMap(AppScope::class)
class HeldViewModel(
    private val feed: FeedRepository,
    private val repository: HeldRepository,
) : ViewModel() {
    private val zone = TimeZone.currentSystemDefault()
    private val _state = MutableStateFlow(HeldUiState())
    val state: StateFlow<HeldUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            feed.held.collect { held ->
                _state.update { it.copy(rows = heldRows(held, zone), waiting = held.count { h -> h.status == HeldAction.Status.HELD }) }
            }
        }
        refreshAllowed()
    }

    fun toggleAllowed() {
        _state.update { it.copy(showAllowed = !it.showAllowed) }
        if (_state.value.showAllowed) refreshAllowed()
    }

    private fun refreshAllowed() {
        viewModelScope.launch {
            (repository.activity() as? ApiResult.Success)?.value?.let { entries ->
                _state.update { it.copy(allowed = allowedRows(entries, zone), allowedCount = entries.size) }
            }
        }
    }
}
