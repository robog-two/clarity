package net.robog.clarity.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Dispatchers
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
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.app.call.CallService
import net.robog.clarity.app.call.CallTracker
import net.robog.clarity.app.call.liveCall
import net.robog.clarity.app.call.resumable
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
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.Today
import kotlinx.datetime.isoDayNumber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Where a row of "I’m doing" sits, top to bottom. [INACTIVE] rows (done, cancelled, failed on an
 * earlier day, open with no update for [INACTIVE_AFTER]) are folded away under one small line.
 */
enum class DoingGroup { NEEDS_YOU, FAILED, RUNNING, SCHEDULED, INACTIVE }

/** Today keeps finished tasks back to the start of the local day this long ago. Matches the server. */
val TODAY_KEEPS: Duration = 2.days

/** An open task (not scheduled, nothing to review) with no update for this long goes to Inactive. */
val INACTIVE_AFTER: Duration = 2.days

/** An open task with no update for this long is not shown at all. */
val HIDDEN_AFTER: Duration = 4.days

/** Why an Inactive row is there. It shows as the row's mark and is spoken, never written. */
enum class InactiveState { DONE, FAILED, CANCELLED, STALE }

/** A row of "I’m doing": the task, the held action to review when it is waiting for the person, and its group. */
data class DoingRow(val task: AgentTask, val review: HeldActionId?, val group: DoingGroup = groupOf(task, review, task.updatedAt) ?: DoingGroup.INACTIVE)

/** The rows of "I’m doing": the main list, and the Inactive ones (newest first) behind one fold. */
data class Doing(val main: List<DoingRow> = emptyList(), val inactive: List<DoingRow> = emptyList(), val now: Instant = Instant.fromEpochSeconds(0)) {
    val isEmpty: Boolean get() = main.isEmpty() && inactive.isEmpty()
}

/** The state an Inactive [task] shows. */
fun inactiveState(task: AgentTask): InactiveState = when (task.status) {
    AgentTask.Status.DONE -> InactiveState.DONE
    AgentTask.Status.FAILED -> InactiveState.FAILED
    AgentTask.Status.CANCELLED -> InactiveState.CANCELLED
    else -> InactiveState.STALE
}

/**
 * The group a task belongs in at [now] in [zone], or null when it is not shown. A held action still
 * waiting on the person always shows in the main list, however old. FAILED stays in the main list on
 * the local day it failed (it needs the person), then moves to Inactive. DONE and CANCELLED go to
 * Inactive. Finished tasks from before the start of the local day [TODAY_KEEPS] ago are hidden. An
 * open task goes to Inactive after [INACTIVE_AFTER] without an update and is hidden after
 * [HIDDEN_AFTER]; a scheduled one never ages out.
 */
fun groupOf(task: AgentTask, review: HeldActionId?, now: Instant, zone: TimeZone = TimeZone.UTC): DoingGroup? {
    if (review != null) return DoingGroup.NEEDS_YOU
    val age = now - task.updatedAt
    return when (task.status) {
        AgentTask.Status.SCHEDULED -> DoingGroup.SCHEDULED
        AgentTask.Status.WORKING, AgentTask.Status.DRAFTED, AgentTask.Status.WAITING_FOR_YOU -> when {
            age >= HIDDEN_AFTER -> null
            age >= INACTIVE_AFTER -> DoingGroup.INACTIVE
            task.status == AgentTask.Status.WORKING -> DoingGroup.RUNNING
            else -> DoingGroup.NEEDS_YOU
        }
        AgentTask.Status.DONE, AgentTask.Status.CANCELLED, AgentTask.Status.FAILED -> when {
            task.updatedAt < keptSince(now, zone) -> null
            task.status == AgentTask.Status.FAILED && task.updatedAt >= startOfLocalDay(now, zone) -> DoingGroup.FAILED
            else -> DoingGroup.INACTIVE
        }
    }
}

