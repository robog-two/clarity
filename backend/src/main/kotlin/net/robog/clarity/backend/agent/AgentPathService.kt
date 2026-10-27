package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonElement
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.http.EdgeProblem
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.respondProblem
import net.robog.clarity.backend.persistence.AgentRuns
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.persistence.wire
import net.robog.clarity.backend.vault.GrantStore
import net.robog.clarity.harness.HarnessJson
import net.robog.clarity.harness.Usage
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.path.AgentPath
import net.robog.clarity.shared.path.PathItem
import net.robog.clarity.shared.path.PathSubject
import net.robog.clarity.shared.path.PathTotals
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll

/** A helper run as the path needs it (`agent_run`, helpers only). */
private data class RunRow(
    val id: String,
    val role: String,
    val task: String,
    val status: String,
    val result: String?,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val usage: JsonElement?,
)

/** Model and decision costs, in millionths of a dollar; null when there were none (or none recorded). */
private data class Costs(val model: Long?, val decision: Long?) {
    operator fun plus(o: Costs) = Costs(sum(model, o.model), sum(decision, o.decision))

    private fun sum(a: Long?, b: Long?): Long? = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)
}

/** A run's items and what they cost. */
private class RunPath(val run: RunRow, val items: List<PathItem>, val costs: Costs) {
    val child: ChildRun get() = ChildRun(
        run.id, run.role, run.task, run.status, run.startedAt, run.finishedAt,
        steps = items.count(::countsAsStep), browserSteps = items.count { it is PathItem.Browser },
    )
}

/**
 * Builds the Agent's Path of a task or a helper run from the journals (`agent_journal`), the
 * helper runs (`agent_run`), the task links (`agent_task_run`), the browser audit trail and the
 * model calls. Read-only: nothing here writes, and the lead thread is never created by a read.
 */
@Inject
@SingleIn(AppScope::class)
class AgentPathService(
    private val database: Database,
    private val tasks: TaskStore,
    private val audit: BrowserAudit,
    private val grants: GrantStore,
    private val clock: Clock,
) {
    private val redact get() = PathRedactor(grants::scrub)

    /** One helper run's path; null for an unknown id (and for the lead thread, which is no task's to show whole). */
    suspend fun run(id: String, from: Int, limit: Int): AgentPath? {
        val path = runPath(id, depth = 0) ?: return null
        val run = path.run
        val end = run.finishedAt ?: path.items.lastOrNull()?.at ?: clock.now()
        return AgentPath(
            subject = PathSubject(PathSubject.Kind.RUN, run.id, redact.text(run.task, 120), run.status, run.startedAt, run.finishedAt),
            totals = PathTotals(path.items.count(::countsAsStep), (end - run.startedAt).inWholeMilliseconds.coerceAtLeast(0), path.costs.model, path.costs.decision),
            items = path.items.page(from, limit),
            next = nextPage(path.items.size, from, limit),
            live = run.status in RunStatus.OPEN,
        )
    }

    /** Task [id]'s path: the lead turns that touched it, with every linked helper's path inline after the helper starts. */
    suspend fun task(id: TaskId, from: Int, limit: Int): AgentPath? {
        val task = tasks.get(id) ?: return null
        val created = tasks.createdAt(id) ?: task.updatedAt
        val children = task.runIds.mapNotNull { runPath(it, depth = 1) }.associateBy { it.run.id }
        val childInfo = children.mapValues { it.value.child }

        val lead = leadThreadId()
        val turns = if (lead == null) emptyList() else turnsTouching(leadTurns(journal(lead, since = created - 2.minutes)), id.value)
        val leadItems = turns.flatMap { runItems(lead!!, it, emptyList(), childInfo, redact, lead = true) }
        // A helper linked to the task whose start is not in those turns still gets its node, at its start.
        val shown = leadItems.filterIsInstance<PathItem.Helper>().map { it.childRunId }.toSet()
        val extra = childInfo.values.filter { it.id !in shown }
            .map { PathItem.Helper(it.startedAt, lead ?: it.id, 0, it.id, it.task, it.status, it.role, it.steps, it.browserSteps, it.finishedAt) }
        val top = (leadItems + extra).withIndex().sortedWith(compareBy({ it.value.at }, { it.index })).map { it.value }
        val items = top.flatMap { item -> listOf(item) + ((item as? PathItem.Helper)?.let { children[it.childRunId]?.items }.orEmpty()) }

        var costs = children.values.fold(Costs(null, null)) { acc, c -> acc + c.costs }
        if (lead != null) turns.forEach { turn -> costs += costs(lead, turn.first().at, turn.last().at + 5.seconds) }

        val running = task.status == AgentTask.Status.WORKING || children.values.any { it.run.status in RunStatus.OPEN }
        val first = items.firstOrNull()?.at ?: created
        val last = if (running) clock.now() else items.lastOrNull()?.at ?: task.updatedAt
        return AgentPath(
            subject = PathSubject(PathSubject.Kind.TASK, id.value, task.title, task.status.wire(), created, if (running) null else task.updatedAt,
                detail = task.detail, heldActionId = task.heldActionId?.value,
            ),
            totals = PathTotals(items.count(::countsAsStep), (last - first).inWholeMilliseconds.coerceAtLeast(0), costs.model, costs.decision),
            items = items.page(from, limit),
            next = nextPage(items.size, from, limit),
            live = running,
        )
    }

    private suspend fun runPath(id: String, depth: Int): RunPath? {
        val run = helperRun(id) ?: return null
        val steps = audit.steps(id)
        val items = runItems(id, journal(id, since = null), steps, emptyMap(), redact, lead = false, depth = depth).toMutableList()
        val result = run.result
        if (result != null && run.status in setOf(RunStatus.DONE, RunStatus.FAILED)) {
            items += PathItem.Finished(run.finishedAt ?: items.lastOrNull()?.at ?: run.startedAt, id, depth, run.status == RunStatus.DONE, redact.text(result))
        }
        val recorded = costs(id, null, null)
        val fallback = run.usage?.let { runCatching { HarnessJson.decodeFromJsonElement(Usage.serializer(), it) }.getOrNull()?.reportedCostMicros }
        val stepCost = steps.mapNotNull { it.costMicros }.takeIf { it.isNotEmpty() }?.sum()
        return RunPath(run, items, Costs(recorded.model ?: fallback, recorded.decision ?: stepCost))
    }

    private suspend fun helperRun(id: String): RunRow? {
        val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: return null
        return database.tx {
            AgentRuns.selectAll().where { (AgentRuns.id eq uuid) and (AgentRuns.reason eq "helper") }.singleOrNull()?.let { r ->
                val task = r[AgentRuns.task]
                RunRow(
                    id = r[AgentRuns.id].toString(),
                    role = r[AgentRuns.role],
                    task = (task as? kotlinx.serialization.json.JsonPrimitive)?.content ?: task?.toString().orEmpty(),
                    status = r[AgentRuns.status],
                    result = r[AgentRuns.result],
                    startedAt = r[AgentRuns.startedAt],
                    finishedAt = r[AgentRuns.finishedAt],
                    usage = r[AgentRuns.usage],
                )
            }
        }
    }

    /** The lead thread's id, if it exists (never created here). */
    private suspend fun leadThreadId(): String? = database.tx {
        AgentThreads.selectAll().orderBy(AgentThreads.createdAt).limit(1).singleOrNull()?.get(AgentThreads.id)?.toString()
    }

    private suspend fun journal(runId: String, since: Instant?): List<JournalRow> = database.tx {
        AgentJournal.selectAll()
            .where { if (since == null) AgentJournal.runId eq runId else (AgentJournal.runId eq runId) and (AgentJournal.createdAt greaterEq since) }
            .orderBy(AgentJournal.seq, SortOrder.ASC)
            .map { JournalRow(it[AgentJournal.seq], it[AgentJournal.createdAt], it[AgentJournal.message]) }
    }

    /** What [runId]'s model calls cost (between [from] and [until] when given): the agents' own, and the decision model's. */
    private suspend fun costs(runId: String, from: Instant?, until: Instant?): Costs = database.tx {
        val window = if (from != null && until != null) " AND at BETWEEN ? AND ?" else ""
        jdbc().prepareStatement(
            "SELECT sum(cost_micros) FILTER (WHERE role IS DISTINCT FROM 'decision'), count(*) FILTER (WHERE role IS DISTINCT FROM 'decision'), " +
                "sum(cost_micros) FILTER (WHERE role = 'decision'), count(*) FILTER (WHERE role = 'decision') FROM model_call WHERE run_id = ?$window",
        ).use { st ->
            st.setString(1, runId)
            if (from != null && until != null) {
                st.setTimestamp(2, java.sql.Timestamp.from(from.toJavaInstant()))
                st.setTimestamp(3, java.sql.Timestamp.from(until.toJavaInstant()))
            }
            st.executeQuery().use { rs ->
                rs.next()
                val model = rs.getLong(1).takeUnless { rs.wasNull() }
                val modelCalls = rs.getLong(2)
                val decision = rs.getLong(3).takeUnless { rs.wasNull() }
                val decisionCalls = rs.getLong(4)
                Costs(model.takeIf { modelCalls > 0 }, decision.takeIf { decisionCalls > 0 })
            }
        }
    }
}

