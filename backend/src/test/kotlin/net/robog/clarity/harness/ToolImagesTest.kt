package net.robog.clarity.harness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val schema = buildJsonObject {}

private fun toolCall(id: String) = Message(Role.ASSISTANT, listOf(Block.ToolUse(id, "view_image", buildJsonObject {})))

private fun shown(id: String) = Message(Role.USER, listOf(Block.Result(id, "ok"), Block.Image(id, "image/png", "QUJD")))

private fun body(vararg messages: Message, model: String = "openai/gpt-5", showImages: Boolean = true) = OpenRouterModelGateway.buildBody(
    ModelRequest(model, "sys", messages.toList(), listOf(ToolDefinition("view_image", "d", schema)), 1_000, null),
    showImages = showImages,
).getValue("messages").jsonArray

/** The wire messages after the system one, as (role, part types or "string"). */
private fun shape(messages: JsonArray) = messages.drop(1).map { m ->
    val o = m.jsonObject
    o["role"]!!.jsonPrimitive.content to (o["content"] as? JsonArray)?.map { it.jsonObject["type"]!!.jsonPrimitive.content }
}

class ToolImagesTest : FunSpec({
    test("the tool message stays text; the picture follows in a user message with image_url parts") {
        val wire = body(Message.user("go"), toolCall("c1"), shown("c1"))
        shape(wire) shouldContainExactly listOf(
            "user" to null,
            "assistant" to null,
            "tool" to null,
            "user" to listOf("text", "image_url"),
        )
        val tool = wire[3].jsonObject
        tool["content"]!!.jsonPrimitive.content shouldBe "ok"
        val parts = wire[4].jsonObject["content"]!!.jsonArray
        parts[0].jsonObject["text"]!!.jsonPrimitive.content shouldContain "c1"
        parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content shouldBe "data:image/png;base64,QUJD"
    }

    test("a text that follows joins the picture's user message, pictures first") {
        val withText = Message(Role.USER, listOf(Block.Result("c1", "ok"), Block.Image("c1", "image/png", "QUJD"), Block.Text("note")))
        val wire = body(Message.user("go"), toolCall("c1"), withText)
        shape(wire).last() shouldBe ("user" to listOf("text", "image_url", "text"))
    }

    test("two pictures of parallel calls come after both tool messages") {
        val both = Message(
            Role.USER,
            listOf(Block.Result("a", "1"), Block.Result("b", "2"), Block.Image("a", "image/png", "QQ=="), Block.Image("b", "image/jpeg", "Qg==")),
        )
        val assistant = Message(Role.ASSISTANT, listOf(Block.ToolUse("a", "view_image", buildJsonObject {}), Block.ToolUse("b", "view_image", buildJsonObject {})))
        val wire = body(Message.user("go"), assistant, both)
        shape(wire).map { it.first } shouldContainExactly listOf("user", "assistant", "tool", "tool", "user")
        shape(wire).last().second shouldBe listOf("text", "image_url", "text", "image_url")
    }

    test("only the newest few pictures are sent; older ones are a note, and the choice is stable as the thread grows") {
        val messages = mutableListOf(Message.user("go"))
        for (i in 1..6) messages += listOf(toolCall("c$i"), shown("c$i"))
        val wire = body(*messages.toTypedArray())
        val notes = wire.filter { it.jsonObject["role"]!!.jsonPrimitive.content == "user" }.drop(1).map { m ->
            m.jsonObject["content"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        }
        notes.map { it.contains("image_url") } shouldContainExactly listOf(false, false, true, true, true, true)
        val omitted = wire.filter { it.jsonObject["role"]!!.jsonPrimitive.content == "user" }[1].jsonObject["content"]!!.jsonArray
        omitted.single().jsonObject["text"]!!.jsonPrimitive.content shouldBe IMAGE_OMITTED
        keptImages(messages) shouldBe setOf(6 to 1, 8 to 1, 10 to 1, 12 to 1)
    }

    test("a model that does not take images gets a note instead") {
        val wire = body(Message.user("go"), toolCall("c1"), shown("c1"), showImages = false)
        wire.last().jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content shouldBe IMAGE_UNSUPPORTED
    }

    test("a refusal that names images is told apart from other errors") {
        OpenRouterModelGateway.refusesImages(400, "No endpoints found that support image input") shouldBe true
        OpenRouterModelGateway.refusesImages(404, "model does not support multimodal") shouldBe true
        OpenRouterModelGateway.refusesImages(400, "invalid tool schema") shouldBe false
        OpenRouterModelGateway.refusesImages(500, "image decoding failed") shouldBe false
        OpenRouterModelGateway.refusesImages(null, "image") shouldBe false
        OpenRouterModelGateway.hasImages(listOf(Message.user("x"))) shouldBe false
        OpenRouterModelGateway.hasImages(listOf(shown("c"))) shouldBe true
    }

    test("the cache breakpoint ignores trailing pictures") {
        val messages = listOf(Message.user("go"), toolCall("c1"), shown("c1"), Message(Role.ASSISTANT, listOf(Block.Text("seen"))), Message.user("next"))
        messageCacheBreakpoint(messages) shouldBe (2 to 0)
    }

    test("pictures survive the journal as written and read back") {
        val message = shown("c1")
        val json = HarnessJson.encodeToString(Message.serializer(), message)
        HarnessJson.decodeFromString(Message.serializer(), json) shouldBe message
    }

    test("the harness journals a tool's shown pictures after its results and offers them to the next call") {
        val viewer = fakeTool("view_image") { ToolResult.Ok("looked", show = listOf(ToolImage("image/png", "QUJD"))) }
        val other = fakeTool("other")
        val gateway = ScriptedGateway(
            listOf(
                response(StopReason.TOOL_USE, toolUse("1", "view_image"), toolUse("2", "other")),
                response(StopReason.END_TURN, Block.Text("done")),
            ),
        )
        val journal = InMemoryRunJournal()
        val events = Harness(gateway, journal = journal).run(RunRequest("m", "system", listOf(Message.user("go")), listOf(viewer, other), ToolPolicy.Default, 5)).toList()
        events.last().shouldBeInstanceOf<HarnessEvent.Finished>()
        val results = gateway.requests[1].messages.last()
        results.content shouldContainExactly listOf(Block.Result("1", "looked"), Block.Result("2", "other ok"), Block.Image("1", "image/png", "QUJD"))
    }
})
