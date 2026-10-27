package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.OpenRouterModelGateway
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.feed.FeedItem

class CompactionTopicTest : FunSpec({
    test("the topic call is tiny: no tools, no cache, 16 tokens, the end of a long summary") {
        val summary = "a".repeat(10_000) + " Ulta hair appointment on Friday."
        val request = topicRequest("deepseek/deepseek-v4.1-flash-1017", summary)
        request.maxTokens shouldBe TOPIC_MAX_TOKENS
        request.tools shouldBe emptyList()
        request.cache shouldBe null
        request.effort shouldBe null
        val input = (request.messages.single().content.single() as Block.Text).text
        input shouldEndWith "Friday.</summary>"
        (input.length <= TOPIC_INPUT_CHARS + "<summary></summary>".length) shouldBe true
        OpenRouterModelGateway.buildBody(request)["max_tokens"]!!.jsonPrimitive.content shouldBe "16"
    }

    test("answers are trimmed to at most four plain words") {
        cleanTopic("Ulta Hair Appt") shouldBe "Ulta Hair Appt"
        cleanTopic("“Ulta Hair Appt.”") shouldBe "Ulta Hair Appt"
        cleanTopic("  **Dana Contract Terms**\nbecause the person…") shouldBe "Dana Contract Terms"
        cleanTopic("Title: Planning the school field trip form") shouldBe "Planning the school field"
        cleanTopic("") shouldBe null
        cleanTopic("  \n \"\" ") shouldBe null
    }

    test("old stored items without a topic still decode, and a marker round-trips") {
        val old = """{"id":"f1","seq":1,"at":"2026-10-26T09:00:00Z","author":"agent","cards":[{"type":"text","text":"hi"}]}"""
        ClarityJson.decodeFromString(FeedItem.serializer(), old).topic shouldBe null
        val marker = """{"id":"f2","seq":2,"at":"2026-10-26T09:00:00Z","author":"agent","cards":[],"topic":"Ulta Hair Appt"}"""
        val item = ClarityJson.decodeFromString(FeedItem.serializer(), marker)
        item.topic shouldBe "Ulta Hair Appt"
        ClarityJson.parseToJsonElement(ClarityJson.encodeToString(FeedItem.serializer(), item)).jsonObject["topic"]!!.jsonPrimitive.content shouldBe "Ulta Hair Appt"
    }
})
