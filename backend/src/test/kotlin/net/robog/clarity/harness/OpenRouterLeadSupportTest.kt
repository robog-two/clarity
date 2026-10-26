package net.robog.clarity.harness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private fun wire(request: ModelRequest) = OpenRouterModelGateway.buildBody(request)["messages"]!!.jsonArray.map { it.jsonObject }

private fun roles(messages: List<JsonObject>) = messages.map { it["role"]!!.jsonPrimitive.content }

private fun content(message: JsonObject) = message["content"]!!.jsonPrimitive.content

private fun request(model: String, vararg messages: Message, effort: String? = "medium") =
    ModelRequest(model, "sys", messages.toList(), emptyList(), 1_000, effort)

/** What the lead needs from the harness and the gateways to run on OpenRouter. No real model is called. */
class OpenRouterLeadSupportTest : FunSpec({

    test("the summary marker: recognised only as a user message's one text block; contextSince takes either marker") {
        val old = Message.user("old")
        val summary = Message.summary("the gist")
        summary.isSummary shouldBe true
        Message.system(SUMMARY_MARKER).isSummary shouldBe false
        Message(Role.USER, listOf(Block.Text(SUMMARY_MARKER), Block.Text("x"))).isSummary shouldBe false
        val compaction = Message(Role.ASSISTANT, listOf(Block.Raw(buildJsonObject { put("type", "compaction"); put("content", "s") })))

        contextSince(listOf(old, summary, Message.user("next"))) shouldContainExactly listOf(summary, Message.user("next"))
        contextSince(listOf(old, compaction, Message.user("next"))) shouldContainExactly listOf(compaction, Message.user("next"))
        // The latest marker wins, whichever kind.
        contextSince(listOf(old, compaction, summary)) shouldContainExactly listOf(summary)
        contextSince(listOf(old, summary, compaction)) shouldContainExactly listOf(compaction)
        // sinceSummary (what every provider is sent) ignores Anthropic's block: Anthropic drops before it itself.
        sinceSummary(listOf(old, compaction, Message.user("next"))) shouldContainExactly listOf(old, compaction, Message.user("next"))
        sinceSummary(listOf(old, summary)) shouldContainExactly listOf(summary)
    }

    test("the harness sends the context from the latest summary on and keeps the journal whole") {
        val journal = InMemoryRunJournal()
        val history = listOf(Message.user("old question"), Message(Role.ASSISTANT, listOf(Block.Text("old answer"))), Message.summary("the gist"), Message.user("new"))
        history.forEach { journal.append("t", it) }
        val gateway = ScriptedGateway(listOf(response(StopReason.END_TURN, Block.Text("hi"))))
        Harness(gateway, journal).resume("t", RunRequest("m", "sys", emptyList())).toList()

        gateway.requests.single().messages shouldContainExactly listOf(Message.summary("the gist"), Message.user("new"))
        journal.load("t").take(4) shouldContainExactly history
    }

    test("Anthropic to OpenRouter: the server compaction block's summary reaches the model, what came before does not") {
        val thinking = Block.Raw(buildJsonObject { put("type", "thinking"); put("thinking", ""); put("signature", "sig") })
        val compaction = Block.Raw(buildJsonObject { put("type", "compaction"); put("content", "they like tea"); put("signature", "c-sig") })
        val messages = wire(
            request(
                "anthropic/claude-opus-5.5",
                Message.user("before the compaction"),
                Message(Role.ASSISTANT, listOf(Block.Text("old reply"))),
                Message.user("what do I drink?"),
                Message(Role.ASSISTANT, listOf(thinking, compaction, Block.Text("You like tea."))),
                Message.user("thanks"),
            ),
        )
        roles(messages) shouldContainExactly listOf("system", "user", "assistant", "user")
        content(messages[1]) shouldBe "$SUMMARY_MARKER\nthey like tea"
        content(messages[2]) shouldBe "You like tea."
        content(messages[3]) shouldBe "thanks"
        messages.toString() shouldNotContain "before the compaction"
        messages.toString() shouldNotContain "sig"

        // An encrypted-only compaction block still marks the cut, with a plain note.
        val encrypted = Block.Raw(buildJsonObject { put("type", "compaction"); put("encrypted_content", "xyz") })
        val cut = wire(request("a/b", Message.user("old"), Message(Role.ASSISTANT, listOf(encrypted)), Message.user("hi")))
        roles(cut) shouldContainExactly listOf("system", "user", "user")
        content(cut[1]) shouldBe "$SUMMARY_MARKER\n${OpenRouterModelGateway.UNREADABLE_SUMMARY}"
    }

    test("OpenRouter to Anthropic: OpenRouter turns replay without thinking or reasoning, and the cache breakpoint still lands") {
        val reasoning = OpenRouterModelGateway.reasoningBlock("a/b", listOf(buildJsonObject { put("type", "reasoning.text"); put("text", "hmm") }))
        val history = listOf(
            Message.summary("the gist"),
            Message.user("q1"),
            Message(Role.ASSISTANT, listOf(reasoning, Block.Text("a1"))),
            Message.user("q2"),
            Message(Role.ASSISTANT, listOf(reasoning, Block.ToolUse("call_1", "t", JsonObject(emptyMap())))),
            Message(Role.USER, listOf(Block.Result("call_1", "ok"))),
        )
        val request = ModelRequest("claude-opus-5-5", listOf(SystemBlock("sys", cacheBreakpoint = true)), wireOrder(sinceSummary(history)), emptyList(), 1_000, "medium")
        // On the last user message before the last assistant turn (q2), as on any Anthropic thread.
        request.messageBreakpoint shouldBe (3 to 0)
        val body = AnthropicModelGateway.bodyJson(AnthropicModelGateway.buildParams(request))
        body.toString() shouldNotContain "openrouter_reasoning"
        body.toString() shouldNotContain "hmm"
        val assistant = body["messages"]!!.jsonArray[2].jsonObject["content"]!!.jsonArray
        assistant.map { it.jsonObject["type"]!!.jsonPrimitive.content } shouldContainExactly listOf("text")
        body["messages"]!!.jsonArray[3].jsonObject["content"]!!.jsonArray.single().jsonObject.containsKey("cache_control") shouldBe true
    }

    test("effort is applied per request: the last effort message, else the request's") {
        fun effortOf(vararg messages: Message) = OpenRouterModelGateway.buildBody(request("a/b", *messages))["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content
        effortOf(Message.user("hi")) shouldBe "medium"
        effortOf(Message.user("hi"), Message.effort("low")) shouldBe "low"
        effortOf(Message.user("hi"), Message.effort("low"), Message(Role.ASSISTANT, listOf(Block.Text("ok"))), Message.user("bye"), Message.effort("medium")) shouldBe "medium"
        // Effort messages before the latest compaction point no longer count.
        effortOf(Message.user("hi"), Message.effort("low"), Message(Role.ASSISTANT, listOf(Block.Text("ok"))), Message.summary("gist")) shouldBe "medium"
    }

    test("reasoning_details are kept from the stream, tagged, and replayed to the same model only") {
        val acc = OpenRouterModelGateway.StreamAccumulator("deepseek/r2")
        acc.accept("""{"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"Let me ","index":0,"format":"x"}]}}]}""")
        acc.accept("""{"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"think","index":0,"signature":"s1"}]}}]}""")
        acc.accept("""{"choices":[{"delta":{"content":"Answer"},"finish_reason":"stop"}]}""")
        val response = acc.response()
        val raw = response.content.first() as Block.Raw
        raw.type shouldBe OpenRouterModelGateway.REASONING_TYPE
        raw.foreignProvider shouldBe "openrouter"
        raw.json["model"] shouldBe JsonPrimitive("deepseek/r2")
        raw.json["details"] shouldBe buildJsonArray {
            add(buildJsonObject { put("type", "reasoning.text"); put("text", "Let me think"); put("index", 0); put("format", "x"); put("signature", "s1") })
        }
        response.content[1] shouldBe Block.Text("Answer")

        val turn = Message(Role.ASSISTANT, journalStable(response.content))
        val same = wire(request("deepseek/r2", Message.user("q"), turn, Message.user("more")))
        same[2]["reasoning_details"] shouldBe raw.json["details"]
        val other = wire(request("openai/gpt-5", Message.user("q"), turn, Message.user("more")))
        other[2].containsKey("reasoning_details") shouldBe false

        // Never to Anthropic.
        val anthropic = AnthropicModelGateway.bodyJson(AnthropicModelGateway.buildParams(request("claude-opus-5-5", Message.user("q"), turn, Message.user("more"))))
        anthropic.toString() shouldNotContain "reasoning"

        // Reasoning alone (no answer) is not kept, so no assistant turn is reasoning only.
        val alone = OpenRouterModelGateway.StreamAccumulator("deepseek/r2")
        alone.accept("""{"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"..."}]},"finish_reason":"length"}]}""")
        alone.response().content.shouldBeEmpty()
    }

    test("no cache-miss events without a cache (OpenRouter); still raised with one (Anthropic)") {
        fun script() = ScriptedGateway(
            listOf(
                response(StopReason.TOOL_USE, toolUse("c1", "t"), usage = Usage(1_000, 10)),
                response(StopReason.END_TURN, Block.Text("done"), usage = Usage(1_100, 10)),
            ),
        )
        val tool = fakeTool("t")
        val uncached = Harness(script()).run(RunRequest("a/b", listOf(SystemBlock("s")), listOf(Message.user("go")), listOf(tool), cache = null)).toList()
        uncached.filterIsInstance<HarnessEvent.CacheMiss>().shouldBeEmpty()
        uncached.filterIsInstance<HarnessEvent.Called>().map { it.usage.promptTokens } shouldContainExactly listOf(1_000L, 1_100L)
        val cached = Harness(script()).run(RunRequest("m", listOf(SystemBlock("s")), listOf(Message.user("go")), listOf(tool))).toList()
        cached.filterIsInstance<HarnessEvent.CacheMiss>() shouldHaveSize 1
    }
})
