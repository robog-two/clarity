package net.robog.clarity.harness

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val log = KotlinLogging.logger {}

/** OpenRouter refused or failed a request. [status] is the HTTP status, or null for an error sent mid-stream. */
class OpenRouterException(val status: Int?, message: String) : RuntimeException(message)

/**
 * OpenRouter's provider routing (`provider` in the request). `data_collection: deny` is always sent.
 * [zdr] keeps the request on zero-data-retention endpoints only; [quantizations] (empty: any) keeps
 * it off low-precision endpoints (fp4/int4 hurt tool calling); [sort] (null: OpenRouter's own
 * balancing) orders the endpoints left, `price` cheapest first. [order] (empty: none) pins the
 * providers to try first, in order (one provider keeps its prompt cache warm); [allowFallbacks]
 * false allows no other provider (null: OpenRouter's default, true).
 */
data class OpenRouterRouting(
    val zdr: Boolean = true,
    val quantizations: List<String> = DEFAULT_QUANTIZATIONS,
    val sort: String? = "price",
    val order: List<String> = emptyList(),
    val allowFallbacks: Boolean? = null,
) {
    companion object {
        val DEFAULT_QUANTIZATIONS = listOf("fp8", "bf16", "fp16")
    }
}

/**
 * Keeping a conversation on one upstream provider, so its prompt cache stays warm. [session] (the
 * run: the lead's thread or a helper) goes as `session_id`, OpenRouter's sticky routing key;
 * [preferred] is the provider that served the conversation's last call, tried first
 * ([OpenRouterModelGateway.providerOrder]). OpenRouter skips its own stickiness when an order is
 * given, so the order carries it then; fallbacks stay allowed, so an outage only moves the
 * conversation to another provider.
 */
data class Stickiness(val session: String? = null, val preferred: String? = null)

/**
 * [ModelGateway] over OpenRouter's OpenAI-compatible Chat Completions API, with the JDK's own
 * `java.net.http` client (no extra dependency) and kotlinx.serialization. OpenRouter is Clarity's
 * only model provider; Claude models are reached through it (`anthropic/...`).
 *
 * Request shape:
 *  - only the context since the latest compaction point is sent ([contextSince]); a `compaction`
 *    block from the Anthropic API (in journals from before OpenRouter was the only provider) becomes
 *    a user message with its summary text ([UNREADABLE_SUMMARY] when it has none), so what it
 *    summarized is kept;
 *  - the system blocks become one leading `system` message; a mid-conversation system message with
 *    text (an operator notice) becomes a text part of the user message it follows, under
 *    [OPERATOR_UPDATE_HEADING] (it takes the place of an [OPERATOR_UPDATE_PLACEHOLDER] text, and
 *    opens a user message after tool results), since some upstreams drop or hoist non-leading
 *    `system` messages; effort-only ones are dropped (effort is per request here);
 *  - tool uses become assistant `tool_calls` (arguments as a JSON string), tool results `tool`
 *    messages; other [Block.Raw] is dropped (old Anthropic thinking, tool search and server tool
 *    blocks), except this provider's own `reasoning_details` ([REASONING_TYPE]), replayed to the
 *    same model only;
 *  - all tools are sent as `function` tools, `tool_choice: auto`;
 *  - `reasoning.effort` is the effort in effect for this request: that of the last effort-only
 *    message since the latest compaction point, else [ModelRequest.effort] ([effortIn]);
 *    `response_format: json_schema` from [ModelRequest.structuredOutput], and always `provider.data_collection: deny` (no provider that
 *    keeps or trains on prompts), the rest of `provider` from [OpenRouterRouting] (zero data retention,
 *    quantizations, sort) and `usage.include` (the response carries its cost);
 *  - the run as `session_id` and the provider that served its last call first in `provider.order`
 *    ([Stickiness]), so a conversation stays on one provider and its cache; the provider that served
 *    each call is in its [Usage.provider];
 *  - prompt caching, for models that cache only at explicit breakpoints ([usesCacheBreakpoints]:
 *    Claude) and only when [ModelRequest.cache] is set: the system message becomes text parts, the
 *    parts of system blocks with [SystemBlock.cacheBreakpoint] get `cache_control`, as does the user
 *    text at [ModelRequest.messageBreakpoint], and [CachePolicy.automatic] adds the top-level
 *    `cache_control` (automatic caching). Each is `{"type": "ephemeral"}`, plus `"ttl": "1h"` for a
 *    one hour cache (OpenRouter's prompt caching guide). The message breakpoint moves to the last
 *    operator notice that follows its message, and one that falls on a tool result with no notice
 *    after it is not sent (the guide documents breakpoints on system and user content only); the automatic one
 *    covers the tail. Other models cache on their own and get no `cache_control`.
 * A long thread (the lead) compacts client-side (`LeadAgent`, a [Message.summary] in its journal).
 *
 * Always streamed (SSE) so long outputs do not hit timeouts; the blocking read runs in
 * [runInterruptible], so cancelling the coroutine aborts it. A 429/5xx or a failed connection is
 * retried after each of [retryDelays] before anything was read.
 */
