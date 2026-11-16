package net.robog.clarity.backend.agent

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.robog.clarity.backend.sessions.PlanItem
import net.robog.clarity.backend.sessions.PlanItemState
import net.robog.clarity.backend.sessions.PlanItemStore
import net.robog.clarity.backend.sessions.clockTimeIn
import net.robog.clarity.backend.sessions.wordsOf
import net.robog.clarity.harness.Description
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.tool
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.feed.Nudge
import net.robog.clarity.shared.time.ScheduleClock

/** The words every check-in about a plan item starts its note with (also how the nudge tool knows it is already covered). */
const val PLAN_CHECK_IN = "Plan check-in"

/** At most this many follow-up nudges (Later, Make it smaller) per plan item per day. */
const val MAX_FOLLOW_UPS_PER_DAY = 2

/** How soon the check-in after "Make it smaller" comes when the lead gives no time. */
val SMALLER_RENUDGE = 10.minutes

/** The buttons of a stuck item's check-in and the exact words a tap sends. */
object CheckInChoices {
    const val STARTING = "Starting"
    const val LATER = "Later"
    const val SMALLER = "Make it smaller"
}

/** Pure: the nudge note for the check-in of an if-then, about [item] when one is linked. */
fun planCheckInNote(ifThen: IfThen, item: PlanItem?): String = buildString {
    append(PLAN_CHECK_IN).append(": ")
    if (item != null) append("\"").append(item.text).append("\" (plan item ").append(item.id).append("). ")
    append(ifThen.sentence())
    val step = ifThen.firstStep ?: item?.firstStep
    if (!step.isNullOrBlank()) append(" First step: ").append(step.trim()).append('.')
}

/**
 * Pure: the plan item an if-then is about. [explicit] (the id the model gave) wins when it is one of
 * [today]; else the item whose words the if-then mentions most; else the only item there is.
 */
fun linkedItem(ifThen: IfThen, explicit: String?, today: List<PlanItem>): PlanItem? {
    explicit?.trim()?.let { id -> today.firstOrNull { it.id == id }?.let { return it } }
    val said = wordsOf(ifThen.cue + " " + ifThen.action).toSet()
    val scored = today.map { it to wordsOf(it.text).toSet().intersect(said).size }.filter { it.second >= 1 }
    val top = scored.maxOfOrNull { it.second }
    return scored.filter { it.second == top }.singleOrNull()?.first ?: today.singleOrNull()
}

/** One if-then to check in on, with the plan item it was linked to. */
data class PlanCheckIn(val ifThen: IfThen, val item: PlanItem?)

/** A nudge [planAutoNudges] wants set. */
data class AutoNudge(val at: Instant, val note: String, val planItemId: String?)

/** What [planAutoNudges] decided: the nudges to set, and a plain note for each if-then with a clock time that got none. */
data class AutoNudgePlan(val nudges: List<AutoNudge>, val skipped: List<String>)

/**
 * Pure: the nudges to set after the if-thens of [date] are saved: one at each if-then whose cue names
 * a clock time. The limits of the nudge tool apply (future, within 7 days, 10 pending, 12 on the day,
 * [sameDay] already counted); an if-then whose time already has a nudge around it (within the duplicate
 * window, the person's own or an earlier one of these) gets none.
 */
fun planAutoNudges(checkIns: List<PlanCheckIn>, date: LocalDate, zone: TimeZone, now: Instant, pending: List<Nudge>, sameDay: Int): AutoNudgePlan {
    val set = mutableListOf<AutoNudge>()
    val skipped = mutableListOf<String>()
    var waiting = pending
    var count = sameDay
    for (check in checkIns) {
        val time = clockTimeIn(check.ifThen.cue) ?: continue
        val at = ScheduleClock.occurrence(date, time, zone)
        val note = planCheckInNote(check.ifThen, check.item)
        val covered = waiting.any { (it.at - at).absoluteValue < NUDGE_DUPLICATE_WINDOW && (it.planItemId != null || it.note.startsWith(PLAN_CHECK_IN) || (check.item != null && it.note.contains(check.item.id))) }
        if (covered) { skipped += "${agentTime(at, zone)}: a check-in is already set around then"; continue }
        val refusal = nudgeRefusal(at, note, null, now, waiting, count, zone, check.item?.id)
        if (refusal != null) { skipped += refusal; continue }
        set += AutoNudge(at, note, check.item?.id)
        waiting = waiting + Nudge(net.robog.clarity.shared.feed.NudgeId("planned-${set.size}"), at, note, null, Nudge.Status.PENDING, now, planItemId = check.item?.id)
        count++
    }
    return AutoNudgePlan(set, skipped)
}

