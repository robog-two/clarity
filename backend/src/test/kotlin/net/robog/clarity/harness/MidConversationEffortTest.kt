package net.robog.clarity.harness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The effort-only system message: kept in the journal, applied per request, never sent itself. */
class MidConversationEffortTest : FunSpec({
    fun request(vararg messages: Message) = ModelRequest(
        model = "anthropic/claude-opus-5.5",
        system = "persona",
        messages = messages.toList(),
        tools = emptyList(),
        maxTokens = 1000,
        effort = "medium",
    )

    test("an effort-only message sets the request's reasoning effort and is not sent as a message") {
        val body = OpenRouterModelGateway.buildBody(request(Message.user("good morning"), Message.effort("low")))
        body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content shouldBe "low"
        body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content } shouldBe listOf("system", "user")
    }

    test("Claude keeps efforts above high; other models are capped there") {
        OpenRouterModelGateway.buildBody(request(Message.user("hi"), Message.effort("max")))["reasoning"]!!
            .jsonObject["effort"]!!.jsonPrimitive.content shouldBe "max"
        OpenRouterModelGateway.buildBody(request(Message.user("hi"), Message.effort("xhigh")).copy(model = "xiaomi/mimo-v2.6-pro"))["reasoning"]!!
            .jsonObject["effort"]!!.jsonPrimitive.content shouldBe "high"
    }

    test("journals written before the field existed still decode, and the message round-trips") {
        val old = """{"role":"SYSTEM","content":[{"type":"text","text":"x"}]}"""
        HarnessJson.decodeFromString(Message.serializer(), old).effort shouldBe null
        val encoded = HarnessJson.encodeToString(Message.serializer(), Message.effort("low"))
        HarnessJson.decodeFromString(Message.serializer(), encoded) shouldBe Message.effort("low")
        HarnessJson.encodeToString(Message.serializer(), Message.user("x")).contains("effort") shouldBe false
    }
})
