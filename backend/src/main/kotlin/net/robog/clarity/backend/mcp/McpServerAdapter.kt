package net.robog.clarity.backend.mcp

import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.invokeSafely

/** A fresh MCP [Server] exposing [tools]. Cheap to build, which is what makes stateless serving viable. */
fun newMcpServer(tools: List<Tool>, name: String = "clarity", version: String = "1"): Server =
    Server(
        serverInfo = Implementation(name = name, version = version),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    ).also { server -> tools.forEach(server::registerTool) }

/**
 * The single adapter from a harness [Tool] to an MCP tool registration, so tools are defined once
 * and served to both the in-process agent and external clients. Tool failures become MCP
 * `isError` results (not protocol errors), matching how the harness reports them to the model.
 */
fun Server.registerTool(tool: Tool) {
    addTool(
        name = tool.name,
        description = tool.description,
        inputSchema = tool.inputSchema.toToolSchema(),
        toolAnnotations = ToolAnnotations(readOnlyHint = tool.effect == ToolEffect.READ),
    ) { request ->
        when (val result = tool.invokeSafely(request.arguments ?: JsonObject(emptyMap()))) {
            is ToolResult.Ok -> CallToolResult(content = listOf(TextContent(result.content)))
            is ToolResult.Error -> CallToolResult(content = listOf(TextContent(result.message)), isError = true)
        }
    }
}

private fun JsonObject.toToolSchema() = ToolSchema(
    properties = this["properties"] as? JsonObject,
    required = (this["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
    defs = this["\$defs"] as? JsonObject,
)

/**
 * Mounts a *stateless* streamable-HTTP MCP endpoint on this [Route]: every request gets its own
 * short-lived [Server] and transport, so there are no sessions to expire or leak. The SDK's own
 * `Application.mcpStreamableHttp` installs routes at the application root and therefore cannot sit
 * inside `authenticate { }`; this is the same stateless handler built on its public transport API
 * so the endpoint can be mounted under any route, auth included.
 */
fun Route.statelessMcp(path: String, serverFactory: () -> Server) {
    route(path) {
        post { handleMcp(serverFactory) }
        get { handleMcp(serverFactory) }
        delete { handleMcp(serverFactory) }
    }
}

private suspend fun RoutingContext.handleMcp(serverFactory: () -> Server) {
    val transport = StreamableHttpServerTransport(StreamableHttpServerTransport.Configuration(enableJsonResponse = true))
    transport.setSessionIdGenerator(null)
    val session = serverFactory().createSession(transport)
    try {
        transport.handleRequest(null, call)
    } finally {
        session.close()
    }
}
