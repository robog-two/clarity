package net.robog.clarity.harness

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The harness' own JSON configuration. Deliberately independent of the backend's wire JSON so the
 * package can be lifted into its own module; tools that decode backend/shared types pass their own.
 */
val HarnessJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * One content block of a conversation turn. This is the small provider-neutral subset the loop
 * needs to understand; everything else the model emits (thinking, redacted thinking, fallback
 * markers) travels as [Raw] and is handed back to the provider unchanged, which is what keeps the
 * history append-only and preserved-thinking safe.
 */
@Serializable
sealed interface Block {
    @Serializable @SerialName("text")
    data class Text(val text: String) : Block

    @Serializable @SerialName("tool_use")
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : Block

    /** A tool's answer, sent back in the *user* turn that follows the assistant's `tool_use`. */
    @Serializable @SerialName("tool_result")
    data class Result(val toolUseId: String, val content: String, val isError: Boolean = false) : Block

    /** A provider-specific block, kept verbatim in the provider's own JSON shape. */
    @Serializable @SerialName("raw")
    data class Raw(val json: JsonObject) : Block
}

@Serializable
enum class Role { USER, ASSISTANT }

@Serializable
data class Message(val role: Role, val content: List<Block>) {
    companion object {
        fun user(text: String) = Message(Role.USER, listOf(Block.Text(text)))
    }
}

/** Token accounting, summed across the turns of a run. */
@Serializable
data class Usage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheCreationInputTokens: Long = 0,
    val cacheReadInputTokens: Long = 0,
) {
    operator fun plus(other: Usage) = Usage(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        cacheCreationInputTokens + other.cacheCreationInputTokens,
        cacheReadInputTokens + other.cacheReadInputTokens,
    )

    companion object {
        val ZERO = Usage()
    }
}

/** Why the model stopped generating, normalised from the provider's stop reasons. */
enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, PAUSE_TURN, OTHER }

/** What the model is told about a tool. */
data class ToolDefinition(val name: String, val description: String, val inputSchema: JsonObject)

/** A single model call: the full (append-only) history plus the stable request prefix. */
data class ModelRequest(
    val model: String,
    val system: String,
    val messages: List<Message>,
    val tools: List<ToolDefinition>,
    val maxTokens: Long,
    /** `low | medium | high | xhigh | max`; null leaves the model's default. */
    val effort: String?,
)

/** The finished (accumulated) assistant turn. */
data class ModelResponse(val content: List<Block>, val stopReason: StopReason, val usage: Usage) {
    val toolUses: List<Block.ToolUse> get() = content.filterIsInstance<Block.ToolUse>()
    val text: String get() = content.filterIsInstance<Block.Text>().joinToString("") { it.text }
}
