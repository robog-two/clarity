package net.robog.clarity.backend.agent

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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaDuration
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.httpsProxyFromEnv
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert

private val log = KotlinLogging.logger {}

/** One embeddings request's vectors (in input order) and what it cost. */
class EmbeddingBatch(val vectors: List<FloatArray>, val promptTokens: Long, val costMicros: Long?)

/** Turns texts into vectors. A seam: tests use a fake, production [OpenRouterEmbeddingClient]. */
fun interface EmbeddingClient {
    suspend fun embed(texts: List<String>): EmbeddingBatch
}

/** The embeddings API refused or failed a request. [status] is the HTTP status, null when it was not reached. */
class EmbeddingException(val status: Int?, message: String) : RuntimeException(message)

/**
 * OpenRouter's OpenAI-compatible `POST /embeddings` with the JDK's HTTP client, through the egress
 * proxy like the chat gateway. Always asks for zero-data-retention endpoints that do not collect
 * data, only from [providers] in that order (no fallback to others, so every vector comes from the
 * same weights). No `dimensions` parameter: the model's endpoints do not list it as supported, so
 * vectors come back at full size and [Embeddings] truncates them.
 */
class OpenRouterEmbeddingClient(
    private val apiKey: String,
    baseUrl: String,
    proxy: Proxy?,
    private val model: String,
    private val providers: List<String>,
) : EmbeddingClient {
    private val endpoint: URI = URI.create(baseUrl.trimEnd('/') + "/embeddings")

    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(30.seconds.toJavaDuration())
        .apply { if (proxy != null) proxy(FixedProxy(proxy)) }
        .build()

    override suspend fun embed(texts: List<String>): EmbeddingBatch {
        val body = embeddingRequestBody(model, texts, providers).toString()
        val call = HttpRequest.newBuilder(endpoint)
            .timeout(90.seconds.toJavaDuration())
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = runInterruptible(Dispatchers.IO) {
            try {
                http.send(call, HttpResponse.BodyHandlers.ofString())
            } catch (e: IOException) {
                if (e.cause is InterruptedException || Thread.interrupted()) throw InterruptedException("cancelled")
                throw EmbeddingException(null, "could not reach OpenRouter: ${e::class.simpleName}")
            }
        }
        val text = response.body().orEmpty()
        if (response.statusCode() !in 200..299) {
            throw EmbeddingException(response.statusCode(), "OpenRouter embeddings answered ${response.statusCode()}: ${text.take(300)}".replace(apiKey, "[redacted]"))
        }
        return parseEmbeddingResponse(text, texts.size)
    }

    private class FixedProxy(private val proxy: Proxy) : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = listOf(proxy)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }
}

/** Pure: the `/embeddings` request body. */
fun embeddingRequestBody(model: String, texts: List<String>, providers: List<String>): JsonObject = buildJsonObject {
    put("model", model)
    putJsonArray("input") { texts.forEach { add(it) } }
    put("encoding_format", "float")
    putJsonObject("provider") {
        putJsonArray("order") { providers.forEach { add(it) } }
        put("allow_fallbacks", false)
        put("data_collection", "deny")
        put("zdr", true)
    }
}

/** Pure: vectors (by `index`) and usage from an `/embeddings` response; throws when it is not one. */
fun parseEmbeddingResponse(text: String, expected: Int): EmbeddingBatch {
    val root = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull()
        ?: throw EmbeddingException(200, "not a JSON object: ${text.take(200)}")
    (root["error"] as? JsonObject)?.let { error ->
        val code = (error["code"] as? JsonPrimitive)?.intOrNull
        throw EmbeddingException(code, "OpenRouter embeddings error: ${(error["message"] as? JsonPrimitive)?.contentOrNull?.take(300)}")
    }
    val data = root["data"] as? JsonArray ?: throw EmbeddingException(200, "no data in the response")
    val byIndex = data.mapIndexed { i, item ->
        val obj = item as JsonObject
        val index = (obj["index"] as? JsonPrimitive)?.intOrNull ?: i
        index to ((obj["embedding"] as? JsonArray) ?: throw EmbeddingException(200, "an embedding is not a float array")).let { arr ->
            FloatArray(arr.size) { (arr[it] as JsonPrimitive).float }
        }
    }.toMap()
    if (byIndex.size != expected || (0 until expected).any { it !in byIndex }) {
        throw EmbeddingException(200, "expected $expected embeddings, got ${byIndex.size}")
    }
    val usage = root["usage"] as? JsonObject
    val tokens = (usage?.get("prompt_tokens") as? JsonPrimitive)?.longOrNull ?: 0
    val cost = (usage?.get("cost") as? JsonPrimitive)?.doubleOrNull?.let { (it * 1_000_000).roundToLong() }
    return EmbeddingBatch((0 until expected).map { byIndex.getValue(it) }, tokens, cost)
}

