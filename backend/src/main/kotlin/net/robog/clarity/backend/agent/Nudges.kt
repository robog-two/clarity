package net.robog.clarity.backend.agent

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import net.robog.clarity.backend.persistence.NounReader
import net.robog.clarity.backend.persistence.TimeRange
import net.robog.clarity.backend.persistence.parseWire
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.persistence.wire
import net.robog.clarity.harness.Description
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.tool
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.backend.sessions.PlanItemStore
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.Nudge
import net.robog.clarity.shared.feed.NudgeId
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.time.ScheduleClock
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** Mirrors db/migration/V24__nudge.sql. */
object NudgeTable : Table("nudge") {
    val id = uuid("id")
    val dueAt = timestamp("due_at")
    val note = text("note")
    val taskId = uuid("task_id").nullable()
    val status = text("status")
    val reason = text("reason").nullable()
    val createdAt = timestamp("created_at")
    val resolvedAt = timestamp("resolved_at").nullable()
    val planItemId = uuid("plan_item_id").nullable()
    val followUp = bool("follow_up")
    val autoCheckIn = bool("auto_check_in")
    override val primaryKey = PrimaryKey(id)
}

/** How many nudges may wait at once. */
const val MAX_PENDING_NUDGES = 10

/** How many nudges may fire on one of the person's days (and be set for one). */
const val MAX_NUDGES_PER_DAY = 12

/** The most nudges waiting at once, the person's and the plan's together. */
const val MAX_PENDING_NUDGES_ALL = 20

/** The most nudges (waiting or fired) due on one of the person's days, the person's and the plan's together. */
const val MAX_NUDGES_ALL_PER_DAY = 20

/** Pure: why one more nudge cannot be set because of the ceiling over everyone's nudges, or null. [pendingAll] wait now, [dayAll] are due that day. */
fun combinedRefusal(pendingAll: Int, dayAll: Int): String? = when {
    pendingAll >= MAX_PENDING_NUDGES_ALL -> "There are already $MAX_PENDING_NUDGES_ALL nudges and check-ins waiting in all. Cancel one first."
    dayAll >= MAX_NUDGES_ALL_PER_DAY -> "That day already has $MAX_NUDGES_ALL_PER_DAY nudges and check-ins in all, the most allowed."
    else -> null
}

/** Why `cancel_nudge` cancels (the lead's own doing): a check-in cancelled so counts as settled and is not made again by a re-save. */
const val AGENT_CANCEL_REASON = "cancelled by the agent"

/** Serialises the writers of plan check-ins (see `replaceCheckIns`). */
private const val PLAN_WRITE_LOCK = 0x706c616e63L

/** A nudge to be stored. */
data class NewNudge(val at: Instant, val note: String, val planItemId: String? = null, val followUp: Boolean = false, val autoCheckIn: Boolean = false)

/** How far ahead a nudge may be set. */
val NUDGE_HORIZON: Duration = 7.days

/** Two nudges about the same thing this close together are one too many. */
val NUDGE_DUPLICATE_WINDOW: Duration = 10.minutes

const val MAX_NUDGE_NOTE_CHARS = 500

/** How a due nudge was resolved. [reason] is plain words for the timeline; null when it fired. */
data class NudgeResolution(val status: Nudge.Status, val reason: String? = null) {
    init { require(status != Nudge.Status.PENDING) { "a resolution is not pending" } }
}

/** The durable nudges (`nudge`). Each pending row is resolved exactly once, by [resolveDue] or [cancel]. */
interface NudgeStore {
    suspend fun create(at: Instant, note: String, taskId: TaskId?, planItemId: String? = null, followUp: Boolean = false, autoCheckIn: Boolean = false): Nudge

    suspend fun get(id: NudgeId): Nudge?

    /** Pending, soonest first. */
    suspend fun pending(): List<Nudge>

    /** The person's own nudges (not plan check-ins or follow-ups) due in [from, until) that are pending or fired: what counts toward a day's cap when setting one. */
    suspend fun countActive(from: Instant, until: Instant): Int

    /** The automatic check-ins (any status, follow-ups excluded) due in [from, until). */
    suspend fun autoCheckIns(from: Instant, until: Instant): List<Nudge>

