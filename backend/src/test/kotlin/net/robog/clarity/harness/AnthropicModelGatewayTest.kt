package net.robog.clarity.harness

import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.jvm.optionals.getOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