/** The start of the window Today keeps finished tasks in: the start of the local day [TODAY_KEEPS] before [now]. */
fun keptSince(now: Instant, zone: TimeZone): Instant = startOfLocalDay(now - TODAY_KEEPS, zone)

/**
 * The rows of "I’m doing" at [now]. A task that is drafted or waiting and carries a held action that
 * still needs the person gets a review target; anything the server settled since (or that never was
 * held) is a plain status row. Rows come grouped (needs you, failed, running, scheduled), newest
 * first within a group, with the Inactive ones apart and tasks that aged out dropped.
 */
fun doingRows(tasks: List<AgentTask>, held: Map<HeldActionId, HeldAction>, now: Instant, zone: TimeZone = TimeZone.UTC): Doing {
    val rows = tasks.mapNotNull { task ->
        val id = task.heldActionId
        val waitingForYou = task.status == AgentTask.Status.DRAFTED || task.status == AgentTask.Status.WAITING_FOR_YOU
        val needsReview = id != null && waitingForYou && (held[id]?.waiting ?: true)
        val review = if (needsReview) id else null
        groupOf(task, review, now, zone)?.let { DoingRow(task, review, it) }
    }.sortedWith(compareBy<DoingRow> { it.group }.thenByDescending { it.task.updatedAt })
    return Doing(
        main = rows.filter { it.group < DoingGroup.INACTIVE },
        inactive = rows.filter { it.group == DoingGroup.INACTIVE },
        now = now,
    )
}

/** The Inactive fold's line: just the count of tasks and parked items, "6 inactive"; null (no fold) when empty. */
fun inactiveLine(inactive: Int, parked: Int): String? = (inactive + parked).takeIf { it > 0 }?.let { "$it inactive" }

/**
 * The server's tasks with anything fresher from the live feed laid over them (same id, newer
 * `updatedAt`), then the live tasks the server didn't list that changed at or after [since] (a task
 * just started, or one that finished before the next fetch), so they show without a refresh. Older
 * live tasks the server left out stay out: it lists every open task, so those are settled history.
 */
fun mergeTasks(fromToday: List<AgentTask>, live: List<AgentTask>, since: Instant? = null): List<AgentTask> {
    val liveById = live.associateBy { it.id }
    val merged = fromToday.map { t -> liveById[t.id]?.takeIf { it.updatedAt >= t.updatedAt } ?: t }
    if (since == null) return merged
    val listed = fromToday.mapTo(HashSet()) { it.id }
    return merged + live.filter { it.id !in listed && it.updatedAt >= since }
}

/** The instant the local day of [now] began in [zone]. */
fun startOfLocalDay(now: Instant, zone: TimeZone): Instant = now.toLocalDateTime(zone).date.atStartOfDayIn(zone)

/** "Wednesday, November 4th." The phone's clock already sits right above it, so no time. */
fun dateKicker(date: LocalDate): String =
    "${dayName(date.dayOfWeek.isoDayNumber)}, ${monthName(date.month)} ${ordinal(date.day)}."

/** 1st, 2nd, 3rd, 4th, 11th, 12th, 13th, 21st. */
fun ordinal(n: Int): String = n.toString() + when {
    n % 100 in 11..13 -> "th"
    n % 10 == 1 -> "st"
    n % 10 == 2 -> "nd"
    n % 10 == 3 -> "rd"
    else -> "th"
}

private fun monthName(month: Month): String = month.name.lowercase().replaceFirstChar { it.titlecase() }

/** "2 waiting" / "Nothing waiting". */
fun heldLine(waiting: Int): String = if (waiting == 0) "Nothing waiting" else "$waiting waiting"

data class TodayUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val today: Today? = null,
    val doing: Doing = Doing(),
    val heldWaiting: Int = 0,
    /** The server has no `/today` yet. */
    val unavailable: Boolean = false,
    val error: String? = null,
    val kicker: String = "",
    /** Which call Today offers to ring for ([callOffer]). */
    val callOffer: CallOffer = CallOffer.NONE,
    /** Why the call could not ring. */
    val callAgainProblem: String? = null,
)