    /**
     * In one transaction, under a lock that serialises plan writers: cancels the nudges [cancel] that are STILL pending
     * (one that fired in between is left, and no replacement is made for its item), then stores each of [create] that the
     * combined and per-side limits still allow (counted again here; [dayOf] is the person's day of a moment). Returns the new ones.
     */
    suspend fun replaceCheckIns(cancel: List<NudgeId>, reason: String, create: List<NewNudge>, dayOf: (Instant) -> Pair<Instant, Instant>): List<Nudge>

    /** Plan nudges that fired in [from, until). */
    suspend fun countFiredPlan(from: Instant, until: Instant): Int

    /** The same for the plan flow's nudges (check-ins an if-then set, follow-ups), which have their own cap. */
    suspend fun countActivePlan(from: Instant, until: Instant): Int

    /** Follow-up nudges for the plan item [planItemId] due in [from, until) that are pending or fired. */
    suspend fun countFollowUps(planItemId: String, from: Instant, until: Instant): Int

    /** Cancels the pending nudges of the plan item [planItemId] due in [from, until) (all of them when both are null); returns how many. */
    suspend fun cancelForPlanItem(planItemId: String, reason: String, from: Instant? = null, until: Instant? = null): Int

    /** Nudges that fired in [from, until). */
    suspend fun countFired(from: Instant, until: Instant): Int

    /** Cancels a pending nudge; returns it as it now is (a nudge that already resolved stays as it was), null when unknown. */
    suspend fun cancel(id: NudgeId, reason: String): Nudge?

    /** Resolved nudges since [since], newest first: the timeline. */
    suspend fun history(since: Instant, limit: Int = 200): List<Nudge>

