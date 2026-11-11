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
import net.robog.clarity.backend.persistence.HelperChanges
import net.robog.clarity.backend.persistence.HelperQuestions
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.HarnessJson
import net.robog.clarity.harness.Usage
import net.robog.clarity.shared.ClarityJson
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
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
    val finishedAt: Instant? = null,
    /** Ids of the third-party MCP servers whose tools it was given. */
    val servers: List<String> = emptyList(),
    /** A fixed kind of helper with its own tools and caps ([MCP_SWEEP_PROFILE]); null for a delegated one. */
    val profile: String? = null,
    /** What the lead said a good result is, one item each; the result answers them. */
    val doneWhen: List<String> = emptyList(),
    /** Times the lead sent the finished work back with feedback. */
    val revisions: Int = 0,
    /** The automatic check of [doneWhen] against the result; empty when it did not run or was unavailable. */
    val check: List<CriterionCheck> = emptyList(),
)

interface RunStore {
    suspend fun create(
        id: String,
        parentRunId: String?,
        role: String,
        task: String,
        tools: List<String>?,
        startedAt: Instant,
        servers: List<String> = emptyList(),
        profile: String? = null,
        doneWhen: List<String> = emptyList(),
    )
    suspend fun markRunning(id: String)

    /**
     * Counts a start of run [id] (its first, or a resume after a restart) and returns how many there have
     * been. With [counts] false (a helper waiting for the lead's answer) nothing is added.
     */
    suspend fun recordStart(id: String, counts: Boolean = true): Int = 1

    /** Helper runs started at or after [since], newest first. */
    suspend fun startedSince(since: Instant): List<HelperRun> = list(openOnly = false, limit = 200).filter { it.startedAt >= since }
    suspend fun finish(id: String, status: String, result: String?, usage: Usage?, error: String?, finishedAt: Instant)
    suspend fun get(id: String): HelperRun?

    /** Stores a copy of the run's transcript (already scrubbed of secret values) for diagnosis. */
    suspend fun saveTranscript(id: String, transcript: JsonElement) {}

    /**
     * Web searches run [id] has already made, from its recorded model calls (`model_call`): what a
     * resumed run (after a restart, or the result nudge) counts against its search cap.
     */
    suspend fun webSearchesUsed(id: String): Long = 0

    /** What run [id] has already spent, in millionths of a dollar, from its recorded model calls (`model_call`). */
    suspend fun costUsed(id: String): Long = 0

    /** Adds what the lead sent run [id] after it started; returns it with its number. */
    suspend fun addChange(id: String, kind: ChangeKind, text: String, at: Instant): TaskChange = TaskChange(1, kind, text, at)

    /** Everything the lead sent run [id], oldest first. */
    suspend fun changes(id: String): List<TaskChange> = emptyList()

    /**
     * Reopens finished run [id] for a revision with the lead's [feedback]: done becomes queued, its
     * revision count goes up, its start count resets and the feedback is stored as a [ChangeKind.REVISION]
     * (all or nothing). Null (nothing changed) unless it is done with fewer than [maxRevisions].
     */
    suspend fun reopen(id: String, maxRevisions: Int, feedback: String, at: Instant): TaskChange? = null

    /** Keeps the automatic check of run [id]. */
    suspend fun saveCheck(id: String, check: List<CriterionCheck>) {}

    /**
     * Records the question that tool call [toolUseId] of run [id] asks, or returns the one it already
     * recorded (a resumed call). Null when the run asked [max] questions already.
     */
    suspend fun ask(id: String, toolUseId: String, text: String, at: Instant, max: Int): HelperQuestion? = null

    suspend fun questions(id: String): List<HelperQuestion> = emptyList()

    /** Records the lead's [answer] to the open question of run [id]; null when none is open. */
    suspend fun answer(id: String, answer: String, at: Instant): HelperQuestion? = null

    /** Closes question [seq] of run [id] without an answer (the wait ran out). False if it was answered meanwhile. */
    suspend fun expire(id: String, seq: Int): Boolean = false

