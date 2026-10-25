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
) {
    /** The lead role's model (the former single `CLARITY_AGENT_MODEL`). */
    val model: String get() = roles.lead.model

    /** The lead role's effort: low | medium | high | xhigh | max. */
    val effort: String get() = roles.lead.effort
}

/**
 * One external MCP server. [keyFile] (optional) holds a bearer key sent as `Authorization: Bearer`
 * (e.g. a Podman secret mounted at /run/secrets/sandbox_key).
 */
data class McpServerConfig(val name: String, val url: String, val keyFile: String? = null)

/** Who is calling the model; roles never switch models inside a thread (the cache is per model). */
enum class AgentRole { LEAD, HELPER, GUARD, TRIAGE }

/**
 * Per-role model settings. [effort] is the Messages API `output_config.effort`
 * (`low | medium | high | xhigh | max`); [cacheTtl] is `5m` or `1h`; [compaction] turns on
 * server-side compaction (only the long-lived lead thread needs it).
 */
data class RoleConfig(
    val model: String,
    val effort: String = "medium",
    val maxTokens: Long = 32_000,
    val cacheTtl: String = "5m",
    val compaction: Boolean = false,
)

data class AgentRoles(
    val lead: RoleConfig = RoleConfig("claude-opus-5-5", effort = "medium", maxTokens = 64_000, cacheTtl = "1h", compaction = true),
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
}
