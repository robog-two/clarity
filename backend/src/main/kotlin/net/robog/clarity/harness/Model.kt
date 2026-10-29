package net.robog.clarity.harness

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
 * needs to understand; anything else travels as [Raw]: today OpenRouter's `reasoning_details`
 * ([OpenRouterModelGateway.REASONING_TYPE]), and in journals written before the direct Anthropic API
 * was removed also Anthropic's own blocks (thinking with signatures, `compaction`, server tool use and
 * results such as `tool_search_tool_result`). Those old blocks are never rewritten; they are read as
 * they are when a request is built (a `compaction` block becomes its summary text, the rest is left out).
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
        /** The provider's `type` field (e.g. `openrouter_reasoning`, or a stored Anthropic `compaction`), if present. */
        val type: String? get() = (json["type"] as? JsonPrimitive)?.contentOrNull

        /**
         * A server-side compaction block from the Anthropic API, kept in journals written before
         * Clarity moved to OpenRouter only. It summarizes everything before it ([Message.isCompactionPoint]).
         */
        val isCompaction: Boolean get() = type == "compaction"

        /** The readable summary of a stored Anthropic `compaction` block (`content`); null when it has none (encrypted only). */
        val compactionSummary: String? get() = if (isCompaction) (json["content"] as? JsonPrimitive)?.contentOrNull else null

        /**
         * The provider that wrote this block ([FOREIGN_PROVIDER_KEY]), e.g. `openrouter` for its
         * reasoning; null for the untagged Anthropic blocks of old journals.
         */
        val foreignProvider: String? get() = (json[FOREIGN_PROVIDER_KEY] as? JsonPrimitive)?.contentOrNull

        companion object {
            /** Tags a [Raw] block with the provider that wrote it (the name predates OpenRouter being the only one). */
            const val FOREIGN_PROVIDER_KEY = "clarity_provider"
        }
    }
}

@Serializable
enum class Role { USER, ASSISTANT, SYSTEM }

/**
 * One turn. [Role.SYSTEM] is a mid-conversation operator message (injected events, triggers,
 * helper results), sent as a `system` message in place; it must satisfy [systemPlacementViolations].
 */
@Serializable
data class Message(
    val role: Role,
    val content: List<Block>,
    /**
     * Only on [Role.SYSTEM] messages: the effort (`low|medium|high|xhigh|max`) the model uses from this
     * point of the conversation on. Not sent itself: each request runs at the effort in effect at its
     * end ([effortIn]). An effort-only message has empty [content].
     */
    val effort: String? = null,
) {
    companion object {
        fun user(text: String) = Message(Role.USER, listOf(Block.Text(text)))
        fun system(text: String) = Message(Role.SYSTEM, listOf(Block.Text(text)))

        /** An effort-only operator message: changes the effort for the rest of the conversation; the journal stays append-only. */
        fun effort(effort: String) = Message(Role.SYSTEM, emptyList(), effort)

        /** A client-side compaction summary ([isSummary]): what is sent to the model starts here. */
        fun summary(text: String) = user("$SUMMARY_MARKER\n$text")
    }

    /** A user message of one text block starting with [SUMMARY_MARKER]: a client-side compaction summary. */
    val isSummary: Boolean get() = role == Role.USER && (content.singleOrNull() as? Block.Text)?.text?.startsWith(SUMMARY_MARKER) == true

    /** Summarizes everything before it: a client summary ([isSummary]) or a message holding a stored Anthropic `compaction` block. */
    val isCompactionPoint: Boolean get() = isSummary || content.any { it is Block.Raw && it.isCompaction }
}

/**
 * First line of a client-side compaction summary. Compaction appends the summary as a new user
 * message, so the journal stays append-only; requests start at the latest one ([sinceSummary]).
 */
const val SUMMARY_MARKER = "[clarity:summary] A summary of the conversation before this point:"

/**
 * Pure: [messages] from the latest client summary ([Message.isSummary]) on, or all of them. Cutting
 * the front only keeps later thinking blocks valid, and the journal itself is never rewritten.
 */
fun sinceSummary(messages: List<Message>): List<Message> {
    val last = messages.indexOfLast { it.isSummary }
    return if (last <= 0) messages else messages.subList(last, messages.size)
}

