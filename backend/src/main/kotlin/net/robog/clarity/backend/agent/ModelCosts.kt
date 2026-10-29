package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.ModelGateway
import net.robog.clarity.harness.ModelRequest
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.Usage
import net.robog.clarity.harness.currentRunId
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert

private val log = KotlinLogging.logger {}

/** Pure: the 5 minute and 1 hour cache writes; an unsplit total counts as 5 minute writes. */
fun cacheWrites(u: Usage): Pair<Long, Long> {
    val unsplit = (u.cacheCreationInputTokens - u.cacheCreation5mTokens - u.cacheCreation1hTokens).coerceAtLeast(0)
    return (u.cacheCreation5mTokens + unsplit) to u.cacheCreation1hTokens
}

/**
 * Pure: what one call cost, in millionths of a dollar: OpenRouter's own figure (`usage.cost`, which
 * includes cache reads and writes at the serving provider's prices); null when it did not say.
 */
fun costMicros(u: Usage): Long? = u.reportedCostMicros

object ModelCalls : Table("model_call") {
    val id = long("id").autoIncrement()
    val at = timestamp("at")
    val model = text("model")
    val inputTokens = long("input_tokens")
    val outputTokens = long("output_tokens")
    val cacheReadTokens = long("cache_read_tokens")
    val cacheWrite5mTokens = long("cache_write_5m_tokens")
    val cacheWrite1hTokens = long("cache_write_1h_tokens")
    val costMicros = long("cost_micros").nullable()
    /** Who called (`lead`, `helper`, `guard`, `triage`); null for calls made before roles were recorded. */
    val role = text("role").nullable()
    /** The run (lead thread or helper) that made the call (V21); null outside a run and for older calls. */
    val runId = text("run_id").nullable()
    /** Web searches the call ran (V25); its cost is part of [costMicros], which OpenRouter reports in full. */
    val webSearchRequests = integer("web_search_requests").default(0)
    /** The upstream provider OpenRouter routed the call to (V27); null when it did not say, and for older calls. */
    val provider = text("provider").nullable()
    /** The call ended without usage (V35: cancelled, failed, or none sent): it happened, its tokens and cost are unknown. */
    val incomplete = bool("incomplete").default(false)
    override val primaryKey = PrimaryKey(id)
}

/** Where every model call's usage goes: the model actually called, and which role called it. */
fun interface UsageRecorder {
    suspend fun record(model: String, usage: Usage, role: AgentRole?)

    /** A call that ended without (full) usage: cancelled, failed, or none sent. [usage] is what is known. */
    suspend fun recordIncomplete(model: String, usage: Usage, role: AgentRole?) = record(model, usage, role)
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DbUsageRecorder(private val database: Database, private val clock: Clock) : UsageRecorder {
    override suspend fun record(model: String, usage: Usage, role: AgentRole?) = insert(model, usage, role, incomplete = false)

    override suspend fun recordIncomplete(model: String, usage: Usage, role: AgentRole?) = insert(model, usage, role, incomplete = true)

    private suspend fun insert(model: String, usage: Usage, role: AgentRole?, incomplete: Boolean) {
        val (w5, w1) = cacheWrites(usage)
        val run = currentRunId()
        database.tx {
            ModelCalls.insert {
                it[at] = clock.now()
                it[ModelCalls.model] = model
                it[inputTokens] = usage.inputTokens
                it[outputTokens] = usage.outputTokens
                it[cacheReadTokens] = usage.cacheReadInputTokens
                it[cacheWrite5mTokens] = w5
                it[cacheWrite1hTokens] = w1
                it[costMicros] = costMicros(usage)
                it[ModelCalls.role] = role?.wireName
                it[ModelCalls.runId] = run
                it[ModelCalls.webSearchRequests] = usage.webSearchRequests.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                it[ModelCalls.provider] = usage.provider
                it[ModelCalls.incomplete] = incomplete
            }
        }
    }
}

/**
 * Records the usage of every call made through [inner] (as [role]), a call that was cancelled,
 * failed or sent no usage too (as incomplete); a failed record never fails the call. Before each
 * call [guard] may stop it ([SpendLimitReached]): then no call is made.
 */
class RecordingGateway(
    private val inner: ModelGateway,
    private val recorder: UsageRecorder,
    private val role: AgentRole? = null,
    private val guard: SpendGuard = SpendGuard.OFF,
) : ModelGateway {
    override suspend fun generate(request: ModelRequest, onTextDelta: (String) -> Unit): ModelResponse =
        recorded(request) { inner.generate(request, onTextDelta) }

    override suspend fun complete(request: ModelRequest): ModelResponse =
        recorded(request) { inner.complete(request) }

    private suspend fun recorded(request: ModelRequest, call: suspend () -> ModelResponse): ModelResponse {
        guard.check(role)?.let { throw SpendLimitReached(it) }
        val response = try {
            call()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { record(request.model, Usage.ZERO, incomplete = true) }
            throw e
        } catch (e: Exception) {
            record(request.model, Usage.ZERO, incomplete = true)
            throw e
        }
        if (response.usage.reportedCostMicros == null) {
            log.warn { "model call to ${request.model} (${role?.wireName ?: "no role"}) came back with no cost${if (response.usageMissing) " and no usage" else ""}" }
        }
        record(request.model, response.usage, incomplete = response.usageMissing)
        return response
    }

    private suspend fun record(model: String, usage: Usage, incomplete: Boolean) {
        try {
            if (incomplete) recorder.recordIncomplete(model, usage, role) else recorder.record(model, usage, role)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "could not record model usage: ${e.message}" }
        }
    }
}
