package net.robog.clarity.backend.config

import kotlin.time.Duration

/** Agent subsystem configuration (`clarity.agent` in application.yaml). */
data class AgentConfig(
    /** Null/blank disables agents entirely (dispatcher logs and returns null). */
    val anthropicApiKey: String?,
    val model: String = "claude-opus-5-5",
    /** Messages API `output_config.effort`: low | medium | high | xhigh | max. */
    val effort: String = "medium",
    val maxTurns: Int = 30,
    val maxConcurrentRuns: Int = 2,
    val reviewInterval: Duration,
    val mcpServers: List<McpServerConfig> = emptyList(),
)

data class McpServerConfig(val name: String, val url: String)
