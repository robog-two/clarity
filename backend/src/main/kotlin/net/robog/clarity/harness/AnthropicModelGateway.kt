package net.robog.clarity.harness

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlock
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolChoiceAuto
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [ModelGateway] over the Anthropic Messages API (Java SDK). The only class in the codebase that
 * touches SDK types.
 *
 * Request shape (all of it is stable per run except `messages`, to keep the cache prefix intact):
 *  - beta messages endpoint, because server-side refusal fallback is beta-gated;
 *  - `thinking: adaptive` (Opus 5.5 cannot run without thinking), `output_config.effort` always explicit;
 *  - top-level automatic prompt caching (`cache_control: ephemeral`), system prompt as a plain string
 *    and tools in the caller's (sorted) order;
 *  - `tool_choice: auto` (forced `any`/`tool` is rejected by this model family);
 *  - `fallbacks: "default"` with beta `server-side-fallback-2026-07-01`;
 *  - streamed and accumulated, with eager input streaming on every tool (the harness validates
 *    inputs against the tool's schema before running anything, and never runs a truncated turn).
 */
class AnthropicModelGateway(private val client: AnthropicClient) : ModelGateway {

    constructor(apiKey: String) : this(AnthropicOkHttpClient.builder().apiKey(apiKey).build())

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

        internal fun buildParams(request: ModelRequest): MessageCreateParams {
            val builder = MessageCreateParams.builder()
                .model(request.model)
                .maxTokens(request.maxTokens)
                .system(request.system)
                .messages(request.messages.toSdkMessages())
                .thinking(BetaThinkingConfigAdaptive.builder().build())
                .cacheControl(BetaCacheControlEphemeral.builder().build())
                .addBeta(FALLBACK_BETA)
                .fallbacksDefault()
            request.effort?.let {
                builder.outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(it)).build())
            }
            if (request.tools.isNotEmpty()) {
                request.tools.forEach { builder.addTool(it.toSdkTool()) }
                builder.toolChoice(BetaToolChoiceAuto.builder().build())
            }
            return builder.build()
        }

        // The SDK's own JSON mapping turns our wire-shaped JSON into its typed params, so we never
        // hand-build (and mis-name) its block classes.
        private inline fun <reified T : Any> JsonElement.toSdk(): T = JsonValue.from(toJavaValue()).convert(T::class.java) ?: error("cannot convert to ${T::class.simpleName}")

        private fun ToolDefinition.toSdkTool(): BetaTool = buildJsonObject {
            put("name", name)
            put("description", description)
            put("input_schema", inputSchema)
            put("eager_input_streaming", true)
        }.toSdk()

        private fun List<Message>.toSdkMessages(): List<BetaMessageParam> = map { it.toWire().toSdk<BetaMessageParam>() }

        private fun Message.toWire(): JsonObject = buildJsonObject {
            put("role", if (role == Role.USER) "user" else "assistant")
            put("content", JsonArray(content.map { it.toWire() }))
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
            return ModelResponse(
                content = message.content().map { it.toBlock() },
                stopReason = message.stopReason().getOrNull()?.toStopReason() ?: StopReason.OTHER,
                usage = Usage(
                    inputTokens = usage.inputTokens(),
                    outputTokens = usage.outputTokens(),
                    cacheCreationInputTokens = usage.cacheCreationInputTokens().getOrNull() ?: 0,
                    cacheReadInputTokens = usage.cacheReadInputTokens().getOrNull() ?: 0,
                ),
            )
        }

        private fun BetaContentBlock.toBlock(): Block {
            text().getOrNull()?.let { return Block.Text(it.text()) }
            toolUse().getOrNull()?.let {
                val input = it._input().convert(Map::class.java).toJsonElement()
                return Block.ToolUse(it.id(), it.name(), input as? JsonObject ?: JsonObject(emptyMap()))
            }
            // Thinking, redacted thinking, fallback markers, ...: replayed verbatim.
            return Block.Raw(JsonValue.from(this).convert(Map::class.java).toJsonElement() as JsonObject)
        }

        private fun BetaStopReason.toStopReason(): StopReason = when (known()) {
            BetaStopReason.Known.END_TURN, BetaStopReason.Known.STOP_SEQUENCE -> StopReason.END_TURN
            BetaStopReason.Known.TOOL_USE -> StopReason.TOOL_USE
            BetaStopReason.Known.MAX_TOKENS -> StopReason.MAX_TOKENS
            BetaStopReason.Known.REFUSAL -> StopReason.REFUSAL
            BetaStopReason.Known.PAUSE_TURN -> StopReason.PAUSE_TURN
            else -> StopReason.OTHER
        }
    }
}
