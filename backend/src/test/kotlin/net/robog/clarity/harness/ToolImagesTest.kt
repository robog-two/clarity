package net.robog.clarity.harness

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val schema = buildJsonObject {}

private fun toolCall(id: String) = Message(Role.ASSISTANT, listOf(Block.ToolUse(id, "view_image", buildJsonObject {})))

private fun shown(id: String) = Message(Role.USER, listOf(Block.Result(id, "ok"), Block.Image(id, "image/png", 3)))

private val PICTURE = ToolImage("image/png", "QUJD")

private fun allPictures(messages: List<Message>) =
    messages.flatMap { it.content }.filterIsInstance<Block.Image>().associate { it.toolUseId to PICTURE }

private fun body(
    vararg messages: Message,
    model: String = "openai/gpt-5",
    note: String? = null,
    pictures: Map<String, ToolImage> = allPictures(messages.toList()),
) = OpenRouterModelGateway.buildBody(
    ModelRequest(model, "sys", messages.toList(), listOf(ToolDefinition("view_image", "d", schema)), 1_000, null),
    imageNote = note,
    pictures = pictures,
).getValue("messages").jsonArray

/** The wire messages after the system one, as (role, part types or null for a plain string). */
private fun shape(messages: JsonArray) = messages.drop(1).map { m ->
    val o = m.jsonObject
    o["role"]!!.jsonPrimitive.content to (o["content"] as? JsonArray)?.map { it.jsonObject["type"]!!.jsonPrimitive.content }
}

private fun texts(message: kotlinx.serialization.json.JsonElement) =
    message.jsonObject["content"]!!.jsonArray.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }

class ToolImagesTest : FunSpec({
    test("the tool message stays text; the picture follows in a user message with image_url parts") {
        val wire = body(Message.user("go"), toolCall("c1"), shown("c1"))
        shape(wire) shouldContainExactly listOf("user" to null, "assistant" to null, "tool" to null, "user" to listOf("text", "image_url"))
        wire[3].jsonObject["content"]!!.jsonPrimitive.content shouldBe "ok"
        val parts = wire[4].jsonObject["content"]!!.jsonArray
        parts[0].jsonObject["text"]!!.jsonPrimitive.content shouldContain "c1"
        parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content shouldBe "data:image/png;base64,QUJD"
    }

    test("a text that follows joins the picture's user message, pictures first") {
        val withText = Message(Role.USER, listOf(Block.Result("c1", "ok"), Block.Image("c1", "image/png", 3), Block.Text("note")))
        shape(body(Message.user("go"), toolCall("c1"), withText)).last() shouldBe ("user" to listOf("text", "image_url", "text"))
    }

    test("two pictures of parallel calls come after both tool messages") {
        val both = Message(Role.USER, listOf(Block.Result("a", "1"), Block.Result("b", "2"), Block.Image("a", "image/png", 3), Block.Image("b", "image/jpeg", 3)))
        val assistant = Message(Role.ASSISTANT, listOf(Block.ToolUse("a", "view_image", buildJsonObject {}), Block.ToolUse("b", "view_image", buildJsonObject {})))
        val wire = body(Message.user("go"), assistant, both)
        shape(wire).map { it.first } shouldContainExactly listOf("user", "assistant", "tool", "tool", "user")
        shape(wire).last().second shouldBe listOf("text", "image_url", "text", "image_url")
    }

    test("only the newest two pictures are sent; older ones are a fixed note, by position, so a replay is the same") {
        val messages = mutableListOf(Message.user("go"))
        for (i in 1..6) messages += listOf(toolCall("c$i"), shown("c$i"))
        val wire = body(*messages.toTypedArray())
        val users = wire.filter { it.jsonObject["role"]!!.jsonPrimitive.content == "user" }.drop(1)
        users.map { u -> u.jsonObject["content"]!!.jsonArray.any { it.jsonObject["type"]!!.jsonPrimitive.content == "image_url" } } shouldContainExactly
            listOf(false, false, false, false, true, true)
        texts(users[0]) shouldBe listOf(IMAGE_OMITTED)
        keptImages(messages) shouldBe setOf(10 to 1, 12 to 1)
        body(*messages.toTypedArray()).toString() shouldBe wire.toString()
    }

    test("a picture whose bytes are gone is a fixed note") {
        val wire = body(Message.user("go"), toolCall("c1"), shown("c1"), pictures = emptyMap())
        texts(wire.last()) shouldBe listOf(IMAGE_EXPIRED)
    }

    test("a note given for the whole request replaces every picture") {
        val wire = body(Message.user("go"), toolCall("c1"), shown("c1"), note = IMAGE_UNSUPPORTED)
        texts(wire.last()) shouldBe listOf(IMAGE_UNSUPPORTED)
    }

    test("what a provider says about pictures: no image input, or one bad picture, or something else") {
        fun r(status: Int?, message: String) = OpenRouterModelGateway.imageRefusal(status, message)
        r(400, "No endpoints found that support image input") shouldBe ImageRefusal.UNSUPPORTED
        r(404, "This model does not support image input") shouldBe ImageRefusal.UNSUPPORTED
        r(400, "input_modalities of the model do not include image") shouldBe ImageRefusal.UNSUPPORTED
        r(400, "No endpoints found for this model that support image input") shouldBe ImageRefusal.UNSUPPORTED
        r(400, "This model doesn't support images") shouldBe ImageRefusal.UNSUPPORTED
        r(400, "image format not supported") shouldBe ImageRefusal.REJECTED
        r(415, "Unsupported image type") shouldBe ImageRefusal.REJECTED
        r(400, "multimodal input is not supported") shouldBe ImageRefusal.UNSUPPORTED
        r(400, "Image input not supported for this model") shouldBe ImageRefusal.UNSUPPORTED
        r(400, "This model does not support vision") shouldBe ImageRefusal.UNSUPPORTED
        r(400, "model does not accept images") shouldBe ImageRefusal.UNSUPPORTED
        r(400, "does not support multimodal input") shouldBe ImageRefusal.UNSUPPORTED
        r(400, "vision is not supported") shouldBe ImageRefusal.UNSUPPORTED
        r(400, "Invalid image: the file could not be decoded") shouldBe ImageRefusal.REJECTED
        r(413, "image too large") shouldBe ImageRefusal.REJECTED
        r(400, "invalid tool schema") shouldBe null
        r(500, "image decoding failed") shouldBe null
        r(null, "image") shouldBe null
    }

    test("a model's no-image answer is believed for an hour, by the injected clock") {
        var now = kotlin.time.Instant.parse("2026-10-26T12:00:00Z")
        val clock = object : kotlin.time.Clock { override fun now() = now }
        val memory = TimedMemory(clock, kotlin.time.Duration.parse("1h"))
        memory.knows("m") shouldBe false
        memory.remember("m")
        now += kotlin.time.Duration.parse("59m")
        memory.knows("m") shouldBe true
        now += kotlin.time.Duration.parse("2m")
        memory.knows("m") shouldBe false
    }

    test("the cache breakpoint ignores trailing pictures") {
        val messages = listOf(Message.user("go"), toolCall("c1"), shown("c1"), Message(Role.ASSISTANT, listOf(Block.Text("seen"))), Message.user("next"))
        messageCacheBreakpoint(messages) shouldBe (2 to 0)
    }

    test("the journal keeps a reference to a picture, never its bytes") {
        val message = Message(Role.USER, listOf(Block.Result("c1", "ok"), Block.Image("c1", "image/png", 3)))
        val json = HarnessJson.encodeToString(Message.serializer(), message)
        json shouldNotContain "QUJD"
        HarnessJson.decodeFromString(Message.serializer(), json) shouldBe message
    }

    test("the harness keeps the picture in the store, journals only the reference after the results, and the next call can read it") {
        val viewer = viewerTool(runs = null)
        val other = fakeTool("other")
        val gateway = ScriptedGateway(
            listOf(
                response(StopReason.TOOL_USE, toolUse("1", "view_image"), toolUse("2", "other")),
                response(StopReason.END_TURN, Block.Text("done")),
            ),
        )
        val store = InMemoryToolImageStore()
        val journal = InMemoryRunJournal()
        val request = RunRequest("m", listOf(SystemBlock("system")), listOf(Message.user("go")), listOf(viewer, other), ToolPolicy.Default, 5, runId = "run-1")
        val events = Harness(gateway, journal = journal, images = store).run(request).toList()
        events.last().shouldBeInstanceOf<HarnessEvent.Finished>()
        gateway.requests[1].messages.last().content shouldContainExactly
            listOf(Block.Result("1", "looked"), Block.Result("2", "other ok"), Block.Image("1", "image/png", 3))
        store.load("run-1", "1") shouldBe PICTURE
        HarnessJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(Message.serializer()), journal.load("run-1")) shouldNotContain "QUJD"
    }

    test("a store that fails does not end the run: the result carries a note and the block shows as expired") {
        val broken = object : ToolImageStore {
            override suspend fun save(runId: String, toolUseId: String, image: ToolImage) = throw java.io.IOException("disk full")
            override suspend fun load(runId: String, toolUseId: String): ToolImage? = null
        }
        val gateway = ScriptedGateway(listOf(response(StopReason.TOOL_USE, toolUse("1", "view_image")), response(StopReason.END_TURN, Block.Text("done"))))
        val request = RunRequest("m", listOf(SystemBlock("system")), listOf(Message.user("go")), listOf(viewerTool(runs = null)), ToolPolicy.Default, 5, runId = "run-1")
        Harness(gateway, journal = InMemoryRunJournal(), images = broken).run(request).toList().last().shouldBeInstanceOf<HarnessEvent.Finished>()
        gateway.requests[1].messages.last().content shouldContainExactly
            listOf(Block.Result("1", "looked" + IMAGE_NOT_KEPT), Block.Image("1", "image/png", 3))
    }

    test("a call that shows a picture runs again on resume instead of being answered from the ledger") {
        val runs = mutableListOf<Int>()
        val viewer = viewerTool(runs)
        val plain = fakeTool("plain") { runs += 99; ToolResult.Ok("plain ok") }
        val ledger = InMemoryToolLedger()
        ledger.record("run-1", "1", LedgerEntry("view_image", "looked", false))
        ledger.record("run-1", "2", LedgerEntry("plain", "plain from ledger", false))
        val journal = InMemoryRunJournal()
        journal.append("run-1", Message.user("go"))
        journal.append("run-1", Message(Role.ASSISTANT, listOf(toolUse("1", "view_image"), toolUse("2", "plain"))))
        val gateway = ScriptedGateway(listOf(response(StopReason.END_TURN, Block.Text("done"))))
        val store = InMemoryToolImageStore()
        val request = RunRequest("m", listOf(SystemBlock("system")), listOf(Message.user("go")), listOf(viewer, plain), ToolPolicy.Default, 5, runId = "run-1")
        Harness(gateway, journal = journal, ledger = ledger, images = store).resume("run-1", request).toList().last().shouldBeInstanceOf<HarnessEvent.Finished>()
        runs shouldBe listOf(1) // the viewer ran again, the plain tool did not
        gateway.requests.single().messages.last().content shouldContainExactly
            listOf(Block.Result("1", "looked"), Block.Result("2", "plain from ledger"), Block.Image("1", "image/png", 3))
        store.load("run-1", "1") shouldBe PICTURE
    }

    test("a model that refuses pictures is remembered; one bad picture gets a note once; other errors stay errors") {
        val seen = CopyOnWriteArrayList<String>()
        val reply = java.util.concurrent.atomic.AtomicReference("no-input")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.readAllBytes().decodeToString()
                seen += body
                try {
                    when {
                        "image_url" in body && reply.get() == "no-input" -> ex.answer(400, """{"error":{"message":"No endpoints found that support image input"}}""")
                        "image_url" in body && reply.get() == "bad" -> ex.answer(400, """{"error":{"message":"Invalid image: could not decode"}}""")
                        "image_url" in body && reply.get() == "other" -> ex.answer(400, """{"error":{"message":"invalid tool schema"}}""")
                        else -> ex.sse()
                    }
                } finally { ex.close() }
            }
            start()
        }
        try {
            val store = InMemoryToolImageStore().also { kotlinx.coroutines.runBlocking { it.save("run-9", "c1", PICTURE) } }
            val gateway = OpenRouterModelGateway("k", "http://127.0.0.1:${server.address.port}/api/v1", retryDelays = listOf(10.milliseconds), images = store)
            val request = ModelRequest("vendor/blind", "sys", listOf(Message.user("go"), toolCall("c1"), shown("c1")), emptyList(), 1_000, null)
            withContext(CurrentRun("run-9")) { gateway.generate(request) {} }
            seen.size shouldBe 2
            seen[1] shouldContain IMAGE_UNSUPPORTED.take(30)
            seen[1] shouldNotContain "image_url"
            withContext(CurrentRun("run-9")) { gateway.generate(request) {} }
            seen.size shouldBe 3 // remembered: no failed call first
            seen[2] shouldNotContain "image_url"

            reply.set("bad")
            seen.clear()
            withContext(CurrentRun("run-9")) { gateway.generate(request.copy(model = "vendor/other")) {} }
            seen.size shouldBe 2
            seen[1] shouldContain "provider refused it"
            reply.set("other")
            seen.clear()
            val failure = runCatching { withContext(CurrentRun("run-9")) { gateway.generate(request.copy(model = "vendor/third")) {} } }.exceptionOrNull()
            failure.shouldBeInstanceOf<OpenRouterException>()
            seen.size shouldBe 1
        } finally {
            server.stop(0)
        }
    }
})

private fun HttpExchange.answer(status: Int, text: String) {
    val bytes = text.toByteArray()
    responseHeaders.add("content-type", "application/json")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun HttpExchange.sse() {
    responseHeaders.add("content-type", "text/event-stream")
    sendResponseHeaders(200, 0)
    responseBody.use {
        it.write("""data: {"id":"g","choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}""".toByteArray())
        it.write("\n\ndata: [DONE]\n\n".toByteArray())
    }
}

/** A `view_image` that shows [PICTURE]; records each run in [runs] when given. */
private fun viewerTool(runs: MutableList<Int>?): Tool = object : Tool {
    override val name = "view_image"
    override val description = "fake"
    override val inputSchema = buildJsonObject {}
    override val effect = ToolEffect.READ
    override val returnsImages = true
    override suspend fun invoke(input: kotlinx.serialization.json.JsonObject): ToolResult {
        runs?.add(1)
        return ToolResult.Ok("looked", show = listOf(PICTURE))
    }
}