/**
 * Pure: the part of [messages] the model still reads: from the latest compaction point
 * ([Message.isCompactionPoint]: a client summary, or a stored Anthropic `compaction` block, which
 * summarized everything before it) on, or all of them.
 */
fun contextSince(messages: List<Message>): List<Message> {
    val last = messages.indexOfLast { it.isCompactionPoint }
    return if (last <= 0) messages else messages.subList(last, messages.size)
}

/** Pure: the effort [messages] run at: that of the last effort-only message, else [default]. */
fun effortIn(messages: List<Message>, default: String?): String? =
    messages.lastOrNull { it.role == Role.SYSTEM && it.effort != null }?.effort ?: default

/**
 * Placement rule for mid-conversation system messages: each must be followed (possibly through other
 * system messages) by an assistant turn or end the conversation, and one that ends it must follow a
 * user message. So never a system message right before a user message. Effort-only messages (no
 * content) are allowed anywhere. Returns the offending indexes (empty means valid). Pure, so callers
 * can check before appending.
 */
fun systemPlacementViolations(messages: List<Message>): List<Int> = messages.indices.filter { i ->
    val m = messages[i]
    if (m.role != Role.SYSTEM || (m.content.isEmpty() && m.effort != null)) return@filter false
    val before = messages.subList(0, i).lastOrNull { it.role != Role.SYSTEM }?.role
    val after = messages.subList(i + 1, messages.size).firstOrNull { it.role != Role.SYSTEM }?.role
    when (after) {
        Role.ASSISTANT -> false
        null -> before != Role.USER
        else -> true
    }
}

/**
 * Pure: [messages] in an order the API accepts. A user message that lands right after system
 * messages (the person spoke while an operator update was being answered) moves in front of them,
 * joining the user message there. The journal keeps the order things happened in; only the request
 * is reordered, the same way every time, so the cached prefix stays stable.
 */
fun wireOrder(messages: List<Message>): List<Message> {
    val out = ArrayList<Message>(messages.size)
    for (m in messages) {
        if (m.role != Role.USER || out.lastOrNull()?.role != Role.SYSTEM) {
            out += m
            continue
        }
        var start = out.size
        while (start > 0 && out[start - 1].role == Role.SYSTEM) start--
        val previous = out.getOrNull(start - 1)
        if (previous?.role == Role.USER) out[start - 1] = previous.copy(content = previous.content + m.content) else out.add(start, m)
    }
    return out
}

/**
 * Pure: [element] with every object's keys in Postgres `jsonb` order (shorter keys first, equal
 * lengths by UTF-8 bytes), recursively. The journal is `jsonb`, which reorders keys, so a turn
 * replayed from it would differ from the same turn replayed in memory (tool inputs are rendered in
 * key order) and the prompt cache would miss from that turn on. Putting assistant content in this
 * order before it enters the history makes both byte-identical. Idempotent.
 */
fun jsonbKeyOrder(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.entries.sortedWith(JSONB_KEY_ORDER).associateTo(LinkedHashMap()) { (k, v) -> k to jsonbKeyOrder(v) },
    )
    is JsonArray -> JsonArray(element.map(::jsonbKeyOrder))
    else -> element
}

private val JSONB_KEY_ORDER: Comparator<Map.Entry<String, JsonElement>> = Comparator { a, b ->
    val x = a.key.encodeToByteArray()
    val y = b.key.encodeToByteArray()
    if (x.size != y.size) return@Comparator x.size - y.size
    for (i in x.indices) {
        val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
        if (d != 0) return@Comparator d
    }
    0
}

/** Pure: [blocks] with their JSON payloads (tool inputs, provider blocks) in [jsonbKeyOrder]. */
fun journalStable(blocks: List<Block>): List<Block> = blocks.map { block ->
    when (block) {
        is Block.ToolUse -> block.copy(input = jsonbKeyOrder(block.input) as JsonObject)
        is Block.Raw -> Block.Raw(jsonbKeyOrder(block.json) as JsonObject)
        else -> block
    }
}

/**
 * Pure: where to put the explicit cache breakpoint inside [messages] (wire order), as
 * (message index, block index), or null. It goes on the last block of the last user message before
 * the last assistant turn: where the previous request's prompt ended, so its cache entry is read
 * however many blocks the turn since added. The automatic breakpoint alone looks back only 20
 * blocks, and a missed lookback falls back to the system prompt and rewrites the whole history.
 * Only text and tool-result blocks after the latest compaction block carry it.
 */
