package net.robog.clarity.backend.reports

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import net.robog.clarity.shared.report.ReportFormat
import net.robog.clarity.shared.report.ReportStyle

private val PNG_URL = "https://example.com/hare.png"

private fun ok(type: String, bytes: ByteArray) = ImageResponse(200, type, null, ByteArrayInputStream(bytes))
private fun redirect(to: String, status: Int = 302) = ImageResponse(status, null, to, ByteArrayInputStream(ByteArray(0)))

/** A transport over a fixed table of answers; records the addresses asked for. */
private class Table(private vararg val answers: Pair<String, () -> ImageResponse>) : ImageTransport {
    val asked = mutableListOf<String>()
    override fun get(uri: URI): ImageResponse {
        asked += uri.toString()
        return answers.firstOrNull { it.first == uri.toString() }?.second?.invoke() ?: ImageResponse(404, null, null, null)
    }
}

private fun fetched(outcome: ImageOutcome): FetchedImage = outcome.shouldBeInstanceOf<ImageOutcome.Fetched>().image
private fun failure(outcome: ImageOutcome): String = outcome.shouldBeInstanceOf<ImageOutcome.Failed>().reason

class ReportImageTest : FunSpec({
    test("the key is the lowercase hex SHA-256 of the trimmed address, as the renderer computes it") {
        imageKey(PNG_URL) shouldBe "640dbf8d7b514cf29975648c0ccb012c97a06cfd4ab3d8bb9b8486300fa826fc"
        imageKey("  $PNG_URL\n") shouldBe imageKey(PNG_URL)
        // the production shape: percent-escapes, plus signs and a query are hashed exactly as written
        imageKey("https://images.example.com/content/v1/abc/H+%26+B+2018-2865.jpg?format=1500w") shouldBe "ba93f912c631c761d413a288fbc03bbd98c70b1341b1e6822d60f32ea30a7b3d"
        extractImageUrls("![Hare & Bristle salon](https://images.example.com/content/v1/abc/H+%26+B+2018-2865.jpg?format=1500w)") shouldContainExactly listOf("https://images.example.com/content/v1/abc/H+%26+B+2018-2865.jpg?format=1500w")
        extractImageUrls("![Angle](<https://example.com/angle.png>)") shouldContainExactly listOf("https://example.com/angle.png")
        imageKey("https://example.com/Hare.png") shouldBe imageKey("https://example.com/Hare.png")
        (imageKey("https://example.com/Hare.png") == imageKey(PNG_URL)) shouldBe false
    }

    test("addresses come out of image links, once each, in order, as written") {
        val markdown = """
            # Salon

            ![Hare & Bristle salon](https://images.example.com/a/H+%26+B+2018-2865.jpg)

            Text with ![inline](http://example.com/b.png "a title") and ![again](https://images.example.com/a/H+%26+B+2018-2865.jpg).

            ![angle](<https://example.com/c d.gif>)
            ![paren](https://example.com/d_(1).webp)
            ![local](/etc/passwd)
            ![data](data:image/png;base64,AAAA)
            ![file](file:///etc/passwd)
            [a link](https://example.com/not-an-image.png)
        """.trimIndent()
        extractImageUrls(markdown) shouldContainExactly listOf(
            "https://images.example.com/a/H+%26+B+2018-2865.jpg",
            "http://example.com/b.png",
            "https://example.com/c d.gif",
            "https://example.com/d_(1).webp",
            "/etc/passwd",
            "data:image/png;base64,AAAA",
            "file:///etc/passwd",
        )
        // only http and https are ever fetched, and an address with a login in it is not
        imagesToFetch(markdown) shouldContainExactly listOf(
            "https://images.example.com/a/H+%26+B+2018-2865.jpg",
            "http://example.com/b.png",
            "https://example.com/d_(1).webp",
        )
        fetchableImageUrl("https://user:pw@example.com/a.png") shouldBe false
        fetchableImageUrl("ftp://example.com/a.png") shouldBe false
        fetchableImageUrl("//example.com/a.png") shouldBe false
    }

    test("images inside fenced code are not images") {
        extractImageUrls("```\n![x](https://example.com/in-code.png)\n```\n\n![y](https://example.com/real.png)\n") shouldContainExactly
            listOf("https://example.com/real.png")
    }

    test("at most 20 images are fetched for a report") {
        val markdown = (1..30).joinToString("\n\n") { "![n$it](https://example.com/$it.png)" }
        imagesToFetch(markdown) shouldHaveSize MAX_IMAGES_PER_REPORT
        imagesToFetch(markdown).last() shouldBe "https://example.com/20.png"
    }

    test("a type is found by its first bytes, SVG and text are not images") {
        sniffImageType(testImage("png")) shouldBe "image/png"
        sniffImageType(testImage("jpg")) shouldBe "image/jpeg"
        sniffImageType(testImage("gif")) shouldBe "image/gif"
        sniffImageType("RIFF....WEBPVP8 ".encodeToByteArray()) shouldBe "image/webp"
        sniffImageType("RIFF....WAVEfmt ".encodeToByteArray()) shouldBe null
        sniffImageType("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".encodeToByteArray()) shouldBe null
        sniffImageType("<html>".encodeToByteArray()) shouldBe null
        sniffImageType(ByteArray(0)) shouldBe null
    }

    test("width and height come from the header bytes") {
        imageSize(testImage("png", 30, 20), "image/png") shouldBe (30 to 20)
        imageSize(testImage("gif", 31, 21), "image/gif") shouldBe (31 to 21)
        imageSize(testImage("jpg", 32, 22), "image/jpeg") shouldBe (32 to 22)
        // WebP: a lossy, a lossless and an extended header, built by hand
        val lossy = "RIFF".encodeToByteArray() + ByteArray(4) + "WEBPVP8 ".encodeToByteArray() + ByteArray(7) +
            byteArrayOf(0x9d.toByte(), 0x01, 0x2a) + byteArrayOf(40, 0, 30, 0)
        imageSize(lossy, "image/webp") shouldBe (40 to 30)
        val lossless = "RIFF".encodeToByteArray() + ByteArray(4) + "WEBPVP8L".encodeToByteArray() + ByteArray(4) + byteArrayOf(0x2f) +
            // width-1 = 49 (14 bits), height-1 = 59 (14 bits)
            run { val bits = 49 or (59 shl 14); byteArrayOf(bits.toByte(), (bits shr 8).toByte(), (bits shr 16).toByte(), (bits shr 24).toByte()) } + ByteArray(6)
        imageSize(lossless, "image/webp") shouldBe (50 to 60)
        val extended = "RIFF".encodeToByteArray() + ByteArray(4) + "WEBPVP8X".encodeToByteArray() + ByteArray(8) + byteArrayOf(69, 0, 0, 79, 0, 0)
        imageSize(extended, "image/webp") shouldBe (70 to 80)
        // a cut-off or false header is no size, never an exception
        imageSize(testImage("png").copyOf(10), "image/png") shouldBe null
        imageSize(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), "image/jpeg") shouldBe null
        imageSize(ByteArray(40), "image/webp") shouldBe null
    }

    test("a good image is kept with its type and size") {
        val png = testImage("png", 12, 7)
        val image = fetched(fetchImage(PNG_URL, Table(PNG_URL to { ok("image/png", png) })))
        image.contentType shouldBe "image/png"
        image.bytes.size shouldBe png.size
        image.width to image.height shouldBe (12 to 7)
        fetched(fetchImage(PNG_URL, Table(PNG_URL to { ok("IMAGE/PNG; charset=binary", png) }))).contentType shouldBe "image/png"
        fetched(fetchImage(PNG_URL, Table(PNG_URL to { ok("image/jpg", testImage("jpg")) }))).contentType shouldBe "image/jpeg"
    }

    test("the Content-Type and the first bytes must both say PNG, JPEG, GIF or WebP, and agree") {
        val png = testImage("png")
        failure(fetchImage(PNG_URL, Table(PNG_URL to { ok("image/svg+xml", "<svg/>".encodeToByteArray()) }))) shouldContain "Content-Type"
        failure(fetchImage(PNG_URL, Table(PNG_URL to { ok("text/html", png) }))) shouldContain "Content-Type"
        failure(fetchImage(PNG_URL, Table(PNG_URL to { ImageResponse(200, null, null, ByteArrayInputStream(png)) }))) shouldContain "missing"
        // the header says PNG, the bytes are an SVG or a page
        failure(fetchImage(PNG_URL, Table(PNG_URL to { ok("image/png", "<svg xmlns='http://www.w3.org/2000/svg'/>".encodeToByteArray()) }))) shouldContain "bytes"
        failure(fetchImage(PNG_URL, Table(PNG_URL to { ok("image/png", "<html></html>".encodeToByteArray()) }))) shouldContain "bytes"
        // both are images, of different kinds
        failure(fetchImage(PNG_URL, Table(PNG_URL to { ok("image/gif", png) }))) shouldContain "image/png"
        failure(fetchImage(PNG_URL, Table(PNG_URL to { ImageResponse(404, "image/png", null, ByteArrayInputStream(png)) }))) shouldBe "answered 404"
    }

    test("an image over 5 MB is refused while it streams, not after") {
        var read = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 0x89.also { read++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                read += len
                for (i in 0 until len) b[off + i] = 0
                return len
            }
        }
        failure(fetchImage(PNG_URL, Table(PNG_URL to { ImageResponse(200, "image/png", null, endless) }))) shouldContain "larger"
        (read < MAX_IMAGE_BYTES + 64 * 1024) shouldBe true
        // exactly the limit still fits
        val exact = testImage("png").copyOf(MAX_IMAGE_BYTES)
        fetched(fetchImage(PNG_URL, Table(PNG_URL to { ok("image/png", exact) }))).bytes.size shouldBe MAX_IMAGE_BYTES
        failure(fetchImage(PNG_URL, Table(PNG_URL to { ok("image/png", testImage("png").copyOf(MAX_IMAGE_BYTES + 1)) }))) shouldContain "larger"
    }

    test("up to 3 redirects are followed, a relative one against the address it came from") {
        val png = testImage("png")
        val table = Table(
            PNG_URL to { redirect("/two.png") },
            "https://example.com/two.png" to { redirect("http://cdn.example.com/three.png", 301) },
            "http://cdn.example.com/three.png" to { redirect("https://cdn.example.com/four.png", 307) },
            "https://cdn.example.com/four.png" to { ok("image/png", png) },
        )
        fetched(fetchImage(PNG_URL, table)).bytes.size shouldBe png.size
        table.asked shouldHaveSize 4
    }

    test("a fourth redirect is refused") {
        val table = Table(
            PNG_URL to { redirect("https://example.com/2.png") },
            "https://example.com/2.png" to { redirect("https://example.com/3.png") },
            "https://example.com/3.png" to { redirect("https://example.com/4.png") },
            "https://example.com/4.png" to { redirect("https://example.com/5.png") },
            "https://example.com/5.png" to { ok("image/png", testImage("png")) },
        )
        failure(fetchImage(PNG_URL, table)) shouldBe "too many redirects"
        table.asked shouldHaveSize 4
        failure(fetchImage(PNG_URL, Table(PNG_URL to { ImageResponse(302, null, null, null) }))) shouldContain "location"
    }

    test("every redirect is checked as http or https again, and nothing else is asked for") {
        for (target in listOf("file:///etc/passwd", "ftp://example.com/a.png", "data:image/png;base64,AAAA", "gopher://example.com/", "https://user:pw@example.com/a.png")) {
            val table = Table(PNG_URL to { redirect(target) })
            failure(fetchImage(PNG_URL, table)) shouldBe "only http and https addresses are fetched"
            table.asked shouldContainExactly listOf(PNG_URL)
        }
        // and the first address itself
        for (first in listOf("file:///etc/passwd", "/etc/passwd", "data:image/png;base64,AAAA", "javascript:alert(1)", "https:///a.png")) {
            val table = Table()
            failure(fetchImage(first, table)) shouldBe "only http and https addresses are fetched"
            table.asked shouldHaveSize 0
        }
    }

    test("a network error is a failure, not an exception") {
        failure(fetchImage(PNG_URL) { throw IOException("connection reset") }) shouldContain "IOException"
    }

    test("a render request carries each image as base64 with its type and size; txt and md carry none") {
        val png = testImage("png", 5, 4)
        val key = imageKey(PNG_URL)
        val body = renderRequestBody("![x]($PNG_URL)", ReportStyle(), ReportFormat.PDF, mapOf(key to RenderImage("image/png", png, 5, 4)))
        body shouldContain "\"images\":{\"$key\":{\"type\":\"image/png\",\"data\":\"${java.util.Base64.getEncoder().encodeToString(png)}\",\"width\":5,\"height\":4}}"
        renderRequestBody("# Plain", ReportStyle(), ReportFormat.PDF) shouldNotContain "\"data\""
    }

    test("images over the request cap are dropped and the rest kept in order") {
        val big = RenderImage("image/png", ByteArray(3_000_000), null, null)
        val small = RenderImage("image/png", ByteArray(1_000), null, null)
        val images = linkedMapOf("a" to big, "b" to big, "c" to small)
        // cap for two of the big ones plus a little: the second big one does not fit, the small one does
        val kept = imagesThatFit("# Report", images, cap = 3_000_000 * 4 / 3 + 200_000)
        kept.keys.toList() shouldContainExactly listOf("a", "c")
        imagesThatFit("# Report", images).keys.toList() shouldContainExactly listOf("a", "b", "c")
        imagesThatFit("x".repeat(100), images, cap = 10_000).keys.toList() shouldHaveSize 0
    }
})
