package net.robog.clarity.sandbox

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.nio.file.Files

class McpServerTest : FunSpec({
    test("lists the six tools and executes calls over streamable HTTP with the bearer key") {
        val ws = Workspace(Files.createTempDirectory("sbx-mcp"))
        testApplication {
            application { sandboxModule(sandboxTools(ws), bearerKey = "k3y") }
            val http = createClient {
                install(SSE)
                install(io.ktor.client.plugins.DefaultRequest) { header(HttpHeaders.Authorization, "Bearer k3y") }
            }
            val client = http.mcpStreamableHttp("http://localhost$MCP_PATH")

            val tools = client.listTools().tools
            tools.map { it.name }.shouldContainExactlyInAnyOrder(
                "shell", "read_file", "write_file", "edit_file", "search_files", "list_files",
            )
            tools.first { it.name == "read_file" }.annotations?.readOnlyHint shouldBe true
            tools.first { it.name == "shell" }.inputSchema.required shouldBe listOf("command")

            val ok = client.callTool("shell", mapOf("command" to "echo from-sandbox"))
            ok.isError shouldBe null
            ok.content.single().shouldBeInstanceOf<TextContent>().text shouldContain "from-sandbox"

            val refused = client.callTool("read_file", mapOf("path" to "../etc/passwd"))
            refused.isError shouldBe true
            refused.content.single().shouldBeInstanceOf<TextContent>().text shouldContain "outside /workspace"
            client.close()
        }
    }

    test("GET and DELETE on the stateless endpoint answer 405 with Allow: POST, with or without a session header") {
        testApplication {
            application { sandboxModule(emptyList(), bearerKey = null) }
            for (session in listOf(null, "unknown-session")) {
                val get = client.get(MCP_PATH) {
                    header(HttpHeaders.Accept, "text/event-stream")
                    session?.let { header("Mcp-Session-Id", it) }
                }
                get.status shouldBe HttpStatusCode.MethodNotAllowed
                get.headers[HttpHeaders.Allow] shouldBe "POST"
                client.delete(MCP_PATH) { session?.let { header("Mcp-Session-Id", it) } }.status shouldBe HttpStatusCode.MethodNotAllowed
            }
        }
    }

    test("requests without the right key get 401; health stays open") {
        testApplication {
            application { sandboxModule(emptyList(), bearerKey = "k3y") }
            val body = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
            client.post(MCP_PATH) { contentType(ContentType.Application.Json); setBody(body) }.status shouldBe HttpStatusCode.Unauthorized
            client.post(MCP_PATH) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer wrong")
                setBody(body)
            }.status shouldBe HttpStatusCode.Unauthorized
            client.get("/healthz").status shouldBe HttpStatusCode.OK
        }
    }
})
