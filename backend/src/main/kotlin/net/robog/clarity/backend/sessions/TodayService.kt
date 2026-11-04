package net.robog.clarity.backend.sessions

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.backend.agent.NudgeStore
import net.robog.clarity.backend.agent.SettingsProvider
import net.robog.clarity.backend.agent.TaskStore
import net.robog.clarity.backend.governance.HeldActionStore
import net.robog.clarity.backend.governance.PENDING_STATUSES
import net.robog.clarity.backend.governance.zoneOrUtc
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.Nudge
import net.robog.clarity.shared.session.SessionKind
import net.robog.clarity.shared.session.Today

/** Pure: the local date of [now] in the person's [zoneId]. */
fun localDate(now: Instant, zoneId: String): LocalDate = now.toLocalDateTime(zoneOrUtc(zoneId)).date

/** Pure: the instant the person's local [date] starts in [zoneId] (DST-safe). */
fun startOfDay(date: LocalDate, zoneId: String): Instant = date.atStartOfDayIn(zoneOrUtc(zoneId))

/**
 * Pure: Today from the day's plan, the open tasks and those finished in the last two local days, the number of held
 * actions, the pending nudges and whether a morning call started today ([callStarted]).
 */
fun todayOf(
    date: LocalDate,
    plan: DayPlan?,
    doing: List<AgentTask>,
    heldCount: Int,
    nudges: List<Nudge> = emptyList(),
    callStarted: Boolean = false,
): Today = Today(
    date = date,
    priorities = plan?.priorities.orEmpty(),
    plan = plan?.plan.orEmpty(),
    doing = doing,
    heldCount = heldCount,
    parked = plan?.parked.orEmpty(),
    called = plan?.called ?: false,
    callStarted = callStarted || plan?.called == true,
    nudges = nudges,
)

/** Today lists tasks that finished since the start of the person's local day this many days ago. The app hides older ones; the tasks and audit rows stay on the server. */
val TODAY_KEEPS: DatePeriod = DatePeriod(days = 2)

/** Pure: the instant Today starts listing finished tasks from, the start of the local day [TODAY_KEEPS] before [date]. */
fun todayKeepsSince(date: LocalDate, zoneId: String): Instant = startOfDay(date.minus(TODAY_KEEPS), zoneId)

/** `GET /api/v1/today`. */
@Inject
@SingleIn(AppScope::class)
class TodayService(
    private val plans: DayPlanStore,
    private val tasks: TaskStore,
    private val held: HeldActionStore,
    private val nudges: NudgeStore,
    private val sessions: SessionStore,
    private val settings: SettingsProvider,
    private val clock: Clock,
) {
    suspend fun today(): Today {
        val zone = settings.current().homeZone
        val date = localDate(clock.now(), zone)
        // Open tasks plus those finished in the last two local days, so a task stays openable to catch up on.
        val midnight = startOfDay(date, zone)
        val doing = tasks.list(finishedSince = todayKeepsSince(date, zone))
        val callStarted = sessions.startedSince(SessionKind.MORNING_CALL, midnight)
        return todayOf(date, plans.get(date), doing, held.count(PENDING_STATUSES).toInt(), nudges.pending(), callStarted)
    }
}
