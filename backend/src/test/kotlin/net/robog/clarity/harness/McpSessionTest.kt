package net.robog.clarity.harness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.ContentType
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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A stateful MCP gateway like supergateway: each initialize opens a session (one browser), which
 * lives until it is DELETEd. The provider must end every session it drops, including one a killed
 * process left behind, or the browser refuses the next ("Maximum number of active sessions").
 */
class McpSessionTest : FunSpec({
    class Gateway {
        val opened = CopyOnWriteArrayList<String>()
        val deleted = CopyOnWriteArrayList<String>()
        var calls = 0
        var cutOff = false
        val live: List<String> get() = opened - deleted.toSet()
    }

    fun io.ktor.server.application.Application.gateway(g: Gateway) = routing {
        post("/mcp") {
            val body = Json.parseToJsonElement(call.receiveText()).jsonObject
            val id = body["id"]
            when (body["method"]?.jsonPrimitive?.content) {
                "initialize" -> {
                    val session = "s${g.opened.size + 1}"
                    g.opened += session
                    call.response.header("Mcp-Session-Id", session)
                    call.respondText(
                        """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"fake","version":"1"}}}""",
                        ContentType.Application.Json,
                    )
                }
                "tools/list" -> call.respondText(
                    """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"click","inputSchema":{"type":"object","properties":{}}}]}}""",
                    ContentType.Application.Json,
                )
                "tools/call" -> {
                    g.calls++
                    if (g.cutOff) {
                        call.respondText("""{"jsonrpc":"2.0","id":$id,"res""", ContentType.Application.Json)
                    } else {
                        call.respondText("""{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"clicked"}]}}""", ContentType.Application.Json)
                    }
                }
                else -> call.respond(HttpStatusCode.Accepted)
            }
        }
        get("/mcp") { call.respond(HttpStatusCode.MethodNotAllowed) }
        delete("/mcp") {
            call.request.headers["Mcp-Session-Id"]?.let { g.deleted += it }
            call.respond(HttpStatusCode.OK)
        }
    }

    class Memory(var id: String? = null) : McpSessionMemory {
        override fun load() = id
        override fun save(id: String?) { this.id = id }
    }

    test("a session left by a killed process is ended before a new one opens; close ends the current one") {
        testApplication {
            val g = Gateway()
            application { gateway(g) }
            val memory = Memory("s-old")
            val provider = McpToolProvider("http://localhost/mcp", createClient { install(SSE) }, sessions = memory)
            withTimeout(20.seconds) {
                val click = provider.tools().single()
                click.invoke(JsonObject(emptyMap())) shouldBe ToolResult.Ok("clicked")
                g.deleted shouldContainExactly listOf("s-old")
                memory.id shouldBe "s1"
                provider.close()
                g.deleted shouldContainExactly listOf("s-old", "s1")
                g.live shouldBe emptyList()
                memory.id shouldBe null
            }
        }
    }

    test("a reply that comes back cut off is reported, not retried on a new session") {
        testApplication {
            val g = Gateway()
            application { gateway(g) }
            val provider = McpToolProvider("http://localhost/mcp", createClient { install(SSE) })
            withTimeout(20.seconds) {
                val click = provider.tools().single()
                g.cutOff = true
                click.invoke(JsonObject(emptyMap())).shouldBeInstanceOf<ToolResult.Error>().message shouldStartWith CUT_OFF_REPLY
                g.calls shouldBe 1
                g.opened.size shouldBe 1
                provider.close()
                g.live shouldBe emptyList()
            }
        }
    }
})
