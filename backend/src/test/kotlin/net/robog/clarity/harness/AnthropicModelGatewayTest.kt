package net.robog.clarity.harness

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.jvm.optionals.getOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Exercises request construction and response mapping offline; no network, no API key. */
class AnthropicModelGatewayTest : FunSpec({

    val thinking = buildJsonObject { put("type", "thinking"); put("thinking", ""); put("signature", "sig-123") }

    test("request: opus 5.5, adaptive thinking, explicit effort, top-level caching, default fallbacks, auto tool choice") {
        val request = ModelRequest(
            model = "claude-opus-5-5",
            system = "stable system prompt",
            messages = listOf(
                Message.user("go"),
                Message(
                    Role.ASSISTANT,
                    listOf(Block.Raw(thinking), Block.Text("looking"), toolUse("toolu_1", "list_messages", "from" to "x")),
                ),
                Message(Role.USER, listOf(Block.Result("toolu_1", "[]"), Block.Result("toolu_2", "bad", isError = true))),
            ),
            tools = listOf(ToolDefinition("list_messages", "List", buildJsonObject { put("type", "object") })),
            maxTokens = 64_000,
            effort = "medium",
        )

        val params = AnthropicModelGateway.buildParams(request)

        params.maxTokens() shouldBe 64_000
        params.betas().getOrNull().orEmpty().map { it.asString() } shouldContainExactly
            listOf(AnthropicModelGateway.FALLBACK_BETA)
        params.fallbacks().isPresent shouldBe true
        params.cacheControl().isPresent shouldBe true
        params.thinking().getOrNull()?.isAdaptive() shouldBe true
        params.outputConfig().getOrNull()?.effort()?.getOrNull()?.asString() shouldBe "medium"
        params.toolChoice().getOrNull()?.isAuto() shouldBe true
        params.tools().getOrNull()?.size shouldBe 1
        params.messages().size shouldBe 3
        // Thinking survives the round trip into the next request unchanged.
        params.messages()[1].content().getOrNull()?.asContentBlockParams()?.first()?.isThinking() shouldBe true
    }

    test("request JSON: system blocks with ttl breakpoint, deferred tools + bm25 search, compaction, system role, structured output") {
        val schema = buildJsonObject { put("type", "object") }
        val compaction = Block.Raw(buildJsonObject { put("type", "compaction"); put("content", "summary so far") })
        val request = ModelRequest(
            model = "claude-opus-5-5",
            system = listOf(SystemBlock("persona", cacheBreakpoint = true), SystemBlock("constitution")),
            messages = listOf(
                Message.user("hi"),
                Message.system("trigger fired"),
                Message(Role.ASSISTANT, listOf(compaction, Block.Text("ok"))),
                Message.user("again"),
            ),
            tools = listOf(ToolDefinition("alpha", "A", schema)),
            maxTokens = 1000,
            effort = "low",
            deferredTools = listOf(ToolDefinition("zeta", "Z", schema)),
            serverTools = setOf(ServerTool.WEB_SEARCH, ServerTool.WEB_FETCH),
            cache = CachePolicy(ttl = "1h"),
            compaction = CompactionConfig(triggerInputTokens = 120_000, instructions = "keep promises"),
            thinkingDisplay = "summarized",
            structuredOutput = schema,
        )

        val params = AnthropicModelGateway.buildParams(request)
        val body = AnthropicModelGateway.bodyJson(params)

        params.betas().getOrNull().orEmpty().map { it.asString() }.toSet() shouldBe
            setOf(AnthropicModelGateway.FALLBACK_BETA, AnthropicModelGateway.COMPACTION_BETA)

        val system = body["system"]!!.jsonArray
        system[0].jsonObject["cache_control"]!!.jsonObject["ttl"]!!.jsonPrimitive.content shouldBe "1h"
        system[1].jsonObject.containsKey("cache_control") shouldBe false
        body["cache_control"]!!.jsonObject["ttl"]!!.jsonPrimitive.content shouldBe "1h"
        body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "adaptive"
        body["thinking"]!!.jsonObject["display"]!!.jsonPrimitive.content shouldBe "summarized"

        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        tools.map { it["name"]!!.jsonPrimitive.content } shouldContainExactly
            listOf("alpha", "web_search", "web_fetch", "tool_search_tool_bm25", "zeta")
        tools[0].containsKey("defer_loading") shouldBe false
        tools[3]["type"]!!.jsonPrimitive.content shouldBe "tool_search_tool_bm25_20251119"
        tools[4]["defer_loading"]!!.jsonPrimitive.content shouldBe "true"

        val edit = body["context_management"]!!.jsonObject["edits"]!!.jsonArray.single().jsonObject
        edit["type"]!!.jsonPrimitive.content shouldBe "compact_20260112"
        edit["trigger"]!!.jsonObject["value"]!!.jsonPrimitive.content shouldBe "120000"
        edit["instructions"]!!.jsonPrimitive.content shouldBe "keep promises"

        val output = body["output_config"]!!.jsonObject
        output["effort"]!!.jsonPrimitive.content shouldBe "low"
        output["format"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "json_schema"

        val roles = body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        roles shouldContainExactly listOf("user", "system", "assistant", "user")
        // The compaction block goes back byte-for-byte.
        body["messages"]!!.jsonArray[2].jsonObject["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content shouldBe "compaction"
    }

    test("caching can be disabled and 5m is the implicit default") {
        val body = AnthropicModelGateway.bodyJson(
            AnthropicModelGateway.buildParams(
                ModelRequest("claude-opus-5-5", listOf(SystemBlock("s", cacheBreakpoint = true)), listOf(Message.user("x")), emptyList(), 10, null),
            ),
        )
        body["system"]!!.jsonArray[0].jsonObject["cache_control"]!!.jsonObject.containsKey("ttl") shouldBe false
        val off = AnthropicModelGateway.bodyJson(
            AnthropicModelGateway.buildParams(
                ModelRequest("claude-opus-5-5", listOf(SystemBlock("s")), listOf(Message.user("x")), emptyList(), 10, null, cache = null),
            ),
        )
        off.containsKey("cache_control") shouldBe false
    }

    test("more than four cache breakpoints, or every tool deferred, is rejected up front") {
        val tooMany = List(4) { SystemBlock("b$it", cacheBreakpoint = true) }
        shouldThrow<IllegalArgumentException> {
            ModelRequest("claude-opus-5-5", tooMany, listOf(Message.user("x")), emptyList(), 10, null)
        }
        shouldThrow<IllegalArgumentException> {
            ModelRequest(
                "claude-opus-5-5", listOf(SystemBlock("s")), listOf(Message.user("x")), emptyList(), 10, null,
                deferredTools = listOf(ToolDefinition("t", "t", buildJsonObject { put("type", "object") })),
            )
        }
    }

    test("response mapping: compaction block and stop reason round trip verbatim") {
        val message = Json.parseToJsonElement(
            """
            {"id":"msg_2","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[{"type":"compaction","content":"the summary"},{"type":"text","text":"continuing"}],
             "stop_reason":"compaction","stop_sequence":null,
             "usage":{"input_tokens":1,"output_tokens":2,
               "cache_creation":{"ephemeral_5m_input_tokens":4,"ephemeral_1h_input_tokens":6},
               "cache_creation_input_tokens":10,"cache_read_input_tokens":0}}
            """.trimIndent(),
        ).toJavaValue().let { JsonValue.from(it).convert(BetaMessage::class.java) }!!

        val mapped = AnthropicModelGateway.toModelResponse(message)

        mapped.stopReason shouldBe StopReason.COMPACTION
        mapped.usage.cacheCreation5mTokens shouldBe 4
        mapped.usage.cacheCreation1hTokens shouldBe 6
        val block = mapped.content[0].shouldBeInstanceOf<Block.Raw>()
        block.isCompaction shouldBe true
        block.json["content"]!!.jsonPrimitive.content shouldBe "the summary"
    }

    test("no tools means no tool_choice") {
        val params = AnthropicModelGateway.buildParams(
            ModelRequest("claude-opus-5-5", "s", listOf(Message.user("hi")), emptyList(), 1000, null),
        )
        params.toolChoice().isPresent shouldBe false
        params.outputConfig().isPresent shouldBe false
    }

    test("response mapping: text, tool_use, thinking kept raw, stop reason and usage incl. cache tokens") {
        val message = Json.parseToJsonElement(
            """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[
               {"type":"thinking","thinking":"","signature":"sig-123"},
               {"type":"text","text":"checking"},
               {"type":"tool_use","id":"toolu_1","name":"list_messages","input":{"from":"2026-01-01T00:00:00Z","limit":5}}],
             "stop_reason":"tool_use","stop_sequence":null,
             "usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":3,"cache_creation_input_tokens":2}}
            """.trimIndent(),
        ).toJavaValue().let { JsonValue.from(it).convert(BetaMessage::class.java) }!!

        val mapped = AnthropicModelGateway.toModelResponse(message)

        mapped.stopReason shouldBe StopReason.TOOL_USE
        mapped.usage shouldBe Usage(10, 5, cacheCreationInputTokens = 2, cacheReadInputTokens = 3)
        val raw = mapped.content[0].shouldBeInstanceOf<Block.Raw>()
        raw.json["type"].toString() shouldBe "\"thinking\""
        raw.json["signature"].toString() shouldBe "\"sig-123\""
        mapped.content[1] shouldBe Block.Text("checking")
        val use = mapped.content[2].shouldBeInstanceOf<Block.ToolUse>()
        use.name shouldBe "list_messages"
        (use.input as JsonObject)["limit"].toString() shouldBe "5"
    }
})