fun messageCacheBreakpoint(messages: List<Message>): Pair<Int, Int>? {
    val lastAssistant = messages.indexOfLast { it.role == Role.ASSISTANT }
    if (lastAssistant < 0) return null
    val lastCompaction = messages.indexOfLast { m -> m.content.any { it is Block.Raw && it.isCompaction } }
    val index = (lastAssistant - 1 downTo 0).firstOrNull { messages[it].role != Role.SYSTEM } ?: return null
    val message = messages[index]
    if (index <= lastCompaction || message.role != Role.USER || message.content.isEmpty()) return null
    val block = message.content.last()
    return if (block is Block.Text || block is Block.Result) index to message.content.lastIndex else null
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
    /** What the provider says the call cost, in millionths of a dollar (OpenRouter); null when it does not say. */
    val reportedCostMicros: Long? = null,
    /** Web searches the server ran for this call (`usage.server_tool_use_details.web_search_requests`). */
    val webSearchRequests: Long = 0,
    /** The upstream provider that served the call (OpenRouter's `provider`); in a sum, the latest known. */
    val provider: String? = null,
) {
    /** The whole prompt of one call: uncached input plus cache reads and writes. */
    val promptTokens: Long get() = inputTokens + cacheReadInputTokens + cacheCreationInputTokens

    operator fun plus(other: Usage) = Usage(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        cacheCreationInputTokens + other.cacheCreationInputTokens,
        cacheReadInputTokens + other.cacheReadInputTokens,
        cacheCreation5mTokens + other.cacheCreation5mTokens,
        cacheCreation1hTokens + other.cacheCreation1hTokens,
        if (reportedCostMicros == null && other.reportedCostMicros == null) null else (reportedCostMicros ?: 0) + (other.reportedCostMicros ?: 0),
        webSearchRequests + other.webSearchRequests,
        other.provider ?: provider,
    )

    companion object {
        val ZERO = Usage()
    }
}

/** Why the model stopped generating, normalised from the provider's stop reasons. */
enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, OTHER }

/** What the model is told about a tool. */
data class ToolDefinition(val name: String, val description: String, val inputSchema: JsonObject)

/**
 * A block of the system prompt. The cache-stable order is: frozen static blocks, then the
 * constitution block (changes only when amended). [cacheBreakpoint] puts an explicit cache
 * breakpoint after this block (use it on the last static block).
 */
data class SystemBlock(val text: String, val cacheBreakpoint: Boolean = false)

/**
 * Prompt caching, for models that take explicit `cache_control` breakpoints (Claude through
 * OpenRouter, [usesCacheBreakpoints]); other models cache automatically and ignore it. Explicit
 * breakpoints come from [SystemBlock.cacheBreakpoint] and [ModelRequest.messageBreakpoint];
 * [automatic] adds the top-level automatic breakpoint that follows the growing tail. At most four
 * breakpoints in total. [ttl] is `5m` (default) or `1h`.
 */
data class CachePolicy(val ttl: String = "5m", val automatic: Boolean = true) {
    init {
        require(ttl == "5m" || ttl == "1h") { "cache ttl must be 5m or 1h, was $ttl" }
    }
}

/** The smallest prompt size at which a role may be set to compact its thread (`compactAtTokens`). */
const val MIN_COMPACT_AT_TOKENS = 50_000L

/**
 * Pure: whether [model] (an OpenRouter id) caches only at explicit `cache_control` breakpoints:
 * Anthropic's Claude models. OpenRouter passes the breakpoints through to them; most other
 * providers cache automatically, and Gemini uses only the last breakpoint, so they get none.
 */
fun usesCacheBreakpoints(model: String): Boolean = model.removePrefix("~").startsWith("anthropic/")

/** How much of each search result's page OpenRouter passes on by default: a short excerpt. */
const val DEFAULT_SEARCH_EXCERPT_CHARS: Int = 400

