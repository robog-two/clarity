package net.robog.clarity.harness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlin.jvm.optionals.getOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The effort-only system message (beta `mid-conversation-output-config-2026-07-01`), offline. */
class MidConversationEffortTest : FunSpec({
    fun request(vararg messages: Message) = ModelRequest(
        model = "claude-opus-5-5",
        system = "persona",
        messages = messages.toList(),
        tools = emptyList(),
        maxTokens = 1000,
        effort = "medium",
    )

    test("an effort-only message goes out as a system message with output_config and the beta header") {
        val params = AnthropicModelGateway.buildParams(
            request(Message.user("good morning"), Message.effort("low"), Message(Role.ASSISTANT, listOf(Block.Text("hi")))),
        )
        params.betas().getOrNull().orEmpty().map { it.asString() } shouldContain AnthropicModelGateway.MID_CONVERSATION_EFFORT_BETA

        val body = AnthropicModelGateway.bodyJson(params)
        val system = body["messages"]!!.jsonArray[1].jsonObject
        system["role"]!!.jsonPrimitive.content shouldBe "system"
        system["content"]!!.jsonArray.size shouldBe 0
        system["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content shouldBe "low"
        // the request's own effort is untouched
        body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content shouldBe "medium"
    }

    test("without one the beta header is not sent and ordinary system messages carry no output_config") {
        val params = AnthropicModelGateway.buildParams(request(Message.user("hi"), Message.system("event"), Message(Role.ASSISTANT, listOf(Block.Text("ok")))))
        params.betas().getOrNull().orEmpty().map { it.asString() } shouldNotContain AnthropicModelGateway.MID_CONVERSATION_EFFORT_BETA
        AnthropicModelGateway.bodyJson(params)["messages"]!!.jsonArray[1].jsonObject.containsKey("output_config") shouldBe false
    }

    test("journals written before the field existed still decode, and the message round-trips") {
        val old = """{"role":"SYSTEM","content":[{"type":"text","text":"x"}]}"""
        HarnessJson.decodeFromString(Message.serializer(), old).effort shouldBe null
        val encoded = HarnessJson.encodeToString(Message.serializer(), Message.effort("low"))
        HarnessJson.decodeFromString(Message.serializer(), encoded) shouldBe Message.effort("low")
        HarnessJson.encodeToString(Message.serializer(), Message.user("x")).contains("effort") shouldBe false
    }
})
