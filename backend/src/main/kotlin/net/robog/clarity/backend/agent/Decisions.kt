package net.robog.clarity.backend.agent

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.httpsProxyFromEnv
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert

private val log = KotlinLogging.logger {}

/**
 * One question for a decision model ("System One" format). The model answers with calibrated
 * probabilities over fixed answers, never with text, so it cannot be talked into doing anything:
 * the worst a manipulated state can do is flip an answer.
 */
sealed interface DecisionQuestion {
    val instructions: String

    /** Yes or no; the answer is the probability of yes. [whenTrue] and [whenFalse] say what each means. */
    data class Noul(override val instructions: String, val whenTrue: String, val whenFalse: String) : DecisionQuestion

    /** One of [criteria]'s keys (option to what it means); the answer has a probability per option. */
    data class Choice(override val instructions: String, val criteria: Map<String, String>) : DecisionQuestion {
        init { require(criteria.isNotEmpty()) { "a choice needs options" } }
    }
}

sealed interface DecisionAnswer {
    /** [probability] that the proposition holds. */
    data class Noul(val probability: Double) : DecisionAnswer

    data class Choice(val choice: String, val probabilities: Map<String, Double>, val confidence: Double?) : DecisionAnswer

    /** The model declined to answer this question. */
    data object Refusal : DecisionAnswer
}

/** What one call cost; [costMicros] is the provider's figure in millionths of a dollar, when reported. */
data class DecisionUsage(val inputTokens: Long, val outputTokens: Long, val costMicros: Long?)

/** A decision response: an answer per question id, the exact model build, who served it, and usage. */
data class DecisionResult(
    val answers: Map<String, DecisionAnswer>,
    val model: String?,
    val provider: String?,
    val usage: DecisionUsage,
)

/** Why a decision call gave no result. */
sealed interface DecisionError {
    val message: String

    /** Not reached (connection failed or timed out). */
    data class Unreachable(override val message: String) : DecisionError

    /** The API answered with an error status. */
    data class Http(val status: Int, override val message: String) : DecisionError

    /** The body was not a decisions response. */
    data class Malformed(override val message: String) : DecisionError

    /** The whole request was refused. */
    data class Refused(override val message: String) : DecisionError
}

/** Asks a decision model. A seam: tests use a fake, production [OpenRouterDecisionClient]. */
fun interface DecisionClient {
    suspend fun decide(state: JsonElement, questions: Map<String, DecisionQuestion>): Either<DecisionError, DecisionResult>
}

/**
 * Pure: the decisions endpoint for an OpenRouter base URL. Decisions sit outside the `/api/v1`
 * prefix, at `/api/alpha/decisions`, so a base ending in `/v1` has that segment replaced.
 */
fun decisionsEndpoint(baseUrl: String): String {
    val base = baseUrl.trimEnd('/')
    return (if (base.endsWith("/v1")) base.dropLast(3) else base) + "/alpha/decisions"
}

/** Pure: the request body. With [zdr], only zero-data-retention endpoints that do not collect data. */
fun decisionRequestBody(model: String, state: JsonElement, questions: Map<String, DecisionQuestion>, zdr: Boolean): JsonObject =
    buildJsonObject {
        put("model", model)
        put("state", state)
        putJsonObject("questions") {
            questions.forEach { (id, q) ->
                putJsonObject(id) {
                    when (q) {
                        is DecisionQuestion.Noul -> {
                            put("type", "noul")
                            put("instructions", q.instructions)
                            putJsonObject("criteria") {
                                put("true", q.whenTrue)
                                put("false", q.whenFalse)
                            }
                        }
                        is DecisionQuestion.Choice -> {
                            put("type", "choice")
                            put("instructions", q.instructions)
                            putJsonObject("criteria") { q.criteria.forEach { (k, v) -> put(k, v) } }
                        }
                    }
                }
            }
        }
        if (zdr) {
            putJsonObject("provider") {
                put("zdr", true)
                put("data_collection", "deny")
            }
        }
    }

