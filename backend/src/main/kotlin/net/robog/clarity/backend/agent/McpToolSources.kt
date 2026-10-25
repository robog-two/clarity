package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.lifecycle.BackgroundService
import net.robog.clarity.harness.McpToolProvider
import net.robog.clarity.harness.Tool

private val log = KotlinLogging.logger {}

/**
 * The agent's external tool servers (`clarity.agent.mcpServers`, e.g. the browser and sandbox
 * containers). One lazily-connecting [McpToolProvider] per server, shared by all runs. A server
 * that is down at run time is skipped with a warning rather than failing the run.
 */
@Inject
@SingleIn(AppScope::class)
class McpToolSources(config: AgentConfig) {
    private fun newClient(bearer: String?): HttpClient =
        HttpClient(CIO) {
            install(SSE)
            // MCP sessions are long-lived streams; the default request timeout would kill them.
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = 10_000
            }
            if (bearer != null) {
                install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer $bearer") }
            }
        }

    private val clients = mutableListOf<HttpClient>()

    private val providers: List<Pair<String, McpToolProvider>> by lazy {
        config.mcpServers.map { server ->
            val bearer = server.keyFile?.let { readKey(server.name, it) }
            val client = newClient(bearer).also { clients += it }
            // Name-prefixed so two servers exposing `navigate` cannot collide.
            server.name to McpToolProvider(server.url, client, namePrefix = "${server.name}__")
        }
    }

    private fun readKey(server: String, path: String): String? =
        try {
            Path(path).readText().trim().ifEmpty { null }
        } catch (e: java.io.IOException) {
            log.warn { "MCP server '$server': cannot read key file $path (${e.message}); connecting without a key" }
            null
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
        clients.forEach { it.close() }
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
