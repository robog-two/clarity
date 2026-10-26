package net.robog.clarity.harness

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
 * needs to understand; everything else the model emits (thinking, redacted thinking, compaction,
 * server tool use and results, fallback markers) travels as [Raw] and is handed back to the
 * provider unchanged, which is what keeps the history append-only and preserved-thinking safe.
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

    /**
     * A provider-specific block, kept verbatim in the provider's own JSON shape. Never inspected
     * beyond [type], so replay is byte-exact.
     */
    @Serializable @SerialName("raw")
    data class Raw(val json: JsonObject) : Block {
        /** The provider's `type` field (e.g. `compaction`, `server_tool_use`), if present. */
        val type: String? get() = (json["type"] as? JsonPrimitive)?.contentOrNull

        val isCompaction: Boolean get() = type == "compaction"
    }
}

@Serializable
enum class Role { USER, ASSISTANT, SYSTEM }

/**
 * One turn. [Role.SYSTEM] is a mid-conversation operator message (injected events, triggers,
 * helper results): supported by current models without a beta header, does not break the prompt
 * cache, and must satisfy [systemPlacementViolations].
 */
@Serializable
data class Message(
    val role: Role,
    val content: List<Block>,
    /**
     * Only on [Role.SYSTEM] messages: the effort (`low|medium|high|xhigh|max`) the model uses from this
     * point of the conversation on (beta `mid-conversation-output-config-2026-07-01`). Sent as the
     * message's `output_config`; an effort-only message has empty [content].
     */
    val effort: String? = null,
) {
    companion object {
        fun user(text: String) = Message(Role.USER, listOf(Block.Text(text)))
        fun system(text: String) = Message(Role.SYSTEM, listOf(Block.Text(text)))

        /** An effort-only operator message: changes the effort for the rest of the conversation without breaking the cached prefix. */
        fun effort(effort: String) = Message(Role.SYSTEM, emptyList(), effort)
    }
}

/**
 * Placement rule for mid-conversation system messages: each must follow a user message (possibly
 * through other system messages) or directly precede an assistant turn. Returns the offending
 * indexes (empty means valid). Pure, so callers can check before appending.
 */
fun systemPlacementViolations(messages: List<Message>): List<Int> = messages.indices.filter { i ->
    if (messages[i].role != Role.SYSTEM) return@filter false
    val before = messages.subList(0, i).lastOrNull { it.role != Role.SYSTEM }?.role
    val after = messages.subList(i + 1, messages.size).firstOrNull { it.role != Role.SYSTEM }?.role
    !(before == Role.USER || after == Role.ASSISTANT)
}

/** Token accounting, summed across the turns of a run. */
@Serializable
data class Usage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheCreationInputTokens: Long = 0,
    val cacheReadInputTokens: Long = 0,
    /** The part of [cacheCreationInputTokens] written with the 5 minute TTL, when the provider splits it. */
    val cacheCreation5mTokens: Long = 0,
    /** The part of [cacheCreationInputTokens] written with the 1 hour TTL, when the provider splits it. */
    val cacheCreation1hTokens: Long = 0,
) {
    operator fun plus(other: Usage) = Usage(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        cacheCreationInputTokens + other.cacheCreationInputTokens,
        cacheReadInputTokens + other.cacheReadInputTokens,
        cacheCreation5mTokens + other.cacheCreation5mTokens,
        cacheCreation1hTokens + other.cacheCreation1hTokens,
    )

    companion object {
        val ZERO = Usage()
    }
}

/** Why the model stopped generating, normalised from the provider's stop reasons. */
enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, PAUSE_TURN, COMPACTION, OTHER }

/** What the model is told about a tool. */
data class ToolDefinition(val name: String, val description: String, val inputSchema: JsonObject)

/**
 * A block of the system prompt. The cache-stable order is: frozen static blocks, then the
 * constitution block (changes only when amended). [cacheBreakpoint] puts an explicit cache
 * breakpoint after this block (use it on the last static block).
 */
data class SystemBlock(val text: String, val cacheBreakpoint: Boolean = false)