/** Builds the embedding client, or null when embeddings are off or there is no OpenRouter key. */
fun interface EmbeddingClientFactory {
    fun create(): EmbeddingClient?
}

@Inject
@ContributesBinding(AppScope::class)
class OpenRouterEmbeddingClientFactory(private val config: AgentConfig) : EmbeddingClientFactory {
    override fun create(): EmbeddingClient? {
        val key = config.openRouterApiKey?.takeIf { it.isNotBlank() } ?: return null
        if (!config.memory.embeddings) return null
        return OpenRouterEmbeddingClient(key, config.openRouterBaseUrl, httpsProxyFromEnv(System.getenv()), config.memory.embeddingsModel, config.memory.embeddingProviders)
    }
}

/** Pure: the first [dims] values of [v] (all when [dims] is 0 or larger), scaled to unit length. */
fun fitVector(v: FloatArray, dims: Int): FloatArray {
    val out = if (dims in 1 until v.size) v.copyOf(dims) else v.copyOf()
    var sum = 0.0
    for (x in out) sum += x * x
    val norm = sqrt(sum)
    if (norm > 0) for (i in out.indices) out[i] = (out[i] / norm).toFloat()
    return out
}

/** Pure: float32 little-endian. */
fun vectorBytes(v: FloatArray): ByteArray =
    ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { v.forEach { putFloat(it) } }.array()

fun vectorOf(bytes: ByteArray): FloatArray {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(bytes.size / 4) { buffer.getFloat() }
}

/**
 * The embedding side of memory search: documents (batched, by the indexer) and queries (at search
 * time). Never fails a caller: an error, a rate limit or a missing key gives null, and after a
 * failure embeddings pause (5 minutes, doubling to an hour) so search falls back to full-text only.
 * Every request's usage is recorded in `model_call` as role `embedding`.
 */