private fun <T> List<T>.page(from: Int, limit: Int): List<T> = drop(from.coerceAtLeast(0)).take(limit)

private fun nextPage(size: Int, from: Int, limit: Int): Int? = (from.coerceAtLeast(0) + limit).takeIf { it < size }

/** `GET /api/v1/tasks/{id}/path` and `GET /api/v1/runs/{id}/path`, device-authenticated and read-only. */
@Inject
@ContributesIntoSet(AppScope::class)
class AgentPathRoutes(private val paths: AgentPathService) : HttpModule {
    override fun install(application: Application) {
        application.routing {
            authenticate(AuthProviders.DEVICE) {
                get("${ApiPaths.TASKS}/{id}/path") {
                    val (from, limit) = call.paging() ?: return@get call.respondProblem(EdgeProblem.BadRequest("from and limit must be numbers"))
                    val path = paths.task(TaskId(call.parameters["id"].orEmpty()), from, limit)
                    if (path == null) call.respondProblem(EdgeProblem.NotFound("no such task")) else call.respond(path)
                }
                get("${ApiPaths.RUNS}/{id}/path") {
                    val (from, limit) = call.paging() ?: return@get call.respondProblem(EdgeProblem.BadRequest("from and limit must be numbers"))
                    val path = paths.run(call.parameters["id"].orEmpty(), from, limit)
                    if (path == null) call.respondProblem(EdgeProblem.NotFound("no such run")) else call.respond(path)
                }
            }
        }
    }

    private fun io.ktor.server.application.ApplicationCall.paging(): Pair<Int, Int>? {
        val from = request.queryParameters["from"]?.let { it.toIntOrNull() ?: return null } ?: 0
        val limit = request.queryParameters["limit"]?.let { it.toIntOrNull() ?: return null } ?: DEFAULT_PAGE
        return from.coerceAtLeast(0) to limit.coerceIn(1, MAX_PAGE)
    }

    private companion object {
        const val DEFAULT_PAGE = 100
        const val MAX_PAGE = 500
    }
}
