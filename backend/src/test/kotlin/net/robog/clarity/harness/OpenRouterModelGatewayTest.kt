package net.robog.clarity.harness

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val KEY = "sk-or-test-key-0123456789"

/** One request the fake endpoint saw. */
private data class Seen(val method: String, val uri: String, val headers: Map<String, List<String>>, val body: String)

/**
 * A fake Chat Completions endpoint on loopback: records each request and answers with [respond].
 * Never the real API.
 */
private class FakeOpenRouter(private val respond: (HttpExchange, Int) -> Unit) : AutoCloseable {
    val seen = CopyOnWriteArrayList<Seen>()
    private val calls = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            seen += Seen(ex.requestMethod, ex.requestURI.toString(), ex.requestHeaders.mapKeys { it.key.lowercase() }, ex.requestBody.readAllBytes().decodeToString())
            try {
                respond(ex, calls.getAndIncrement())
            } catch (_: Exception) {
                // the client went away (cancellation test)
            } finally {
                ex.close()
            }
        }
        executor = java.util.concurrent.Executors.newCachedThreadPool()
        start()
    }
    val port: Int get() = server.address.port
    val baseUrl: String get() = "http://127.0.0.1:$port/api/v1"

    override fun close() = server.stop(0)
}

private fun HttpExchange.sse(vararg events: String) {
    responseHeaders.add("content-type", "text/event-stream")
    sendResponseHeaders(200, 0)
    responseBody.use { out ->
        for (e in events) {
            out.write(e.toByteArray())
            out.flush()
        }
    }
}

