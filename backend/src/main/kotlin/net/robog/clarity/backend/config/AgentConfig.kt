package net.robog.clarity.backend.config

import kotlin.time.Duration

/** Agent subsystem configuration (`clarity.agent` in application.yaml). */
data class AgentConfig(
    /** Null/blank disables agents entirely (dispatcher logs and returns null). */
    val anthropicApiKey: String?,
    val maxTurns: Int = 30,
    val maxConcurrentRuns: Int = 2,
    val reviewInterval: Duration,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val roles: AgentRoles = AgentRoles(),
    /** Overrides the Anthropic API base URL (`ANTHROPIC_BASE_URL`): a gateway or proxy, or a fake endpoint in tests. */
    val anthropicBaseUrl: String? = null,
    /** `OPENROUTER_API_KEY`; required when a role's [RoleConfig.provider] is [ModelProvider.OPENROUTER]. */
    val openRouterApiKey: String? = null,
    /** `OPENROUTER_BASE_URL`; a fake endpoint in tests. */
    val openRouterBaseUrl: String = DEFAULT_OPENROUTER_BASE_URL,
) {
    /** The providers some boot-time role runs on; the live set comes from [net.robog.clarity.backend.agent.ModelSettings]. */
    val providersInUse: Set<ModelProvider> get() = roles.providersInUse

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
 * Who serves a role's model. [OPENROUTER] speaks the OpenAI-compatible API without prompt-cache
 * breakpoints or server-side compaction, so it suits the one-shot roles (guard, triage); the lead
 * stays on [ANTHROPIC] until compaction runs client-side.
 */
enum class ModelProvider { ANTHROPIC, OPENROUTER }

/**
 * Who is calling the model. A role's settings can change at runtime
 * ([net.robog.clarity.backend.agent.ModelSettings]), but never inside a run: the lead picks them up
 * at its next turn, a helper keeps those it started with, guard and triage read them per call.
 * A switch costs one cold prompt cache (the cache is per model).
 */
enum class AgentRole { LEAD, HELPER, GUARD, TRIAGE }

/** The efforts the Messages API takes (`output_config.effort`). */
val EFFORTS: List<String> = net.robog.clarity.shared.session.ModelRoles.EFFORTS

/**
 * Per-role model settings (from env or yaml, under any runtime override). [effort] is the Messages
 * API `output_config.effort` (one of [EFFORTS]); [cacheTtl] is `5m` or `1h`; [compaction] turns on
 * server-side compaction (only the long-lived lead thread needs it); [compactAtTokens] is when.
 */
data class RoleConfig(
    val model: String,
    val effort: String = "medium",
    val maxTokens: Long = 32_000,
    val cacheTtl: String = "5m",
    val compaction: Boolean = false,
    /** Compact once the prompt reaches this many tokens (at least 50K). Smaller is cheaper per turn but forgets sooner. */
    val compactAtTokens: Long = 150_000,
    /**
     * Also compact while the person is away ([net.robog.clarity.backend.agent.LeadAgent.compactIfIdle]). Off: a
     * compact-now request sends different compaction settings, so it never reads the warm cache and
     * rewrites the whole history at the cache-write price, and the next turn writes the summary again.
     */
    val idleCompaction: Boolean = false,
    /** Who serves [model]; the model name is that provider's (`anthropic/claude-haiku-5.5` on OpenRouter). */
    val provider: ModelProvider = ModelProvider.ANTHROPIC,
)

data class AgentRoles(
    val lead: RoleConfig = RoleConfig("claude-opus-5-5", effort = "medium", maxTokens = 64_000, cacheTtl = "1h", compaction = true, compactAtTokens = 60_000),
    val helper: RoleConfig = RoleConfig("claude-sonnet-5-5", effort = "medium", maxTokens = 32_000),
    val guard: RoleConfig = RoleConfig("claude-haiku-5-5", effort = "low", maxTokens = 4_000),
    val triage: RoleConfig = RoleConfig("claude-haiku-5-5", effort = "low", maxTokens = 4_000),
) {
    operator fun get(role: AgentRole): RoleConfig = when (role) {
        AgentRole.LEAD -> lead
        AgentRole.HELPER -> helper
        AgentRole.GUARD -> guard
        AgentRole.TRIAGE -> triage
    }

    /** The providers some role runs on; agents are enabled once each has a key. */
    val providersInUse: Set<ModelProvider> get() = AgentRole.entries.mapTo(sortedSetOf()) { this[it].provider }
}
