package net.robog.clarity.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.app.data.FeedRepository
import net.robog.clarity.app.data.TodayRepository
import net.robog.clarity.app.ui.held.waiting
import net.robog.clarity.app.ui.onboarding.dayName
import net.robog.clarity.app.ui.onboarding.formatTime
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.session.Today
import kotlinx.datetime.isoDayNumber

/** A row of "I’m doing": the task, and the held action to review when it is waiting for the person. */
data class DoingRow(val task: AgentTask, val review: HeldActionId?)

/**
 * The rows of "I’m doing". A task that is drafted or waiting and carries a held action that still
 * needs the person gets a review target; anything the server settled since (or that never was held)
 * is a plain status row.
 */
fun doingRows(tasks: List<AgentTask>, held: Map<HeldActionId, HeldAction>): List<DoingRow> =
    tasks.map { task ->
        val id = task.heldActionId
        val waitingForYou = task.status == AgentTask.Status.DRAFTED || task.status == AgentTask.Status.WAITING_FOR_YOU
        val needsReview = id != null && waitingForYou && (held[id]?.waiting ?: true)
        DoingRow(task, if (needsReview) id else null)
    }

/** The server's tasks with anything fresher from the live feed laid over them (same id, newer `updatedAt`). */
fun mergeTasks(fromToday: List<AgentTask>, live: List<AgentTask>): List<AgentTask> {
    val liveById = live.associateBy { it.id }
    return fromToday.map { t -> liveById[t.id]?.takeIf { it.updatedAt >= t.updatedAt } ?: t }
}

/** "monday, october 26 · 10:04" */
fun dateKicker(date: LocalDate, time: LocalTime): String =
    "${dayName(date.dayOfWeek.isoDayNumber).lowercase()}, ${monthName(date.month)} ${date.day} · ${formatTime(time)}"

private fun monthName(month: Month): String = month.name.lowercase()

/** "2 held today" / "nothing held today". */
fun heldLine(waiting: Int): String = if (waiting == 0) "nothing held today" else "$waiting held today"

/** "Feedback for Alex, expense report. Back in tomorrow’s call." */
fun parkedLine(parked: List<String>): String? =
    parked.takeIf { it.isNotEmpty() }?.joinToString(", ")?.replaceFirstChar { it.uppercase() }?.let { "$it. Back in tomorrow’s call." }

data class TodayUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val today: Today? = null,
    val doing: List<DoingRow> = emptyList(),
    val heldWaiting: Int = 0,
    /** The server has no `/today` yet. */
    val unavailable: Boolean = false,
    val error: String? = null,
    val kicker: String = "",
)

/** Today: the plan, what the agent is doing, held items and the parked list. */
@Inject
@ViewModelKey(TodayViewModel::class)
@ContributesIntoMap(AppScope::class)
class TodayViewModel(
    private val repository: TodayRepository,
    private val feed: FeedRepository,
    private val clock: AppClock,
) : ViewModel() {
    private val _state = MutableStateFlow(TodayUiState())
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    private var fetched: Today? = null
    private var liveTasks: List<AgentTask> = emptyList()
    private var liveHeld: List<HeldAction> = emptyList()

    init {
        viewModelScope.launch {
            combine(feed.tasks, feed.held) { tasks, held -> tasks to held }.collect { (tasks, held) ->
                liveTasks = tasks
                liveHeld = held
                publish()
            }
        }
        // New server-side activity (a task appeared, an action was held) changes what Today lists: fetch again.
        viewModelScope.launch {
            combine(feed.tasks, feed.held) { tasks, held -> tasks.map { it.id to it.status } to held.map { it.id to it.status } }
                .distinctUntilChanged()
                .drop(1)
                .collect { fetch(quiet = true) }
        }
        viewModelScope.launch { fetch(quiet = false) }
    }

    /** Pull to refresh. */
    fun refresh() {
        viewModelScope.launch { fetch(quiet = false, pulled = true) }
    }

    /** Server rows with the live feed laid over them. */
    private fun publish() = _state.update { s ->
        s.copy(
            doing = doingRows(mergeTasks(fetched?.doing.orEmpty(), liveTasks), liveHeld.associateBy { it.id }),
            heldWaiting = if (liveHeld.isEmpty()) fetched?.heldCount ?: 0 else liveHeld.count { it.status == HeldAction.Status.HELD },
        )
    }

    private suspend fun fetch(quiet: Boolean, pulled: Boolean = false) {
        if (!quiet) _state.update { it.copy(refreshing = pulled, loading = it.today == null && !pulled) }
        when (val r = repository.today()) {
            is ApiResult.Success -> {
                fetched = r.value
                val now = clock.now().toLocalDateTime(TimeZone.currentSystemDefault())
                _state.update {
                    it.copy(loading = false, refreshing = false, today = r.value, unavailable = false, error = null, kicker = dateKicker(r.value.date, now.time))
                }
                publish()
            }
            is ApiResult.Failure -> _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    unavailable = r.error is ApiError.NotFound,
                    error = if (quiet || r.error is ApiError.NotFound) it.error else r.error.sentence(),
                )
            }
        }
    }
}
