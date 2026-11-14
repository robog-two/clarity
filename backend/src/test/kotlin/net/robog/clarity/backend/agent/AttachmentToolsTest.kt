package net.robog.clarity.backend.agent

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Role
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.robog.clarity.backend.attachments.Attachment
import net.robog.clarity.backend.attachments.AttachmentSource
import net.robog.clarity.backend.attachments.FetchError
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.harness.Access
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolPolicy
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.noun.AttachmentMeta

private fun png(width: Int, height: Int): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until width step 7) for (y in 0 until height) image.setRGB(x, y, (x * 31 + y * 17) and 0xFFFFFF)
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}

private fun jpeg(width: Int, height: Int): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until width step 5) for (y in 0 until height) image.setRGB(x, y, (x * 13 + y * 7) and 0xFFFFFF)
    return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
}

private fun sizeOf(image: net.robog.clarity.harness.ToolImage): Pair<Int, Int> {
    val read = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(image.base64)))
    return read.width to read.height
}

class ImageInputTest : FunSpec({
    test("the format comes from the first bytes, not the name") {
        imageKind(png(4, 4)) shouldBe ImageKind.PNG
        imageKind(jpeg(8, 8)) shouldBe ImageKind.JPEG
        imageKind("GIF89a....".toByteArray()) shouldBe ImageKind.GIF
        imageKind("RIFF\u0001\u0000\u0000\u0000WEBPVP8 ".toByteArray()) shouldBe ImageKind.WEBP
        imageKind("%PDF-1.4".toByteArray()) shouldBe null
        imageKind(ByteArray(0)) shouldBe null
        imageKind("<svg></svg>".toByteArray()) shouldBe null
    }

    test("scaling keeps proportions, only shrinks, and never reaches zero") {
        scaledSize(800, 600) shouldBe (800 to 600)
        scaledSize(3136, 1568) shouldBe (1568 to 784)
        scaledSize(1000, 4704) shouldBe (333 to 1568)
        scaledSize(10_000, 1) shouldBe (1568 to 1)
        scaledSize(1568, 1568) shouldBe (1568 to 1568)
    }

    test("a picture that fits is sent as it is") {
        val bytes = png(400, 300)
        val prepared = prepareImage(bytes).getOrNull()!!
        prepared.image.mimeType shouldBe "image/png"
        Base64.getDecoder().decode(prepared.image.base64).toList() shouldBe bytes.toList()
        prepared.width to prepared.height shouldBe (400 to 300)
        prepared.scaledFrom shouldBe null
    }

    test("a big picture is scaled down to the limit and says so") {
        val prepared = prepareImage(png(3200, 2000)).getOrNull()!!
        sizeOf(prepared.image) shouldBe (1568 to 980)
        prepared.scaledFrom shouldBe (3200 to 2000)
        prepared.width to prepared.height shouldBe (1568 to 980)
        val jpg = prepareImage(jpeg(2400, 1600)).getOrNull()!!
        jpg.image.mimeType shouldBe "image/jpeg"
        sizeOf(jpg.image) shouldBe (1568 to 1045)
    }

    test("what is not a picture, a damaged picture and a huge file are refused with advice") {
        prepareImage("%PDF-1.4 text".toByteArray()).leftOrNull()!! shouldContain "not a PNG"
        prepareImage(png(10, 10).copyOf(40)).leftOrNull()!! shouldContain "could not be read"
        prepareImage(ByteArray((MAX_IMAGE_FILE_BYTES + 1).toInt())).leftOrNull()!! shouldContain "too large"
    }

    test("WebP and GIF are passed through when small, a big WebP is refused") {
        val webp = "RIFF\u0001\u0000\u0000\u0000WEBPVP8 ".toByteArray() + ByteArray(100)
        prepareImage(webp).getOrNull()!!.image.mimeType shouldBe "image/webp"
        prepareImage(ByteArray(MAX_SENT_IMAGE_BYTES + 1).also { "RIFF\u0001\u0000\u0000\u0000WEBP".toByteArray().copyInto(it) }).leftOrNull()!! shouldContain "WebP"
    }
})

