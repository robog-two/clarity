package net.robog.clarity.harness

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaContentBlock
import com.anthropic.models.beta.messages.BetaContextManagementConfig
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaSystemMessageParam
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.beta.messages.BetaToolChoiceAuto
import com.anthropic.models.beta.messages.BetaToolUnion
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * [ModelGateway] over the Anthropic Messages API (Java SDK). The only class in the codebase that
 * touches SDK types.
 *
 * SDK findings (anthropic-java 3.3.0, inspected with `javap`; no upgrade was needed). Everything the
 * harness needs is typed on `MessageCreateParams` (the beta endpoint), so no escape hatch is used
 * for the request except where noted:
 *  - betas: `addBeta(String)`; we send `server-side-fallback-2026-07-01` and `compact-2026-01-12`;
 *  - compaction: `contextManagement(BetaContextManagementConfig)` with a `BetaCompact20260112Edit`
 *    (`trigger`, `instructions`, `pauseAfterCompaction`); responses carry `BetaCompactionBlock`s and
 *    stop reason `compaction`, kept as [Block.Raw] and replayed verbatim;
 *  - tool search: `BetaToolSearchToolBm25_20251119` plus `BetaTool.deferLoading`;
 *  - web search / fetch: `BetaWebSearchTool20260209` / `BetaWebFetchTool20260209`;
 *  - caching: top-level `cacheControl(BetaCacheControlEphemeral)` and per-block `cacheControl`
 *    with `ttl` (`5m`/`1h`); usage exposes `cacheCreation` with the 5m/1h split;
 *  - mid-conversation system messages: `addMessage(BetaSystemMessageParam)` (no beta header); their
 *    `outputConfig(BetaSystemMessageOutputConfig)` (`effort`) needs beta `mid-conversation-output-config-2026-07-01`;
 *  - refusal fallback: `fallbacksDefault()`; effort: `BetaOutputConfig.effort`;
 *  - `thinking.display`: `BetaThinkingConfigAdaptive.display`; structured output: `output_config.format`
 *    (`json_schema`), set through the SDK's own JSON mapping so field names stay the API's.
 * Because the SDK's typed classes are populated by converting wire-shaped JSON with the SDK's own
 * mapper ([toSdk]), we never hand-build (and mis-name) its block classes, and the same JSON shape is
 * what [bodyJson] prints for tests.
 *
 * Request shape (all of it is stable per run except `messages`, to keep the cache prefix intact):
 *  - `thinking: adaptive` (Opus 5.5 cannot run without thinking; never disabled), explicit effort;
 *  - system as a list of text blocks, explicit cache breakpoint on flagged blocks, optional top-level
 *    automatic caching;
 *  - tools in the caller's (sorted) order, deferred ones flagged, the BM25 search tool added when any
 *    are deferred; `tool_choice: auto` (forced `any`/`tool` is rejected by this model family);
 *  - streamed and accumulated, with eager input streaming on every client tool (the harness validates
 *    inputs against the tool's schema before running anything, and never runs a truncated turn).
 */
class AnthropicModelGateway(private val client: AnthropicClient) : ModelGateway {

    constructor(apiKey: String, proxy: java.net.Proxy? = null) : this(
        AnthropicOkHttpClient.builder().apiKey(apiKey).putHeader("X-Api-Key", apiKey).apply { if (proxy != null) proxy(proxy) }.build(),
    )

    override suspend fun generate(request: ModelRequest, onTextDelta: (String) -> Unit): ModelResponse {
        val params = buildParams(request)
        // The SDK is blocking; runInterruptible lets coroutine cancellation interrupt the socket read.
        return runInterruptible(Dispatchers.IO) { stream(params, onTextDelta) }
    }

    private fun stream(params: MessageCreateParams, onTextDelta: (String) -> Unit): ModelResponse {
        val accumulator = BetaMessageAccumulator.create()
        client.beta().messages().createStreaming(params).use { response ->
            response.stream().forEach { event ->
                accumulator.accumulate(event)
                event.contentBlockDelta().getOrNull()
                    ?.delta()?.text()?.getOrNull()
                    ?.let { onTextDelta(it.text()) }
            }
        }
        return toModelResponse(accumulator.message())
    }

    companion object {
        /** Beta flag enabling `fallbacks: "default"` (Anthropic-chosen substitute, routed by refusal category). */
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        /** Beta flag for server-side compaction. */
        const val COMPACTION_BETA = "compact-2026-01-12"

        /** Beta flag for `output_config.effort` on mid-conversation system messages. */
        const val MID_CONVERSATION_EFFORT_BETA = "mid-conversation-output-config-2026-07-01"

        const val TOOL_SEARCH_TYPE = "tool_search_tool_bm25_20251119"
        const val TOOL_SEARCH_NAME = "tool_search_tool_bm25"

        internal fun buildParams(request: ModelRequest): MessageCreateParams {
            val builder = MessageCreateParams.builder()
                .model(request.model)
                .maxTokens(request.maxTokens)
                .systemOfBetaTextBlockParams(request.system.map { it.toWire(request.cache).toSdk<BetaTextBlockParam>() })
                .thinking(thinkingWire(request.thinkingDisplay).toSdk<BetaThinkingConfigAdaptive>())

            request.messages.forEach { message ->
                if (message.role == Role.SYSTEM) {
                    builder.addMessage(message.toWire().toSdk<BetaSystemMessageParam>())
                } else {
                    builder.addMessage(message.toWire().toSdk<BetaMessageParam>())
                }
            }
            if (request.messages.any { it.effort != null }) builder.addBeta(MID_CONVERSATION_EFFORT_BETA)
            request.cache?.takeIf { it.automatic }?.let { builder.cacheControl(it.ephemeral().toSdk<BetaCacheControlEphemeral>()) }
            if (request.fallbacks) builder.addBeta(FALLBACK_BETA).fallbacksDefault()

            request.compaction?.let {
                builder.addBeta(COMPACTION_BETA)
                builder.contextManagement(compactionWire(it).toSdk<BetaContextManagementConfig>())
            }
            outputConfigWire(request.effort, request.structuredOutput)?.let { builder.outputConfig(it.toSdk<BetaOutputConfig>()) }

            val tools = request.tools.map { it.toWire(deferred = false) } +
                request.serverTools.sortedBy { it.ordinal }.map { server ->
                    buildJsonObject { put("type", server.wireType); put("name", server.wireName) }
                } +
                if (request.deferredTools.isEmpty()) {
                    emptyList()
                } else {
                    listOf(buildJsonObject { put("type", TOOL_SEARCH_TYPE); put("name", TOOL_SEARCH_NAME) }) +
                        request.deferredTools.map { it.toWire(deferred = true) }
                }
            if (tools.isNotEmpty()) {
                tools.forEach { builder.addTool(it.toSdk<BetaToolUnion>()) }
                builder.toolChoice(BetaToolChoiceAuto.builder().build())
            }
            return builder.build()
        }

        /** The request body exactly as it goes over the wire; for tests and debugging. */
        internal fun bodyJson(params: MessageCreateParams): JsonObject =
            HarnessJson.parseToJsonElement(com.anthropic.core.jsonMapper().writeValueAsString(params._body())) as JsonObject

        // The SDK's own JSON mapping turns our wire-shaped JSON into its typed params, so we never
        // hand-build (and mis-name) its block classes.
        private inline fun <reified T : Any> JsonElement.toSdk(): T =
            JsonValue.from(toJavaValue()).convert(T::class.java) ?: error("cannot convert to ${T::class.simpleName}")

        private fun CachePolicy.ephemeral(): JsonObject = buildJsonObject {
            put("type", "ephemeral")
            // 5m is the API default; only spell out the longer TTL.
            if (ttl != "5m") put("ttl", ttl)
        }

        private fun SystemBlock.toWire(cache: CachePolicy?): JsonObject = buildJsonObject {
            put("type", "text")
            put("text", text)
            if (cacheBreakpoint && cache != null) put("cache_control", cache.ephemeral())
        }

        private fun thinkingWire(display: String?): JsonObject = buildJsonObject {
            put("type", "adaptive")
            display?.let { put("display", it) }
        }

        private fun compactionWire(config: CompactionConfig): JsonObject = buildJsonObject {
            put("edits", JsonArray(listOf(buildJsonObject {
                put("type", "compact_20260112")
                putJsonObject("trigger") {
                    put("type", "input_tokens")
                    put("value", config.triggerInputTokens)
                }
                config.instructions?.let { put("instructions", it) }
                if (config.pauseAfterCompaction) put("pause_after_compaction", true)
            })))
        }

        private fun outputConfigWire(effort: String?, schema: JsonObject?): JsonObject? {
            if (effort == null && schema == null) return null
            return buildJsonObject {
                effort?.let { put("effort", it) }
                schema?.let {
                    putJsonObject("format") {
                        put("type", "json_schema")
                        put("schema", it)
                    }
                }
            }
        }

        private fun ToolDefinition.toWire(deferred: Boolean): JsonObject = buildJsonObject {
            put("name", name)
            put("description", description)
            put("input_schema", inputSchema)
            put("eager_input_streaming", true)
            if (deferred) put("defer_loading", true)
        }

        private fun Message.toWire(): JsonObject = buildJsonObject {
            put(
                "role",
                when (role) {
                    Role.USER -> "user"
                    Role.ASSISTANT -> "assistant"
                    Role.SYSTEM -> "system"
                },
            )
            put("content", JsonArray(content.map { it.toWire() }))
            if (role == Role.SYSTEM && effort != null) putJsonObject("output_config") { put("effort", effort) }
        }

        private fun Block.toWire(): JsonObject = when (this) {
            is Block.Text -> buildJsonObject { put("type", "text"); put("text", text) }
            is Block.ToolUse -> buildJsonObject {
                put("type", "tool_use"); put("id", id); put("name", name); put("input", input)
            }
            is Block.Result -> buildJsonObject {
                put("type", "tool_result"); put("tool_use_id", toolUseId); put("content", content)
                if (isError) put("is_error", true)
            }
            is Block.Raw -> json
        }

        internal fun toModelResponse(message: BetaMessage): ModelResponse {
            val usage = message.usage()
            val split = usage.cacheCreation().getOrNull()
            return ModelResponse(
                content = message.content().map { it.toBlock() },
                stopReason = message.stopReason().getOrNull()?.toStopReason() ?: StopReason.OTHER,
                usage = Usage(
                    inputTokens = usage.inputTokens(),
                    outputTokens = usage.outputTokens(),
                    cacheCreationInputTokens = usage.cacheCreationInputTokens().getOrNull() ?: 0,
                    cacheReadInputTokens = usage.cacheReadInputTokens().getOrNull() ?: 0,
                    cacheCreation5mTokens = split?.ephemeral5mInputTokens() ?: 0,
                    cacheCreation1hTokens = split?.ephemeral1hInputTokens() ?: 0,
                ),
            )
        }

        private fun BetaContentBlock.toBlock(): Block {
            // Text with citations (web search / fetch) is kept raw so the citations replay verbatim.
            text().getOrNull()?.let { if (it.citations().getOrNull().isNullOrEmpty()) return Block.Text(it.text()) }
            toolUse().getOrNull()?.let {
                // A truncated input fails to convert; an empty object makes validation report the
                // missing parameters to the model instead of crashing the run.
                val input = runCatching { it._input().convert(Map::class.java).toJsonElement() }.getOrNull()
                return Block.ToolUse(it.id(), it.name(), input as? JsonObject ?: JsonObject(emptyMap()))
            }
            // Thinking, redacted thinking, compaction, server tool use/results, fallback markers, ...:
            // replayed verbatim.
            return Block.Raw(JsonValue.from(this).convert(Map::class.java).toJsonElement() as JsonObject)
        }

        private fun BetaStopReason.toStopReason(): StopReason = when (known()) {
            BetaStopReason.Known.END_TURN, BetaStopReason.Known.STOP_SEQUENCE -> StopReason.END_TURN
            BetaStopReason.Known.TOOL_USE -> StopReason.TOOL_USE
            BetaStopReason.Known.MAX_TOKENS -> StopReason.MAX_TOKENS
            BetaStopReason.Known.REFUSAL -> StopReason.REFUSAL
            BetaStopReason.Known.PAUSE_TURN -> StopReason.PAUSE_TURN
            BetaStopReason.Known.COMPACTION -> StopReason.COMPACTION
            else -> StopReason.OTHER
        }
    }
}
