package net.robog.clarity.backend.mcp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.sse.SSE
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.harness.Description
import net.robog.clarity.harness.McpToolProvider
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.tool
import net.robog.clarity.shared.ClarityJson

@Serializable
private class EchoInput(@Description("text to echo") val text: String)

private val echo = tool<EchoInput>("echo", "Echo the text back") { ToolResult.Ok("echo: ${it.text}") }
private val failing = tool<EchoInput>("write_fail", "Always fails", ToolEffect.WRITE) { ToolResult.Error("denied") }

/** The bits of the real server the MCP endpoint relies on: app-level JSON content negotiation (ClarityJson). */
private fun Application.appLikeServer(tools: List<Tool>) {
    install(ContentNegotiation) { json(ClarityJson) }
    routing { mcpEndpoint("/mcp") { tools } }
}

/**
 * Round trip through the real MCP SDK on both sides: our server adapter, over Ktor's test engine,
 * called by the MCP client and by the harness' [McpToolProvider]. Auth is out of scope here: the
 * endpoint function is mounted directly, exactly as [McpServerModule] mounts it inside `authenticate`.
 */
class McpServerTest : FunSpec({

    test("server lists tools with schemas and annotations, and executes calls") {
        testApplication {
            application { appLikeServer(listOf(echo, failing)) }
            val http = createClient { install(SSE) }
            val client = http.mcpStreamableHttp("http://localhost/mcp")

            val tools = client.listTools().tools
            tools.map { it.name } shouldContainExactly listOf("echo", "write_fail")
            tools.first().inputSchema.required shouldBe listOf("text")
            tools.first().annotations?.readOnlyHint shouldBe true
            tools.last().annotations?.readOnlyHint shouldBe false

            val ok = client.callTool("echo", mapOf("text" to "hi"))
            ok.isError shouldBe null
            ok.content.single().shouldBeInstanceOf<TextContent>().text shouldBe "echo: hi"

            val bad = client.callTool("echo", emptyMap())
            bad.isError shouldBe true

            client.callTool("write_fail", mapOf("text" to "x")).isError shouldBe true
            client.close()
        }
    }

    test("McpToolProvider adapts remote tools to harness tools (EXTERNAL by default) and calls them") {
        testApplication {
            application { appLikeServer(listOf(echo)) }
            val http = createClient { install(SSE) }
            val provider = McpToolProvider("http://localhost/mcp", http, namePrefix = "srv__")

            val tools = provider.tools()
            val adapted = tools.single()
            adapted.name shouldBe "srv__echo"
            adapted.effect shouldBe ToolEffect.EXTERNAL
            adapted.inputSchema["required"].toString() shouldBe """["text"]"""

            adapted.invoke(buildJsonObject { put("text", JsonPrimitive("yo")) }) shouldBe ToolResult.Ok("echo: yo")
            adapted.invoke(buildJsonObject {}).shouldBeInstanceOf<ToolResult.Error>()

            // Dropping the session simulates a restart; the next call reconnects lazily.
            provider.close()
            adapted.invoke(buildJsonObject { put("text", JsonPrimitive("again")) }) shouldBe ToolResult.Ok("echo: again")
            provider.close()
        }
    }
})