class OpenRouterModelGateway(
    private val apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
    proxy: Proxy? = null,
    private val retryDelays: List<Duration> = listOf(500.milliseconds, 2.seconds),
    private val routing: OpenRouterRouting = OpenRouterRouting(),
    /** Told which upstream provider served each response (the stream's `provider`), when it says. */
    private val onServed: (provider: String) -> Unit = {},
) : ModelGateway {
    private val endpoint: URI = URI.create(baseUrl.trimEnd('/') + "/chat/completions")

    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(CONNECT_TIMEOUT.toJavaDuration())
        .apply { if (proxy != null) proxy(FixedProxy(proxy)) }
        .build()

    /** The provider that last served each conversation ([currentRunId]) and model, most recent last. */
    private val lastServed = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > STICKY_CONVERSATIONS
    }

    override suspend fun generate(request: ModelRequest, onTextDelta: (String) -> Unit): ModelResponse {
        val session = currentRunId()
        val key = session?.let { "$it ${request.model}" }
        val preferred = key?.let { synchronized(lastServed) { lastServed[it] } }
        val response = generate(request, Stickiness(session, preferred), onTextDelta)
        val served = response.usage.provider
        if (key != null && served != null) synchronized(lastServed) { lastServed[key] = served }
        return response
    }

    private suspend fun generate(request: ModelRequest, sticky: Stickiness, onTextDelta: (String) -> Unit): ModelResponse {
        val body = HarnessJson.encodeToString(JsonObject.serializer(), buildBody(request, routing, sticky))
        val call = HttpRequest.newBuilder(endpoint)
            .timeout(RESPONSE_TIMEOUT.toJavaDuration())
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        var attempt = 0
        while (true) {
            try {
                return runInterruptible(Dispatchers.IO) {
                    try {
                        send(call, request.model, onTextDelta)
                    } catch (e: IOException) {
                        // The JDK stream turns an interrupt into an IOException; surface it as the
                        // interrupt it was, so cancellation reads as cancellation.
                        if (e.cause is InterruptedException || Thread.interrupted()) throw InterruptedException("cancelled")
                        throw e
                    }
                }
            } catch (e: Retryable) {
                val wait = retryDelays.getOrNull(attempt++) ?: throw (e.cause ?: e)
                log.info { "OpenRouter call failed (${e.message}); retrying in $wait" }
                delay(wait)
            }
        }
    }

    /** Thrown before any of the body was read, so the call can be made again. */
    private class Retryable(message: String, cause: Exception) : RuntimeException(message, cause)

    private fun send(call: HttpRequest, model: String, onTextDelta: (String) -> Unit): ModelResponse {
        val response = try {
            http.send(call, HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: IOException) {
            throw Retryable(e::class.simpleName.orEmpty(), IOException("could not reach OpenRouter: ${e::class.simpleName}", e))
        }
        response.body().use { stream ->
            val status = response.statusCode()
            if (status !in 200..299) {
                val error = OpenRouterException(status, "OpenRouter answered $status: ${errorMessage(stream.readPrefix())}".redact())
                if (status == 408 || status == 429 || status >= 500) throw Retryable("status $status", error)
                throw error
            }
            val accumulator = StreamAccumulator(model, onTextDelta)
            stream.bufferedReader().useLines { lines -> parseSse(lines, accumulator::accept) }
            accumulator.provider?.let(onServed)
            return accumulator.response()
        }
    }

    private fun InputStream.readPrefix(): String = readNBytes(ERROR_BODY_LIMIT).decodeToString()

    // The key never goes into an exception, even if a server echoed it.
    private fun String.redact(): String = if (apiKey.isEmpty()) this else replace(apiKey, "[redacted]")

    /** Routes every request through the egress proxy. */
    private class FixedProxy(private val proxy: Proxy) : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = listOf(proxy)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"

        private val CONNECT_TIMEOUT = 30.seconds

        /** Until the response headers arrive; the stream itself has no deadline. */
        private val RESPONSE_TIMEOUT = 10.minutes

        private const val ERROR_BODY_LIMIT = 2_000

        /** Prefix of a tool result the tool reported as failed (the wire has no `is_error`). */
        const val ERROR_PREFIX = "[error] "

        /** The `type` of OpenRouter's web search server tool. */
        const val WEB_SEARCH_TYPE = "openrouter:web_search"

        /** [Block.Raw.type] of the `reasoning_details` kept from a response, tagged with the provider and [REASONING_MODEL]. */
        const val REASONING_TYPE = "openrouter_reasoning"

        /** The model that wrote a [REASONING_TYPE] block; it is replayed only to that model. */
        const val REASONING_MODEL = "model"

        private const val PROVIDER = "openrouter"

        /** What a stored Anthropic `compaction` block becomes when its summary is encrypted only. */
        const val UNREADABLE_SUMMARY = "(The earlier conversation was summarized, but the summary can't be read here.)"

        /** Conversations whose last provider is remembered ([Stickiness.preferred]). */
        private const val STICKY_CONVERSATIONS = 256

        /**
         * Pure: the provider order sent: the one that served this conversation last ([preferred]) in
         * front of the configured [OpenRouterRouting.order]. With fallbacks turned off and no order
         * configured, nothing is preferred, so one provider never becomes the only one allowed.
         */
        internal fun providerOrder(routing: OpenRouterRouting, preferred: String?): List<String> {
            val usable = preferred?.takeIf { routing.allowFallbacks != false || it in routing.order }
            return (listOfNotNull(usable) + routing.order).distinct()
        }

        /** The request body exactly as it goes over the wire; for tests and debugging. */
        internal fun buildBody(request: ModelRequest, routing: OpenRouterRouting = OpenRouterRouting(), sticky: Stickiness = Stickiness()): JsonObject {
            val cache = request.cache?.takeIf { usesCacheBreakpoints(request.model) }
            val order = providerOrder(routing, sticky.preferred)
            return buildJsonObject {
                put("model", request.model)
                sticky.session?.let { put("session_id", it) }
                put("messages", wireMessages(request, cache))
                if (cache?.automatic == true) put("cache_control", cacheControl(cache))
                put("max_tokens", request.maxTokens)
                put("stream", true)
                putJsonObject("stream_options") { put("include_usage", true) }
                putJsonObject("usage") { put("include", true) }
                putJsonObject("provider") {
                    put("data_collection", "deny")
                    if (routing.zdr) put("zdr", true)
                    if (routing.quantizations.isNotEmpty()) put("quantizations", JsonArray(routing.quantizations.map(::JsonPrimitive)))
                    routing.sort?.let { put("sort", it) }
                    if (order.isNotEmpty()) put("order", JsonArray(order.map(::JsonPrimitive)))
                    routing.allowFallbacks?.let { put("allow_fallbacks", it) }
                }
                effortIn(contextSince(request.messages), request.effort)?.let { putJsonObject("reasoning") { put("effort", reasoningEffort(it, request.model)) } }
                val search = request.webSearch
                if (request.tools.isNotEmpty() || search != null) {
                    put("tools", JsonArray(request.tools.map { it.toWire() } + listOfNotNull(search?.toWire())))
                    put("tool_choice", "auto")
                }
                // Bounds the server-tool steps of this request (web search is the only server tool sent).
                search?.let { put("max_tool_calls", it.maxSearches) }
                request.structuredOutput?.let { schema ->
                    putJsonObject("response_format") {
                        put("type", "json_schema")
                        putJsonObject("json_schema") {
                            put("name", "answer")
                            put("strict", true)
                            put("schema", schema)
                        }
                    }
                }
            }
        }

        /**
         * The `reasoning.effort` sent for [effort]. Claude takes `low` to `max` through OpenRouter (its
         * reasoning guide: the thinking budget is a share of `max_tokens`, 0.95 for `xhigh` and `max`);
         * other models are capped at `high`.
         */
        internal fun reasoningEffort(effort: String, model: String = ""): String = when {
            usesCacheBreakpoints(model) -> effort
            effort == "xhigh" || effort == "max" -> "high"
            else -> effort
        }

        /** `{"type": "ephemeral"}`, with `"ttl": "1h"` for a one hour cache (5 minutes is the default). */
        private fun cacheControl(cache: CachePolicy): JsonObject = buildJsonObject {
            put("type", "ephemeral")
            if (cache.ttl == "1h") put("ttl", "1h")
        }

        private fun textPart(text: String, cache: CachePolicy?): JsonObject = buildJsonObject {
            put("type", "text")
            put("text", text)
            cache?.let { put("cache_control", cacheControl(it)) }
        }

        /** OpenRouter's web search server tool (server tools guide): engine, mode and result caps. */
        internal fun WebSearchTool.toWire(): JsonObject = buildJsonObject {
            put("type", WEB_SEARCH_TYPE)
            putJsonObject("parameters") {
                put("engine", engine)
                mode?.let { put("mode", it) }
                put("max_results", maxResults)
                put("max_total_results", maxTotalResults ?: (maxResults * maxSearches))
                if (excludedDomains.isNotEmpty()) put("excluded_domains", JsonArray(excludedDomains.map(::JsonPrimitive)))
                maxCharacters?.let { put("max_characters", it) }
            }
        }

        private fun ToolDefinition.toWire(): JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", name)
                put("description", description)
                put("parameters", inputSchema)
            }
        }

        private fun wireMessages(request: ModelRequest, cache: CachePolicy?): JsonArray = buildJsonArray {
            if (request.system.isNotEmpty()) {
                if (cache != null && request.system.any { it.cacheBreakpoint }) {
                    // One text part per block, so a breakpoint ends exactly where its block does.
                    add(chat("system", JsonArray(request.system.map { block -> textPart(block.text, cache.takeIf { block.cacheBreakpoint }) })))
                } else {
                    add(chat("system", JsonPrimitive(request.systemText)))
                }
            }
            val context = contextSince(request.messages)
            val offset = request.messages.size - context.size
            // (message index in context, block index): the last block of a user message. Only user text
            // carries it on this wire: that text, or the last operator notice that follows the message.
            val breakpoint = request.messageBreakpoint?.takeIf { cache != null }
                ?.let { (m, b) -> (m - offset) to b }
                ?.takeIf { (m, b) -> m >= 0 && context[m].role == Role.USER && context[m].content.getOrNull(b) != null }
            // The text parts of the user message being built, kept back so operator notices can join it,
            // and whether its last part carries the message breakpoint.
            val userParts = mutableListOf<String>()
            var userMarked = false
            fun flushUser() {
                if (userParts.isNotEmpty()) {
                    val content = if (userParts.size == 1 && !userMarked) {
                        JsonPrimitive(userParts.single())
                    } else {
                        JsonArray(userParts.mapIndexed { i, text -> textPart(text, cache.takeIf { userMarked && i == userParts.lastIndex }) })
                    }
                    add(chat("user", content))
                    userParts.clear()
                }
                userMarked = false
            }
            for ((index, original) in context.withIndex()) {
                if (original.role != Role.SYSTEM) flushUser()
                // Anthropic drops everything before its compaction block; here the block becomes its summary text.
                val compaction = original.content.indexOfLast { it is Block.Raw && it.isCompaction }
                val message = if (compaction < 0) {
                    original
                } else {
                    val summary = (original.content[compaction] as Block.Raw).compactionSummary ?: UNREADABLE_SUMMARY
                    add(chat("user", JsonPrimitive("$SUMMARY_MARKER\n$summary")))
                    original.copy(content = original.content.drop(compaction + 1))
                }
                // The block that carries the message breakpoint, if it survived the cut above.
                val marked = breakpoint?.takeIf { it.first == index }
                    ?.let { (_, b) -> original.content[b] }
                    ?.takeIf { block -> message.content.any { it === block } }
                // Other Raw blocks are old Anthropic ones (thinking signatures, tool search, server tools) or another model's reasoning.
                val texts = message.content.filterIsInstance<Block.Text>().map { it.text }
                when (message.role) {
                    // An effort-only system message is dropped: effort is set per request here. One with
                    // text is an operator notice, carried in the user message ([operatorUpdate]).
                    Role.SYSTEM -> if (texts.isNotEmpty()) {
                        val notice = operatorUpdate(texts.joinToString("\n\n"))
                        // The placeholder only stood in for the notice; the notice takes its place.
                        if (userParts.singleOrNull() == OPERATOR_UPDATE_PLACEHOLDER) userParts.clear()
                        userParts += notice
                    }
                    Role.USER -> {
                        // Tool results must follow the assistant's tool calls directly, so they go first.
                        message.content.filterIsInstance<Block.Result>().forEach { result ->
                            add(buildJsonObject {
                                put("role", "tool")
                                put("tool_call_id", result.toolUseId)
                                put("content", if (result.isError) ERROR_PREFIX + result.content else result.content)
                            })
                        }
                        message.content.filterIsInstance<Block.Text>().forEach { userParts += it.text }
                        // The breakpoint sits on the message's last block, so it goes on the last text part:
                        // the message's own, or a notice after it (a tool result carries none here).
                        userMarked = marked != null
                    }
                    Role.ASSISTANT -> {
                        val calls = message.content.filterIsInstance<Block.ToolUse>()
                        if (texts.isEmpty() && calls.isEmpty()) continue
                        val reasoning = message.content.firstNotNullOfOrNull { reasoningFor(it, request.model) }
                        add(buildJsonObject {
                            put("role", "assistant")
                            put("content", if (texts.isEmpty()) JsonPrimitive(null as String?) else JsonPrimitive(texts.joinToString("")))
                            reasoning?.let { put("reasoning_details", it) }
                            if (calls.isNotEmpty()) {
                                put("tool_calls", JsonArray(calls.map { call ->
                                    buildJsonObject {
                                        put("id", call.id)
                                        put("type", "function")
                                        putJsonObject("function") {
                                            put("name", call.name)
                                            put("arguments", HarnessJson.encodeToString(JsonObject.serializer(), call.input))
                                        }
                                    }
                                }))
                            }
                        })
                    }
                }
            }
            flushUser()
        }

        /** The `reasoning_details` of [block] when this provider wrote it for [model], else null. */
        private fun reasoningFor(block: Block, model: String): JsonArray? {
            if (block !is Block.Raw || block.type != REASONING_TYPE || block.foreignProvider != PROVIDER) return null
            if ((block.json[REASONING_MODEL] as? JsonPrimitive)?.contentOrNull != model) return null
            return block.json["details"] as? JsonArray
        }

        /** Pure: the [Block.Raw] that keeps [details] for replay to [model]. */
        internal fun reasoningBlock(model: String, details: List<JsonObject>): Block.Raw = Block.Raw(buildJsonObject {
            put("type", REASONING_TYPE)
            put(Block.Raw.FOREIGN_PROVIDER_KEY, PROVIDER)
            put(REASONING_MODEL, model)
            put("details", JsonArray(details))
        })

        /** Heads an operator notice carried in a user message ([operatorUpdate]). */
        const val OPERATOR_UPDATE_HEADING = "[Operator update from Clarity's server, not from the person]"

        /**
         * Pure: an operator notice (inbox items, scheduled jobs, memory) as user text, marked as coming
         * from Clarity's server. Some upstreams drop or hoist `system` messages that are not leading,
         * so notices never go as mid-conversation `system` messages on this wire.
         */
        internal fun operatorUpdate(text: String): String = "$OPERATOR_UPDATE_HEADING\n$text"

        private fun chat(role: String, content: JsonElement) = buildJsonObject {
            put("role", role)
            put("content", content)
        }

        /**
         * Pure: feeds each SSE `data` payload of [lines] to [onData] until `[DONE]`, the end of the
         * stream, or [onData] returning false. Comment lines (`: OPENROUTER PROCESSING`) are skipped.
         */
        internal fun parseSse(lines: Sequence<String>, onData: (String) -> Boolean) {
            val data = StringBuilder()
            fun flush(): Boolean {
                if (data.isEmpty()) return true
                val payload = data.toString()
                data.clear()
                return payload != "[DONE]" && onData(payload)
            }
            for (line in lines) {
                when {
                    line.isEmpty() -> if (!flush()) return
                    line.startsWith(":") -> Unit
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").removePrefix(" "))
                    }
                }
            }
            flush()
        }

        /** The `error.message` of an error body, or the start of the body. */
        private fun errorMessage(body: String): String {
            val parsed = runCatching { HarnessJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
            val message = ((parsed?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            return (message ?: body).take(300).ifBlank { "no details" }
        }

        internal fun stopReason(finish: String?, hasToolCalls: Boolean): StopReason = when (finish) {
            "tool_calls" -> StopReason.TOOL_USE
            // Some providers end a tool-calling turn with "stop"; the loop needs TOOL_USE to run them.
            "stop" -> if (hasToolCalls) StopReason.TOOL_USE else StopReason.END_TURN
            "length" -> StopReason.MAX_TOKENS
            "content_filter" -> StopReason.REFUSAL
            else -> StopReason.OTHER
        }

        /**
         * Pure: OpenRouter's usage in the Anthropic meaning: `inputTokens` excludes cache reads and
         * writes (the prompt total includes them), and the reported `cost` (USD) in micro-dollars.
         */
        internal fun toUsage(usage: JsonObject): Usage {
            fun JsonObject.long(key: String): Long = (get(key) as? JsonPrimitive)?.longOrNull ?: 0
            val details = usage["prompt_tokens_details"] as? JsonObject ?: JsonObject(emptyMap())
            val cached = details.long("cached_tokens")
            val written = details.long("cache_write_tokens")
            val cost = (usage["cost"] as? JsonPrimitive)?.doubleOrNull
            // OpenRouter streams `server_tool_use_details`; its docs name `server_tool_use`.
            val searches = listOf("server_tool_use_details", "server_tool_use")
                .firstNotNullOfOrNull { usage[it] as? JsonObject }?.long("web_search_requests") ?: 0
            return Usage(
                inputTokens = (usage.long("prompt_tokens") - cached - written).coerceAtLeast(0),
                outputTokens = usage.long("completion_tokens"),
                cacheCreationInputTokens = written,
                cacheReadInputTokens = cached,
                reportedCostMicros = cost?.let { (it * 1_000_000).roundToLong() },
                webSearchRequests = searches,
            )
        }

        /**
         * Pure: the `url_citation` annotations in [annotations] (a message's or a delta's
         * `annotations`). Takes the nested shape (`{"type": "url_citation", "url_citation": {...}}`)
         * and a flat one; anything else is skipped. `content` is the cited excerpt, when sent.
         */
        internal fun urlCitations(annotations: JsonArray?): List<UrlCitation> = annotations.orEmpty().mapNotNull { element ->
            val annotation = element as? JsonObject ?: return@mapNotNull null
            if ((annotation["type"] as? JsonPrimitive)?.contentOrNull != "url_citation") return@mapNotNull null
            val body = annotation["url_citation"] as? JsonObject ?: annotation
            fun field(name: String) = (body[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val url = field("url") ?: return@mapNotNull null
            UrlCitation(url, field("title"), field("content"))
        }
    }

    /** Folds the stream's chunks into one [ModelResponse]. */
    internal class StreamAccumulator(private val model: String = "", private val onTextDelta: (String) -> Unit = {}) {
        private val text = StringBuilder()
        private val reasoning = sortedMapOf<Int, MutableMap<String, JsonElement>>()
        private val calls = sortedMapOf<Int, Call>()
        private val citations = LinkedHashMap<String, UrlCitation>()
        private var finish: String? = null
        private var usage: Usage = Usage.ZERO

        /** The upstream provider that served the stream, from its chunks' `provider`. */
        var provider: String? = null
            private set

        private class Call(var id: String? = null, var name: String = "", val arguments: StringBuilder = StringBuilder())

        /** Takes one chunk; returns true to keep reading. Throws on an `error` chunk. */
        fun accept(payload: String): Boolean {
            val chunk = HarnessJson.parseToJsonElement(payload) as? JsonObject ?: return true
            (chunk["error"] as? JsonObject)?.let { error ->
                val message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: "unknown error"
                throw OpenRouterException(null, "OpenRouter stream failed: ${message.take(300)}")
            }
            (chunk["usage"] as? JsonObject)?.let { usage = toUsage(it) }
            (chunk["provider"] as? JsonPrimitive)?.contentOrNull?.let { provider = it }
            val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return true
            val delta = choice["delta"] as? JsonObject
            // Web search citations: streamed in a delta, or on the message of a non-streamed answer.
            for (holder in listOf(delta, choice["message"] as? JsonObject)) {
                urlCitations(holder?.get("annotations") as? JsonArray).forEach { citations.putIfAbsent(it.url, it) }
            }
            ((delta?.get("content") as? JsonPrimitive)?.contentOrNull)?.takeIf { it.isNotEmpty() }?.let {
                text.append(it)
                onTextDelta(it)
            }
            (delta?.get("reasoning_details") as? JsonArray)?.forEach { element ->
                val part = element as? JsonObject ?: return@forEach
                val index = (part["index"] as? JsonPrimitive)?.longOrNull?.toInt() ?: reasoning.size
                val detail = reasoning.getOrPut(index) { linkedMapOf() }
                // A detail streams in pieces: its text fields are joined, everything else is the latest value.
                part.forEach { (key, value) ->
                    val previous = (detail[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val piece = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    detail[key] = if (key in REASONING_TEXT_FIELDS && previous != null && piece != null) JsonPrimitive(previous + piece) else value
                }
            }
            (delta?.get("tool_calls") as? JsonArray)?.forEach { element ->
                val part = element as? JsonObject ?: return@forEach
                val index = (part["index"] as? JsonPrimitive)?.longOrNull?.toInt() ?: calls.size
                val call = calls.getOrPut(index) { Call() }
                (part["id"] as? JsonPrimitive)?.contentOrNull?.let { call.id = it }
                val function = part["function"] as? JsonObject
                (function?.get("name") as? JsonPrimitive)?.contentOrNull?.let { call.name += it }
                (function?.get("arguments") as? JsonPrimitive)?.contentOrNull?.let { call.arguments.append(it) }
            }
            (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let { finish = it }
            return true
        }

        fun response(): ModelResponse {
            val uses = calls.map { (index, call) ->
                // A truncated or malformed input becomes {}, so validation reports the missing
                // parameters to the model instead of crashing the run (as with Anthropic).
                val input = runCatching { HarnessJson.parseToJsonElement(call.arguments.toString()) as? JsonObject }.getOrNull()
                Block.ToolUse(call.id ?: "call_$index", call.name, input ?: JsonObject(emptyMap()))
            }
            val answer = (if (text.isEmpty()) emptyList() else listOf(Block.Text(text.toString()))) + uses
            // Reasoning is kept only beside an answer, so no assistant turn is reasoning alone.
            val kept = if (answer.isEmpty() || reasoning.isEmpty()) emptyList() else listOf(reasoningBlock(model, reasoning.values.map(::JsonObject)))
            // Citations likewise, after the answer; the request builder never sends them back.
            val cited = if (answer.isEmpty() || citations.isEmpty()) emptyList() else listOf(citationsBlock(citations.values.toList()))
            // Citations mean a search ran, even when the usage did not count it: at least one, so the run is tainted.
            val searches = if (citations.isEmpty()) usage.webSearchRequests else usage.webSearchRequests.coerceAtLeast(1)
            return ModelResponse(
                kept + answer + cited,
                stopReason(finish, uses.isNotEmpty()),
                usage.copy(webSearchRequests = searches, provider = provider),
            )
        }

        private companion object {
            val REASONING_TEXT_FIELDS = setOf("text", "summary", "data")
        }
    }
}