/** Pure: the pending auto check-in near [at] that already covers a nudge for that time, if any. */
fun coveringCheckIn(at: Instant, pending: List<Nudge>): Nudge? =
    pending.firstOrNull { (it.at - at).absoluteValue < NUDGE_DUPLICATE_WINDOW && (it.planItemId != null || it.note.startsWith(PLAN_CHECK_IN)) }

/** How the person answered the check-in of a plan item. */
@Serializable
enum class PlanOutcome {
    @SerialName("started") STARTED,
    @SerialName("later") LATER,
    @SerialName("smaller") SMALLER,
    @SerialName("done") DONE,
    @SerialName("dropped") DROPPED,
}

/** What an answer does to a plan item. */
sealed interface PlanMove {
    data object Started : PlanMove
    data object Done : PlanMove
    data object Dropped : PlanMove

    /** One more check-in at [at]; [firstStep] is the smaller step agreed (Make it smaller), null for Later. */
    data class FollowUp(val at: Instant, val note: String, val firstStep: String?) : PlanMove
}

/**
 * Pure: what [outcome] does to [item] on [today]. Starting ends the day's check-ins; Later needs the
 * time the person picked; Make it smaller needs the smaller step (and checks in again in
 * [SMALLER_RENUDGE] unless a time is given). A finished item takes no answer, and once the person
 * said they are starting, no more check-ins come that day. Left is the message for the model.
 */
fun planMove(item: PlanItem, outcome: PlanOutcome, at: String?, firstStep: String?, today: LocalDate, now: Instant, zone: TimeZone): Either<String, PlanMove> {
    if (item.state != PlanItemState.OPEN) return "\"${item.text}\" is already ${if (item.state == PlanItemState.DONE) "done" else "dropped"}; there is nothing left to do for it.".left()
    return when (outcome) {
        PlanOutcome.STARTED -> PlanMove.Started.right()
        PlanOutcome.DONE -> PlanMove.Done.right()
        PlanOutcome.DROPPED -> PlanMove.Dropped.right()
        PlanOutcome.LATER, PlanOutcome.SMALLER -> {
            if (item.startedOn == today) return "The person already said they are starting \"${item.text}\" today, so no more check-ins on it today.".left()
            val step = firstStep?.trim()?.takeIf { it.isNotEmpty() }
            if (outcome == PlanOutcome.SMALLER && step == null) return "`firstStep` is required for smaller: the new, smaller first step.".left()
            val time: Either<String, Instant> = when {
                !at.isNullOrBlank() -> readNudgeTime(at, zone, now)
                outcome == PlanOutcome.SMALLER -> (now + SMALLER_RENUDGE).right()
                else -> "`at` is required for later: the time the person picked.".left()
            }
            time.map { dueAt ->
                val note = buildString {
                    append(PLAN_CHECK_IN).append(": \"").append(item.text).append("\" (plan item ").append(item.id).append("). ")
                    if (outcome == PlanOutcome.SMALLER) append("The step was too big; the smaller first step is: ").append(step).append('.')
                    else append("The person asked to be checked on this later.")
                }
                PlanMove.FollowUp(dueAt, note, if (outcome == PlanOutcome.SMALLER) step else null)
            }
        }
    }
}

/** Pure: why one more follow-up for the item on a day cannot be set, or null; [existing] counts the day's pending and fired follow-ups. */
fun followUpRefusal(existing: Int): String? =
    if (existing >= MAX_FOLLOW_UPS_PER_DAY) "That item already has $MAX_FOLLOW_UPS_PER_DAY follow-up check-ins that day, the most. Ask the person to pick a time on another day, or to drop it." else null