    /** Runs that were queued or running when the process stopped. */
    suspend fun open(): List<HelperRun>
    suspend fun list(openOnly: Boolean, limit: Int = 50): List<HelperRun>
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExposedRunStore(private val database: Database, private val clock: Clock) : RunStore {
    override suspend fun create(
        id: String,
        parentRunId: String?,
        role: String,
        task: String,
        tools: List<String>?,
        startedAt: Instant,
        servers: List<String>,
        profile: String?,
        doneWhen: List<String>,
    ) {
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
                it[AgentRuns.mcpServers] = servers.takeIf { s -> s.isNotEmpty() }?.let { s -> ClarityJson.encodeToJsonElement(ListSerializer(String.serializer()), s) }
                it[AgentRuns.profile] = profile
                it[AgentRuns.doneWhen] = doneWhen.takeIf { d -> d.isNotEmpty() }?.let { d -> ClarityJson.encodeToJsonElement(ListSerializer(String.serializer()), d) }
            }
        }
    }

    override suspend fun markRunning(id: String) {
        database.tx { AgentRuns.update({ AgentRuns.id eq Uuid.parse(id) }) { it[status] = RunStatus.RUNNING } }
    }

    override suspend fun recordStart(id: String, counts: Boolean): Int = database.tx {
        val uuid = Uuid.parse(id)
        // One writer per run (its own execute), so read-then-write is safe.
        val current = AgentRuns.select(AgentRuns.starts).where { AgentRuns.id eq uuid }.singleOrNull()?.get(AgentRuns.starts) ?: 0
        if (!counts) return@tx current
        AgentRuns.update({ AgentRuns.id eq uuid }) { it[starts] = current + 1 }
        current + 1
    }

    override suspend fun startedSince(since: Instant): List<HelperRun> = database.tx {
        AgentRuns.selectAll().where { (AgentRuns.reason eq "helper") and (AgentRuns.startedAt greaterEq since) }
            .orderBy(AgentRuns.startedAt, org.jetbrains.exposed.v1.core.SortOrder.DESC).map { it.toRun() }
    }

    override suspend fun finish(id: String, status: String, result: String?, usage: Usage?, error: String?, finishedAt: Instant) {
        database.tx {
            // The whole run's usage from its recorded calls (every resume), else what the caller saw.
            val total = recordedUsage(id) ?: usage
            AgentRuns.update({ (AgentRuns.id eq Uuid.parse(id)) and (AgentRuns.status inList RunStatus.OPEN) }) {
                it[AgentRuns.status] = status
                it[AgentRuns.result] = result
                it[AgentRuns.usage] = total?.let { u -> HarnessJson.encodeToJsonElement(Usage.serializer(), u) }
                it[AgentRuns.error] = error
                it[AgentRuns.finishedAt] = finishedAt
            }
        }
    }

    override suspend fun saveTranscript(id: String, transcript: JsonElement) {
        database.tx { AgentRuns.update({ AgentRuns.id eq Uuid.parse(id) }) { it[AgentRuns.transcript] = transcript } }
    }

    override suspend fun webSearchesUsed(id: String): Long = database.tx {
        val total = ModelCalls.webSearchRequests.sum()
        ModelCalls.select(total).where { ModelCalls.runId eq id }.singleOrNull()?.get(total)?.toLong() ?: 0L
    }

    override suspend fun costUsed(id: String): Long = database.tx {
        val total = ModelCalls.costMicros.sum()
        ModelCalls.select(total).where { ModelCalls.runId eq id }.singleOrNull()?.get(total) ?: 0L
    }

    override suspend fun addChange(id: String, kind: ChangeKind, text: String, at: Instant): TaskChange = database.tx {
        insertChange(Uuid.parse(id), kind, text, at)
    }

    /** Stores a change under the next number of run [uuid]. Inside a transaction. */
    private fun insertChange(uuid: Uuid, kind: ChangeKind, text: String, at: Instant): TaskChange {
        // The run row is the lock: changes to one run take their numbers one at a time.
        AgentRuns.select(AgentRuns.id).where { AgentRuns.id eq uuid }.forUpdate().toList()
        val highest = HelperChanges.seq.max()
        val seq = (HelperChanges.select(highest).where { HelperChanges.runId eq uuid }.single()[highest] ?: 0) + 1
        HelperChanges.insert {
            it[runId] = uuid
            it[HelperChanges.seq] = seq
            it[HelperChanges.kind] = kind.wire
            it[HelperChanges.text] = text
            it[HelperChanges.at] = at
        }
        return TaskChange(seq, kind, text, at)
    }

    override suspend fun changes(id: String): List<TaskChange> = database.tx {
        HelperChanges.selectAll().where { HelperChanges.runId eq Uuid.parse(id) }.orderBy(HelperChanges.seq)
            .map { TaskChange(it[HelperChanges.seq], ChangeKind.of(it[HelperChanges.kind]), it[HelperChanges.text], it[HelperChanges.at]) }
    }

    override suspend fun reopen(id: String, maxRevisions: Int, feedback: String, at: Instant): TaskChange? = database.tx {
        val reopened = AgentRuns.update({
            (AgentRuns.id eq Uuid.parse(id)) and (AgentRuns.status eq RunStatus.DONE) and (AgentRuns.revisions less maxRevisions)
        }) {
            it[status] = RunStatus.QUEUED
            it[finishedAt] = null
            it[starts] = 0
            it[criteriaCheck] = null
            it[revisions] = revisions + 1
        } > 0
        if (reopened) insertChange(Uuid.parse(id), ChangeKind.REVISION, feedback, at) else null
    }

    override suspend fun saveCheck(id: String, check: List<CriterionCheck>) {
        database.tx {
            AgentRuns.update({ AgentRuns.id eq Uuid.parse(id) }) {
                it[criteriaCheck] = check.takeIf { c -> c.isNotEmpty() }
                    ?.let { c -> ClarityJson.encodeToJsonElement(ListSerializer(CriterionCheck.serializer()), c) }
            }
        }
    }

    override suspend fun ask(id: String, toolUseId: String, text: String, at: Instant, max: Int): HelperQuestion? = database.tx {
        val uuid = Uuid.parse(id)
        AgentRuns.select(AgentRuns.id).where { AgentRuns.id eq uuid }.forUpdate().toList()
        val known = HelperQuestions.selectAll().where { (HelperQuestions.runId eq uuid) and (HelperQuestions.toolUseId eq toolUseId) }.singleOrNull()
        if (known != null) return@tx known.toQuestion()
        val asked = HelperQuestions.selectAll().where { HelperQuestions.runId eq uuid }.count().toInt()
        if (asked >= max) return@tx null
        HelperQuestions.insert {
            it[runId] = uuid
            it[seq] = asked + 1
            it[HelperQuestions.toolUseId] = toolUseId
            it[HelperQuestions.text] = text
            it[askedAt] = at
        }
        HelperQuestion(asked + 1, toolUseId, text, at)
    }

    override suspend fun questions(id: String): List<HelperQuestion> = database.tx {
        HelperQuestions.selectAll().where { HelperQuestions.runId eq Uuid.parse(id) }.orderBy(HelperQuestions.seq).map { it.toQuestion() }
    }

    override suspend fun answer(id: String, answer: String, at: Instant): HelperQuestion? = database.tx {
        val uuid = Uuid.parse(id)
        val open = HelperQuestions.selectAll().where {
            (HelperQuestions.runId eq uuid) and HelperQuestions.answer.isNull() and (HelperQuestions.timedOut eq false)
        }.orderBy(HelperQuestions.seq, org.jetbrains.exposed.v1.core.SortOrder.DESC).firstOrNull() ?: return@tx null
        val seq = open[HelperQuestions.seq]
        HelperQuestions.update({ (HelperQuestions.runId eq uuid) and (HelperQuestions.seq eq seq) }) {
            it[HelperQuestions.answer] = answer
            it[answeredAt] = at
        }
        open.toQuestion().copy(answer = answer, answeredAt = at)
    }

    override suspend fun expire(id: String, seq: Int): Boolean = database.tx {
        HelperQuestions.update({
            (HelperQuestions.runId eq Uuid.parse(id)) and (HelperQuestions.seq eq seq) and HelperQuestions.answer.isNull()
        }) { it[timedOut] = true } > 0
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
        finishedAt = this[AgentRuns.finishedAt],
        servers = this[AgentRuns.mcpServers]?.takeIf { it !is JsonNull }
            ?.let { ClarityJson.decodeFromJsonElement(ListSerializer(String.serializer()), it) }.orEmpty(),
        profile = this[AgentRuns.profile],
        doneWhen = this[AgentRuns.doneWhen]?.takeIf { it !is JsonNull }
            ?.let { ClarityJson.decodeFromJsonElement(ListSerializer(String.serializer()), it) }.orEmpty(),
        revisions = this[AgentRuns.revisions],
        check = this[AgentRuns.criteriaCheck]?.takeIf { it !is JsonNull }
            ?.let { runCatching { ClarityJson.decodeFromJsonElement(ListSerializer(CriterionCheck.serializer()), it) }.getOrNull() }.orEmpty(),
    )
}

