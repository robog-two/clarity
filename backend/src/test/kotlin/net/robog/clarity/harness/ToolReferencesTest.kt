package net.robog.clarity.harness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class ToolReferencesTest : FunSpec({
    test("tool references to tools no longer offered are dropped from the request") {
        val result = Json.parseToJsonElement(
            """{"type":"tool_search_tool_result","tool_use_id":"s1","content":{"type":"tool_search_tool_search_result","tool_references":[{"type":"tool_reference","tool_name":"move_event"},{"type":"tool_reference","tool_name":"browser__fill_by_uid"}]}}""",
        ) as JsonObject
        val messages = listOf(Message(Role.USER, listOf(Block.Text("hi"))), Message(Role.ASSISTANT, listOf(Block.Raw(result))))
        val cleaned = withoutMissingToolReferences(messages, setOf("move_event"))
        val names = ((cleaned[1].content.single() as Block.Raw).json["content"] as JsonObject)["tool_references"].toString()
        names shouldContain "move_event"
        ("browser__fill_by_uid" in names) shouldBe false
        withoutMissingToolReferences(messages, setOf("move_event", "browser__fill_by_uid")) shouldBe messages
        cleaned[0] shouldBe messages[0]
    }
})
