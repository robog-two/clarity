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
import java.util.concurrent.atomic.AtomicBoolean
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
 * [ModelGateway] over OpenRouter's OpenAI-compatible Chat Completions API, with the JDK's own
 * `java.net.http` client (no extra dependency) and kotlinx.serialization.
 *
 * Request shape:
 *  - the system blocks become one leading `system` message; mid-conversation system messages with
 *    text stay `system` messages in place, effort-only ones are dropped (effort is per request here);
 *  - tool uses become assistant `tool_calls` (arguments as a JSON string), tool results `tool`
 *    messages; [Block.Raw] (thinking, compaction, server tools: Anthropic-only) is dropped;
 *  - loaded and deferred tools are all sent as `function` tools (no tool search), `tool_choice: auto`;
 *  - `reasoning.effort` from [ModelRequest.effort], `response_format: json_schema` from
 *    [ModelRequest.structuredOutput], and always `provider.data_collection: deny` (no provider that
 *    keeps or trains on prompts) and `usage.include` (the response carries its cost).
 * Not supported: server tools (rejected), prompt-cache breakpoints, refusal fallbacks and
 * server-side compaction (all ignored). So a role with compaction (the lead) must not run here
 * until the client-side compaction exists.
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
) : ModelGateway {
    private val endpoint: URI = URI.create(baseUrl.trimEnd('/') + "/chat/completions")

    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(CONNECT_TIMEOUT.toJavaDuration())
        .apply { if (proxy != null) proxy(FixedProxy(proxy)) }
        .build()

    private val compactionWarned = AtomicBoolean(false)

    override suspend fun generate(request: ModelRequest, onTextDelta: (String) -> Unit): ModelResponse {
        if (request.compaction != null && compactionWarned.compareAndSet(false, true)) {
            log.warn { "OpenRouter has no server-side compaction; ${request.model} runs without it (the lead must stay on Anthropic for now)" }
        }
        val body = HarnessJson.encodeToString(JsonObject.serializer(), buildBody(request))
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
                        send(call, onTextDelta)
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

    private fun send(call: HttpRequest, onTextDelta: (String) -> Unit): ModelResponse {
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
            val accumulator = StreamAccumulator(onTextDelta)
            stream.bufferedReader().useLines { lines -> parseSse(lines, accumulator::accept) }
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

        /** The request body exactly as it goes over the wire; for tests and debugging. */
        internal fun buildBody(request: ModelRequest): JsonObject {
            require(request.serverTools.isEmpty()) { "OpenRouter does not run server tools (${request.serverTools.joinToString { it.wireName }})" }
            // request.fallbacks: OpenRouter's own model fallbacks (`models: [...]`) are not used yet.
            return buildJsonObject {
                put("model", request.model)
                put("messages", wireMessages(request))
                put("max_tokens", request.maxTokens)
                put("stream", true)
                putJsonObject("stream_options") { put("include_usage", true) }
                putJsonObject("usage") { put("include", true) }
                putJsonObject("provider") { put("data_collection", "deny") }
                request.effort?.let { putJsonObject("reasoning") { put("effort", reasoningEffort(it)) } }
                val tools = request.tools + request.deferredTools
                if (tools.isNotEmpty()) {
                    put("tools", JsonArray(tools.map { it.toWire() }))
                    put("tool_choice", "auto")
                }
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

        /** OpenRouter's efforts stop at `high`. */
        internal fun reasoningEffort(effort: String): String = when (effort) {
            "xhigh", "max" -> "high"
            else -> effort
        }

        private fun ToolDefinition.toWire(): JsonObject = buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", name)
                put("description", description)
                put("parameters", inputSchema)
            }
        }

        private fun wireMessages(request: ModelRequest): JsonArray = buildJsonArray {
            if (request.system.isNotEmpty()) add(chat("system", JsonPrimitive(request.systemText)))
            for (message in request.messages) {
                // Raw blocks are Anthropic-only (thinking signatures, compaction, server tools); no other provider reads them.
                val texts = message.content.filterIsInstance<Block.Text>().map { it.text }
                when (message.role) {
                    // An effort-only system message is dropped: effort is set per request here.
                    Role.SYSTEM -> if (texts.isNotEmpty()) add(chat("system", JsonPrimitive(texts.joinToString("\n\n"))))
                    Role.USER -> {
                        // Tool results must follow the assistant's tool calls directly, so they go first.
                        message.content.filterIsInstance<Block.Result>().forEach { result ->
                            add(buildJsonObject {
                                put("role", "tool")
                                put("tool_call_id", result.toolUseId)
                                put("content", if (result.isError) ERROR_PREFIX + result.content else result.content)
                            })
                        }
                        when (texts.size) {
                            0 -> Unit
                            1 -> add(chat("user", JsonPrimitive(texts.single())))
                            else -> add(chat("user", JsonArray(texts.map { buildJsonObject { put("type", "text"); put("text", it) } })))
                        }
                    }
                    Role.ASSISTANT -> {
                        val calls = message.content.filterIsInstance<Block.ToolUse>()
                        if (texts.isEmpty() && calls.isEmpty()) continue
                        add(buildJsonObject {
                            put("role", "assistant")
                            put("content", if (texts.isEmpty()) JsonPrimitive(null as String?) else JsonPrimitive(texts.joinToString("")))
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
        }

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
            return Usage(
                inputTokens = (usage.long("prompt_tokens") - cached - written).coerceAtLeast(0),
                outputTokens = usage.long("completion_tokens"),
                cacheCreationInputTokens = written,
                cacheReadInputTokens = cached,
                reportedCostMicros = cost?.let { (it * 1_000_000).roundToLong() },
            )
        }
    }

    /** Folds the stream's chunks into one [ModelResponse]. */
    internal class StreamAccumulator(private val onTextDelta: (String) -> Unit = {}) {
        private val text = StringBuilder()
        private val calls = sortedMapOf<Int, Call>()
        private var finish: String? = null
        private var usage: Usage = Usage.ZERO

        private class Call(var id: String? = null, var name: String = "", val arguments: StringBuilder = StringBuilder())

        /** Takes one chunk; returns true to keep reading. Throws on an `error` chunk. */
        fun accept(payload: String): Boolean {
            val chunk = HarnessJson.parseToJsonElement(payload) as? JsonObject ?: return true
            (chunk["error"] as? JsonObject)?.let { error ->
                val message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: "unknown error"
                throw OpenRouterException(null, "OpenRouter stream failed: ${message.take(300)}")
            }
            (chunk["usage"] as? JsonObject)?.let { usage = toUsage(it) }
            val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return true
            val delta = choice["delta"] as? JsonObject
            ((delta?.get("content") as? JsonPrimitive)?.contentOrNull)?.takeIf { it.isNotEmpty() }?.let {
                text.append(it)
                onTextDelta(it)
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
            val content = (if (text.isEmpty()) emptyList() else listOf(Block.Text(text.toString()))) + uses
            return ModelResponse(content, stopReason(finish, uses.isNotEmpty()), usage)
        }
    }
}
