package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.persistence.parseWire
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.persistence.wire
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.FeedEvent
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.governance.HeldActionId
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** The one lead thread (`agent_thread`). Its journal is stored under its id. */
data class LeadThread(val id: String, val compactedAt: Instant?, val lastTurnAt: Instant?, val lastUserInputAt: Instant?)

interface ThreadStore {
    /** The lead thread, created on first use. */
    suspend fun ensure(): LeadThread
    suspend fun touchTurn(at: Instant)
    suspend fun touchUserInput(at: Instant)
    suspend fun markCompacted(at: Instant)
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExposedThreadStore(private val database: Database, private val clock: Clock) : ThreadStore {
    override suspend fun ensure(): LeadThread = database.tx {
        val existing = AgentThreads.selectAll().orderBy(AgentThreads.createdAt).limit(1).singleOrNull()
        val row = existing ?: run {
            val id = Uuid.random()
            AgentThreads.insert {
                it[AgentThreads.id] = id
                it[createdAt] = clock.now()
            }
            AgentThreads.selectAll().where { AgentThreads.id eq id }.single()
        }
        LeadThread(
            row[AgentThreads.id].toString(), row[AgentThreads.compactedAt], row[AgentThreads.lastTurnAt], row[AgentThreads.lastUserInputAt],
        )
    }

    override suspend fun touchTurn(at: Instant) {
        database.tx { AgentThreads.update { it[lastTurnAt] = at } }
    }

    override suspend fun touchUserInput(at: Instant) {
        database.tx { AgentThreads.update { it[lastUserInputAt] = at } }
    }

    override suspend fun markCompacted(at: Instant) {
        database.tx { AgentThreads.update { it[compactedAt] = at } }
    }
}

/** The agent's own to-do list ("what I'm doing" on Today); changes are pushed to the app. */
interface TaskStore {
    /** Creates the task when [id] is null, else updates the given fields. Null when [id] is unknown. */
    suspend fun upsert(
        id: TaskId?,
        title: String?,
        detail: String?,
        status: AgentTask.Status?,
        heldActionId: HeldActionId? = null,
    ): AgentTask?

    /** Newest first (by last update), so fresh work leads and stale tasks sink. */
    suspend fun list(includeFinished: Boolean = false): List<AgentTask>

    suspend fun get(id: TaskId): AgentTask?

    /**
     * Records that helper [runId] works on task [id] ([how]: [TaskLink.EXPLICIT] or
     * [TaskLink.INFERRED]); the first link of a pair wins and nothing is rewritten. The task, with
     * its run ids, is pushed to the app. False when the task is unknown.
     */
    suspend fun link(id: TaskId, runId: String, how: String): Boolean

    /** When task [id] was created (its Agent's Path starts there). */
    suspend fun createdAt(id: TaskId): Instant?
}

/** How a helper run came to be linked to a task (`agent_task_run.how`). */
object TaskLink {
    const val EXPLICIT = "explicit"
    const val INFERRED = "inferred"
}

/**
 * The lead's current turn, for linking helpers to tasks without the lead naming one: the tasks it
 * created or updated, and the helpers it started without a task. While the turn has touched exactly
 * one task, helpers started in it belong to that task. Reset at every turn. Thread-safe.
 */
class TurnTasks {
    private val tasks = LinkedHashSet<String>()
    private val unlinked = mutableListOf<String>()

    @Synchronized fun reset() {
        tasks.clear()
        unlinked.clear()
    }

    /** Notes that the turn touched [task]; returns the unlinked helpers that now belong to it. */
    @Synchronized fun touched(task: String): List<String> {
        tasks += task
        if (tasks.size != 1) return emptyList()
        return unlinked.toList().also { unlinked.clear() }
    }