/**
 * OpenRouter's web search server tool (`{"type": "openrouter:web_search"}`): the search runs on
 * OpenRouter's side, its results go straight into the model's context, and the answer carries
 * `url_citation` annotations ([UrlCitation]). Only helpers the lead granted `web_search` get it,
 * never the lead. [maxSearches] caps the searches of one request (`max_tool_calls`, which also bounds
 * any other server tool) and [maxResults] the results of each; [maxTotalResults] the results of the
 * whole request. [excludedDomains] is the engine's block list (Parallel, Exa and others honour it).
 * [maxCharacters] caps each result's excerpt (`max_characters`; Exa, Parallel and Perplexity): the
 * helper reads pages itself with `fetch_page`, so a search only needs enough to pick them.
 */
data class WebSearchTool(
    val engine: String = "parallel",
    val mode: String? = "fast",
    val maxResults: Int = 5,
    val maxSearches: Int = 10,
    val maxTotalResults: Int? = null,
    val excludedDomains: List<String> = emptyList(),
    val maxCharacters: Int? = DEFAULT_SEARCH_EXCERPT_CHARS,
) {
    init {
        require(maxSearches >= 1) { "maxSearches must be at least 1" }
        require(maxResults in 1..25) { "maxResults must be from 1 to 25" }
        require(maxCharacters == null || maxCharacters in 1..100_000) { "maxCharacters must be from 1 to 100000" }
    }
}

/** A `url_citation` annotation of an answer that used web search: where a claim came from. */
@kotlinx.serialization.Serializable
data class UrlCitation(val url: String, val title: String? = null, val content: String? = null)

/** [Block.Raw.type] of the citations kept beside an answer; never sent back to the model. */
const val CITATIONS_TYPE = "openrouter_citations"

/** Pure: the [Block.Raw] that keeps [citations] in the journal, where `cite_sources` finds them. */
fun citationsBlock(citations: List<UrlCitation>): Block.Raw = Block.Raw(
    kotlinx.serialization.json.buildJsonObject {
        put("type", JsonPrimitive(CITATIONS_TYPE))
        put("citations", HarnessJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(UrlCitation.serializer()), citations))
    },
)

/** Pure: every citation kept in [messages] ([citationsBlock]), first occurrence of each URL, in order. */
fun citationsIn(messages: List<Message>): List<UrlCitation> = messages.asSequence()
    .flatMap { it.content.asSequence() }
    .filterIsInstance<Block.Raw>()
    .filter { it.type == CITATIONS_TYPE }
    .flatMap { block ->
        val list = block.json["citations"] as? JsonArray ?: return@flatMap emptySequence<UrlCitation>()
        list.asSequence().mapNotNull { runCatching { HarnessJson.decodeFromJsonElement(UrlCitation.serializer(), it) }.getOrNull() }
    }
    .distinctBy { it.url }
    .toList()

/** A single model call: the full (append-only) history plus the stable request prefix. */
data class ModelRequest(
    val model: String,
    val system: List<SystemBlock>,
    val messages: List<Message>,
    /** Client tools, in byte-stable order. */
    val tools: List<ToolDefinition>,
    val maxTokens: Long,
    /** `low | medium | high | xhigh | max`; null leaves the model's default. */
    val effort: String?,
    /** Null disables caching (no breakpoints are sent). */
    val cache: CachePolicy? = CachePolicy(),
    /** JSON schema the final text must conform to (single calls: guard, triage, summaries). */
    val structuredOutput: JsonObject? = null,
    /** OpenRouter's web search server tool; null (the default, and always for the lead) sends none. */
    val webSearch: WebSearchTool? = null,
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

    /** The explicit breakpoint in [messages] ([messageCacheBreakpoint]) when caching is on and a slot is free. */
    val messageBreakpoint: Pair<Int, Int>? =
        if (cache != null && system.count { it.cacheBreakpoint } + (if (cache.automatic) 1 else 0) < MAX_CACHE_BREAKPOINTS) {
            messageCacheBreakpoint(messages)
        } else {
            null
        }

    init {
        val breakpoints = system.count { it.cacheBreakpoint } + (if (cache?.automatic == true) 1 else 0) +
            (if (messageBreakpoint != null) 1 else 0)
        require(cache != null || system.none { it.cacheBreakpoint }) { "cache breakpoints requested but caching is disabled" }
        require(breakpoints <= MAX_CACHE_BREAKPOINTS) { "at most $MAX_CACHE_BREAKPOINTS cache breakpoints, requested $breakpoints" }
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