class ModelCapabilitiesTest : FunSpec({
    val listing = """
        {"data":[
          {"id":"openai/gpt-5","architecture":{"input_modalities":["text","image"],"output_modalities":["text"]}},
          {"id":"vendor/text-only","architecture":{"input_modalities":["text"]}},
          {"id":"vendor/no-architecture"},
          {"id":"vendor/m:free","architecture":{"input_modalities":["text","image","file"]}}
        ]}
    """.trimIndent()

    test("the list says which models take images; entries it does not describe are left out") {
        parseImageSupport(listing) shouldBe mapOf("openai/gpt-5" to true, "vendor/text-only" to false, "vendor/m:free" to true)
        parseImageSupport("not json") shouldBe emptyMap()
        parseImageSupport("""{"data":"x"}""") shouldBe emptyMap()
    }

    test("an id is matched without the latest-alias tilde and the variant suffix") {
        val known = parseImageSupport(listing)
        imageSupportOf(known, "openai/gpt-5") shouldBe true
        imageSupportOf(known, "~openai/gpt-5") shouldBe true
        imageSupportOf(known, "openai/gpt-5:nitro") shouldBe true
        imageSupportOf(known, "vendor/text-only:free") shouldBe false
        imageSupportOf(known, "vendor/m:free") shouldBe true
        imageSupportOf(known, "somebody/else") shouldBe null
    }

    test("a run that starts while the list is downloading does not wait for it") {
        runBlocking {
            val release = kotlinx.coroutines.CompletableDeferred<String?>()
            val capabilities = OpenRouterModelCapabilities({ release.await() })
            val first = async { capabilities.acceptsImages("openai/gpt-5") }
            delay(100) // the first is inside the download now
            withTimeout(2_000) { capabilities.acceptsImages("openai/gpt-5") } shouldBe null // unknown for now, and not blocked
            release.complete(listing)
            first.await() shouldBe true
            capabilities.acceptsImages("vendor/text-only") shouldBe false
        }
    }

    test("the list is fetched once and kept; a failed fetch is tried again later") {
        runTest {
            var now = Instant.parse("2026-11-13T12:00:00Z")
            val clock = object : kotlin.time.Clock { override fun now() = now }
            var loads = 0
            var answer: String? = null
            val capabilities = OpenRouterModelCapabilities({ loads++; answer }, clock, ttl = 24.hours, retryAfter = 5.minutes)
            capabilities.acceptsImages("openai/gpt-5") shouldBe null
            capabilities.acceptsImages("openai/gpt-5") shouldBe null
            loads shouldBe 1 // not asked again within the retry wait
            now += 6.minutes
            answer = listing
            capabilities.acceptsImages("openai/gpt-5") shouldBe true
            capabilities.acceptsImages("vendor/text-only") shouldBe false
            loads shouldBe 2
            now += 23.hours
            capabilities.acceptsImages("openai/gpt-5") shouldBe true
            loads shouldBe 2
            now += 2.hours
            answer = null // the refresh fails: what is known stays, as it was
            capabilities.acceptsImages("openai/gpt-5") shouldBe true
            loads shouldBe 3
        }
    }
})

private class FakeSource(
    val files: Map<String, Attachment> = emptyMap(),
    val lists: Map<String, List<AttachmentMeta>> = emptyMap(),
) : AttachmentSource {
    override suspend fun fetch(entityId: EntityId, part: String): Either<FetchError, Attachment> =
        files["${entityId.value}/$part"]?.right() ?: FetchError.NotFound("The message has no attachment $part.").left()

    override suspend fun list(entityId: EntityId): Either<FetchError, List<AttachmentMeta>> =
        lists[entityId.value]?.right() ?: FetchError.NotFound("No such message.").left()
}

/** A sandbox tool that records its input and answers [answer]. */
private class FakeSandboxTool(override val name: String, val answer: (JsonObject) -> ToolResult = { ToolResult.Ok("done") }) : Tool {
    val calls = mutableListOf<JsonObject>()
    override val description = "fake"
    override val inputSchema = buildJsonObject { put("type", "object") }
    override val effect = ToolEffect.READ
    override suspend fun invoke(input: JsonObject): ToolResult { calls += input; return answer(input) }
}

private fun toolsFor(source: AttachmentSource, supportsImages: Boolean?) = AttachmentTools(
    source,
    ModelSettings(AgentConfig(reviewInterval = 2.hours), InMemoryRoleOverrideStore(kotlin.time.Clock.System)),
    ModelCapabilities { supportsImages },
)

