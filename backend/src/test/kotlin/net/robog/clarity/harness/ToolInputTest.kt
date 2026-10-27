package net.robog.clarity.harness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject

@Serializable
private data class Item(val text: String, val note: String = "")

@Serializable
private data class Group(val label: String, val items: List<Item>)

@Serializable
private data class Input(
    val main: Item? = null,
    val items: List<Item> = emptyList(),
    val groups: List<Group> = emptyList(),
    val raw: String = "",
)

private fun parse(text: String) = HarnessJson.parseToJsonElement(text).jsonObject

/** Runs a typed tool that echoes what it decoded, so a test can see the result. */
private suspend fun decode(input: String): ToolResult = tool<Input>("t", "test") { ToolResult.Ok(it.toString()) }.invoke(parse(input))

class ToolInputTest : FunSpec({

    test("a stringified object parameter decodes") {
        decode("""{"main": "{\"text\": \"Finish ML midterm\", \"note\": \"needs to be done\"}"}""") shouldBe
            ToolResult.Ok(Input(main = Item("Finish ML midterm", "needs to be done")).toString())
    }

    test("a stringified list parameter decodes, with surrounding whitespace") {
        decode("""{"items": "  [{\"text\": \"a\"}, {\"text\": \"b\"}]\n"}""") shouldBe
            ToolResult.Ok(Input(items = listOf(Item("a"), Item("b"))).toString())
    }

    test("stringified objects nested inside lists, and lists inside those, decode") {
        val input = """{"groups": [{"label": "g", "items": "[\"{\\\"text\\\": \\\"deep\\\"}\"]"}], "items": ["{\"text\": \"x\"}"]}"""
        decode(input) shouldBe
            ToolResult.Ok(Input(items = listOf(Item("x")), groups = listOf(Group("g", listOf(Item("deep"))))).toString())
    }

    test("a String field holding JSON text stays a string") {
        decode("""{"raw": "{\"text\": \"not an object\"}", "main": {"text": "[1, 2]"}}""") shouldBe
            ToolResult.Ok(Input(main = Item("[1, 2]"), raw = """{"text": "not an object"}""").toString())
    }

    test("an unparseable or wrong-shaped string still yields Invalid input") {
        decode("""{"main": "{\"text\": "}""").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "Invalid input for t"
        decode("""{"items": "{\"text\": \"x\"}"}""").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "Invalid input for t"
        decode("""{"main": "plain words"}""").shouldBeInstanceOf<ToolResult.Error>().message shouldContain "Invalid input for t"
    }

    test("the harness's schema check lets a stringified list through, but still rejects a wrong type") {
        val schema = tool<Input>("t", "test") { ToolResult.Ok("") }.inputSchema
        validateToolInput(schema, parse("""{"items": "[{\"text\": \"a\"}]"}""")) shouldBe null
        validateToolInput(schema, parse("""{"items": "a, b"}""")) shouldBe "parameter 'items' must be of type array"
        validateToolInput(schema, parse("""{"items": "{\"text\": \"a\"}"}""")) shouldBe "parameter 'items' must be of type array"
    }

    test("end to end: a stringified list argument reaches the tool decoded") {
        val echo = tool<Input>("t", "test") { ToolResult.Ok(it.items.joinToString { item -> item.text }) }
        val call = Block.ToolUse("1", "t", parse("""{"items": "[{\"text\": \"a\"}, {\"text\": \"b\"}]"}"""))
        val gateway = ScriptedGateway(
            listOf(response(StopReason.TOOL_USE, call), response(StopReason.END_TURN, Block.Text("done"))),
        )
        Harness(gateway).run(
            RunRequest("claude-opus-5-5", "system", listOf(Message.user("go")), listOf(echo), ToolPolicy.Default, 10),
        ).toList()
        val result = gateway.requests[1].messages.last().content.single().shouldBeInstanceOf<Block.Result>()
        result.isError shouldBe false
        result.content shouldBe "a, b"
    }
})
