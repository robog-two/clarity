package net.robog.clarity.backend.config

import kotlin.time.Duration

/** Agent subsystem configuration (`clarity.agent` in application.yaml). */
data class AgentConfig(
    /**
     * `OPENROUTER_API_KEY` (or `_FILE`): every role's model runs on OpenRouter. Configuration loading
     * requires it; null only in tests, where agents then stay disabled.
     */
    val openRouterApiKey: String? = null,
    val maxTurns: Int = 30,
    /** A helper's step limit (`CLARITY_AGENT_HELPER_MAX_TURNS`): browsing takes many small steps. */
    val helperMaxTurns: Int = 60,
    val maxConcurrentRuns: Int = 2,
    val reviewInterval: Duration,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val roles: AgentRoles = AgentRoles(),
    /** `OPENROUTER_BASE_URL`; a fake endpoint in tests. */
    val openRouterBaseUrl: String = DEFAULT_OPENROUTER_BASE_URL,
    /** Memory recall: semantic search over the agent's memory (`CLARITY_MEMORY_*`). */
    val memory: MemoryConfig = MemoryConfig(),
    /** `OPENROUTER_ZDR`, `OPENROUTER_QUANTIZATIONS`, `OPENROUTER_SORT`: which OpenRouter endpoints may serve a request. */
    val openRouterRouting: net.robog.clarity.harness.OpenRouterRouting = net.robog.clarity.harness.OpenRouterRouting(),
    /** The decision model and the periodic review's gate (`CLARITY_DECISION*`, `CLARITY_REVIEW_GATE*`). */
    val decisions: DecisionConfig = DecisionConfig(),
    /** The helper's browser: Jev grounding, gate and thresholds (`CLARITY_JEV_*`, `CLARITY_BROWSER_*`). */
    val browser: BrowserPilotConfig = BrowserPilotConfig(),
    /** OpenRouter web search for helpers the lead grants it (`CLARITY_WEB_SEARCH*`). */
    val webSearch: WebSearchConfig = WebSearchConfig(),
) {
    /** The lead role's boot-time model (the former single `CLARITY_AGENT_MODEL`). */
    val model: String get() = roles.lead.model

    /** The lead role's boot-time effort: low | medium | high | xhigh | max. */
    val effort: String get() = roles.lead.effort
}

/**
 * One external MCP server. [keyFile] (optional) holds a bearer key sent as `Authorization: Bearer`
 * (e.g. a Podman secret mounted at /run/secrets/sandbox_key).
 */
data class McpServerConfig(val name: String, val url: String, val keyFile: String? = null)

const val DEFAULT_OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"

/**
 * Memory recall settings. Embeddings for semantic search come from OpenRouter's embeddings endpoint
 * and are used only when [embeddings] is on and an OpenRouter key is configured; without them search
 * is full-text only. [embeddingsModel] is an OpenRouter model id. [embeddingDimensions] keeps the
 * first that many values of each vector, renormalized (Matryoshka truncation, which Qwen3-Embedding
 * is trained for; 0 = keep all). The endpoints list no `dimensions` parameter, so it is not sent.
 * Only the providers in [embeddingProviders] are used, in that order.
 */
data class MemoryConfig(
    val embeddings: Boolean = true,
    val embeddingsModel: String = DEFAULT_EMBEDDINGS_MODEL,
    val embeddingDimensions: Int = 1024,
    val embeddingProviders: List<String> = listOf("deepinfra", "nebius"),
)

const val DEFAULT_EMBEDDINGS_MODEL = "qwen/qwen3-embedding-8b"

/**
 * The decision model: an OpenRouter model that answers fixed questions with probabilities
 * (`POST /api/alpha/decisions`, "System One" format), used only when [enabled] and an OpenRouter
 * key is configured. The periodic review asks it whether anything that changed needs the lead now
 * ([reviewGate]); it wakes the lead when the probability is at least [reviewThreshold], and always
 * once [reviewMaxQuiet] has passed since the lead last reviewed. Any failure wakes the lead.
 */
data class DecisionConfig(
    val enabled: Boolean = true,
    val model: String = DEFAULT_DECISION_MODEL,
    val reviewGate: Boolean = true,
    val reviewThreshold: Double = 0.3,
    val reviewMaxQuiet: Duration = Duration.parse("8h"),
)

const val DEFAULT_DECISION_MODEL = "microsoft/microsoft-decision-1"

/**
 * The helper's browser ([net.robog.clarity.backend.agent.BrowserPilot]). Jev ([jevModel], a pinned
 * dated id) finds the element an action names on a screenshot, double-checks the step and verifies
 * the outcome. An element counts as found at [visibleThreshold] (0.7: in live runs
 * present targets scored 0.84-1.0, absent ones 0.03-0.04, and a link drawn as a button 0.53-0.59;
 * below it the pilot asks once more by the element's own name before giving up); a step is ordinary browsing for Jev
 * at [approveThreshold], and with [jevMayApprove] off (the default) Jev can only make a step stricter.
 * Once a sign-in code was sent, a step Jev rates at [resendThreshold] or more as sending another is
 * refused. [textGrounding] lets an exact name match in the text view be confirmed by the text decision
 * model instead of a screenshot. A browser task stops after [maxActions] actions.
 */
