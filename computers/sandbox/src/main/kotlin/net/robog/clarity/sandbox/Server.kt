package net.robog.clarity.sandbox

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

const val MCP_PATH = "/mcp"

/** A fresh MCP server exposing [tools]; cheap, which is what makes stateless serving fine here. */
fun newSandboxMcpServer(tools: List<SandboxTool>): Server =
    Server(
        serverInfo = Implementation(name = "sandboxd", version = "1"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    ).also { server ->
        for (tool in tools) {
            server.addTool(
                name = tool.name,
                description = tool.description,
                inputSchema = ToolSchema(
                    properties = tool.inputSchema["properties"] as? JsonObject,
                    required = (tool.inputSchema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                ),
                toolAnnotations = ToolAnnotations(readOnlyHint = tool.readOnly),
            ) { request ->
                try {
                    CallToolResult(content = listOf(TextContent(tool.invoke(request.arguments ?: JsonObject(emptyMap())))))
                } catch (e: SandboxException) {
                    CallToolResult(content = listOf(TextContent(e.message ?: "Request refused.")), isError = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CallToolResult(
                        content = listOf(TextContent("Sandbox internal error: ${e::class.simpleName}: ${e.message}. Retry once; if it persists, report it and carry on without this tool.")),
                        isError = true,
                    )
                }
            }
        }
    }

/** Bearer-key check on the MCP route. Null [key] means "no authentication" (explicitly opted into). */
private fun bearerAuth(key: String?) = createRouteScopedPlugin("SandboxBearerAuth") {
    onCall { call ->
        if (key == null) return@onCall
        val presented = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
        val ok = presented != null &&
            MessageDigest.isEqual(presented.toByteArray(), key.toByteArray())
        if (!ok) {
            call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
            call.respond(HttpStatusCode.Unauthorized)
        }
    }
}

fun Application.sandboxModule(tools: List<SandboxTool>, bearerKey: String?) {
    install(ContentNegotiation) { json() }
    routing {
        get("/healthz") { call.respondText("ok") }
        route(MCP_PATH) {
            install(bearerAuth(bearerKey))
            post { handleMcp(tools) }
            // Stateless: no server-initiated SSE stream and no sessions to end, so per the streamable
            // HTTP spec GET and DELETE get 405 (the SDK's transport throws on a GET without a session).
            get { methodNotAllowed() }
            delete { methodNotAllowed() }
        }
    }
}

private suspend fun RoutingContext.methodNotAllowed() {
    call.response.headers.append(HttpHeaders.Allow, "POST")
    call.respond(HttpStatusCode.MethodNotAllowed)
}

/** Stateless streamable HTTP: each request gets its own short-lived server+transport, no sessions to leak. */
private suspend fun RoutingContext.handleMcp(tools: List<SandboxTool>) {
    val transport = StreamableHttpServerTransport(StreamableHttpServerTransport.Configuration(enableJsonResponse = true))
    transport.setSessionIdGenerator(null)
    val session = newSandboxMcpServer(tools).createSession(transport)
    try {
        transport.handleRequest(null, call)
    } finally {
        session.close()
    }
}
