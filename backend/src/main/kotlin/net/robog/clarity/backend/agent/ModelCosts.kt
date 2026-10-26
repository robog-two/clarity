package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong
import kotlin.time.Clock
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.ModelGateway
import net.robog.clarity.harness.ModelRequest
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.Usage
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert

private val log = KotlinLogging.logger {}

/** Dollars per million tokens. Cache writes cost 1.25x input (5 minute TTL) or 2x (1 hour). */
data class ModelPrice(val input: Double, val output: Double, val cacheRead: Double)

/** Pure: the price of [model] by family (Claude API list prices, October 2026), or null when unknown. */
fun priceOf(model: String, promptTokens: Long = 0): ModelPrice? = when {
    model.startsWith("claude-opus-5-5") -> ModelPrice(4.0, 20.0, 0.20)
    model.startsWith("claude-sonnet-5-5") -> ModelPrice(2.0, 10.0, 0.20)
    // Haiku 5.5 costs more above 100K prompt tokens.
    model.startsWith("claude-haiku-5-5") ->
        if (promptTokens > 100_000) ModelPrice(0.50, 2.50, 0.05) else ModelPrice(0.10, 0.50, 0.01)
    else -> null
}

/** Pure: the 5 minute and 1 hour cache writes; an unsplit total counts as 5 minute writes. */
fun cacheWrites(u: Usage): Pair<Long, Long> {
    val unsplit = (u.cacheCreationInputTokens - u.cacheCreation5mTokens - u.cacheCreation1hTokens).coerceAtLeast(0)
    return (u.cacheCreation5mTokens + unsplit) to u.cacheCreation1hTokens
}

/** Pure: what one call cost, in millionths of a dollar, or null for an unknown model. */
fun costMicros(model: String, u: Usage): Long? {
    val (w5, w1) = cacheWrites(u)
    val prompt = u.inputTokens + u.cacheReadInputTokens + w5 + w1
    val p = priceOf(model, prompt) ?: return null
    // dollars per million tokens == micro-dollars per token
    val micros = u.inputTokens * p.input + u.outputTokens * p.output + u.cacheReadInputTokens * p.cacheRead +
        w5 * p.input * 1.25 + w1 * p.input * 2.0
    return micros.roundToLong()
}

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
    override val primaryKey = PrimaryKey(id)
}

/** Where every model call's usage goes. */
fun interface UsageRecorder {
    suspend fun record(model: String, usage: Usage)
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DbUsageRecorder(private val database: Database, private val clock: Clock) : UsageRecorder {
    override suspend fun record(model: String, usage: Usage) {
        val (w5, w1) = cacheWrites(usage)
        database.tx {
            ModelCalls.insert {
                it[at] = clock.now()
                it[ModelCalls.model] = model
                it[inputTokens] = usage.inputTokens
                it[outputTokens] = usage.outputTokens
                it[cacheReadTokens] = usage.cacheReadInputTokens
                it[cacheWrite5mTokens] = w5
                it[cacheWrite1hTokens] = w1
                it[costMicros] = costMicros(model, usage)
            }
        }
    }
}

/** Records the usage of every call made through [inner]; a failed record never fails the call. */
class RecordingGateway(private val inner: ModelGateway, private val recorder: UsageRecorder) : ModelGateway {
    override suspend fun generate(request: ModelRequest, onTextDelta: (String) -> Unit): ModelResponse =
        inner.generate(request, onTextDelta).also { record(request.model, it.usage) }

    override suspend fun complete(request: ModelRequest): ModelResponse =
        inner.complete(request).also { record(request.model, it.usage) }

    private suspend fun record(model: String, usage: Usage) {
        try {
            recorder.record(model, usage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "could not record model usage: ${e.message}" }
        }
    }
}
