package net.robog.clarity.harness

import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * a restarted browser container does not wedge the agent. Tools default to [ToolEffect.EXTERNAL]
 * (third-party code we cannot vouch for), hence approval-gated under the default policy.
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
) {
    private val lock = Mutex()
    private var session: Client? = null

    /** Lists the server's tools (all pages), connecting if needed. */
    suspend fun tools(): List<Tool> {
        val client = connection()
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
            runCatching { old?.close() }
        }
    }

    private suspend fun connection(): Client = lock.withLock {
        session ?: httpClient.mcpStreamableHttp(url).also { session = it }
    }

    private suspend fun invalidate(dead: Client) = withContext(NonCancellable) {
        lock.withLock {
            if (session === dead) session = null
            runCatching { dead.close() }
        }
    }

    private suspend fun call(remoteName: String, input: JsonObject): ToolResult {
        val args = input.mapValues { it.value.toJavaValue() }
        repeat(2) { attempt ->
            val client = try {
                connection()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == 1) return ToolResult.Error("MCP server unreachable: ${e.message}")
                return@repeat
            }
            try {
                val result = client.callTool(remoteName, args)
                val text = result.content.joinToString("\n") { block ->
                    if (block is TextContent) block.text else "[${block::class.simpleName ?: "content"} omitted]"
                }
                return if (result.isError == true) ToolResult.Error(text) else ToolResult.Ok(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                invalidate(client)
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

private fun ToolSchema.toJsonObject(): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", properties ?: JsonObject(emptyMap()))
    required?.let { put("required", JsonArray(it.map(::JsonPrimitive))) }
    defs?.let { put("\$defs", it as JsonElement) }
}
