package net.robog.clarity.backend.scheduler

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.LocalTime
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.InboxStore
import net.robog.clarity.backend.agent.Priority
import net.robog.clarity.backend.governance.ConstitutionStore
import net.robog.clarity.backend.outings.BusySpan
import net.robog.clarity.backend.agent.homeDay
import net.robog.clarity.backend.outings.IdeaState
import net.robog.clarity.backend.outings.MAX_EVENTS_READ
import net.robog.clarity.backend.outings.SCOUT_BUDGET_WINDOW
import net.robog.clarity.backend.outings.busyFrom
import net.robog.clarity.backend.outings.OUTING_DAYS_AHEAD
import net.robog.clarity.backend.outings.OutingPeople
import net.robog.clarity.backend.outings.OutingStore
import net.robog.clarity.backend.outings.ScoutPlan
import net.robog.clarity.backend.outings.freeSlots
import net.robog.clarity.backend.outings.outingInstruction
import net.robog.clarity.backend.outings.scoutPlan
import net.robog.clarity.backend.persistence.NounReader
import net.robog.clarity.backend.persistence.TimeRange
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.time.ScheduleClock
import net.robog.clarity.shared.time.WeeklyTime
import net.robog.clarity.shared.time.zoneOrUtc

private val log = KotlinLogging.logger {}

/** Tuesday and Friday (ISO days), in the afternoon of the person's day. */
val OUTING_SCOUT_DAYS: Set<Int> = setOf(2, 5)
val OUTING_SCOUT_AT: LocalTime = LocalTime(15, 0)

/**
 * Twice a week in the afternoon: looks at the person's next seven days ([freeSlots]) and, only when
 * there is free time and fewer than two suggestions were posted in the last 6 days 23 hours ([scoutPlan], from `outing_idea.suggested_at`), wakes the lead
 * with the slots, how many ideas are saved and the people the person may bring ([OutingPeople.recent]).
 * A skip costs no model call. Everything private (calendar, contacts, messages) stays on the server;
 * the lead gets slot times and contact names only, and a web search helper gets less (see ARCHITECTURE.md).
 */
@Inject
@ContributesIntoSet(AppScope::class)
class OutingScoutJob(
    private val reader: NounReader,
    private val constitutions: ConstitutionStore,
    private val ideas: OutingStore,
    private val people: OutingPeople,
    private val inbox: InboxStore,
) : ScheduledJob {
    override val name = OUTING_JOB

    override fun schedule(settings: Settings) = Schedule.Computed { after, zone ->
        checkNotNull(ScheduleClock.nextOccurrence(WeeklyTime(OUTING_SCOUT_AT, OUTING_SCOUT_DAYS), zone, after))
    }

    override suspend fun fire(context: FireContext) {
        val zone = zoneOrUtc(context.settings.homeZone)
        val now = context.now
        val from = homeDay(now, zone).first
        val until = from + OUTING_DAYS_AHEAD.days
        val busy = busyFrom(busyBetween(from, until), until)
        val slots = freeSlots(busy, constitutions.current()?.protectedTimes.orEmpty(), now, zone)
        // Suggestions are counted by when they were posted; a little under a week, so the same weekday and hour next week is not skipped.
        val plan = scoutPlan(slots, ideas.offeredSince(now - SCOUT_BUDGET_WINDOW).size)
        if (plan !is ScoutPlan.Offer) {
            log.info { "outing scout: $plan" }
            return
        }
        val open = ideas.all().count { it.state == IdeaState.OPEN }
        inbox.enqueue(
            InboxItem.JobDue(
                job = name,
                instruction = outingInstruction(plan.slots, people.recent(now), open, zone),
                priority = Priority.LOW,
            ),
            dedupKey = "job:$name:${context.scheduledFor}",
        )
    }

    private suspend fun busyBetween(from: Instant, until: Instant): List<BusySpan> =
        reader.calendarEvents(TimeRange(from, until), limit = MAX_EVENTS_READ).map { BusySpan(it.payload.start, it.payload.end, it.payload.allDay) }

}

const val OUTING_JOB = "outing-scout"
