package net.robog.clarity.backend.sessions

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.InboxStore
import net.robog.clarity.backend.agent.Priority
import net.robog.clarity.backend.governance.zoneOrUtc
import net.robog.clarity.backend.scheduler.FireContext
import net.robog.clarity.backend.scheduler.Schedule
import net.robog.clarity.backend.scheduler.ScheduledJob
import net.robog.clarity.shared.session.Settings

/** Pure: the instruction to the lead for the quiet preparation, with yesterday's plan and what was parked. */
fun prepInstruction(yesterday: DayPlan?): String = buildString {
    append(Playbooks.preparation())
    if (yesterday != null && (yesterday.priorities.isNotEmpty() || yesterday.plan.isNotEmpty() || yesterday.parked.isNotEmpty())) {
        append("\nYesterday (").append(yesterday.date).append("):")
        if (yesterday.priorities.isNotEmpty()) append("\n- priorities: ").append(yesterday.priorities.joinToString("; ") { it.text })
        if (yesterday.plan.isNotEmpty()) append("\n- plan: ").append(yesterday.plan.joinToString(" ") { it.sentence() })
        if (yesterday.parked.isNotEmpty()) append("\n- parked until today: ").append(yesterday.parked.joinToString("; "))
    } else {
        append("\nThere is no plan from yesterday.")
    }
}

/**
 * Ten minutes (or the heads-up time) before the morning call on each call day: asks the lead to
 * prepare silently, which also warms the prompt cache for the call. Says nothing to the person.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class MorningCallPrepJob(private val inbox: InboxStore, private val plans: DayPlanStore) : ScheduledJob {
    override val name = "morning-call-prep"

    override fun schedule(settings: Settings) = Schedule.Computed { after, zone ->
        nextPrepAfter(settings.morningCall, after, zone) ?: (after + NO_CALL_RECHECK)
    }

    override suspend fun fire(context: FireContext) {
        val call = context.settings.morningCall
        if (!call.enabled) return
        val callDate = localDate(context.scheduledFor + prepLead(call), context.settings.homeZone)
        val yesterday = plans.get(callDate.minus(DatePeriod(days = 1)))
        inbox.enqueue(
            InboxItem.System(prepInstruction(yesterday), Priority.LOW),
            dedupKey = "job:$name:${context.scheduledFor}",
        )
    }

    private companion object { val NO_CALL_RECHECK = kotlin.time.Duration.parse("1d") }
}

/** Rings snoozed calls when their time comes, and ends amend sessions whose amendment was decided. */
@Inject
@ContributesIntoSet(AppScope::class)
class SnoozeRecallJob(private val sessions: SessionService) : ScheduledJob {
    override val name = "snooze-recall"

    override fun schedule(settings: Settings) = Schedule.Every(15.seconds)

    override suspend fun fire(context: FireContext) {
        sessions.recallDue()
        sessions.sweep()
    }
}