class AttachmentToolsTest : FunSpec({
    val id = "0f3a9c1e-1111-2222-3333-444455556666"
    val pdf = Attachment("%PDF-1.4".toByteArray(), "report.pdf", "application/pdf")
    val source = FakeSource(files = mapOf("$id/2" to pdf), lists = mapOf(id to listOf(AttachmentMeta("2", "report.pdf", "application/pdf", 8))))

    test("the tools exist only where the sandbox tools they need do, and view_image only for a model that is not known to refuse images") {
        runTest {
            val write = FakeSandboxTool("sandbox__write_file")
            val read = FakeSandboxTool("sandbox__read_file_base64")
            toolsFor(source, true).all(listOf(write, read)).map { it.name } shouldContainExactly listOf(SAVE_ATTACHMENT, VIEW_IMAGE)
            toolsFor(source, null).all(listOf(write, read)).map { it.name } shouldContainExactly listOf(SAVE_ATTACHMENT, VIEW_IMAGE)
            toolsFor(source, false).all(listOf(write, read)).map { it.name } shouldContainExactly listOf(SAVE_ATTACHMENT)
            toolsFor(source, true).all(listOf(write)).map { it.name } shouldContainExactly listOf(SAVE_ATTACHMENT)
            toolsFor(source, true).all(listOf(read)).map { it.name } shouldContainExactly listOf(VIEW_IMAGE)
            toolsFor(source, true).all(emptyList()) shouldBe emptyList()
        }
    }

    test("save_attachment writes the bytes under attachments/<short id>/<name> and says where") {
        runTest {
            val write = FakeSandboxTool("sandbox__write_file")
            val save = toolsFor(source, true).all(listOf(write)).single()
            val result = save.invoke(buildJsonObject { put("messageId", id); put("part", "2") }).shouldBeInstanceOf<ToolResult.Ok>()
            val json = kotlinx.serialization.json.Json.parseToJsonElement(result.content).jsonObject
            json["path"]!!.jsonPrimitive.content shouldBe "/workspace/attachments/0f3a9c1e/report.pdf"
            json["type"]!!.jsonPrimitive.content shouldBe "application/pdf"
            json["size"]!!.jsonPrimitive.content shouldBe "8"
            write.calls.single()["path"]!!.jsonPrimitive.content shouldBe "attachments/0f3a9c1e/report.pdf"
            Base64.getDecoder().decode(write.calls.single()["content_base64"]!!.jsonPrimitive.content).decodeToString() shouldBe "%PDF-1.4"
        }
    }

    test("save_attachment without a part lists the attachments; a bad part, an unknown one and a failing sandbox are errors") {
        runTest {
            val write = FakeSandboxTool("sandbox__write_file")
            val save = toolsFor(source, true).all(listOf(write)).single()
            val listed = save.invoke(buildJsonObject { put("messageId", id) }).shouldBeInstanceOf<ToolResult.Ok>().content
            listed shouldContain "\"part\":\"2\""
            save.invoke(buildJsonObject { put("messageId", "nope") }).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "No such message"
            save.invoke(buildJsonObject { put("messageId", id); put("part", "../2") }).shouldBeInstanceOf<ToolResult.Error>()
            save.invoke(buildJsonObject { put("messageId", id); put("part", "*") }).shouldBeInstanceOf<ToolResult.Error>()
            save.invoke(buildJsonObject { put("messageId", id); put("part", "9") }).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "no attachment 9"
            write.calls.size shouldBe 0
            val broken = FakeSandboxTool("sandbox__write_file") { ToolResult.Error("disk full") }
            toolsFor(source, true).all(listOf(broken)).single().invoke(buildJsonObject { put("messageId", id); put("part", "2") })
                .shouldBeInstanceOf<ToolResult.Error>().message shouldContain "disk full"
        }
    }

    test("view_image reads the file from the sandbox and shows it, scaled when large") {
        runTest {
            val bytes = png(3000, 1500)
            val read = FakeSandboxTool("sandbox__read_file_base64") { ToolResult.Ok(Base64.getEncoder().encodeToString(bytes)) }
            val view = toolsFor(source, true).all(listOf(read)).single()
            val result = view.invoke(buildJsonObject { put("path", "/workspace/attachments/0f3a9c1e/scan.png") }).shouldBeInstanceOf<ToolResult.Ok>()
            result.content shouldContain "scaled down from 3000 x 1500"
            result.images shouldBe emptyList()
            sizeOf(result.show.single()) shouldBe (1568 to 784)
            read.calls.single()["path"]!!.jsonPrimitive.content shouldBe "attachments/0f3a9c1e/scan.png"
        }
    }

    test("view_image answers a file that is not a picture, or a sandbox error, with a tool error") {
        runTest {
            val notPicture = FakeSandboxTool("sandbox__read_file_base64") { ToolResult.Ok(Base64.getEncoder().encodeToString("%PDF-1.4".toByteArray())) }
            toolsFor(source, true).all(listOf(notPicture)).single().invoke(buildJsonObject { put("path", "a.pdf") })
                .shouldBeInstanceOf<ToolResult.Error>().message shouldContain "not a PNG"
            val missing = FakeSandboxTool("sandbox__read_file_base64") { ToolResult.Error("\"x.png\" does not exist.") }
            toolsFor(source, true).all(listOf(missing)).single().invoke(buildJsonObject { put("path", "x.png") })
                .shouldBeInstanceOf<ToolResult.Error>().message shouldContain "does not exist"
        }
    }

    test("the vision check is for the model the run uses: its role's") {
        runTest {
            val config = AgentConfig(reviewInterval = 2.hours)
            val settings = ModelSettings(config, InMemoryRoleOverrideStore(kotlin.time.Clock.System))
            val blind = settings[AgentRole.TRIAGE].model
            val asked = mutableListOf<String>()
            val tools = AttachmentTools(source, settings, ModelCapabilities { asked += it; it != blind })
            val read = FakeSandboxTool("sandbox__read_file_base64")
            tools.all(listOf(read), AgentRole.TRIAGE) shouldBe emptyList()
            asked.last() shouldBe blind
            if (settings[AgentRole.HELPER].model != blind) tools.all(listOf(read), AgentRole.HELPER).map { it.name } shouldContainExactly listOf(VIEW_IMAGE)
            asked.last() shouldBe settings[AgentRole.HELPER].model
        }
    }

    test("a call that shows a picture is not answered from the ledger on resume") {
        runTest {
            val read = FakeSandboxTool("sandbox__read_file_base64")
            toolsFor(source, true).all(listOf(read)).single().returnsImages shouldBe true
            toolsFor(source, true).all(listOf(FakeSandboxTool("sandbox__write_file"))).single().returnsImages shouldBe false
        }
    }

    test("a picture counts about 1,600 tokens however its reference looks") {
        val picture = Message(Role.USER, listOf(Block.Image("c1", "image/png", 5_000_000)))
        val text = estimateTokens(listOf(Message(Role.USER, listOf(Block.Text("hi")))))
        (estimateTokens(listOf(picture)) in 1_600L..1_700L) shouldBe true
        (estimateTokens(listOf(picture, picture)) in 3_200L..3_400L) shouldBe true
        val both = Message(Role.USER, listOf(Block.Text("hi"), Block.Image("c1", "image/png", 5_000_000)))
        (estimateTokens(listOf(both)) - text) shouldBe (estimateTokens(listOf(both)) - text).coerceIn(1_600L, 1_700L)
    }

    test("the message's attachment list hides inline parts unless they are pictures") {
        val list = listOf(
            AttachmentMeta("2", "report.pdf", "application/pdf", 10),
            AttachmentMeta("1.2.2", "logo.png", "image/png", 5, inline = true),
            AttachmentMeta("3", null, "text/calendar", null, inline = true),
        )
        val json = attachmentsJson(list).jsonArray
        json.map { it.jsonObject["part"]!!.jsonPrimitive.content } shouldContainExactly listOf("2", "1.2.2")
        json[0].jsonObject.keys shouldBe setOf("part", "name", "type", "size")
        attachmentsJson(emptyList()).jsonArray.size shouldBe 0
    }

    test("the model never sees the sandbox's base64 reader") {
        ToolPolicy.Default.accessFor(FakeSandboxTool("sandbox__read_file_base64")) shouldBe Access.HIDDEN
        ToolPolicy.Default.accessFor(FakeSandboxTool("sandbox__read_file")) shouldBe Access.ALLOW
        (SAVE_ATTACHMENT in UNTRUSTED_SOURCE_TOOLS && VIEW_IMAGE in UNTRUSTED_SOURCE_TOOLS) shouldBe true
        (SAVE_ATTACHMENT in HELPER_BASE && VIEW_IMAGE in HELPER_BASE) shouldBe true
    }
})