data class BrowserPilotConfig(
    val jevModel: String = DEFAULT_JEV_MODEL,
    val visibleThreshold: Double = 0.7,
    val approveThreshold: Double = 0.9,
    val resendThreshold: Double = 0.3,
    val jevMayApprove: Boolean = false,
    val textGrounding: Boolean = false,
    val maxActions: Int = 25,
    /** How long step screenshots are kept in the audit trail (`CLARITY_BROWSER_FRAME_RETENTION`); the rows stay. */
    val frameRetention: kotlin.time.Duration = kotlin.time.Duration.parse("30d"),
    /** One run holds the browser at a time: how long another waits for it (`CLARITY_BROWSER_LEASE_WAIT`). */
    val leaseWait: kotlin.time.Duration = kotlin.time.Duration.parse("30m"),
    /** A holder with no browser call for this long loses the lease (`CLARITY_BROWSER_LEASE_IDLE`), waiting for a person included. */
    val leaseIdle: kotlin.time.Duration = kotlin.time.Duration.parse("10m"),
    /** A holder whose heartbeat is this old is gone (a crash or restart; `CLARITY_BROWSER_LEASE_STALE`). */
    val leaseStale: kotlin.time.Duration = kotlin.time.Duration.parse("2m"),
)

/**
 * OpenRouter's web search server tool (`openrouter:web_search`). Only a helper whose `delegate` call
 * names `web_search` gets it, never the lead, and such a helper loses every personal-data and
 * outbound tool. [engine] is OpenRouter's search engine (`parallel` by default, its `fast` [mode]
 * costs about $0.001 a search); `firecrawl` is refused because OpenRouter rejects it under zero data
 * retention. [maxSearchesPerRun] caps one helper run, [maxResults] each search, and
 * [excludedDomains] is passed as the engine's block list. [enabled] false grants no search at all.
 */
data class WebSearchConfig(
    val enabled: Boolean = true,
    val engine: String = "parallel",
    val mode: String? = "fast",
    val maxSearchesPerRun: Int = 10,
    val maxResults: Int = 5,
    val excludedDomains: List<String> = emptyList(),
) {
    /** The server tool a granted helper's run carries; null when search is off. */
    fun tool(): net.robog.clarity.harness.WebSearchTool? = if (!enabled) null else net.robog.clarity.harness.WebSearchTool(
        engine = engine,
        mode = mode,
        maxResults = maxResults,
        maxSearches = maxSearchesPerRun,
        excludedDomains = excludedDomains,
    )

    companion object {
        val ENGINES = listOf("parallel", "exa", "perplexity", "native", "auto")
    }
}

/** Jev 1.14's dated id on OpenRouter (`canonical_slug` in the models API, released 2026-10-14). */
const val DEFAULT_JEV_MODEL = "typesafe/jev-1.14-20261014"


/**
 * Who is calling the model. A role's settings can change at runtime
 * ([net.robog.clarity.backend.agent.ModelSettings]), but never inside a run: the lead picks them up
 * at its next turn, a helper keeps those it started with, guard and triage read them per call.
 * A switch costs one cold prompt cache (the cache is per model).
 */
enum class AgentRole { LEAD, HELPER, GUARD, TRIAGE }

/** The efforts a role can run at (OpenRouter `reasoning.effort`; above `high` only Claude takes them as they are). */
val EFFORTS: List<String> = net.robog.clarity.shared.session.ModelRoles.EFFORTS

/**
 * Per-role model settings (from env or yaml, under any runtime override). [model] is an OpenRouter
 * model id (`vendor/model`, e.g. `xiaomi/mimo-v2.6-pro` or `anthropic/claude-opus-5.5`); [effort] is
 * one of [EFFORTS]; [cacheTtl] is `5m` or `1h`, used by models that cache at explicit breakpoints
 * (Claude); [compaction] turns on client-side compaction (only the long-lived lead thread needs it);
 * [compactAtTokens] is when.
 */
data class RoleConfig(
    val model: String,
    val effort: String = "medium",
    val maxTokens: Long = 32_000,
    val cacheTtl: String = "5m",
    val compaction: Boolean = false,
    /**
     * Compact once the prompt reaches this many tokens (at least 50K). Smaller is cheaper per turn but
     * forgets sooner. It is the main cost lever for a model without a prompt cache: each turn resends
     * everything since the last summary at the full input price.
     */
    val compactAtTokens: Long = 150_000,
)

const val DEFAULT_LEAD_MODEL = "xiaomi/mimo-v2.6-pro"
const val DEFAULT_HELPER_MODEL = "deepseek/deepseek-v4.1-flash-1017"

data class AgentRoles(
    val lead: RoleConfig = RoleConfig(DEFAULT_LEAD_MODEL, effort = "medium", maxTokens = 64_000, cacheTtl = "1h", compaction = true, compactAtTokens = 60_000),
    val helper: RoleConfig = RoleConfig(DEFAULT_HELPER_MODEL, effort = "medium", maxTokens = 32_000),
    val guard: RoleConfig = RoleConfig(DEFAULT_HELPER_MODEL, effort = "low", maxTokens = 4_000),
    val triage: RoleConfig = RoleConfig(DEFAULT_HELPER_MODEL, effort = "low", maxTokens = 4_000),
) {
    operator fun get(role: AgentRole): RoleConfig = when (role) {
        AgentRole.LEAD -> lead
        AgentRole.HELPER -> helper
        AgentRole.GUARD -> guard
        AgentRole.TRIAGE -> triage
    }
}

