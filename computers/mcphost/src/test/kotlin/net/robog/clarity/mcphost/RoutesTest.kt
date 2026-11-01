package net.robog.clarity.mcphost

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.mcp.McpServerState

class RoutesTest : FunSpec({
    test("every route but /healthz needs the bearer key") {
        Harness().use { h ->
            testApplication {
                application { hostModule(h.supervisor, "k3y") }
                client.get("/healthz").status shouldBe HttpStatusCode.OK
                client.get("/servers").status shouldBe HttpStatusCode.Unauthorized
                client.get("/servers") { header(HttpHeaders.Authorization, "Bearer nope") }.status shouldBe HttpStatusCode.Unauthorized
                client.post("/s/abc/mcp") { setBody("{}") }.status shouldBe HttpStatusCode.Unauthorized
                client.get("/servers") { header(HttpHeaders.Authorization, "Bearer k3y") }.status shouldBe HttpStatusCode.OK
            }
        }
    }

    test("the backend's MCP client works through /s/{id}/mcp, and /servers carries the cached listing") {
        Harness().use { h ->
            h.supervisor.reconcile(h.parse(h.fakeItem("sdk111", "Fake")))
            eventually(30.seconds) { h.supervisor.servers().single().state shouldBe McpServerState.IDLE }
            testApplication {
                application { hostModule(h.supervisor, "k3y") }
                val http = createClient {
                    install(SSE)
                    install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer k3y") }
                }
                val listed = ClarityJson.decodeFromString(ListSerializer(HostServer.serializer()), http.get("/servers").bodyAsText()).single()
                listed.label shouldBe "Fake"
                listed.tools shouldHaveSize 12

                val client = http.mcpStreamableHttp("http://localhost/s/sdk111/mcp")
                client.listTools().tools.map { it.name } shouldContain "echo"
                val reply = client.callTool("echo", mapOf("text" to "through the host"))
                reply.content.single().shouldBeInstanceOf<TextContent>().text shouldBe "through the host"
                client.close()

                http.get("/s/sdk111/mcp").status shouldBe HttpStatusCode.MethodNotAllowed
                http.delete("/s/sdk111/mcp").status shouldBe HttpStatusCode.MethodNotAllowed
                http.post("/s/nosuch/mcp") { contentType(ContentType.Application.Json); setBody("""{"jsonrpc":"2.0","id":1,"method":"ping"}""") }
                    .status shouldBe HttpStatusCode.NotFound
                http.post("/s/sdk111/mcp") { contentType(ContentType.Application.Json); setBody("not json") }.status shouldBe HttpStatusCode.BadRequest
                val batch = http.post("/s/sdk111/mcp") {
                    contentType(ContentType.Application.Json)
                    setBody("""[{"jsonrpc":"2.0","id":"a","method":"ping"},{"jsonrpc":"2.0","method":"notifications/initialized"}]""")
                }.bodyAsText()
                batch shouldBe """[{"jsonrpc":"2.0","id":"a","result":{}}]"""
            }
        }
    }

    test("a remote server gets the requests with its bearer secret, and its session and SSE come back") {
        val seen = CopyOnWriteArrayList<String>()
        val remote = embeddedServer(CIO, port = 0) { fakeRemote(seen) }.start(wait = false)
        val port = remote.engine.resolvedConnectors().first().port
        try {
            val spec = ServerSpec(id = "rem111", label = "Remote", kind = ServerKind.REMOTE, url = "http://127.0.0.1:$port/mcp", bearer = "remote-bearer")
            RemoteServer(spec, null).use { server ->
                server.listTools().map { it.jsonObject["name"]!!.jsonPrimitive.content } shouldBe listOf("remote_tool")
                seen shouldContain "initialize auth=Bearer remote-bearer session=null"
                seen shouldContain "tools/list auth=Bearer remote-bearer session=sess-1"
                seen shouldContain "DELETE session=sess-1"

                val response = server.forward(
                    "POST",
                    mapOf("Accept" to "application/json, text/event-stream", "Content-Type" to "application/json", "Mcp-Session-Id" to "sess-1", "Cookie" to "x"),
                    """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"remote_tool"}}""".toByteArray(),
                )
                response.statusCode() shouldBe 200
                response.headers().firstValue("Content-Type").get() shouldContain "text/event-stream"
                response.body().readAllBytes().toString(Charsets.UTF_8) shouldContain "\"id\":9"
                seen.last() shouldBe "tools/call auth=Bearer remote-bearer session=sess-1 cookie=null"
            }
        } finally {
            remote.stop(0, 0)
        }
    }
})

/** A stateful streamable-HTTP MCP server: a session id on initialize, SSE answers to calls. */
private fun Application.fakeRemote(seen: MutableList<String>) {
    routing {
        post("/mcp") {
            val body = ClarityJson.parseToJsonElement(call.receiveText()).jsonObject
            val method = body["method"]!!.jsonPrimitive.content
            val auth = call.request.header(HttpHeaders.Authorization)
            val session = call.request.header("Mcp-Session-Id")
            seen += if (method == "tools/call") "$method auth=$auth session=$session cookie=${call.request.header("Cookie")}" else "$method auth=$auth session=$session"
            val id = body["id"]
            when (method) {
                "initialize" -> {
                    call.response.header("Mcp-Session-Id", "sess-1")
                    call.respondText(ClarityJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), rpcResult(id, kotlinx.serialization.json.buildJsonObject {
                        put("protocolVersion", HOST_PROTOCOL_VERSION)
                        put("capabilities", kotlinx.serialization.json.JsonObject(emptyMap()))
                        put("serverInfo", kotlinx.serialization.json.buildJsonObject { put("name", "remote"); put("version", "1") })
                    })), ContentType.Application.Json)
                }
                "notifications/initialized" -> call.respondText("", status = HttpStatusCode.Accepted)
                "tools/list" -> call.respondText(
                    "event: message\ndata: " + ClarityJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), rpcResult(id, ClarityJson.parseToJsonElement(
                        """{"tools":[{"name":"remote_tool","inputSchema":{"type":"object"}}]}""",
                    ))) + "\n\n",
                    ContentType.Text.EventStream,
                )
                else -> call.respondText(
                    "data: " + ClarityJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), rpcResult(id, ClarityJson.parseToJsonElement("""{"content":[]}"""))) + "\n\n",
                    ContentType.Text.EventStream,
                )
            }
        }
        delete("/mcp") {
            seen += "DELETE session=${call.request.header("Mcp-Session-Id")}"
            call.respondText("", status = HttpStatusCode.OK)
        }
    }
}

