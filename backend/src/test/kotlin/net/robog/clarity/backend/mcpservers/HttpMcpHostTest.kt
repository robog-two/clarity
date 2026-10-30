package net.robog.clarity.backend.mcpservers

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.McpHostConfig
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.mcp.McpServerState

/** The host's HTTP API (contract 2) as the backend uses it, against an in-process fake host. */
class HttpMcpHostTest : FunSpec({
    test("lists servers with the bearer key and calls a tool at /s/{id}/mcp") {
        val seen = CopyOnWriteArrayList<String>()
        testApplication {
            application {
                routing {
                    get("/servers") {
                        if (call.request.headers[HttpHeaders.Authorization] != "Bearer k1") return@get call.respond(HttpStatusCode.Unauthorized)
                        call.respondText(
                            """[{"id":"gh","label":"GitHub","state":"idle","tools":[{"name":"list_issues","annotations":{"readOnlyHint":true}}]}]""",
                            ContentType.Application.Json,
                        )
                    }
                    post("/s/{id}/mcp") {
                        seen += "${call.parameters["id"]} ${call.request.headers[HttpHeaders.Authorization]}"
                        val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                        val id = body["id"]
                        when (body["method"]?.jsonPrimitive?.content) {
                            "initialize" -> {
                                call.response.header("Mcp-Session-Id", "s1")
                                call.respondText(
                                    """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"fake","version":"1"}}}""",
                                    ContentType.Application.Json,
                                )
                            }
                            "tools/call" -> {
                                val repo = body["params"]?.jsonObject?.get("arguments")?.jsonObject?.get("repo")?.jsonPrimitive?.content
                                call.respondText("""{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"issues of $repo"}]}}""", ContentType.Application.Json)
                            }
                            else -> call.respond(HttpStatusCode.Accepted)
                        }
                    }
                    get("/s/{id}/mcp") { call.respond(HttpStatusCode.MethodNotAllowed) }
                    delete("/s/{id}/mcp") { call.respond(HttpStatusCode.OK) }
                }
            }
            val client = createClient {
                install(SSE)
                install(io.ktor.client.plugins.HttpTimeout)
                install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer k1") }
            }
            val host = HttpMcpHost(McpHostConfig("http://localhost"), client)
            val servers = host.servers()
            servers.map { it.id to it.state } shouldContainExactly listOf("gh" to McpServerState.IDLE)
            servers.single().tools.single().readOnly shouldBe true
            val result = host.call("gh", "list_issues", buildJsonObject { put("repo", "clarity") })
            result.shouldBeInstanceOf<ToolResult.Ok>().content shouldBe "issues of clarity"
            seen.first() shouldBe "gh Bearer k1"
            host.close()
        }
    }
})