private fun HttpExchange.json(status: Int, body: String) {
    val bytes = body.toByteArray()
    responseHeaders.add("content-type", "application/json")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun data(json: String) = "data: $json\n\n"

private fun gateway(fake: FakeOpenRouter, proxy: Proxy? = null, baseUrl: String = fake.baseUrl) =
    OpenRouterModelGateway(KEY, baseUrl, proxy, retryDelays = listOf(10.milliseconds))

private val schema = buildJsonObject { put("type", "object") }

private fun simple(model: String = "anthropic/claude-haiku-5.5", effort: String? = "low") =
    ModelRequest(model, "be brief", listOf(Message.user("hi")), emptyList(), 2_000, effort)

class OpenRouterModelGatewayTest : FunSpec({

    test("request body: system, in-place system messages, tool calls and results, tools, effort, privacy") {
        val thinking = Block.Raw(buildJsonObject { put("type", "thinking"); put("thinking", ""); put("signature", "sig") })
        val request = ModelRequest(
            model = "openai/gpt-5",
            system = listOf(SystemBlock("persona", cacheBreakpoint = true), SystemBlock("constitution")),
            messages = listOf(
                Message.user("go"),
                Message(Role.ASSISTANT, listOf(thinking, Block.Text("looking"), Block.ToolUse("call_1", "list", buildJsonObject { put("from", "x") }))),
                Message(Role.USER, listOf(Block.Result("call_1", "[]"), Block.Result("call_2", "boom", isError = true), Block.Text("and now?"))),
                Message.effort("high"),
                Message.system("trigger fired"),
                Message(Role.ASSISTANT, listOf(thinking)),
                Message(Role.USER, listOf(Block.Text("a"), Block.Text("b"))),
            ),
            tools = listOf(ToolDefinition("list", "List things", schema)),
            maxTokens = 4_000,
            effort = "xhigh",
            deferredTools = listOf(ToolDefinition("zeta", "Z", schema)),
            compaction = CompactionConfig(),
        )

        val body = OpenRouterModelGateway.buildBody(request)

        body["model"]?.jsonPrimitive?.content shouldBe "openai/gpt-5"
        body["max_tokens"]?.jsonPrimitive?.content shouldBe "4000"
        body["stream"]?.jsonPrimitive?.content shouldBe "true"
        body["stream_options"] shouldBe buildJsonObject { put("include_usage", true) }
        body["usage"] shouldBe buildJsonObject { put("include", true) }
        body["provider"] shouldBe buildJsonObject { put("data_collection", "deny") }
        body["reasoning"] shouldBe buildJsonObject { put("effort", "high") }
        body["tool_choice"]?.jsonPrimitive?.content shouldBe "auto"
        body["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content } shouldContainExactly listOf("list", "zeta")
        body["tools"]!!.jsonArray.first().jsonObject shouldBe buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject { put("name", "list"); put("description", "List things"); put("parameters", schema) })
        }
        body.containsKey("response_format") shouldBe false

        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        messages.map { it["role"]!!.jsonPrimitive.content } shouldContainExactly
            listOf("system", "user", "assistant", "tool", "tool", "user", "system", "user")
        messages[0]["content"]!!.jsonPrimitive.content shouldBe "persona\n\nconstitution"
        // Thinking is dropped, text and the tool call stay.
        messages[2]["content"]!!.jsonPrimitive.content shouldBe "looking"
        val call = messages[2]["tool_calls"]!!.jsonArray.single().jsonObject
        call["id"]!!.jsonPrimitive.content shouldBe "call_1"
        call["type"]!!.jsonPrimitive.content shouldBe "function"
        call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content shouldBe "list"
        call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content shouldBe """{"from":"x"}"""
        messages[3] shouldBe buildJsonObject { put("role", "tool"); put("tool_call_id", "call_1"); put("content", "[]") }
        messages[4]["content"]!!.jsonPrimitive.content shouldBe "[error] boom"
        messages[5]["content"]!!.jsonPrimitive.content shouldBe "and now?"
        // The effort-only message and the thinking-only assistant turn are gone; the trigger stays in place.
        messages[6]["content"]!!.jsonPrimitive.content shouldBe "trigger fired"
        messages[7]["content"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content } shouldContainExactly listOf("a", "b")
        body.toString() shouldNotContain "sig"
    }

    test("request body: structured output, no tools, an assistant turn of only tool calls, efforts") {
        val request = ModelRequest(
            "m", emptyList(), listOf(
                Message.user("q"),
                Message(Role.ASSISTANT, listOf(Block.ToolUse("c", "t", JsonObject(emptyMap())))),
                Message(Role.USER, listOf(Block.Result("c", "ok"))),
            ), emptyList(), 100, null, structuredOutput = schema,
        )
        val body = OpenRouterModelGateway.buildBody(request)
        body["response_format"] shouldBe buildJsonObject {
            put("type", "json_schema")
            put("json_schema", buildJsonObject { put("name", "answer"); put("strict", true); put("schema", schema) })
        }
        body.containsKey("tools") shouldBe false
        body.containsKey("tool_choice") shouldBe false
        body.containsKey("reasoning") shouldBe false
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        // No system blocks, no system message.
        messages.map { it["role"]!!.jsonPrimitive.content } shouldContainExactly listOf("user", "assistant", "tool")
        messages[1]["content"] shouldBe JsonNull
        listOf("low", "medium", "high", "xhigh", "max").map(OpenRouterModelGateway::reasoningEffort) shouldContainExactly
            listOf("low", "medium", "high", "high", "high")
    }

    test("server tools are refused") {
        val request = simple().copy(serverTools = setOf(ServerTool.WEB_SEARCH))
        shouldThrow<IllegalArgumentException> { OpenRouterModelGateway.buildBody(request) }
    }

    test("SSE parsing: comments skipped, multi-line data joined, stops at [DONE]") {
        val payloads = mutableListOf<String>()
        OpenRouterModelGateway.parseSse(
            sequenceOf(": OPENROUTER PROCESSING", "", "data: {\"a\":1}", "", "data: {\"b\":", "data: 2}", "", "data: [DONE]", "", "data: {\"late\":1}", ""),
        ) { payloads += it; true }
        payloads shouldContainExactly listOf("{\"a\":1}", "{\"b\":\n2}")
    }

    test("finish reasons map to stop reasons") {
        OpenRouterModelGateway.stopReason("stop", false) shouldBe StopReason.END_TURN
        OpenRouterModelGateway.stopReason("stop", true) shouldBe StopReason.TOOL_USE
        OpenRouterModelGateway.stopReason("tool_calls", true) shouldBe StopReason.TOOL_USE
        OpenRouterModelGateway.stopReason("length", false) shouldBe StopReason.MAX_TOKENS
        OpenRouterModelGateway.stopReason("content_filter", false) shouldBe StopReason.REFUSAL
        OpenRouterModelGateway.stopReason("error", false) shouldBe StopReason.OTHER
        OpenRouterModelGateway.stopReason(null, false) shouldBe StopReason.OTHER
    }

    test("usage: cached and written tokens split out of the prompt, the reported cost in micro-dollars") {
        val usage = OpenRouterModelGateway.toUsage(
            HarnessJson.parseToJsonElement(
                """{"prompt_tokens":1000,"completion_tokens":50,"total_tokens":1050,"cost":0.0012345,
                   "prompt_tokens_details":{"cached_tokens":600,"cache_write_tokens":100}}""",
            ).jsonObject,
        )
        usage shouldBe Usage(inputTokens = 300, outputTokens = 50, cacheCreationInputTokens = 100, cacheReadInputTokens = 600, reportedCostMicros = 1_235)
        OpenRouterModelGateway.toUsage(buildJsonObject { put("prompt_tokens", 10); put("completion_tokens", 2) }) shouldBe Usage(10, 2)
    }

    test("streams text and fragmented tool calls over SSE, with usage and cost") {
        FakeOpenRouter { ex, _ ->
            ex.sse(
                ": OPENROUTER PROCESSING\n\n",
                data("""{"id":"g1","choices":[{"index":0,"delta":{"role":"assistant","content":"Let me "},"finish_reason":null}]}"""),
                data("""{"id":"g1","choices":[{"index":0,"delta":{"content":"look."},"finish_reason":null}]}"""),
                data("""{"id":"g1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"search","arguments":""}}]}}]}"""),
                data("""{"id":"g1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"q\":\"ca"}}]}}]}"""),
                data("""{"id":"g1","choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call_b","type":"function","function":{"name":"open","arguments":"{\"id\":"}}]}}]}"""),
                data("""{"id":"g1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ts\"}"}},{"index":1,"function":{"arguments":"7}"}}]}}]}"""),
                data("""{"id":"g1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""),
                data("""{"id":"g1","choices":[],"usage":{"prompt_tokens":120,"completion_tokens":30,"cost":0.00042,"prompt_tokens_details":{"cached_tokens":20}}}"""),
                "data: [DONE]\n\n",
            )
        }.use { fake ->
            val deltas = mutableListOf<String>()
            val response = gateway(fake).generate(simple().copy(tools = listOf(ToolDefinition("search", "S", schema)))) { deltas += it }

            deltas shouldContainExactly listOf("Let me ", "look.")
            response.content shouldContainExactly listOf(
                Block.Text("Let me look."),
                Block.ToolUse("call_a", "search", buildJsonObject { put("q", "cats") }),
                Block.ToolUse("call_b", "open", buildJsonObject { put("id", 7) }),
            )
            response.stopReason shouldBe StopReason.TOOL_USE
            response.usage shouldBe Usage(inputTokens = 100, outputTokens = 30, cacheReadInputTokens = 20, reportedCostMicros = 420)

            val seen = fake.seen.single()
            seen.method shouldBe "POST"
            seen.uri shouldBe "/api/v1/chat/completions"
            seen.headers["authorization"] shouldBe listOf("Bearer $KEY")
            seen.headers["accept"] shouldBe listOf("text/event-stream")
            val sent = HarnessJson.parseToJsonElement(seen.body).jsonObject
            sent["stream"] shouldBe JsonPrimitive(true)
            sent["provider"] shouldBe buildJsonObject { put("data_collection", "deny") }
        }
    }

    test("a truncated tool input becomes an empty object; a stop with text ends the turn") {
        FakeOpenRouter { ex, n ->
            if (n == 0) {
                ex.sse(
                    data("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c","function":{"name":"t","arguments":"{\"a\":"}}]}}]}"""),
                    data("""{"choices":[{"index":0,"delta":{},"finish_reason":"length"}]}"""),
                    "data: [DONE]\n\n",
                )
            } else {
                ex.sse(data("""{"choices":[{"index":0,"delta":{"content":"{\"verdict\":\"ok\"}"},"finish_reason":"stop"}]}"""), "data: [DONE]\n\n")
            }
        }.use { fake ->
            val g = gateway(fake)
            val truncated = g.generate(simple())
            truncated.content shouldContainExactly listOf(Block.ToolUse("c", "t", JsonObject(emptyMap())))
            truncated.stopReason shouldBe StopReason.MAX_TOKENS
            truncated.usage shouldBe Usage.ZERO

            g.completeJson(simple().copy(structuredOutput = schema)) shouldBe buildJsonObject { put("verdict", "ok") }
            HarnessJson.parseToJsonElement(fake.seen[1].body).jsonObject.containsKey("response_format") shouldBe true
        }
    }

    test("an error status throws with the status and message, never the key, and 4xx is not retried") {
        FakeOpenRouter { ex, _ -> ex.json(401, """{"error":{"code":401,"message":"No auth credentials found for $KEY"}}""") }.use { fake ->
            val e = shouldThrow<OpenRouterException> { gateway(fake).generate(simple()) }
            e.status shouldBe 401
            e.message!! shouldContain "401"
            e.message!! shouldContain "No auth credentials found"
            e.message!! shouldNotContain KEY
            fake.seen.size shouldBe 1
        }
    }

    test("429 and 5xx are retried, then the last failure is thrown") {
        FakeOpenRouter { ex, n ->
            if (n == 0) ex.json(503, """{"error":{"message":"overloaded"}}""") else ex.sse(data("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}"""))
        }.use { fake ->
            gateway(fake).generate(simple()).text shouldBe "ok"
            fake.seen.size shouldBe 2
        }
        FakeOpenRouter { ex, _ -> ex.json(429, """{"error":{"message":"rate limited"}}""") }.use { fake ->
            shouldThrow<OpenRouterException> { gateway(fake).generate(simple()) }.status shouldBe 429
            fake.seen.size shouldBe 2 // one try plus one retry
        }
    }

    test("an error sent mid-stream throws") {
        FakeOpenRouter { ex, _ ->
            ex.sse(
                data("""{"choices":[{"index":0,"delta":{"content":"partial"}}]}"""),
                data("""{"error":{"code":502,"message":"provider disconnected"},"choices":[{"index":0,"delta":{"content":""},"finish_reason":"error"}]}"""),
            )
        }.use { fake ->
            val e = shouldThrow<OpenRouterException> { gateway(fake).generate(simple()) }
            e.message!! shouldContain "provider disconnected"
            e.status shouldBe null
        }
    }

    test("all traffic goes through the egress proxy") {
        // The fake plays the proxy: a plain-HTTP target arrives in absolute form, naming the real host.
        FakeOpenRouter { ex, _ -> ex.sse(data("""{"choices":[{"delta":{"content":"via proxy"},"finish_reason":"stop"}]}""")) }.use { proxy ->
            val viaProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", proxy.port))
            gateway(proxy, viaProxy, baseUrl = "http://openrouter.invalid/api/v1").generate(simple()).text shouldBe "via proxy"
            proxy.seen.single().uri shouldBe "http://openrouter.invalid/api/v1/chat/completions"
        }
        httpsProxyFromEnv(mapOf("CLARITY_HTTPS_PROXY" to "http://egress:3128"))!!.type() shouldBe Proxy.Type.HTTP
    }

    test("cancelling the coroutine aborts a stream that is still open") {
        val release = CountDownLatch(1)
        FakeOpenRouter { ex, _ ->
            ex.responseHeaders.add("content-type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.write(data("""{"choices":[{"delta":{"content":"first"}}]}""").toByteArray())
            ex.responseBody.flush()
            release.await(30, TimeUnit.SECONDS)
        }.use { fake ->
            try {
                val started = CompletableDeferred<Unit>()
                coroutineScope {
                    val call = async { gateway(fake).generate(simple()) { started.complete(Unit) } }
                    withTimeout(10.seconds) { started.await() }
                    withTimeout(5.seconds) { call.cancelAndJoin() }
                    call.isCancelled shouldBe true
                }
            } finally {
                release.countDown()
            }
        }
    }
})