/** The call Today offers to ring for, if any. */
enum class CallOffer {
    NONE,
    /** No morning call today yet: "Start our morning meeting". */
    START,
    /** Today's call ended before the hand-off (or was missed): "We didn't finish this morning". */
    RESTART,
    /** Today's call is still going on with its screen gone: "Our meeting isn't finished". */
    REJOIN,
}

/**
 * Pure: the call Today offers at [now]. While today's call goes on ([liveCall]), ringing again picks
 * it up. Otherwise, until a call reached the hand-off ([Today.called]), ringing starts a new one:
 * [CallOffer.RESTART] when one already started today ([Today.callStarted]), else [CallOffer.START].
 * A snoozed call rings again by itself, and before the day is known nothing is offered.
 */
fun callOffer(today: Today?, session: Session?, now: Instant, zone: TimeZone): CallOffer = when {
    liveCall(session, now, zone) != null -> CallOffer.REJOIN
    session?.state == Session.State.SNOOZED && resumable(session, now, zone) -> CallOffer.NONE
    today == null || today.called -> CallOffer.NONE
    today.callStarted -> CallOffer.RESTART
    else -> CallOffer.START
}

/** Today: the plan, what the agent is doing, held items and the parked list. */
@Inject
@ViewModelKey(TodayViewModel::class)
@ContributesIntoMap(AppScope::class)
class TodayViewModel(
    private val repository: TodayRepository,
    private val feed: FeedRepository,
    private val clock: AppClock,
    private val calls: CallTracker,
    private val callService: CallService,
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
        viewModelScope.launch { calls.session.collect { offerCall() } }
    }

    /** Works out [TodayUiState.callOffer] from the day and the call as now known. */
    private fun offerCall() = _state.update { s ->
        val offer = callOffer(s.today, calls.session.value, clock.now(), clock.zone())
        s.copy(callOffer = offer, callAgainProblem = if (offer != CallOffer.NONE) s.callAgainProblem else null)
    }

    /**
     * "Call me now" / "Call me again": the phone rings like the morning call. Answering picks up the
     * call still going on, or starts a new one ([net.robog.clarity.app.call.callEntry]).
     */
    fun callAgain() {
        // Posting the call is a binder call to the system: off the main thread.
        viewModelScope.launch(Dispatchers.Default) {
            val rang = callService.ringAgain()
            _state.update { it.copy(callAgainProblem = if (rang) null else "Turn on notifications for Clarity.") }
        }
    }

    /** Pull to refresh. */
    fun refresh() {
        viewModelScope.launch { fetch(quiet = false, pulled = true) }
    }

    /** Server rows with the live feed laid over them, plus live tasks from today the server hasn't listed yet. */
    private fun publish() = _state.update { s ->
        val now = clock.now()
        // Before the first fetch there is nothing to lay the feed over.
        val since = fetched?.let { keptSince(now, clock.zone()) }
        s.copy(
            doing = doingRows(mergeTasks(fetched?.doing.orEmpty(), liveTasks, since), liveHeld.associateBy { it.id }, now, clock.zone()),
            heldWaiting = if (liveHeld.isEmpty()) fetched?.heldCount ?: 0 else liveHeld.count { it.status == HeldAction.Status.HELD },
        )
    }

    private suspend fun fetch(quiet: Boolean, pulled: Boolean = false) {
        if (!quiet) _state.update { it.copy(refreshing = pulled, loading = it.today == null && !pulled) }
        when (val r = repository.today()) {
            is ApiResult.Success -> {
                // Whether a call is still going on comes with the day.
                calls.refresh()
                fetched = r.value
                val now = clock.now().toLocalDateTime(clock.zone())
                _state.update {
                    it.copy(loading = false, refreshing = false, today = r.value, unavailable = false, error = null, kicker = dateKicker(now.date))
                }
                offerCall()
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
