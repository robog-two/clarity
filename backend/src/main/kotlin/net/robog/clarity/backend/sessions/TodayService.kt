package net.robog.clarity.backend.sessions

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.backend.agent.NudgeStore
import net.robog.clarity.backend.agent.SettingsProvider
import net.robog.clarity.backend.agent.TaskStore
import net.robog.clarity.backend.governance.HeldActionStore
import net.robog.clarity.backend.governance.PENDING_STATUSES
import net.robog.clarity.backend.governance.zoneOrUtc
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.Nudge
import net.robog.clarity.shared.session.Today

/** Pure: the local date of [now] in the person's [zoneId]. */
fun localDate(now: Instant, zoneId: String): LocalDate = now.toLocalDateTime(zoneOrUtc(zoneId)).date

/** Pure: Today from the day's plan, the open tasks, the number of held actions and the pending nudges. */
fun todayOf(date: LocalDate, plan: DayPlan?, doing: List<AgentTask>, heldCount: Int, nudges: List<Nudge> = emptyList()): Today = Today(
    date = date,
    priorities = plan?.priorities.orEmpty(),
    plan = plan?.plan.orEmpty(),
    doing = doing,
    heldCount = heldCount,
    parked = plan?.parked.orEmpty(),
    called = plan?.called ?: false,
    nudges = nudges,
)

/** `GET /api/v1/today`. */
@Inject
@SingleIn(AppScope::class)
class TodayService(
    private val plans: DayPlanStore,
    private val tasks: TaskStore,
    private val held: HeldActionStore,
    private val nudges: NudgeStore,
    private val settings: SettingsProvider,
    private val clock: Clock,
) {
    suspend fun today(): Today {
        val date = localDate(clock.now(), settings.current().homeZone)
        return todayOf(date, plans.get(date), tasks.list(includeFinished = false), held.count(PENDING_STATUSES).toInt(), nudges.pending())
    }
}
