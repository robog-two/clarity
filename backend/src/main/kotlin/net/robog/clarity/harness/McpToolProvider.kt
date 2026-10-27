package net.robog.clarity.harness

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttpTransport
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Exposes the tools of one remote MCP server (streamable HTTP) as harness [Tool]s.
 *
 * The connection is lazy and self-healing: it is opened on first use, and when a call fails at the
 * transport level the dead session is dropped and the call retried once on a fresh connection, so
 * a restarted browser container does not wedge the agent. A dropped session is also ended on the
 * server (`DELETE`): a stateful gateway keeps one server process (and one browser) per session,
 * and a session merely forgotten stays alive there for hours, holding the browser. A reply that
 * arrives but cannot be read (empty or cut off) keeps the session and is not retried: the call ran.
 * The last session id is kept in [sessions], so the one a killed process left behind is ended when
 * the next process connects. Tools default to [ToolEffect.EXTERNAL] (third-party code we cannot
 * vouch for), hence approval-gated under the default policy.
 *
 * @param httpClient must have Ktor's `SSE` plugin installed (the MCP transport streams over SSE);
 *   the provider does not own or close it.
 * @param namePrefix prepended to every tool name to keep several servers' tools apart.
 */
class McpToolProvider(
    private val url: String,
    private val httpClient: HttpClient,
    private val effect: ToolEffect = ToolEffect.EXTERNAL,
    private val namePrefix: String = "",
    private val sessions: McpSessionMemory? = null,
) {
    private val lock = Mutex()
    private var session: Connection? = null

    private class Connection(val client: Client, val transport: StreamableHttpClientTransport)

    /** Lists the server's tools (all pages), connecting if needed. */
    suspend fun tools(): List<Tool> {
        val client = connection().client
        val found = buildList {
            var cursor: String? = null
            do {
                val page = client.listTools(ListToolsRequest(PaginatedRequestParams(cursor = cursor)))
                addAll(page.tools)
                cursor = page.nextCursor
            } while (cursor != null)
        }
        return found.map { remote ->
            McpTool(
                name = (namePrefix + remote.name).replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64),
                remoteName = remote.name,
                description = remote.description ?: remote.title ?: remote.name,
                inputSchema = remote.inputSchema.toJsonObject(),
            )
        }
    }

    /** Closes the MCP session; a later call reconnects. Safe to call repeatedly. */
    suspend fun close() = withContext(NonCancellable) {
        lock.withLock {
            val old = session
            session = null
            old?.let { end(it) }
        }
    }

    private suspend fun connection(): Connection = lock.withLock {
        session ?: open().also { session = it }
    }

    private suspend fun open(): Connection {
        // Left by a process that never closed it (killed, or crashed): end it so it frees the server.
        sessions?.load()?.let { stale ->
            runCatching { withTimeoutOrNull(END_SESSION_TIMEOUT) { httpClient.delete(url) { header(SESSION_HEADER, stale) } } }
            sessions.save(null)
        }
        val transport = httpClient.mcpStreamableHttpTransport(url)
        val client = Client(Implementation(name = "clarity", version = "1"))
        try {
            client.connect(transport)
        } catch (e: Exception) {
            // initialize may have created the session on the server already
            end(Connection(client, transport))
            throw e
        }
        transport.sessionId?.let { sessions?.save(it) }
        return Connection(client, transport)
    }

    private suspend fun invalidate(dead: Connection) = withContext(NonCancellable) {
        lock.withLock {
            if (session === dead) session = null
            end(dead)
        }
    }

    /** Ends the session on the server too, briefly (a dead server cannot answer), then closes it here. */
    private suspend fun end(connection: Connection) {
        val id = connection.transport.sessionId
        val ended = runCatching { withTimeoutOrNull(END_SESSION_TIMEOUT) { connection.transport.terminateSession() } }.getOrNull() != null
        if (ended && id != null && sessions?.load() == id) sessions.save(null)
        runCatching { connection.client.close() }
    }

    private suspend fun call(remoteName: String, input: JsonObject): ToolResult {
        val args = input.mapValues { it.value.toJavaValue() }
        repeat(2) { attempt ->
            val connection = try {
                connection()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == 1) return ToolResult.Error("MCP server unreachable: ${e.message}")
                return@repeat
            }
            try {
                val result = connection.client.callTool(remoteName, args)
                val text = result.content.joinToString("\n") { block ->
                    if (block is TextContent) block.text else "[${block::class.simpleName ?: "content"} omitted]"
                }
                val images = result.content.filterIsInstance<ImageContent>().map { ToolImage(it.mimeType, it.data) }
                return if (result.isError == true) ToolResult.Error(text) else ToolResult.Ok(text, images)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The reply came but was empty or cut off (a click that navigates can do this): the call ran.
                val unreadable = generateSequence<Throwable>(e) { it.cause }.firstOrNull { it is SerializationException }
                if (unreadable != null) return ToolResult.Error("$CUT_OFF_REPLY ($remoteName): ${unreadable.message?.lineSequence()?.firstOrNull().orEmpty()}")
                invalidate(connection)
                if (attempt == 1) return ToolResult.Error("MCP call to $remoteName failed: ${e.message}")
            }
        }
        return ToolResult.Error("MCP call to $remoteName failed")
    }

    private inner class McpTool(
        override val name: String,
        private val remoteName: String,
        override val description: String,
        override val inputSchema: JsonObject,
    ) : Tool {
        override val effect: ToolEffect get() = this@McpToolProvider.effect
        override suspend fun invoke(input: JsonObject): ToolResult = call(remoteName, input)
    }
}

/** Where an [McpToolProvider] keeps its current session id across process restarts. */
interface McpSessionMemory {
    fun load(): String?
    fun save(id: String?)
}

private const val SESSION_HEADER = "Mcp-Session-Id"

/** How an MCP tool error starts when the call's reply could not be read. */
const val CUT_OFF_REPLY = "The tool's reply was cut off or unreadable"

private val END_SESSION_TIMEOUT = 5.seconds

private fun ToolSchema.toJsonObject(): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", properties ?: JsonObject(emptyMap()))
    required?.let { put("required", JsonArray(it.map(::JsonPrimitive))) }
    defs?.let { put("\$defs", it as JsonElement) }
}
