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
        query.orderBy(AgentTasks.updatedAt to SortOrder.DESC).map { it.toTask() }
    }
}

private fun ResultRow.toTask() = AgentTask(
    id = TaskId(this[AgentTasks.id].toString()),
    title = this[AgentTasks.title],
    detail = this[AgentTasks.detail],
    status = parseWire<AgentTask.Status>(this[AgentTasks.status]) ?: AgentTask.Status.WORKING,
    heldActionId = this[AgentTasks.heldActionId]?.let(::HeldActionId),
    updatedAt = this[AgentTasks.updatedAt],
)