    /** Notes a helper started without a task; returns the task it belongs to, if the turn has one. */
    @Synchronized fun started(runId: String): String? {
        val only = tasks.singleOrNull()
        if (only == null) unlinked += runId
        return only
    }
}

private val FINISHED = listOf(AgentTask.Status.DONE, AgentTask.Status.FAILED, AgentTask.Status.CANCELLED)

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExposedTaskStore(
    private val database: Database,
    private val clock: Clock,
    private val feed: FeedService,
) : TaskStore {
    override suspend fun upsert(
        id: TaskId?,
        title: String?,
        detail: String?,
        status: AgentTask.Status?,
        heldActionId: HeldActionId?,
    ): AgentTask? {
        val now = clock.now()
        val task = database.tx {
            if (id == null) {
                val newId = Uuid.random()
                AgentTasks.insert {
                    it[AgentTasks.id] = newId
                    it[AgentTasks.title] = title ?: "Untitled"
                    it[AgentTasks.detail] = detail.orEmpty()
                    it[AgentTasks.status] = (status ?: AgentTask.Status.WORKING).wire()
                    it[AgentTasks.heldActionId] = heldActionId?.value
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                AgentTasks.selectAll().where { AgentTasks.id eq newId }.single().toTask()
            } else {
                val uuid = runCatching { Uuid.parse(id.value) }.getOrNull() ?: return@tx null
                val changed = AgentTasks.update({ AgentTasks.id eq uuid }) {
                    title?.let { t -> it[AgentTasks.title] = t }
                    detail?.let { d -> it[AgentTasks.detail] = d }
                    status?.let { s -> it[AgentTasks.status] = s.wire() }
                    heldActionId?.let { h -> it[AgentTasks.heldActionId] = h.value }
                    it[updatedAt] = now
                }
                if (changed == 0) null else AgentTasks.selectAll().where { AgentTasks.id eq uuid }.single().toTask()
            }
        }
        task?.let { feed.publish(FeedEvent.TaskChanged(it)) }
        return task
    }

    override suspend fun list(includeFinished: Boolean): List<AgentTask> = database.tx {
        val query = AgentTasks.selectAll()
        if (!includeFinished) query.where { AgentTasks.status inList listOf("working", "drafted", "waiting_for_you", "scheduled") }
        val rows = query.orderBy(AgentTasks.updatedAt to SortOrder.DESC).toList()
        val runs = runsOf(rows.map { it[AgentTasks.id] })
        rows.map { it.toTask(runs[it[AgentTasks.id]].orEmpty()) }
    }

    override suspend fun createdAt(id: TaskId): Instant? {
        val uuid = runCatching { Uuid.parse(id.value) }.getOrNull() ?: return null
        return database.tx { AgentTasks.select(AgentTasks.createdAt).where { AgentTasks.id eq uuid }.singleOrNull()?.get(AgentTasks.createdAt) }
    }

    override suspend fun link(id: TaskId, runId: String, how: String): Boolean {
        val task = runCatching { Uuid.parse(id.value) }.getOrNull() ?: return false
        val run = runCatching { Uuid.parse(runId) }.getOrNull() ?: return false
        val linked = database.tx {
            if (AgentTasks.selectAll().where { AgentTasks.id eq task }.empty()) return@tx null
            AgentTaskRuns.insertIgnore {
                it[taskId] = task
                it[AgentTaskRuns.runId] = run
                it[AgentTaskRuns.how] = how
                it[createdAt] = clock.now()
            }
            AgentTasks.selectAll().where { AgentTasks.id eq task }.single().toTask()
        } ?: return false
        feed.publish(FeedEvent.TaskChanged(linked))
        return true
    }

    override suspend fun get(id: TaskId): AgentTask? {
        val uuid = runCatching { Uuid.parse(id.value) }.getOrNull() ?: return null
        return database.tx {
            AgentTasks.selectAll().where { AgentTasks.id eq uuid }.singleOrNull()?.toTask(runsOf(listOf(uuid))[uuid].orEmpty())
        }
    }
}

/** The run ids linked to each of [tasks], oldest link first. Call inside a transaction. */
private fun runsOf(tasks: List<Uuid>): Map<Uuid, List<String>> =
    if (tasks.isEmpty()) emptyMap()
    else AgentTaskRuns.selectAll().where { AgentTaskRuns.taskId inList tasks }
        .orderBy(AgentTaskRuns.createdAt to SortOrder.ASC)
        .groupBy({ it[AgentTaskRuns.taskId] }, { it[AgentTaskRuns.runId].toString() })

/** A task row with its linked runs (looked up when not given). Call inside a transaction. */
private fun ResultRow.toTask(runIds: List<String> = runsOf(listOf(this[AgentTasks.id]))[this[AgentTasks.id]].orEmpty()) = AgentTask(
    id = TaskId(this[AgentTasks.id].toString()),
    title = this[AgentTasks.title],
    detail = this[AgentTasks.detail],
    status = parseWire<AgentTask.Status>(this[AgentTasks.status]) ?: AgentTask.Status.WORKING,
    heldActionId = this[AgentTasks.heldActionId]?.let(::HeldActionId),
    updatedAt = this[AgentTasks.updatedAt],
    runIds = runIds,
)
