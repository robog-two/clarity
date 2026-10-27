package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import net.robog.clarity.backend.persistence.AgentRuns
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.HarnessJson
import net.robog.clarity.harness.Usage
import net.robog.clarity.shared.ClarityJson
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** Lifecycle states persisted in `agent_run.status`. */
object RunStatus {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    val OPEN = listOf(QUEUED, RUNNING)
}

/** A helper run (`agent_run`). The transcript lives in `agent_journal` under [id]. */
data class HelperRun(
    val id: String,
    val parentRunId: String?,
    val role: String,
    val task: String,
    val tools: List<String>?,
    val status: String,
    val result: String?,
    val startedAt: Instant,
)

interface RunStore {
    suspend fun create(id: String, parentRunId: String?, role: String, task: String, tools: List<String>?, startedAt: Instant)
    suspend fun markRunning(id: String)
    suspend fun finish(id: String, status: String, result: String?, usage: Usage?, error: String?, finishedAt: Instant)
    suspend fun get(id: String): HelperRun?

    /** Stores a copy of the run's transcript (already scrubbed of secret values) for diagnosis. */
    suspend fun saveTranscript(id: String, transcript: JsonElement) {}

    /** Runs that were queued or running when the process stopped. */
    suspend fun open(): List<HelperRun>
    suspend fun list(openOnly: Boolean, limit: Int = 50): List<HelperRun>
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExposedRunStore(private val database: Database, private val clock: Clock) : RunStore {
    override suspend fun create(id: String, parentRunId: String?, role: String, task: String, tools: List<String>?, startedAt: Instant) {
        database.tx {
            AgentRuns.insert {
                it[AgentRuns.id] = Uuid.parse(id)
                it[reason] = "helper"
                it[status] = RunStatus.QUEUED
                it[AgentRuns.task] = ClarityJson.encodeToJsonElement(String.serializer(), task)
                it[AgentRuns.parentRunId] = parentRunId?.let(Uuid::parse)
                it[AgentRuns.role] = role
                it[AgentRuns.tools] = tools?.let { t -> ClarityJson.encodeToJsonElement(ListSerializer(String.serializer()), t) }
                it[AgentRuns.startedAt] = startedAt
            }
        }
    }

    override suspend fun markRunning(id: String) {
        database.tx { AgentRuns.update({ AgentRuns.id eq Uuid.parse(id) }) { it[status] = RunStatus.RUNNING } }
    }

    override suspend fun finish(id: String, status: String, result: String?, usage: Usage?, error: String?, finishedAt: Instant) {
        database.tx {
            AgentRuns.update({ (AgentRuns.id eq Uuid.parse(id)) and (AgentRuns.status inList RunStatus.OPEN) }) {
                it[AgentRuns.status] = status
                it[AgentRuns.result] = result
                it[AgentRuns.usage] = usage?.let { u -> HarnessJson.encodeToJsonElement(Usage.serializer(), u) }
                it[AgentRuns.error] = error
                it[AgentRuns.finishedAt] = finishedAt
            }
        }
    }

    override suspend fun saveTranscript(id: String, transcript: JsonElement) {
        database.tx { AgentRuns.update({ AgentRuns.id eq Uuid.parse(id) }) { it[AgentRuns.transcript] = transcript } }
    }

    override suspend fun get(id: String): HelperRun? {
        val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: return null
        return database.tx { AgentRuns.selectAll().where { (AgentRuns.id eq uuid) and (AgentRuns.reason eq "helper") }.singleOrNull()?.toRun() }
    }

    override suspend fun open(): List<HelperRun> = database.tx {
        AgentRuns.selectAll().where { (AgentRuns.reason eq "helper") and (AgentRuns.status inList RunStatus.OPEN) }
            .orderBy(AgentRuns.startedAt).map { it.toRun() }
    }

    override suspend fun list(openOnly: Boolean, limit: Int): List<HelperRun> = database.tx {
        val base = AgentRuns.selectAll()
        base.where {
            if (openOnly) (AgentRuns.reason eq "helper") and (AgentRuns.status inList RunStatus.OPEN) else AgentRuns.reason eq "helper"
        }
        base.orderBy(AgentRuns.startedAt, org.jetbrains.exposed.v1.core.SortOrder.DESC).limit(limit).map { it.toRun() }
    }
}

private fun ResultRow.toRun(): HelperRun {
    val task = this[AgentRuns.task]
    val tools = this[AgentRuns.tools]
    return HelperRun(
        id = this[AgentRuns.id].toString(),
        parentRunId = this[AgentRuns.parentRunId]?.toString(),
        role = this[AgentRuns.role],
        task = (task as? kotlinx.serialization.json.JsonPrimitive)?.content ?: task?.toString().orEmpty(),
        tools = tools?.takeIf { it !is JsonNull }?.let { ClarityJson.decodeFromJsonElement(ListSerializer(String.serializer()), it) },
        status = this[AgentRuns.status],
        result = this[AgentRuns.result],
        startedAt = this[AgentRuns.startedAt],
    )
}
