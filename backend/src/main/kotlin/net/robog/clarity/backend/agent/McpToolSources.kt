package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.sse.SSE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.lifecycle.BackgroundService
import net.robog.clarity.harness.McpToolProvider
import net.robog.clarity.harness.Tool

private val log = KotlinLogging.logger {}

/**
 * The agent's external tool servers (`clarity.agent.mcpServers`, e.g. the Playwright browser
 * container). One lazily-connecting [McpToolProvider] per server, shared by all runs. A server
 * that is down at run time is skipped with a warning rather than failing the run.
 */
@Inject
@SingleIn(AppScope::class)
class McpToolSources(config: AgentConfig) {
    private val httpClient: HttpClient by lazy {
        HttpClient(CIO) {
            install(SSE)
            // MCP sessions are long-lived streams; the default request timeout would kill them.
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = 10_000
            }
        }
    }

    private val providers: List<Pair<String, McpToolProvider>> by lazy {
        config.mcpServers.map { server ->
            // Name-prefixed so two servers exposing `navigate` cannot collide.
            server.name to McpToolProvider(server.url, httpClient, namePrefix = "${server.name}__")
        }
    }

    suspend fun tools(): List<Tool> = providers.flatMap { (name, provider) ->
        try {
            provider.tools()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "MCP server '$name' unavailable, running without its tools: ${e.message}" }
            emptyList()
        }
    }

    suspend fun close() {
        providers.forEach { (_, provider) -> provider.close() }
        httpClient.close()
    }
}

/** Closes MCP client sessions when the application scope is cancelled at shutdown. */
@Inject
@ContributesIntoSet(AppScope::class)
class McpClientLifecycle(private val sources: McpToolSources) : BackgroundService {
    override val name = "mcp-clients"

    override suspend fun run() {
        try {
            awaitCancellation()
        } finally {
            sources.close()
        }
    }
}
