package net.robog.clarity.harness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Journals written while the lead ran on the direct Anthropic API hold Anthropic's own blocks
 * (thinking with signatures, redacted thinking, tool search, server-side compaction). They stay in
 * Postgres as they are; these fixtures are shaped like those rows (jsonb key order included) and
 * check they are read at request time: a compaction block is a compaction point whose summary is
 * sent, everything else Anthropic-only is left out, and the tool calls around them still pair up.
 */
class StoredAnthropicHistoryTest : FunSpec({
    // One journal row per line, as `agent_journal.message` stores them (jsonb: shorter keys first).
    val rows = listOf(
        """{"role":"USER","content":[{"text":"[inbox, now 2026-10-20T08:00-04:00 (Tue)] Move my dentist appointment to Friday","type":"text"}]}""",
        """{"role":"ASSISTANT","content":[{"json":{"type":"thinking","thinking":"The person wants the dentist moved.","signature":"EqQBCkYIBxgCKkBv0J3Zx2sigsig"},"type":"raw"},{"json":{"id":"srvtoolu_01AbC","name":"tool_search_tool_bm25","type":"server_tool_use","input":{"query":"move calendar event"}},"type":"raw"},{"json":{"type":"tool_search_tool_result","content":{"type":"tool_search_tool_search_result","tool_references":[{"type":"tool_reference","tool_name":"move_event"}]},"tool_use_id":"srvtoolu_01AbC"},"type":"raw"},{"id":"toolu_01XyZ","name":"move_event","type":"tool_use","input":{"to":"2026-10-23T10:00","id":"evt_1"}}]}""",
        """{"role":"USER","content":[{"type":"tool_result","content":"moved","isError":false,"toolUseId":"toolu_01XyZ"}]}""",
        """{"role":"ASSISTANT","content":[{"json":{"data":"EmwKAhgBEgy3redacted","type":"redacted_thinking"},"type":"raw"},{"text":"Done: Friday 10:00.","type":"text"}]}""",
        """{"role":"USER","content":[{"text":"thanks. also, what do I usually drink in the morning?","type":"text"}]}""",
        """{"role":"ASSISTANT","content":[{"json":{"type":"compaction","content":"The person moved the dentist to Friday 10:00. They drink green tea every morning."},"type":"raw"},{"json":{"type":"thinking","thinking":"From the summary: tea.","signature":"EqQBsig2"},"type":"raw"},{"id":"toolu_02","name":"send_to_user","type":"tool_use","input":{"text":"Green tea."}}]}""",
        """{"role":"USER","content":[{"type":"tool_result","content":"sent","isError":false,"toolUseId":"toolu_02"}]}""",
        """{"role":"SYSTEM","effort":"low","content":[]}""",
        """{"role":"USER","content":[{"text":"great","type":"text"}]}""",
    )
    val history = rows.map { HarnessJson.decodeFromString(Message.serializer(), it) }

    test("stored rows decode: Anthropic blocks as raw blocks, the compaction block recognised with its summary") {
        history[1].content.filterIsInstance<Block.Raw>().map { it.type } shouldContainExactly
            listOf("thinking", "server_tool_use", "tool_search_tool_result")
        val compaction = history[5].content.first() as Block.Raw
        compaction.isCompaction shouldBe true
        compaction.compactionSummary shouldBe "The person moved the dentist to Friday 10:00. They drink green tea every morning."
        compaction.foreignProvider shouldBe null
        history[5].isCompactionPoint shouldBe true
        history[7] shouldBe Message.effort("low")
    }

    test("contextSince starts at the stored compaction block, and so does the request") {
        contextSince(history) shouldContainExactly history.drop(5)
        val request = ModelRequest(
            "xiaomi/mimo-v2.6-pro", listOf(SystemBlock("persona", cacheBreakpoint = true)), wireOrder(sinceSummary(history)),
            listOf(ToolDefinition("send_to_user", "Send", kotlinx.serialization.json.buildJsonObject { put("type", JsonPrimitive("object")) })),
            4_000, "medium",
        )
        val body = OpenRouterModelGateway.buildBody(request)
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        messages.map { it["role"]!!.jsonPrimitive.content } shouldContainExactly listOf("system", "user", "assistant", "tool", "user")
        messages[1]["content"]!!.jsonPrimitive.content shouldBe
            "$SUMMARY_MARKER\nThe person moved the dentist to Friday 10:00. They drink green tea every morning."
        // The tool call after the compaction block keeps its result right behind it.
        messages[2]["tool_calls"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content shouldBe "toolu_02"
        messages[3]["tool_call_id"]!!.jsonPrimitive.content shouldBe "toolu_02"
        messages[4]["content"]!!.jsonPrimitive.content shouldBe "great"
        // The stored effort message still sets the effort.
        body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content shouldBe "low"
        // Nothing Anthropic-only reaches the wire, nor anything from before the compaction.
        val wire = body.toString()
        for (gone in listOf("signature", "EqQB", "redacted", "tool_search", "srvtoolu", "dentist appointment", "toolu_01XyZ")) wire shouldNotContain gone
    }

    test("before any compaction: Anthropic blocks are left out and tool calls still pair with their results") {
        val early = history.take(5)
        val body = OpenRouterModelGateway.buildBody(ModelRequest("anthropic/claude-opus-5.5", "persona", early, emptyList(), 4_000, "medium"))
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        messages.map { it["role"]!!.jsonPrimitive.content } shouldContainExactly listOf("system", "user", "assistant", "tool", "assistant", "user")
        messages[2]["tool_calls"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content shouldBe "move_event"
        messages[4]["content"]!!.jsonPrimitive.content shouldBe "Done: Friday 10:00."
        val wire = body.toString()
        for (gone in listOf("signature", "redacted_thinking", "tool_search_tool_result", "server_tool_use")) wire shouldNotContain gone
    }

    test("an encrypted-only stored compaction block still cuts, with the plain note") {
        val encrypted = HarnessJson.decodeFromString(
            Message.serializer(),
            """{"role":"ASSISTANT","content":[{"json":{"type":"compaction","encrypted_content":"Eo8BCkYIBxgC"},"type":"raw"},{"text":"ok","type":"text"}]}""",
        )
        val body = OpenRouterModelGateway.buildBody(ModelRequest("a/b", "persona", listOf(Message.user("old"), encrypted, Message.user("hi")), emptyList(), 1_000, null))
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        messages[1]["content"]!!.jsonPrimitive.content shouldBe "$SUMMARY_MARKER\n${OpenRouterModelGateway.UNREADABLE_SUMMARY}"
        body.toString() shouldNotContain "old"
    }
})
