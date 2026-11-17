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
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.robog.clarity.backend.sessions.PlanItem
import net.robog.clarity.backend.sessions.PlanItemState
import net.robog.clarity.backend.sessions.PlanItemStore
import net.robog.clarity.backend.sessions.PlanSource
import net.robog.clarity.backend.sessions.firstStepNote
import net.robog.clarity.backend.sessions.sameWording
import net.robog.clarity.backend.sessions.wordsOf
import net.robog.clarity.harness.Description
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.tool
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.feed.Nudge
import net.robog.clarity.shared.feed.NudgeId
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

/** Pure: whether a nudge belongs to the plan flow (a check-in an if-then set, a follow-up, or anything linked to a plan item); these have caps of their own. */
/** The plan flow's own nudge (an auto check-in or a follow-up). A nudge the lead links to an item with `nudge` is an ordinary one. */
val Nudge.isPlan: Boolean get() = autoCheckIn || followUp

/** Pure: the nudge note for the check-in of an if-then on [date], about [item] when one is linked. */
fun planCheckInNote(ifThen: IfThen, item: PlanItem?, date: LocalDate): String = buildString {
    append(PLAN_CHECK_IN).append(": ")
    if (item != null) append("\"").append(item.text).append("\" (plan item ").append(item.id).append("). ")
    append(ifThen.sentence())
    val step = ifThen.firstStep?.takeIf { it.isNotBlank() }?.let { "First step: ${it.trim()}" }
        ?: item?.let { firstStepNote(it, date) }?.replaceFirstChar { it.uppercase() }
    if (step != null) append(' ').append(step).append('.')
}

/**
 * Pure: the plan item an if-then is about: the one named by [explicit] when it is one of [today]'s, else the
 * one whose whole wording the if-then repeats (up to three words all of them, longer ones four in five, and
 * the same numbers). Two items that fit equally well, or none, link nothing. An unrelated if-then never
 * attaches to the only item there is.
 */
fun linkedItem(ifThen: IfThen, explicit: String?, today: List<PlanItem>): PlanItem? {
    explicit?.trim()?.let { id -> today.firstOrNull { it.id == id }?.let { return it } }
    val said = wordsOf(ifThen.cue + " " + ifThen.action).toSet()
    val scored = today.mapNotNull { item ->
        val words = wordsOf(item.text).toSet()
        val shared = words.intersect(said).size
        val digits = words.filter { w -> w.any { it.isDigit() } }.toSet()
        val enough = words.isNotEmpty() && digits.all { it in said } && if (words.size <= 3) shared == words.size else shared * 5 >= words.size * 4
        if (enough) item to shared else null
    }
    val top = scored.maxOfOrNull { it.second } ?: return null
    return scored.filter { it.second == top }.singleOrNull()?.first
}

/** One if-then to check in on, with the plan item it was linked to. */
data class PlanCheckIn(val ifThen: IfThen, val item: PlanItem?)

/** A nudge [planAutoNudges] wants set. */
data class AutoNudge(val at: Instant, val note: String, val planItemId: String?)

/**
 * What [planAutoNudges] decided: the nudges to set; what was skipped and why ([skipped]: a malformed or passed `at`, a
 * limit); and the if-thens that came with no `at` ([unscheduled], as sentences) so the lead can nudge itself. Only what
 * is new since the earlier save of the day is reported.
 */
data class AutoNudgePlan(
    val nudges: List<AutoNudge>,
    val skipped: List<String>,
    val unscheduled: List<String> = emptyList(),
)

private fun String.sameNote(other: String) = normalizedNote() == other.normalizedNote()

private fun String.normalizedNote() = trim().lowercase().replace(Regex("\\s+"), " ")

/** Why a check-in was cancelled when the person said they are starting (see `plan_item`); such a check-in counts as having happened. */
const val STARTING_REASON = "the person is starting it"

/** Why a plan saved again cancelled a check-in of its earlier save: not a check-in that happened, so it does not count as settled. */
const val SAVED_AGAIN_REASON = "the plan was saved again"

/** Why a check-in was cancelled because its item left today's plan: it does not count as settled either. */
const val OFF_PLAN_REASON = "no longer on today's plan"

