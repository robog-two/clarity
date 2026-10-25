package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonElement
import net.robog.clarity.backend.persistence.AgentRuns
import net.robog.clarity.backend.persistence.tx
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update

/** Lifecycle states persisted in `agent_run.status`; terminal states mirror the harness' finish reason. */
object RunStatus {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

/** Persistence for agent runs: what ran, why, the full transcript and what it cost. */
interface AgentRunStore {
    suspend fun create(id: AgentRunId, reason: String, task: JsonElement, startedAt: Instant)
    suspend fun markRunning(id: AgentRunId)
    suspend fun finish(
        id: AgentRunId,
        status: String,
        transcript: JsonElement?,
        usage: JsonElement?,
        error: String?,
        finishedAt: Instant,
    )
}

@Inject
@ContributesBinding(AppScope::class)
class ExposedAgentRunStore(private val database: Database) : AgentRunStore {
    override suspend fun create(id: AgentRunId, reason: String, task: JsonElement, startedAt: Instant) {
        database.tx {
            AgentRuns.insert {
                it[AgentRuns.id] = Uuid.parse(id.value)
                it[AgentRuns.reason] = reason
                it[status] = RunStatus.QUEUED
                it[AgentRuns.task] = task
                it[AgentRuns.startedAt] = startedAt
            }
        }
    }

    override suspend fun markRunning(id: AgentRunId) {
        database.tx {
            AgentRuns.update({ AgentRuns.id eq Uuid.parse(id.value) }) { it[status] = RunStatus.RUNNING }
        }
    }

    override suspend fun finish(
        id: AgentRunId,
        status: String,
        transcript: JsonElement?,
        usage: JsonElement?,
        error: String?,
        finishedAt: Instant,
    ) {
        database.tx {
            AgentRuns.update({ AgentRuns.id eq Uuid.parse(id.value) }) {
                it[AgentRuns.status] = status
                it[AgentRuns.transcript] = transcript
                it[AgentRuns.usage] = usage
                it[AgentRuns.error] = error
                it[AgentRuns.finishedAt] = finishedAt
            }
        }
    }
}
