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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable
import net.robog.clarity.backend.persistence.parseWire
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.persistence.wire
import net.robog.clarity.harness.Description
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.tool
import net.robog.clarity.shared.ClarityJson
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
    override val primaryKey = PrimaryKey(id)
}

/** How many nudges may wait at once. */
const val MAX_PENDING_NUDGES = 10

/** How many nudges may fire on one of the person's days (and be set for one). */
const val MAX_NUDGES_PER_DAY = 12

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
    suspend fun create(at: Instant, note: String, taskId: TaskId?): Nudge

    suspend fun get(id: NudgeId): Nudge?

    /** Pending, soonest first. */
    suspend fun pending(): List<Nudge>

    /** Nudges due in [from, until) that are pending or fired: what counts toward a day's cap when setting one. */
    suspend fun countActive(from: Instant, until: Instant): Int

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
    override suspend fun create(at: Instant, note: String, taskId: TaskId?): Nudge {
        val id = Uuid.random()
        val now = clock.now()
        return database.tx {
            NudgeTable.insert {
                it[NudgeTable.id] = id
                it[dueAt] = at
                it[NudgeTable.note] = note
                it[NudgeTable.taskId] = taskId?.let { t -> Uuid.parse(t.value) }
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
            (NudgeTable.status inList listOf(PENDING, FIRED)) and (NudgeTable.dueAt greaterEq from) and (NudgeTable.dueAt less until)
        }.count().toInt()
    }

    override suspend fun countFired(from: Instant, until: Instant): Int = database.tx {
        NudgeTable.selectAll().where {
            (NudgeTable.status eq FIRED) and (NudgeTable.resolvedAt greaterEq from) and (NudgeTable.resolvedAt less until)
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
)

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
fun nudgeRefusal(at: Instant, note: String, taskId: TaskId?, now: Instant, pending: List<Nudge>, sameDay: Int, zone: TimeZone): String? {
    val dup = pending.firstOrNull { other ->
        (other.at - at).absoluteValue < NUDGE_DUPLICATE_WINDOW &&
            if (taskId != null) other.taskId == taskId else other.taskId == null && other.note.normalized() == note.normalized()
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
    private val settings: SettingsProvider,
    private val clock: Clock,
) {
    fun all(): List<Tool> = listOf(nudge(), listNudges(), cancelNudge())

    @Serializable
    class NudgeInput(
        @Description("When to wake up, the person's local time: 2026-10-27T13:30, or 13:30 for today.") val at: String,
        @Description("What to check when it fires, specific enough to ask from: \"ask how the ML report is going; it's due before The Devils at 20:30\".")
        val note: String,
        @Description("The task this check-in is about, if any. A nudge about a task that is done or cancelled by then is skipped.")
        val taskId: String? = null,
    )

    private fun nudge(): Tool = tool<NudgeInput>(
        name = "nudge",
        description = "Schedule yourself to wake up at a time and check in with the person (\"nudge me\", \"check in on me\", \"make sure I...\"). " +
            "When it fires you get the note in your inbox and decide what to ask. It sends nothing by itself. " +
            "For an alert the person wants on their own calendar or phone, use create_reminder instead.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        val zone = settings.homeZone()
        val now = clock.now()
        val at = readNudgeTime(input.at, zone, now).fold({ return@tool ToolResult.Error(it) }, { it })
        val note = input.note.trim()
        val taskId = input.taskId?.trim()?.takeIf { it.isNotEmpty() }?.let(::TaskId)
        if (taskId != null) {
            val task = tasks.get(taskId) ?: return@tool ToolResult.Error("No task with id ${taskId.value}.")
            if (nudgeTaskFinished(task)) return@tool ToolResult.Error("Task \"${task.title}\" is already ${task.status.wire()}; there is nothing to check.")
        }
        val pending = nudges.pending()
        // The very same nudge again (a looping model, or a call re-run after a restart) is the one already set.
        sameNudge(at, note, taskId, pending)?.let {
            return@tool ToolResult.Ok("Nudge ${it.id.value} is already set for ${agentTime(it.at, zone)}; no new one was made.")
        }
        val (dayStart, dayEnd) = homeDay(at, zone)
        nudgeRefusal(at, note, taskId, now, pending, nudges.countActive(dayStart, dayEnd), zone)
            ?.let { return@tool ToolResult.Error(it) }
        val created = nudges.create(at, note, taskId)
        ToolResult.Ok("Nudge ${created.id.value} set for ${agentTime(at, zone)}. Nothing is sent now; when it fires you decide what to ask.")
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
        val after = nudges.cancel(NudgeId(input.id.trim()), "cancelled by the agent")
            ?: return@tool ToolResult.Error("No nudge with id ${input.id}. See list_nudges.")
        when (after.status) {
            Nudge.Status.CANCELLED -> ToolResult.Ok("Nudge ${after.id.value} cancelled.")
            else -> ToolResult.Error("Nudge ${after.id.value} already ${after.status.wire()}; there is nothing to cancel.")
        }
    }
}