/** What a start time the lead gave for an if-then turned out to be. */
sealed interface CheckInTime {
    /** No `at`: the lead gave no start time (a deadline, an event cue, or it was unsure). */
    data object Missing : CheckInTime

    /** `at` is not HH:MM (24 hour). */
    data class Malformed(val raw: String) : CheckInTime

    /** A well formed time today that is now or earlier; nothing is moved to another time. */
    data class Passed(val at: Instant) : CheckInTime

    data class Valid(val at: Instant) : CheckInTime
}

private val HH_MM = Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""")

/** Pure: reads the start time [raw] the lead gave for an if-then of [date]: HH:MM, 24 hour, the person's local time. */
fun readCheckInTime(raw: String?, date: LocalDate, zone: TimeZone, now: Instant): CheckInTime {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return CheckInTime.Missing
    val match = HH_MM.matchEntire(text) ?: return CheckInTime.Malformed(text)
    val at = ScheduleClock.occurrence(date, LocalTime(match.groupValues[1].toInt(), match.groupValues[2].toInt()), zone)
    return if (at <= now) CheckInTime.Passed(at) else CheckInTime.Valid(at)
}

/** Pure: today's check-ins of [checkIns] that may still be set on [date]: none for an item the person already started today. */
fun eligibleCheckIns(checkIns: List<PlanCheckIn>, date: LocalDate): List<PlanCheckIn> = checkIns.filter { it.item?.startedOn != date }

/** Pure: today's open priorities, the only items an if-then may be linked to (parked items are for tomorrow, finished and replaced ones are gone). */
fun linkableToday(open: List<PlanItem>, date: LocalDate): List<PlanItem> =
    open.filter { it.state == PlanItemState.OPEN && it.lastDate == date && it.source == PlanSource.PRIORITY }

/** Pure: whether a check-in of the day that is no longer waiting for its time means it has been dealt with: it fired, was skipped or missed, or something other than a re-save or leaving the plan ended it (Starting, the agent, done, dropped). */
private val Nudge.settled: Boolean
    get() = status == Nudge.Status.FIRED || status == Nudge.Status.SKIPPED || status == Nudge.Status.MISSED ||
        (status == Nudge.Status.CANCELLED && reason != SAVED_AGAIN_REASON && reason != OFF_PLAN_REASON)

/**
 * Pure: the check-in of the day ([existing], any status) that already covers a wanted one for [itemId] at [at] with
 * [note], if any: for an item, any settled check-in of its own, or one waiting whose time has come ([now]; it is about
 * to fire); for a check-in with no item, a settled one at the same time with the same note.
 */
fun blockingCheckIn(existing: List<Nudge>, itemId: String?, at: Instant, note: String, now: Instant): Nudge? =
    existing.firstOrNull { n ->
        val done = n.settled || (n.status == Nudge.Status.PENDING && n.at <= now)
        done && if (itemId != null) n.planItemId == itemId else n.planItemId == null && n.at == at && n.note == note
    }

/** Pure: whether the day's check-ins already cover a wanted one (see [blockingCheckIn]). */
fun checkInBlocked(existing: List<Nudge>, itemId: String?, at: Instant, note: String, now: Instant): Boolean =
    blockingCheckIn(existing, itemId, at, note, now) != null

/**
 * Pure: the nudges to set after the if-thens of [date] are saved: one at each if-then with a valid `at` (see
 * [readCheckInTime]). A time that has already passed or is malformed is skipped and reported, never moved. An
 * if-then with no `at` gets none and is listed. What the earlier save of the day ([previous]) already had is not
 * reported again. [pending] are the waiting plan nudges (the replaced check-ins of this plan are left out by the
 * caller), [sameDay] the plan nudges already due that day, [existing] the day's check-ins of any status (an if-then
 * whose item already had its check-in is skipped, reported once as "kept the earlier check-in", and does not use up a cap); [personPending] and [personDay] are the
 * person's own nudges, for the ceiling over both. Plan nudges have the limits of the nudge tool (within 7 days,
 * 10 pending, 12 a day) counted among themselves, and 20 waiting and 20 a day over both. A check-in is not set when
 * one for the SAME plan item is already around that time; another item's does not matter.
 */
fun planAutoNudges(
    checkIns: List<PlanCheckIn>,
    date: LocalDate,
    zone: TimeZone,
    now: Instant,
    pending: List<Nudge>,
    sameDay: Int,
    personPending: Int = 0,
    personDay: Int = 0,
    existing: List<Nudge> = emptyList(),
    previous: List<IfThen> = emptyList(),
): AutoNudgePlan {
    val set = mutableListOf<AutoNudge>()
    val skipped = mutableListOf<String>()
    val unscheduled = mutableListOf<String>()
    var waiting = pending
    var count = sameDay
    for (check in checkIns) {
        // Reported already: the same if-then (cue, action, start time, first step, item) was in the day's earlier save.
        val reported = previous.any {
            it.cue == check.ifThen.cue && it.action == check.ifThen.action && it.at == check.ifThen.at &&
                it.firstStep == check.ifThen.firstStep && it.item == check.ifThen.item
        }
        val at = when (val time = readCheckInTime(check.ifThen.at, date, zone, now)) {
            CheckInTime.Missing -> { if (!reported) unscheduled += check.ifThen.sentence(); continue }
            is CheckInTime.Malformed -> { if (!reported) skipped += "`at` \"${time.raw}\" of ${check.ifThen.sentence()} is not a time (use HH:MM, 24 hour)"; continue }
            is CheckInTime.Passed -> { if (!reported) skipped += "${agentTime(time.at, zone)} (${check.ifThen.sentence()}) has already passed"; continue }
            is CheckInTime.Valid -> time.at
        }
        val note = planCheckInNote(check.ifThen, check.item, date)
        blockingCheckIn(existing, check.item?.id, at, note, now)?.let { earlier ->
            if (!reported) {
                skipped += when (earlier.status) {
                    Nudge.Status.CANCELLED -> "${agentTime(at, zone)}: not set, its earlier check-in today was cancelled (${earlier.reason ?: "ended"}) and is not set again"
                    Nudge.Status.PENDING -> "${agentTime(at, zone)}: not set, its earlier check-in is about to fire"
                    else -> "${agentTime(at, zone)}: kept the earlier check-in for it (it already had one today)"
                }
            }
            continue
        }
        val covered = waiting.any {
            (it.at - at).absoluteValue < NUDGE_DUPLICATE_WINDOW &&
                if (check.item != null) it.planItemId == check.item.id else it.planItemId == null && it.note.sameNote(note)
        }
        if (covered) { if (!reported) skipped += "${agentTime(at, zone)}: a check-in for it is already set around then"; continue }
        val refusal = combinedRefusal(personPending + waiting.size, personDay + count) ?: nudgeRefusal(at, note, null, now, waiting, count, zone, check.item?.id)
        if (refusal != null) { if (!reported) skipped += refusal; continue }
        set += AutoNudge(at, note, check.item?.id)
        waiting = waiting + Nudge(NudgeId("planned-${set.size}"), at, note, null, Nudge.Status.PENDING, now, planItemId = check.item?.id, autoCheckIn = true)
        count++
    }
    return AutoNudgePlan(set, skipped, unscheduled)
}

/** What saving a plan again does to the check-ins already waiting: [cancel] those no longer wanted, [create] those not there yet. */
data class Reconcile(val cancel: List<Nudge>, val create: List<AutoNudge>)

/**
 * Pure: the changes that make the automatic check-ins of a day ([existing], of ANY status) match the ones the
 * saved plan wants. A waiting check-in at the same time, for the same item, with the same note stays. Another
 * waiting one is cancelled, unless its time has come ([now]): it is about to fire and is left alone. A wanted one
 * is created unless that item already had its check-in today (see [checkInBlocked]: it fired, was skipped or
 * missed, the person's Starting or the agent cancelled it, or it is about to fire). A check-in this plan's own
 * earlier save cancelled does not count. Follow-ups and the person's own nudges are never in [existing].
 */
fun reconcileCheckIns(wanted: List<AutoNudge>, existing: List<Nudge>, now: Instant): Reconcile {
    val waiting = existing.filter { it.status == Nudge.Status.PENDING }.toMutableList()
    val create = wanted.filter { w ->
        val same = waiting.firstOrNull { it.at == w.at && it.planItemId == w.planItemId && it.note == w.note }
        if (same != null) { waiting.remove(same); false } else !checkInBlocked(existing, w.planItemId, w.at, w.note, now)
    }
    return Reconcile(waiting.filter { it.at > now }, create)
}

/**
 * Pure: the waiting automatic check-in near [at] that already covers a nudge the model wants for that time,
 * if any. It covers only a nudge about the SAME plan item: the one the model names in [planItemId], or one
 * whose [subjects] (its note, its task's title) say what that item's text says. A reminder about anything
 * else is never covered. [items] are the plan items by id.
 */
fun coveringCheckIn(at: Instant, planItemId: String?, subjects: List<String>, pending: List<Nudge>, items: Map<String, PlanItem>): Nudge? =
    pending.firstOrNull { n ->
        val itemId = n.planItemId
        n.autoCheckIn && itemId != null && (n.at - at).absoluteValue < NUDGE_DUPLICATE_WINDOW &&
            (itemId == planItemId || items[itemId]?.text?.let { text -> subjects.any { sameWording(text, it) } } == true)
    }

/**
 * Pure: the plan item a lead-made nudge is stored against, or null. Only an id of an open item counts,
 * so the nudge is skipped once the item is done or started, as an automatic check-in is.
 */
fun linkedPlanItem(planItemId: String?, open: Map<String, PlanItem>): String? =
    planItemId?.trim()?.takeIf { it in open }

/** Pure: how a plan item reads in the lead's wake-up: id, text, day, first step and the follow-ups left today. */
fun planItemWakeText(item: PlanItem, date: LocalDate, followUpsLeft: Int): String =
    "id ${item.id}, \"${item.text}\", day ${item.dayOn(date)}" + (firstStepNote(item, date)?.let { ", $it" } ?: "") +
        ", $followUpsLeft follow-up check-in${if (followUpsLeft == 1) "" else "s"} left today"

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
    if (item.state != PlanItemState.OPEN) {
        val what = when (item.state) {
            PlanItemState.DONE -> "already done"
            PlanItemState.DROPPED -> "already dropped"
            else -> "no longer on today's plan (it was replaced by another choice)"
        }
        return "\"${item.text}\" is $what; there is nothing left to do for it.".left()
    }
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

/** Sets the check-ins an if-then plan earns (see [planAutoNudges]) and replaces those of an earlier plan of the same day. */
@Inject
@SingleIn(AppScope::class)
class PlanNudger(
    private val nudges: NudgeStore,
    private val settings: SettingsProvider,
    private val clock: Clock,
) {
    /**
     * Saves the check-ins of the plan of [date]: the waiting ones an earlier save set that the plan no longer wants
     * are cancelled, new ones are created, follow-ups and the person's own nudges are left alone. Returns what the
     * lead should know: what was set, replaced and skipped, and which cues got no check-in (it can nudge itself for those).
     */
    suspend fun schedule(date: LocalDate, allCheckIns: List<PlanCheckIn>, previous: List<IfThen> = emptyList()): String {
        val zone = settings.homeZone()
        val now = clock.now()
        val checkIns = eligibleCheckIns(allCheckIns, date)
        val (dayStart, dayEnd) = homeDay(ScheduleClock.occurrence(date, LocalTime(12, 0), zone), zone)
        val everything = nudges.pending()
        val today = nudges.autoCheckIns(dayStart, dayEnd)
        val waiting = today.filter { it.status == Nudge.Status.PENDING }
        val plan = planAutoNudges(
            checkIns, date, zone, now, everything.filter { it.isPlan } - waiting.toSet(),
            (nudges.countActivePlan(dayStart, dayEnd) - waiting.size).coerceAtLeast(0),
            personPending = everything.count { !it.isPlan }, personDay = nudges.countActive(dayStart, dayEnd),
            existing = today, previous = previous,
        )
        val change = reconcileCheckIns(plan.nudges, today, now)
        // The limits are checked again inside the transaction, and only rows still waiting are cancelled.
        val created = nudges.replaceCheckIns(
            change.cancel.map { it.id }, SAVED_AGAIN_REASON,
            change.create.map { NewNudge(it.at, it.note, it.planItemId, autoCheckIn = true) },
        ) { homeDay(it, zone) }
        return buildString {
            if (created.isNotEmpty()) {
                append(" Check-ins set at ").append(created.joinToString(", ") { agentTime(it.at, zone) }).append("; do not nudge for these yourself.")
                val loose = created.filter { it.planItemId == null }
                if (loose.isNotEmpty()) append(" The one at ").append(loose.joinToString(", ") { agentTime(it.at, zone) }).append(" is not linked to a plan item (pass `item` to link it).")
            }
            if (created.size < change.create.size) append(" Some check-ins were not set: a limit was reached, or the earlier one had just fired.")
            if (change.cancel.isNotEmpty()) append(" Replaced ${change.cancel.size} earlier check-in${if (change.cancel.size == 1) "" else "s"} of this plan.")
            if (plan.skipped.isNotEmpty()) append(" Not set: ").append(plan.skipped.joinToString("; ")).append(". Nudge for these yourself if they need a check-in.")
            if (plan.unscheduled.isNotEmpty()) {
                append(" Nothing wakes you for: ").append(plan.unscheduled.joinToString(" ")).append(" They have no `at`. Set `at` (HH:MM) when the person named a start time, or nudge for them yourself.")
            }
        }
    }

    /** Cancels today's waiting check-ins of items a redone plan put back or replaced: they are no longer on today's plan. */
    suspend fun cancelFor(items: List<PlanItem>, date: LocalDate) {
        if (items.isEmpty()) return
        val zone = settings.homeZone()
        val (dayStart, dayEnd) = homeDay(ScheduleClock.occurrence(date, kotlinx.datetime.LocalTime(12, 0), zone), zone)
        items.forEach { nudges.cancelForPlanItem(it.id, OFF_PLAN_REASON, dayStart, dayEnd) }
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
            "Later and smaller set one more check-in (at most $MAX_FOLLOW_UPS_PER_DAY a day per item); started and finished items get no more check-ins that day. " +
            "A tap on a check-in card comes with an operator note \"Tap answers card ref plan:<id>\": pass exactly that id.",
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
                val (dayStart, dayEnd) = homeDay(now, zone)
                // Only today's: a check-in the person set for another day stays.
                val cancelled = nudges.cancelForPlanItem(item.id, STARTING_REASON, dayStart, dayEnd)
                ToolResult.Ok("Marked started. No more check-ins on \"${item.text}\" today" + (if (cancelled > 0) " ($cancelled waiting ${if (cancelled == 1) "one" else "ones"} cancelled)" else "") + ". Acknowledge in a few words.")
            }
            PlanMove.Done, PlanMove.Dropped -> {
                val done = move == PlanMove.Done
                items.update(item.id) { it.copy(state = if (done) PlanItemState.DONE else PlanItemState.DROPPED) }
                nudges.cancelForPlanItem(item.id, if (done) "done" else "dropped")
                ToolResult.Ok("\"${item.text}\" is ${if (done) "done" else "dropped"}; its waiting check-ins are cancelled.")
            }
            is PlanMove.FollowUp -> {
                // The plan flow has caps of its own: the person's reminders neither block a follow-up nor count against it.
                val everything = nudges.pending()
                val pending = everything.filter { it.isPlan }
                val (dayStart, dayEnd) = homeDay(move.at, zone)
                followUpRefusal(nudges.countFollowUps(item.id, dayStart, dayEnd))?.let { return@tool ToolResult.Error(it) }
                val planDay = nudges.countActivePlan(dayStart, dayEnd)
                combinedRefusal(everything.size, planDay + nudges.countActive(dayStart, dayEnd))?.let { return@tool ToolResult.Error(it) }
                nudgeRefusal(move.at, move.note, null, now, pending, planDay, zone, item.id)?.let { return@tool ToolResult.Error(it) }
                if (move.firstStep != null) items.update(item.id) { it.copy(firstStep = move.firstStep, firstStepOn = today) }
                val created = nudges.create(move.at, move.note, null, item.id, followUp = true)
                ToolResult.Ok("Check-in ${created.id.value} set for ${agentTime(move.at, zone)}." + (move.firstStep?.let { " First step now: $it." } ?: ""))
            }
        }
    }
}