private fun ResultRow.toQuestion() = HelperQuestion(
    seq = this[HelperQuestions.seq],
    toolUseId = this[HelperQuestions.toolUseId],
    text = this[HelperQuestions.text],
    askedAt = this[HelperQuestions.askedAt],
    answer = this[HelperQuestions.answer],
    answeredAt = this[HelperQuestions.answeredAt],
    timedOut = this[HelperQuestions.timedOut],
)

/** The usage of every call recorded for run [id] (`model_call`), summed; null when none was. Inside a transaction. */
private fun recordedUsage(id: String): Usage? {
    val input = ModelCalls.inputTokens.sum()
    val output = ModelCalls.outputTokens.sum()
    val read = ModelCalls.cacheReadTokens.sum()
    val w5 = ModelCalls.cacheWrite5mTokens.sum()
    val w1 = ModelCalls.cacheWrite1hTokens.sum()
    val cost = ModelCalls.costMicros.sum()
    val searches = ModelCalls.webSearchRequests.sum()
    val row = ModelCalls.select(input, output, read, w5, w1, cost, searches).where { ModelCalls.runId eq id }.singleOrNull() ?: return null
    // Sums over no rows are null.
    if (row[input] == null) return null
    val five = row[w5] ?: 0L
    val hour = row[w1] ?: 0L
    return Usage(
        inputTokens = row[input] ?: 0L,
        outputTokens = row[output] ?: 0L,
        cacheCreationInputTokens = five + hour,
        cacheReadInputTokens = row[read] ?: 0L,
        cacheCreation5mTokens = five,
        cacheCreation1hTokens = hour,
        reportedCostMicros = row[cost],
        webSearchRequests = (row[searches] ?: 0).toLong(),
    )
}
