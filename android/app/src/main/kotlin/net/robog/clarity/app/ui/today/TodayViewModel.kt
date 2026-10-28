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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Where a row of "I’m doing" sits, top to bottom. [QUIET] rows are folded away under a small line;
 * [FINISHED] rows (done, failed or cancelled today) are folded under their own line below them.
 */
enum class DoingGroup { NEEDS_YOU, RUNNING, SCHEDULED, QUIET, FINISHED }

/** A waiting or drafted task with nothing held to review goes quiet after this long without an update. */
val QUIET_WAITING_AFTER: Duration = 12.hours

/** Any open task except a scheduled one goes quiet after this long without an update. */
val QUIET_STALE_AFTER: Duration = 48.hours

/** A row of "I’m doing": the task, the held action to review when it is waiting for the person, and its group. */
data class DoingRow(val task: AgentTask, val review: HeldActionId?, val group: DoingGroup = groupOf(task, review, task.updatedAt))

/** The rows of "I’m doing", split into the main group, the quiet ones and those finished today. */
data class Doing(val main: List<DoingRow> = emptyList(), val quiet: List<DoingRow> = emptyList(), val finished: List<DoingRow> = emptyList()) {
    val isEmpty: Boolean get() = main.isEmpty() && quiet.isEmpty() && finished.isEmpty()
}

/**
 * The group a task belongs in at [now]. A held action still waiting on the person always needs
 * them, however old (the Review button must stay reachable). A waiting or drafted task with nothing
 * to review (say the agent asked in chat) stays at the top while fresh, then goes quiet. Running work
 * goes quiet when nothing has touched it for a long while; scheduled work never does. Anything
 * already settled (done, failed, cancelled) is finished: it stays openable for its Agent's Path.
 */
fun groupOf(task: AgentTask, review: HeldActionId?, now: Instant): DoingGroup {
    if (review != null) return DoingGroup.NEEDS_YOU
    val age = now - task.updatedAt
    return when (task.status) {
        AgentTask.Status.SCHEDULED -> DoingGroup.SCHEDULED
        AgentTask.Status.WORKING -> if (age >= QUIET_STALE_AFTER) DoingGroup.QUIET else DoingGroup.RUNNING
        AgentTask.Status.DRAFTED, AgentTask.Status.WAITING_FOR_YOU -> if (age >= QUIET_WAITING_AFTER) DoingGroup.QUIET else DoingGroup.NEEDS_YOU
        AgentTask.Status.DONE, AgentTask.Status.FAILED, AgentTask.Status.CANCELLED -> DoingGroup.FINISHED
    }
}

/**
 * The rows of "I’m doing" at [now]. A task that is drafted or waiting and carries a held action that
 * still needs the person gets a review target; anything the server settled since (or that never was
 * held) is a plain status row. Rows come grouped (needs you, running, scheduled), newest first within
 * a group, with the quiet and the finished ones apart.
 */
fun doingRows(tasks: List<AgentTask>, held: Map<HeldActionId, HeldAction>, now: Instant): Doing {
    val rows = tasks.map { task ->
        val id = task.heldActionId
        val waitingForYou = task.status == AgentTask.Status.DRAFTED || task.status == AgentTask.Status.WAITING_FOR_YOU
        val needsReview = id != null && waitingForYou && (held[id]?.waiting ?: true)
        val review = if (needsReview) id else null
        DoingRow(task, review, groupOf(task, review, now))
    }.sortedWith(compareBy<DoingRow> { it.group }.thenByDescending { it.task.updatedAt })
    return Doing(
        main = rows.filter { it.group < DoingGroup.QUIET },
        quiet = rows.filter { it.group == DoingGroup.QUIET },
        finished = rows.filter { it.group == DoingGroup.FINISHED },
    )
}

/** "3 quieter tasks" / "1 quieter task". */
fun quietLine(count: Int): String = if (count == 1) "1 quieter task" else "$count quieter tasks"

/**
 * The bottom fold's line, counting both: "Quieter and parked · 5", or "2 quieter tasks" /
 * "Parked · 3" when only one has anything; null (no fold) when both are empty.
 */
fun quietAndParkedLine(quiet: Int, parked: Int): String? = when {
    quiet > 0 && parked > 0 -> "Quieter and parked · ${quiet + parked}"
    quiet > 0 -> quietLine(quiet).replaceFirstChar { it.uppercase() }
    parked > 0 -> "Parked · $parked"
    else -> null
}

/** "Finished today · 3". */
fun finishedLine(count: Int): String = "Finished today · $count"

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

/** "monday, october 26 · 10:04" */
fun dateKicker(date: LocalDate, time: LocalTime): String =
    "${dayName(date.dayOfWeek.isoDayNumber).lowercase()}, ${monthName(date.month)} ${date.day} · ${formatTime(time)}"

private fun monthName(month: Month): String = month.name.lowercase().replaceFirstChar { it.titlecase() }

/** "2 held today" / "nothing held today". */
fun heldLine(waiting: Int): String = if (waiting == 0) "nothing held today" else "$waiting held today"

/** "Feedback for Alex, expense report. Back in tomorrow’s call." */
fun parkedLine(parked: List<String>): String? =
    parked.takeIf { it.isNotEmpty() }?.joinToString(", ")?.replaceFirstChar { it.uppercase() }?.let { "$it. Back in tomorrow’s call." }

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
            _state.update { it.copy(callAgainProblem = if (rang) null else "Turn on notifications for Clarity so I can call.") }
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
        val since = fetched?.let { startOfLocalDay(now, clock.zone()) }
        s.copy(
            doing = doingRows(mergeTasks(fetched?.doing.orEmpty(), liveTasks, since), liveHeld.associateBy { it.id }, now),
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
                    it.copy(loading = false, refreshing = false, today = r.value, unavailable = false, error = null, kicker = dateKicker(now.date, now.time))
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