/**
 * Pure: a decisions response. Every asked question must have an answer of the asked type (a
 * missing one or another type is malformed, never a default); `{"type":"refusal"}` is a [DecisionAnswer.Refusal].
 */
fun parseDecisionResponse(text: String, questions: Map<String, DecisionQuestion>): Either<DecisionError, DecisionResult> {
    val root = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull()
        ?: return DecisionError.Malformed("not a JSON object: ${text.take(200)}").left()
    (root["error"] as? JsonObject)?.let { error ->
        val code = (error["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 200
        return DecisionError.Http(code, "decisions error: ${(error["message"] as? JsonPrimitive)?.contentOrNull?.take(300)}").left()
    }
    if (root.string("type") == "refusal") return DecisionError.Refused(root.string("message") ?: "refused").left()
    val answers = root["answers"] as? JsonObject ?: return DecisionError.Malformed("no answers in the response").left()
    val parsed = questions.mapValues { (id, q) ->
        val a = answers[id] as? JsonObject ?: return DecisionError.Malformed("no answer for '$id'").left()
        when (val type = a.string("type")) {
            "refusal" -> DecisionAnswer.Refusal
            "noul" -> {
                if (q !is DecisionQuestion.Noul) return DecisionError.Malformed("'$id' answered as noul").left()
                val p = (a["noul"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it in 0.0..1.0 }
                    ?: return DecisionError.Malformed("'$id' has no probability").left()
                DecisionAnswer.Noul(p)
            }
            "choice" -> {
                if (q !is DecisionQuestion.Choice) return DecisionError.Malformed("'$id' answered as choice").left()
                val choice = a.string("choice")?.takeIf { it in q.criteria } ?: return DecisionError.Malformed("'$id' picked no known option").left()
                val probabilities = (a["probabilities"] as? JsonObject).orEmpty()
                    .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.let { k to it } }.toMap()
                DecisionAnswer.Choice(choice, probabilities, (a["confidence"] as? JsonPrimitive)?.doubleOrNull)
            }
            else -> return DecisionError.Malformed("'$id' has answer type $type").left()
        }
    }
    val usage = root["usage"] as? JsonObject
    return DecisionResult(
        answers = parsed,
        model = root.string("model"),
        provider = root.string("provider"),
        usage = DecisionUsage(
            inputTokens = (usage?.get("input_tokens") as? JsonPrimitive)?.longOrNull ?: 0,
            outputTokens = (usage?.get("output_tokens") as? JsonPrimitive)?.longOrNull ?: 0,
            costMicros = (usage?.get("cost") as? JsonPrimitive)?.doubleOrNull?.let { (it * 1_000_000).roundToLong() },
        ),
    ).right()
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/**
 * OpenRouter's decisions endpoint (`POST /api/alpha/decisions`, alpha) with the JDK's HTTP client,
 * through the egress proxy like the chat gateway. A failed connection, a timeout, a 429 or a 5xx is
 * retried after each of [retryDelays]; anything else is returned as a [DecisionError].
 */
class OpenRouterDecisionClient(
    private val apiKey: String,
    baseUrl: String,
    proxy: Proxy?,
    private val model: String,
    private val zdr: Boolean,
    private val timeout: Duration = 10.seconds,
    private val retryDelays: List<Duration> = listOf(1.seconds),
) : DecisionClient {
    private val endpoint: URI = URI.create(decisionsEndpoint(baseUrl))

    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(timeout.toJavaDuration())
        .apply { if (proxy != null) proxy(FixedProxy(proxy)) }
        .build()

    override suspend fun decide(state: JsonElement, questions: Map<String, DecisionQuestion>): Either<DecisionError, DecisionResult> {
        val body = decisionRequestBody(model, state, questions, zdr).toString()
        val call = HttpRequest.newBuilder(endpoint)
            .timeout(timeout.toJavaDuration())
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        var attempt = 0
        while (true) {
            val outcome = send(call, questions)
            val retryable = outcome.leftOrNull()?.let { it is DecisionError.Unreachable || (it is DecisionError.Http && (it.status == 429 || it.status >= 500)) } == true
            val wait = if (retryable) retryDelays.getOrNull(attempt++) else null
            if (wait == null) return outcome
            log.info { "decision call failed (${outcome.leftOrNull()?.message?.take(120)}); retrying in $wait" }
            delay(wait)
        }
    }

    private suspend fun send(call: HttpRequest, questions: Map<String, DecisionQuestion>): Either<DecisionError, DecisionResult> {
        val response = runInterruptible(Dispatchers.IO) {
            try {
                http.send(call, HttpResponse.BodyHandlers.ofString())
            } catch (e: HttpTimeoutException) {
                null
            } catch (e: IOException) {
                if (e.cause is InterruptedException || Thread.interrupted()) throw InterruptedException("cancelled")
                null
            }
        } ?: return DecisionError.Unreachable("could not reach $endpoint in time").left()
        val text = response.body().orEmpty()
        val status = response.statusCode()
        if (status !in 200..299) return DecisionError.Http(status, "decisions answered $status: ${text.take(300)}".redact()).left()
        return parseDecisionResponse(text, questions).mapLeft { if (it is DecisionError.Http) it.copy(message = it.message.redact()) else it }
    }

    // The key never goes into an error, even if a server echoed it.
    private fun String.redact(): String = if (apiKey.isEmpty()) this else replace(apiKey, "[redacted]")

    private class FixedProxy(private val proxy: Proxy) : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = listOf(proxy)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }
}

/** Builds the decision client, or null when decisions are off or there is no OpenRouter key. */
fun interface DecisionClientFactory {
    fun create(): DecisionClient?
}

@Inject
@ContributesBinding(AppScope::class)
class OpenRouterDecisionClientFactory(private val config: AgentConfig) : DecisionClientFactory {
    override fun create(): DecisionClient? {
        val key = config.openRouterApiKey?.takeIf { it.isNotBlank() } ?: return null
        if (!config.decisions.enabled) return null
        return OpenRouterDecisionClient(
            key, config.openRouterBaseUrl, httpsProxyFromEnv(System.getenv()), config.decisions.model, zdr = config.openRouterRouting.zdr,
        )
    }
}

/**
 * The decision model as the backend uses it: [decide] records every answered call in `model_call`
 * as role `decision` with the reported cost. Null when unavailable (off or no key), so callers can
 * fail open.
 */
@Inject
@SingleIn(AppScope::class)
class Decisions(config: AgentConfig, factory: DecisionClientFactory, private val database: Database, private val clock: Clock) {
    private val client: DecisionClient? = factory.create()
    val model: String = config.decisions.model

    val enabled: Boolean get() = client != null

    /** The answers, or the error; null when decisions are not configured. Never throws (but for cancellation). */
    suspend fun decide(state: JsonElement, questions: Map<String, DecisionQuestion>): Either<DecisionError, DecisionResult>? {
        val client = client ?: return null
        val result = try {
            client.decide(state, questions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DecisionError.Unreachable("decision call failed: ${e::class.simpleName}").left()
        }
        result.onRight { record(it) }
        return result
    }

    private suspend fun record(result: DecisionResult) {
        try {
            database.tx {
                ModelCalls.insert {
                    it[at] = clock.now()
                    it[ModelCalls.model] = this@Decisions.model
                    it[inputTokens] = result.usage.inputTokens
                    it[outputTokens] = result.usage.outputTokens
                    it[cacheReadTokens] = 0
                    it[cacheWrite5mTokens] = 0
                    it[cacheWrite1hTokens] = 0
                    it[costMicros] = result.usage.costMicros
                    it[role] = DECISION_ROLE
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "could not record decision usage: ${e.message}" }
        }
    }

    companion object {
        const val DECISION_ROLE = "decision"
    }
}