@Inject
@SingleIn(AppScope::class)
class Embeddings(
    config: AgentConfig,
    factory: EmbeddingClientFactory,
    private val database: Database,
    private val clock: Clock,
) {
    private val client: EmbeddingClient? = factory.create()
    val model: String = config.memory.embeddingsModel
    val dims: Int = config.memory.embeddingDimensions

    @Volatile private var pausedUntil: Instant = Instant.DISTANT_PAST
    @Volatile private var backoff: Duration = 5.minutes
    private val queries = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 128
    }

    /** Configured (on, with a key). */
    val enabled: Boolean get() = client != null

    /** Configured and not paused after a failure. */
    val available: Boolean get() = client != null && clock.now() >= pausedUntil

    /** Vectors for [texts] (already cleaned of secrets), or null when unavailable or the call failed. */
    suspend fun documents(texts: List<String>): List<FloatArray>? = call(texts)

    /** The vector for a search query, or null (unavailable, failed, or slower than [timeout]). */
    suspend fun query(text: String, timeout: Duration = 5.seconds): FloatArray? {
        val q = SecretDetector.redact(text.trim()).take(1000)
        if (q.isEmpty()) return null
        synchronized(queries) { queries[q] }?.let { return it }
        val vector = withTimeoutOrNull(timeout) { call(listOf(QUERY_INSTRUCTION + q)) }?.single() ?: return null
        synchronized(queries) { queries[q] = vector }
        return vector
    }

    private suspend fun call(texts: List<String>): List<FloatArray>? {
        val client = client ?: return null
        if (texts.isEmpty()) return emptyList()
        if (!available) return null
        val batch = try {
            client.embed(texts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pausedUntil = clock.now() + backoff
            log.warn { "embeddings failed (${e.message?.take(200)}); keyword-only search for $backoff" }
            backoff = minOf(backoff * 2, 1.hours)
            return null
        }
        backoff = 5.minutes
        record(batch)
        return batch.vectors.map { fitVector(it, dims) }
    }

    private suspend fun record(batch: EmbeddingBatch) {
        try {
            database.tx {
                ModelCalls.insert {
                    it[at] = clock.now()
                    it[ModelCalls.model] = this@Embeddings.model
                    it[inputTokens] = batch.promptTokens
                    it[outputTokens] = 0
                    it[cacheReadTokens] = 0
                    it[cacheWrite5mTokens] = 0
                    it[cacheWrite1hTokens] = 0
                    it[costMicros] = batch.costMicros ?: embeddingCostMicros(batch.promptTokens)
                    it[role] = EMBEDDING_ROLE
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "could not record embedding usage: ${e.message}" }
        }
    }

    companion object {
        const val EMBEDDING_ROLE = "embedding"

        /** Qwen3-Embedding wants an instruction on queries (not on documents). */
        const val QUERY_INSTRUCTION =
            "Instruct: Given a question or keywords, retrieve the notes, messages and conversation passages that answer it\nQuery:"
    }
}

/** Pure: list price of `qwen/qwen3-embedding-8b`, $0.01 per million tokens, in millionths of a dollar. */
fun embeddingCostMicros(tokens: Long): Long = (tokens * 0.01).roundToLong()

/** A record that can be searched: a [MemorySource] wire name and the row's id. */
data class MemoryKey(val source: String, val ref: String) {
    override fun toString() = "$source:$ref"

    companion object {
        fun parse(raw: String): MemoryKey? {
            val source = raw.substringBefore(':', "").trim().lowercase()
            val ref = raw.substringAfter(':', "").trim()
            return if (source.isEmpty() || ref.isEmpty()) null else MemoryKey(source, ref)
        }
    }
}

/**
 * The vectors of `memory_embedding` (current model and size) in memory, int8-quantized (one byte
 * per value plus a scale: 1 KB per record at 1024 values), refreshed incrementally by
 * `embedded_at`. Cosine similarity by brute force: the corpus is thousands of records.
 */
@Inject
@SingleIn(AppScope::class)
class VectorIndex(private val database: Database, private val embeddings: Embeddings) {
    private class Entry(val values: ByteArray, val scale: Float)

    private val entries = ConcurrentHashMap<MemoryKey, Entry>()
    private val lock = Mutex()
    @Volatile private var watermark: Instant? = null

    val size: Int get() = entries.size

    /** Loads vectors written since the last refresh. */
    suspend fun refresh() = lock.withLock {
        val since = watermark
        val rows = database.tx {
            jdbc().prepareStatement(
                "SELECT source, ref, vector, embedded_at FROM memory_embedding WHERE model = ? AND dims = ?" +
                    (if (since != null) " AND embedded_at >= ?" else "") + " ORDER BY embedded_at",
            ).use { st ->
                st.setString(1, embeddings.model)
                st.setInt(2, embeddings.dims)
                if (since != null) st.setTimestamp(3, java.sql.Timestamp.from(since.toJavaInstant()))
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(Triple(MemoryKey(rs.getString(1), rs.getString(2)), rs.getBytes(3), rs.getTimestamp(4).toInstant().toKotlinInstant()))
                    }
                }
            }
        }
        rows.forEach { (key, bytes, _) -> put(key, vectorOf(bytes)) }
        rows.lastOrNull()?.let { watermark = it.third }
    }

    fun put(key: MemoryKey, vector: FloatArray) {
        var max = 0f
        for (x in vector) max = maxOf(max, abs(x))
        if (max == 0f) {
            entries[key] = Entry(ByteArray(0), 0f)
            return
        }
        val scale = max / 127f
        entries[key] = Entry(ByteArray(vector.size) { (vector[it] / scale).roundToInt().coerceIn(-127, 127).toByte() }, scale)
    }

    fun remove(keys: Collection<MemoryKey>) = keys.forEach { entries.remove(it) }

    /** The [k] records most similar to [query] (unit length) with similarity at least [minScore], best first. */
    fun nearest(query: FloatArray, k: Int, minScore: Float = 0f, sources: Set<String>? = null): List<Pair<MemoryKey, Float>> {
        val best = java.util.PriorityQueue<Pair<MemoryKey, Float>>(compareBy { it.second })
        for ((key, entry) in entries) {
            if (sources != null && key.source !in sources) continue
            val n = min(entry.values.size, query.size)
            if (n == 0) continue
            var dot = 0f
            for (i in 0 until n) dot += query[i] * entry.values[i]
            val score = dot * entry.scale
            if (score < minScore) continue
            best.add(key to score)
            if (best.size > k) best.poll()
        }
        return best.sortedByDescending { it.second }
    }
}

/** The raw JDBC connection of this transaction, for statements Exposed's DSL does not cover (arrays, bytea). */
fun JdbcTransaction.jdbc(): java.sql.Connection = connection.connection as java.sql.Connection