    /**
     * Resolves every pending nudge due by [now] (at most [limit]): claims the rows with
     * `FOR UPDATE SKIP LOCKED` in one transaction, asks [decide] for each, and records the outcome
     * there too, so two instances never resolve the same nudge. A failure rolls back, and the
     * nudges stay pending for the next tick.
     */
    suspend fun resolveDue(now: Instant, limit: Int, decide: suspend (Nudge) -> NudgeResolution): List<Pair<Nudge, NudgeResolution>>
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExposedNudgeStore(private val database: Database, private val clock: Clock) : NudgeStore {
    override suspend fun create(at: Instant, note: String, taskId: TaskId?, planItemId: String?, followUp: Boolean, autoCheckIn: Boolean): Nudge {
        val id = Uuid.random()
        val now = clock.now()
        return database.tx {
            NudgeTable.insert {
                it[NudgeTable.id] = id
                it[dueAt] = at
                it[NudgeTable.note] = note
                it[NudgeTable.taskId] = taskId?.let { t -> Uuid.parse(t.value) }
                it[NudgeTable.planItemId] = planItemId?.let { p -> Uuid.parse(p) }
                it[NudgeTable.followUp] = followUp
                it[NudgeTable.autoCheckIn] = autoCheckIn
                it[status] = Nudge.Status.PENDING.wire()
                it[createdAt] = now
            }
            NudgeTable.selectAll().where { NudgeTable.id eq id }.single().toNudge()
        }
    }

    override suspend fun get(id: NudgeId): Nudge? {
        val uuid = id.uuidOrNull() ?: return null
        return database.tx { NudgeTable.selectAll().where { NudgeTable.id eq uuid }.singleOrNull()?.toNudge() }
    }

    override suspend fun pending(): List<Nudge> = database.tx {
        NudgeTable.selectAll().where { NudgeTable.status eq PENDING }.orderBy(NudgeTable.dueAt).map { it.toNudge() }
    }

    override suspend fun countActive(from: Instant, until: Instant): Int = database.tx {
        NudgeTable.selectAll().where {
            (NudgeTable.status inList listOf(PENDING, FIRED)) and (NudgeTable.dueAt greaterEq from) and (NudgeTable.dueAt less until) and
                NudgeTable.planItemId.isNull() and not(NudgeTable.autoCheckIn) and not(NudgeTable.followUp)
        }.count().toInt()
    }

    override suspend fun autoCheckIns(from: Instant, until: Instant): List<Nudge> = database.tx {
        NudgeTable.selectAll().where {
            NudgeTable.autoCheckIn and not(NudgeTable.followUp) and (NudgeTable.dueAt greaterEq from) and (NudgeTable.dueAt less until)
        }.orderBy(NudgeTable.dueAt).map { it.toNudge() }
    }

    override suspend fun replaceCheckIns(
        cancel: List<NudgeId>,
        reason: String,
        create: List<NewNudge>,
        dayOf: (Instant) -> Pair<Instant, Instant>,
    ): List<Nudge> {
        val now = clock.now()
        val ids = cancel.mapNotNull { it.uuidOrNull() }
        return database.tx {
            exec("SELECT pg_advisory_xact_lock($PLAN_WRITE_LOCK)")
            val stillPending = NudgeTable.selectAll().where { (NudgeTable.id inList ids) and (NudgeTable.status eq PENDING) }.forUpdate().map { it[NudgeTable.id] }.toSet()
            val lostRows = NudgeTable.selectAll().where { NudgeTable.id inList ids }.filter { it[NudgeTable.id] !in stillPending }
            val lost = lostRows.mapNotNull { it[NudgeTable.planItemId]?.toString() }.toSet()
            // A check-in with no item that fired in between: its replacement (same note or same time) is not made either.
            val lostLoose = lostRows.filter { it[NudgeTable.planItemId] == null }.map { it[NudgeTable.note] to it[NudgeTable.dueAt] }
            if (stillPending.isNotEmpty()) {
                NudgeTable.update({ NudgeTable.id inList stillPending.toList() }) {
                    it[status] = Nudge.Status.CANCELLED.wire()
                    it[NudgeTable.reason] = reason
                    it[resolvedAt] = now
                }
            }
            val planRow = NudgeTable.planItemId.isNotNull() or NudgeTable.autoCheckIn or NudgeTable.followUp
            create.filter { n ->
                if (n.planItemId != null) n.planItemId !in lost else lostLoose.none { (note, at) -> note == n.note || at == n.at }
            }.mapNotNull { n ->
                val (dayStart, dayEnd) = dayOf(n.at)
                val live = NudgeTable.status inList listOf(PENDING, FIRED)
                val inDay = (NudgeTable.dueAt greaterEq dayStart) and (NudgeTable.dueAt less dayEnd)
                val pendingAll = NudgeTable.selectAll().where { NudgeTable.status eq PENDING }.count().toInt()
                val pendingPlan = NudgeTable.selectAll().where { (NudgeTable.status eq PENDING) and planRow }.count().toInt()
                val dayAll = NudgeTable.selectAll().where { live and inDay }.count().toInt()
                val dayPlan = NudgeTable.selectAll().where { live and inDay and planRow }.count().toInt()
                if (combinedRefusal(pendingAll, dayAll) != null || pendingPlan >= MAX_PENDING_NUDGES || dayPlan >= MAX_NUDGES_PER_DAY) return@mapNotNull null
                val id = Uuid.random()
                NudgeTable.insert {
                    it[NudgeTable.id] = id
                    it[dueAt] = n.at
                    it[note] = n.note
                    it[planItemId] = n.planItemId?.let { p -> Uuid.parse(p) }
                    it[followUp] = n.followUp
                    it[autoCheckIn] = n.autoCheckIn
                    it[status] = Nudge.Status.PENDING.wire()
                    it[createdAt] = now
                }
                NudgeTable.selectAll().where { NudgeTable.id eq id }.single().toNudge()
            }
        }
    }

    override suspend fun countFiredPlan(from: Instant, until: Instant): Int = database.tx {
        NudgeTable.selectAll().where {
            (NudgeTable.status eq FIRED) and (NudgeTable.resolvedAt greaterEq from) and (NudgeTable.resolvedAt less until) and
                (NudgeTable.planItemId.isNotNull() or NudgeTable.autoCheckIn or NudgeTable.followUp)
        }.count().toInt()
    }

    override suspend fun countActivePlan(from: Instant, until: Instant): Int = database.tx {
        NudgeTable.selectAll().where {
            (NudgeTable.status inList listOf(PENDING, FIRED)) and (NudgeTable.dueAt greaterEq from) and (NudgeTable.dueAt less until) and
                (NudgeTable.planItemId.isNotNull() or NudgeTable.autoCheckIn or NudgeTable.followUp)
        }.count().toInt()
    }

    override suspend fun countFollowUps(planItemId: String, from: Instant, until: Instant): Int {
        val item = runCatching { Uuid.parse(planItemId) }.getOrNull() ?: return 0
        return database.tx {
            NudgeTable.selectAll().where {
                (NudgeTable.planItemId eq item) and NudgeTable.followUp and (NudgeTable.status inList listOf(PENDING, FIRED)) and
                    (NudgeTable.dueAt greaterEq from) and (NudgeTable.dueAt less until)
            }.count().toInt()
        }
    }

    override suspend fun cancelForPlanItem(planItemId: String, reason: String, from: Instant?, until: Instant?): Int {
        val item = runCatching { Uuid.parse(planItemId) }.getOrNull() ?: return 0
        val now = clock.now()
        return database.tx {
            NudgeTable.update({
                (NudgeTable.planItemId eq item) and (NudgeTable.status eq PENDING) and
                    (if (from != null) NudgeTable.dueAt greaterEq from else Op.TRUE) and (if (until != null) NudgeTable.dueAt less until else Op.TRUE)
            }) {
                it[status] = Nudge.Status.CANCELLED.wire()
                it[NudgeTable.reason] = reason
                it[resolvedAt] = now
            }
        }
    }

    override suspend fun countFired(from: Instant, until: Instant): Int = database.tx {
        NudgeTable.selectAll().where {
            (NudgeTable.status eq FIRED) and (NudgeTable.resolvedAt greaterEq from) and (NudgeTable.resolvedAt less until) and
                NudgeTable.planItemId.isNull() and not(NudgeTable.autoCheckIn) and not(NudgeTable.followUp)
        }.count().toInt()
    }

    override suspend fun cancel(id: NudgeId, reason: String): Nudge? {
        val uuid = id.uuidOrNull() ?: return null
        val now = clock.now()
        return database.tx {
            NudgeTable.update({ (NudgeTable.id eq uuid) and (NudgeTable.status eq PENDING) }) {
                it[status] = Nudge.Status.CANCELLED.wire()
                it[NudgeTable.reason] = reason
                it[resolvedAt] = now
            }
            NudgeTable.selectAll().where { NudgeTable.id eq uuid }.singleOrNull()?.toNudge()
        }
    }

    override suspend fun history(since: Instant, limit: Int): List<Nudge> = database.tx {
        NudgeTable.selectAll().where { NudgeTable.resolvedAt.isNotNull() and (NudgeTable.resolvedAt greaterEq since) }
            .orderBy(NudgeTable.resolvedAt to SortOrder.DESC).limit(limit).map { it.toNudge() }
    }

    override suspend fun resolveDue(
        now: Instant,
        limit: Int,
        decide: suspend (Nudge) -> NudgeResolution,
    ): List<Pair<Nudge, NudgeResolution>> = database.tx {
        val due = NudgeTable.selectAll().where { (NudgeTable.status eq PENDING) and (NudgeTable.dueAt lessEq now) }
            .orderBy(NudgeTable.dueAt).limit(limit)
            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
            .map { it.toNudge() }
        due.map { nudge ->
            val outcome = decide(nudge)
            NudgeTable.update({ NudgeTable.id eq Uuid.parse(nudge.id.value) }) {
                it[status] = outcome.status.wire()
                it[reason] = outcome.reason
                it[resolvedAt] = clock.now()
            }
            nudge to outcome
        }
    }

    private fun NudgeId.uuidOrNull(): Uuid? = runCatching { Uuid.parse(value) }.getOrNull()

    private companion object {
        val PENDING = Nudge.Status.PENDING.wire()
        val FIRED = Nudge.Status.FIRED.wire()
    }
}

private fun ResultRow.toNudge() = Nudge(
    id = NudgeId(this[NudgeTable.id].toString()),
    at = this[NudgeTable.dueAt],
    note = this[NudgeTable.note],
    taskId = this[NudgeTable.taskId]?.let { TaskId(it.toString()) },
    status = parseWire<Nudge.Status>(this[NudgeTable.status]) ?: Nudge.Status.PENDING,
    createdAt = this[NudgeTable.createdAt],
    resolvedAt = this[NudgeTable.resolvedAt],
    reason = this[NudgeTable.reason],
    planItemId = this[NudgeTable.planItemId]?.toString(),
    autoCheckIn = this[NudgeTable.autoCheckIn],
    followUp = this[NudgeTable.followUp],
)

private const val DEFAULT_GAP_MINUTES = 15
private const val MAX_GAP_MINUTES = 240
private const val DEFAULT_WITHIN_HOURS = 8
private const val MAX_WITHIN_HOURS = 24
private const val MAX_FREE_SLOT_EVENTS = 300

/** Pure: the start and end of the person's day that holds [moment]. */
fun homeDay(moment: Instant, zone: TimeZone): Pair<Instant, Instant> {
    val date = ScheduleClock.homeDate(moment, zone)
    return date.atStartOfDayIn(zone) to date.plus(DatePeriod(days = 1)).atStartOfDayIn(zone)
}

private val CLOCK_ONLY = Regex("""^\d{1,2}:\d{2}$""")

/**
 * Pure: a nudge's `at`: a local date-time like the other tools take, or "HH:MM" for that time
 * today on the person's clock. Left is the message for the model.
 */
fun readNudgeTime(raw: String, zone: TimeZone, now: Instant): Either<String, Instant> {
    val text = raw.trim()
    if (CLOCK_ONLY.matches(text)) {
        val time = parseClock(text) ?: return "`at` is not a time of day: \"$raw\".".left()
        return ScheduleClock.occurrence(ScheduleClock.homeDate(now, zone), time, zone).right()
    }
    return readToolTime("at", text, zone)
}

/**
 * Pure: why a new nudge for [at] is refused, or null when it may be set. [pending] are the
 * nudges waiting now; [sameDay] counts the pending and fired ones due on [at]'s day.
 */
fun nudgeRefusal(at: Instant, note: String, taskId: TaskId?, now: Instant, pending: List<Nudge>, sameDay: Int, zone: TimeZone, planItemId: String? = null): String? {
    val dup = pending.firstOrNull { other ->
        (other.at - at).absoluteValue < NUDGE_DUPLICATE_WINDOW &&
            when {
                taskId != null -> other.taskId == taskId
                planItemId != null -> other.planItemId == planItemId
                else -> other.taskId == null && other.note.normalized() == note.normalized()
            }
    }
    return when {
        note.isBlank() -> "`note` must say what to check."
        note.length > MAX_NUDGE_NOTE_CHARS -> "`note` is at most $MAX_NUDGE_NOTE_CHARS characters; say what to check in a sentence or two."
        at <= now -> "${agentTime(at, zone)} has already passed (it is now ${agentTime(now, zone)}). Give a time in the future."
        at > now + NUDGE_HORIZON -> "A nudge can be at most ${NUDGE_HORIZON.inWholeDays} days ahead. For later, use create_reminder or set it nearer the time."
        pending.size >= MAX_PENDING_NUDGES -> "There are already $MAX_PENDING_NUDGES nudges waiting. Cancel one (list_nudges, cancel_nudge) first."
        dup != null -> "Nudge ${dup.id.value} at ${agentTime(dup.at, zone)} already covers this. Keep it, or cancel it and set a new one."
        sameDay >= MAX_NUDGES_PER_DAY -> "That day already has $MAX_NUDGES_PER_DAY nudges, the most allowed. Combine check-ins instead."
        else -> null
    }
}

private fun String.normalized() = trim().lowercase().replace(Regex("\\s+"), " ")

/** Pure: the pending nudge that is exactly this one (same time, task and note), if any. */
fun sameNudge(at: Instant, note: String, taskId: TaskId?, pending: List<Nudge>): Nudge? =
    pending.firstOrNull { it.at == at && it.taskId == taskId && it.note.normalized() == note.normalized() }

private val FINISHED_TASK = setOf(AgentTask.Status.DONE, AgentTask.Status.CANCELLED)

/** Pure: whether a nudge about [task] has nothing left to check. */
fun nudgeTaskFinished(task: AgentTask?): Boolean = task != null && task.status in FINISHED_TASK

/** The lead's nudge tools: Clarity schedules itself to wake up and check in with the person. */
@Inject
@SingleIn(AppScope::class)
class NudgeTools(
    private val nudges: NudgeStore,
    private val tasks: TaskStore,
    private val planItems: PlanItemStore,
    private val settings: SettingsProvider,
    private val clock: Clock,
    private val reader: NounReader,
) {
    fun all(): List<Tool> = listOf(nudge(), listNudges(), cancelNudge())

    @Serializable
    class NudgeInput(
        @Description("When to wake up, the person's local time: 2026-10-27T13:30, or 13:30 for today. Give this or freeSlot, not both.") val at: String? = null,
        @Description("Instead of at: wake up at the next time the person has nothing on their calendar. Use it for check-ins that can wait for a gap.")
        val freeSlot: FreeSlotInput? = null,
        @Description("What to check when it fires, specific enough to ask from: \"ask how the ML report is going; it's due before The Devils at 20:30\".")
        val note: String,
        @Description("The task this check-in is about, if any. A nudge about a task that is done or cancelled by then is skipped.")
        val taskId: String? = null,
        @Description("The plan item (a priority that keeps coming back) this check-in is about, if any. Not needed for the person's own reminders.")
        val planItemId: String? = null,
    )

    @Serializable
    class FreeSlotInput(
        @Description("Look for a gap from this time on (like at); default now.") val after: String? = null,
        @Description("How long the gap must be, in minutes; default $DEFAULT_GAP_MINUTES, at most $MAX_GAP_MINUTES.") val minMinutes: Int? = null,
        @Description("How many hours ahead to look; default $DEFAULT_WITHIN_HOURS, at most $MAX_WITHIN_HOURS.") val within: Int? = null,
    )

    /** The time [slot] asks for: the first gap in the calendar. Left is the message for the model. */
    private suspend fun freeSlotTime(slot: FreeSlotInput, zone: TimeZone, now: Instant): Either<String, Instant> {
        val earliest = now + 1.minutes
        val after = slot.after?.takeIf { it.isNotBlank() }
            ?.let { readNudgeTime(it, zone, now).fold({ msg -> return msg.left() }, { t -> t }) }?.let { maxOf(it, earliest) } ?: earliest
        val gap = (slot.minMinutes ?: DEFAULT_GAP_MINUTES).coerceIn(1, MAX_GAP_MINUTES).minutes
        val until = after + (slot.within ?: DEFAULT_WITHIN_HOURS).coerceIn(1, MAX_WITHIN_HOURS).hours
        val events = reader.calendarEvents(TimeRange(after, until + gap), MAX_FREE_SLOT_EVENTS)
        if (events.size >= MAX_FREE_SLOT_EVENTS) return "The calendar is too full in that window to find a gap. Give an exact time with at.".left()
        return nextFreeStart(events.map { busyOf(it.payload) }, after, gap, until)?.right()
            ?: "No ${gap.inWholeMinutes}-minute gap in the calendar between ${agentTime(after, zone)} and ${agentTime(until, zone)}. Give an exact time with at, or look further ahead.".left()
    }

    private fun nudge(): Tool = tool<NudgeInput>(
        name = "nudge",
        description = "Schedule yourself to wake up at a time and check in with the person (\"nudge me\", \"check in on me\", \"make sure I...\"). " +
            "When it fires you get the note in your inbox and decide what to ask. It sends nothing by itself. " +
            "Give `at` for an exact time, or `freeSlot` for the next gap in the person's calendar (it is only a way to pick the time; check-ins during events are fine). " +
            "For an alert the person wants on their own calendar or phone, use create_reminder instead.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        val zone = settings.homeZone()
        val now = clock.now()
        val slot = input.freeSlot
        if ((input.at == null) == (slot == null)) return@tool ToolResult.Error("Give either `at` (an exact time) or `freeSlot`, not both and not neither.")
        val at = (if (slot != null) freeSlotTime(slot, zone, now) else readNudgeTime(input.at!!, zone, now)).fold({ return@tool ToolResult.Error(it) }, { it })
        val chosen = if (slot != null) " (free from ${agentTime(at, zone)})" else ""
        val note = input.note.trim()
        val taskId = input.taskId?.trim()?.takeIf { it.isNotEmpty() }?.let(::TaskId)
        var taskTitle: String? = null
        if (taskId != null) {
            val task = tasks.get(taskId) ?: return@tool ToolResult.Error("No task with id ${taskId.value}.")
            if (nudgeTaskFinished(task)) return@tool ToolResult.Error("Task \"${task.title}\" is already ${task.status.wire()}; there is nothing to check.")
            taskTitle = task.title
        }
        val everything = nudges.pending()
        // A check-in on the SAME plan item was already set for this time (set_plan does it); a second one would ask twice.
        // A reminder about anything else is never swallowed by it.
        coveringCheckIn(at, input.planItemId?.trim()?.takeIf { it.isNotEmpty() }, listOfNotNull(note, taskTitle), everything, planItems.open().associateBy { it.id })?.let {
            return@tool ToolResult.Ok("Nudge ${it.id.value} at ${agentTime(it.at, zone)} already checks in on the plan then; no new one was made.")
        }
        // The person's own nudges have their limits; the plan flow's check-ins have theirs (see PlanNudger) and do not count here.
        val pending = everything.filter { !it.isPlan }
        // The very same nudge again (a looping model, or a call re-run after a restart) is the one already set.
        sameNudge(at, note, taskId, pending)?.let {
            return@tool ToolResult.Ok("Nudge ${it.id.value} is already set for ${agentTime(it.at, zone)}; no new one was made.")
        }
        val (dayStart, dayEnd) = homeDay(at, zone)
        val personDay = nudges.countActive(dayStart, dayEnd)
        combinedRefusal(everything.size, personDay + nudges.countActivePlan(dayStart, dayEnd))?.let { return@tool ToolResult.Error(it) }
        nudgeRefusal(at, note, taskId, now, pending, personDay, zone)
            ?.let { return@tool ToolResult.Error(it) }
        val created = nudges.create(at, note, taskId)
        ToolResult.Ok("Nudge ${created.id.value} set for ${agentTime(at, zone)}$chosen. Nothing is sent now; when it fires you decide what to ask.")
    }

    @Serializable
    class NoInput

    private fun listNudges(): Tool = tool<NoInput>(
        name = "list_nudges",
        description = "List the nudges waiting to fire (id, time, note, task).",
        json = ClarityJson,
    ) {
        val zone = settings.homeZone()
        val pending = nudges.pending()
        val (dayStart, dayEnd) = homeDay(clock.now(), zone)
        val fired = nudges.countFired(dayStart, dayEnd)
        val lines = pending.map { n ->
            val task = n.taskId?.let { tasks.get(it) }?.let { " · task ${it.id.value}: ${it.title} (${it.status.wire()})" }.orEmpty()
            "${n.id.value} · ${agentTime(n.at, zone)} · ${n.note}$task"
        }
        ToolResult.Ok((if (lines.isEmpty()) "(none waiting)" else lines.joinToString("\n")) + "\nFired today: $fired of $MAX_NUDGES_PER_DAY.")
    }

    @Serializable
    class CancelNudgeInput(@Description("The nudge id from nudge or list_nudges.") val id: String)

    private fun cancelNudge(): Tool = tool<CancelNudgeInput>(
        name = "cancel_nudge",
        description = "Cancel a nudge that has not fired yet (the person gave the update already, or the plan changed).",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        val after = nudges.cancel(NudgeId(input.id.trim()), AGENT_CANCEL_REASON)
            ?: return@tool ToolResult.Error("No nudge with id ${input.id}. See list_nudges.")
        when (after.status) {
            Nudge.Status.CANCELLED -> ToolResult.Ok("Nudge ${after.id.value} cancelled.")
            else -> ToolResult.Error("Nudge ${after.id.value} already ${after.status.wire()}; there is nothing to cancel.")
        }
    }
}
