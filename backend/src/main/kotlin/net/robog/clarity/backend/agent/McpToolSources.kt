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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    /**
     * Lists each server's tools once and logs them, so a misconfigured or unreachable tool server shows
     * up at boot and not at the first turn. The servers may still be starting, hence the retries.
     */
    suspend fun logBootListing(attempts: Int = 10, pause: Duration = 3.seconds) {
        providers.forEach { (name, provider) ->
            for (attempt in 1..attempts) {
                try {
                    val names = provider.tools().map { it.name }
                    log.info { "MCP server '$name': ${names.size} tools (${names.joinToString()})" }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (attempt == attempts) log.warn { "MCP server '$name' unreachable at boot after $attempts attempts: ${e.message}" }
                    else delay(pause)
                }
            }
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

    override suspend fun run() = coroutineScope<Unit> {
        launch { sources.logBootListing() }
        try {
            awaitCancellation()
        } finally {
            sources.close()
        }
    }
}
