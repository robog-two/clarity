package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.harness.HarnessJson
import net.robog.clarity.harness.httpsProxyFromEnv

private val log = KotlinLogging.logger {}

/** What a model can take in; asked before offering a tool that needs it. */
fun interface ModelCapabilities {
    /** Whether [model] (an OpenRouter id) takes images as input; null when that is not known. */
    suspend fun acceptsImages(model: String): Boolean?
}

/** Pure: the models of OpenRouter's `GET /models` answer ([body]) by id, true when `architecture.input_modalities` has `image`. */
fun parseImageSupport(body: String): Map<String, Boolean> {
    val data = (runCatching { HarnessJson.parseToJsonElement(body) }.getOrNull() as? JsonObject)?.get("data") as? JsonArray ?: return emptyMap()
    return data.mapNotNull { element ->
        val model = element as? JsonObject ?: return@mapNotNull null
        val id = (model["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val modalities = (model["architecture"] as? JsonObject)?.get("input_modalities") as? JsonArray ?: return@mapNotNull null
        id to modalities.any { (it as? JsonPrimitive)?.contentOrNull.equals("image", ignoreCase = true) }
    }.toMap()
}

/**
 * Pure: whether [model] takes images according to [known] (see [parseImageSupport]). A leading `~` (OpenRouter's
 * "latest" alias) and a `:variant` suffix (`:nitro`, `:free`) are ignored; a model not in the list is unknown (null).
 */
fun imageSupportOf(known: Map<String, Boolean>, model: String): Boolean? {
    val id = model.trim().removePrefix("~")
    return known[id] ?: known[id.substringBefore(':')]
}

/**
 * Asks OpenRouter's public model list once and keeps the answer for [ttl]; a failed ask is tried again after
 * [retryAfter]. Unknown until it has answered, and never blocks a run for long ([timeout]).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class OpenRouterModelCapabilities(
    /** The raw answer of the model list, or null when it could not be had. */
    private val load: suspend () -> String?,
    private val clock: Clock = Clock.System,
    private val ttl: Duration = 24.hours,
    private val retryAfter: Duration = 5.minutes,
) : ModelCapabilities {
    @Inject
    constructor(config: AgentConfig) : this({ download(config.openRouterBaseUrl) })

    private val lock = Mutex()
    private var known: Map<String, Boolean> = emptyMap()
    private var validUntil: Instant? = null

    override suspend fun acceptsImages(model: String): Boolean? {
        refreshIfDue()
        return imageSupportOf(known, model)
    }

    private suspend fun refreshIfDue() = lock.withLock {
        val now = clock.now()
        if (validUntil?.let { now < it } == true) return@withLock
        val body = try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "could not read OpenRouter's model list: ${e.message}" }
            null
        }
        val parsed = body?.let(::parseImageSupport).orEmpty()
        if (parsed.isNotEmpty()) {
            known = parsed
            validUntil = now + ttl
        } else {
            validUntil = now + retryAfter
        }
    }

    companion object {
        private val timeout: Duration = 10.seconds

        internal suspend fun download(baseUrl: String): String? {
            val proxy = httpsProxyFromEnv(System.getenv())
            val client = HttpClient.newBuilder().connectTimeout(timeout.toJavaDuration())
                .apply { if (proxy != null) proxy(object : java.net.ProxySelector() {
                    override fun select(uri: URI?) = listOf(proxy)
                    override fun connectFailed(uri: URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) = Unit
                }) }
                .build()
            val request = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/models")).timeout(timeout.toJavaDuration()).GET().build()
            val response = runInterruptible(Dispatchers.IO) { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            return response.body().takeIf { response.statusCode() in 200..299 }
        }
    }
}