/** Server-executed tools the provider runs for us (results come back as opaque [Block.Raw]). */
enum class ServerTool(val wireType: String, val wireName: String) {
    WEB_SEARCH("web_search_20260209", "web_search"),
    WEB_FETCH("web_fetch_20260209", "web_fetch"),
}

/**
 * Prompt caching. Explicit breakpoints come from [SystemBlock.cacheBreakpoint]; [automatic] adds
 * the top-level automatic breakpoint that follows the growing tail. Providers allow at most four
 * breakpoints in total. [ttl] is `5m` (default) or `1h`.
 */
data class CachePolicy(val ttl: String = "5m", val automatic: Boolean = true) {
    init {
        require(ttl == "5m" || ttl == "1h") { "cache ttl must be 5m or 1h, was $ttl" }
    }
}

/** Server-side compaction (beta `compact-2026-01-12`). */
data class CompactionConfig(
    /** Compact when the input reaches this many tokens. */
    val triggerInputTokens: Long = 150_000,
    val instructions: String? = null,
    /** Stop after the compaction block so the caller can look at it ([StopReason.COMPACTION]). */
    val pauseAfterCompaction: Boolean = false,
)

/** The compaction settings for a [RunRequest.compactNow] run: compact at the provider's minimum and pause. */
fun compactNowConfig(base: CompactionConfig?): CompactionConfig =
    (base ?: CompactionConfig()).copy(triggerInputTokens = COMPACTION_MIN_TRIGGER_TOKENS, pauseAfterCompaction = true)

/** The smallest trigger the compaction beta accepts. */
const val COMPACTION_MIN_TRIGGER_TOKENS = 50_000L

/** A single model call: the full (append-only) history plus the stable request prefix. */
data class ModelRequest(
    val model: String,
    val system: List<SystemBlock>,
    val messages: List<Message>,
    /** Loaded (non-deferred) client tools, in byte-stable order. */
    val tools: List<ToolDefinition>,
    val maxTokens: Long,
    /** `low | medium | high | xhigh | max`; null leaves the model's default. */
    val effort: String?,
    /** Client tools sent with `defer_loading: true`; the tool search tool is added automatically. */
    val deferredTools: List<ToolDefinition> = emptyList(),
    val serverTools: Set<ServerTool> = emptySet(),
    /** Null disables caching. */
    val cache: CachePolicy? = CachePolicy(),
    val compaction: CompactionConfig? = null,
    /** Server-side refusal fallback (`fallbacks: "default"`). */
    val fallbacks: Boolean = true,
    /** `summarized | omitted`; null leaves the provider default. */
    val thinkingDisplay: String? = null,
    /** JSON schema the final text must conform to (single calls: guard, triage, summaries). */
    val structuredOutput: JsonObject? = null,
) {
    constructor(
        model: String,
        system: String,
        messages: List<Message>,
        tools: List<ToolDefinition>,
        maxTokens: Long,
        effort: String?,
    ) : this(model, listOf(SystemBlock(system)), messages, tools, maxTokens, effort)

    /** The whole system prompt as one string (for logging and simple assertions). */
    val systemText: String get() = system.joinToString("\n\n") { it.text }

    init {
        val breakpoints = system.count { it.cacheBreakpoint } + if (cache?.automatic == true) 1 else 0
        require(cache != null || system.none { it.cacheBreakpoint }) { "cache breakpoints requested but caching is disabled" }
        require(breakpoints <= MAX_CACHE_BREAKPOINTS) { "at most $MAX_CACHE_BREAKPOINTS cache breakpoints, requested $breakpoints" }
        require(tools.isNotEmpty() || deferredTools.isEmpty()) { "at least one tool must be non-deferred" }
    }

    companion object {
        const val MAX_CACHE_BREAKPOINTS = 4
    }
}

/** The finished (accumulated) assistant turn. */
data class ModelResponse(val content: List<Block>, val stopReason: StopReason, val usage: Usage) {
    val toolUses: List<Block.ToolUse> get() = content.filterIsInstance<Block.ToolUse>()
    val text: String get() = content.filterIsInstance<Block.Text>().joinToString("") { it.text }
}