/** Sets the check-ins an if-then plan earns (see [planAutoNudges]). */
@Inject
@SingleIn(AppScope::class)
class PlanNudger(
    private val nudges: NudgeStore,
    private val settings: SettingsProvider,
    private val clock: Clock,
) {
    /** Returns one line for the lead: what was set, and why anything was not. */
    suspend fun schedule(date: LocalDate, checkIns: List<PlanCheckIn>): String {
        if (checkIns.none { clockTimeIn(it.ifThen.cue) != null }) return ""
        val zone = settings.homeZone()
        val now = clock.now()
        val (dayStart, dayEnd) = homeDay(ScheduleClock.occurrence(date, kotlinx.datetime.LocalTime(12, 0), zone), zone)
        val plan = planAutoNudges(checkIns, date, zone, now, nudges.pending(), nudges.countActive(dayStart, dayEnd))
        val created = plan.nudges.map { nudges.create(it.at, it.note, null, it.planItemId) }
        return buildString {
            if (created.isNotEmpty()) append(" Check-ins set at ").append(created.joinToString(", ") { agentTime(it.at, zone) }).append("; do not nudge for these yourself.")
            if (plan.skipped.isNotEmpty()) append(" Not set: ").append(plan.skipped.joinToString("; ")).append('.')
        }
    }
}

/** `plan_item`: what the person answered to the check-in on a stuck item. */
@Inject
@SingleIn(AppScope::class)
class PlanItemTools(
    private val items: PlanItemStore,
    private val nudges: NudgeStore,
    private val settings: SettingsProvider,
    private val clock: Clock,
) {
    fun all(): List<Tool> = listOf(planItem())

    @Serializable
    class PlanItemInput(
        @Description("The plan item id (from set_priorities, the plan check-in note, or the morning preparation).") val id: String,
        @Description(
            "started = the person tapped Starting (no more check-ins on it today); later = Later (give `at`); smaller = Make it smaller " +
                "(give `firstStep`, the smaller step); done; dropped.",
        ) val outcome: PlanOutcome,
        @Description("later: the time the person picked, their local time (13:30 for today, or 2026-10-27T13:30). smaller: when to check in again (default in 10 minutes).")
        val at: String? = null,
        @Description("smaller: the new, smaller first step, a few words.") val firstStep: String? = null,
    )

    private fun planItem(): Tool = tool<PlanItemInput>(
        name = "plan_item",
        description = "Record how the person answered a check-in about a plan item (a priority that keeps coming back): started, later, smaller, done or dropped. " +
            "Later and smaller set one more check-in (at most $MAX_FOLLOW_UPS_PER_DAY a day per item); started and finished items get no more check-ins that day.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        val zone = settings.homeZone()
        val now = clock.now()
        val today = ScheduleClock.homeDate(now, zone)
        val item = items.get(input.id) ?: return@tool ToolResult.Error("No plan item with id ${input.id}.")
        val move = planMove(item, input.outcome, input.at, input.firstStep, today, now, zone).fold({ return@tool ToolResult.Error(it) }, { it })
        when (move) {
            PlanMove.Started -> {
                items.update(item.id) { it.copy(startedOn = today) }
                val cancelled = nudges.cancelForPlanItem(item.id, "the person is starting it")
                ToolResult.Ok("Marked started. No more check-ins on \"${item.text}\" today" + (if (cancelled > 0) " ($cancelled waiting ${if (cancelled == 1) "one" else "ones"} cancelled)" else "") + ". Acknowledge in a few words.")
            }
            PlanMove.Done, PlanMove.Dropped -> {
                val done = move == PlanMove.Done
                items.update(item.id) { it.copy(state = if (done) PlanItemState.DONE else PlanItemState.DROPPED) }
                nudges.cancelForPlanItem(item.id, if (done) "done" else "dropped")
                ToolResult.Ok("\"${item.text}\" is ${if (done) "done" else "dropped"}; its waiting check-ins are cancelled.")
            }
            is PlanMove.FollowUp -> {
                val pending = nudges.pending()
                val (dayStart, dayEnd) = homeDay(move.at, zone)
                followUpRefusal(nudges.countFollowUps(item.id, dayStart, dayEnd))?.let { return@tool ToolResult.Error(it) }
                nudgeRefusal(move.at, move.note, null, now, pending, nudges.countActive(dayStart, dayEnd), zone, item.id)?.let { return@tool ToolResult.Error(it) }
                if (move.firstStep != null) items.update(item.id) { it.copy(firstStep = move.firstStep) }
                val created = nudges.create(move.at, move.note, null, item.id, followUp = true)
                ToolResult.Ok("Check-in ${created.id.value} set for ${agentTime(move.at, zone)}." + (move.firstStep?.let { " First step now: $it." } ?: ""))
            }
        }
    }
}
