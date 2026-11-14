package net.robog.clarity.connector.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.util.Properties
import kotlin.time.Instant
import net.robog.clarity.shared.noun.AttachmentMeta

private val session = Session.getInstance(Properties())
private val fallback = Instant.parse("2026-01-01T00:00:00Z")

private fun parse(raw: String) = MimeMessage(session, raw.trimStart().replace("\n", "\r\n").byteInputStream())

private const val HEAD = "From: Sam Example <sam@example.com>\nTo: me@example.com\nSubject: files\nMessage-ID: <abc.1@example.com>\nMIME-Version: 1.0"

// "%PDF-1.4\n" and a 1x1 PNG header, as base64
private const val PDF = "JVBERi0xLjQK"
private const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

private val withPdf = """
$HEAD
Content-Type: multipart/mixed; boundary="B1"

--B1
Content-Type: text/plain; charset=UTF-8

See the invoice.
--B1
Content-Type: application/pdf; name="invoice.pdf"
Content-Disposition: attachment; filename="invoice.pdf"
Content-Transfer-Encoding: base64

$PDF
--B1--
""".trimStart()

private val richMail = """
$HEAD
Content-Type: multipart/mixed; boundary="OUT"

--OUT
Content-Type: multipart/alternative; boundary="ALT"

--ALT
Content-Type: text/plain; charset=UTF-8

Hello
--ALT
Content-Type: multipart/related; boundary="REL"

--REL
Content-Type: text/html; charset=UTF-8

<p>Hello <img src="cid:logo@example.com"></p>
--REL
Content-Type: image/png; name="logo.png"
Content-Disposition: inline; filename="logo.png"
Content-ID: <logo@example.com>
Content-Transfer-Encoding: base64

$PNG
--REL--
--ALT--
--OUT
Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
Content-Disposition: attachment; filename*=UTF-8''Q3%20%E2%82%AC%20plan.xlsx
Content-Transfer-Encoding: base64

$PDF
--OUT
Content-Type: text/plain; charset=UTF-8
Content-Disposition: attachment; filename="notes.txt"

some notes
--OUT--
""".trimStart()

private val forwarded = """
$HEAD
Content-Type: multipart/mixed; boundary="F"

--F
Content-Type: text/plain

FYI
--F
Content-Type: message/rfc822
Content-Disposition: attachment

From: Dana Example <dana@example.com>
To: sam@example.com
Subject: =?UTF-8?B?UXVhcnRlcmx5IHBsYW4=?=
Message-ID: <inner@example.com>
Content-Type: text/plain

Inner body
--F--
""".trimStart()

private val signed = """
$HEAD
Content-Type: multipart/signed; protocol="application/pkcs7-signature"; micalg=sha-256; boundary="S"

--S
Content-Type: text/plain

Signed text
--S
Content-Type: application/pkcs7-signature; name="smime.p7s"
Content-Disposition: attachment; filename="smime.p7s"
Content-Transfer-Encoding: base64

$PDF
--S--
""".trimStart()

private val wholePdf = """
$HEAD
Content-Type: application/pdf; name="=?UTF-8?B?UsOpc3Vtw6kucGRm?="
Content-Disposition: attachment; filename="=?UTF-8?B?UsOpc3Vtw6kucGRm?="
Content-Transfer-Encoding: base64

$PDF
""".trimStart()

private val bodyOnly = """
$HEAD
Content-Type: multipart/alternative; boundary="A"

--A
Content-Type: text/plain

hi
--A
Content-Type: text/html

<p>hi</p>
--A--
""".trimStart()

private val cidWithoutDisposition = """
$HEAD
Content-Type: multipart/related; boundary="R"

--R
Content-Type: text/html

<img src="cid:pic">
--R
Content-Type: image/jpeg
Content-ID: <pic>
Content-Transfer-Encoding: base64

$PNG
--R--
""".trimStart()

class AttachmentMappingTest : FunSpec({
    test("the message id keeps its angle brackets") {
        parse(withPdf).toPayload("me@example.com", fallback)!!.messageId shouldBe "<abc.1@example.com>"
        parse("From: a@x.org\nTo: b@x.org\n\nhi").toPayload("b@x.org", fallback)!!.messageId.shouldBeNull()
    }

    test("a file next to the body is part 2 with its name, type and size") {
        val list = parse(withPdf).toPayload("me@example.com", fallback)!!.attachments
        list shouldHaveSize 1
        val pdf = list.single()
        pdf.part shouldBe "2"
        pdf.filename shouldBe "invoice.pdf"
        pdf.mimeType shouldBe "application/pdf"
        pdf.inline shouldBe false
        pdf.size.shouldNotBeNull() shouldBeGreaterThan 11L
    }

    test("nested parts get their dotted path; inline image flagged; encoded names decoded; text files kept") {
        val list = parse(richMail).toPayload("me@example.com", fallback)!!.attachments
        list.map { it.part } shouldBe listOf("1.2.2", "2", "3")
        val (logo, sheet, notes) = list
        logo.filename shouldBe "logo.png"
        logo.mimeType shouldBe "image/png"
        logo.inline shouldBe true
        sheet.filename shouldBe "Q3 € plan.xlsx"
        sheet.mimeType shouldBe "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        sheet.inline shouldBe false
        notes.filename shouldBe "notes.txt"
        notes.mimeType shouldBe "text/plain"
    }

    test("a forwarded message is an attachment named after its subject, and is not opened") {
        val list = parse(forwarded).toPayload("me@example.com", fallback)!!.attachments
        list shouldBe listOf(AttachmentMeta("2", "Quarterly plan.eml", "message/rfc822", list.single().size, inline = false))
    }

    test("signatures are not attachments") {
        parse(signed).toPayload("me@example.com", fallback)!!.attachments.shouldBeEmpty()
    }

    test("a message that is one file is part 1, with an RFC 2047 name decoded") {
        val list = parse(wholePdf).toPayload("me@example.com", fallback)!!.attachments
        list.single().part shouldBe "1"
        list.single().filename shouldBe "Résumé.pdf"
    }

    test("body text alone has no attachments") {
        parse(bodyOnly).toPayload("me@example.com", fallback)!!.attachments.shouldBeEmpty()
        parse("From: a@x.org\nTo: b@x.org\nContent-Type: text/plain\n\nhi").toPayload("b@x.org", fallback)!!.attachments.shouldBeEmpty()
    }

    test("an image with a Content-ID and no disposition counts as inline") {
        val list = parse(cidWithoutDisposition).toPayload("me@example.com", fallback)!!.attachments
        list.single().part shouldBe "2"
        list.single().inline shouldBe true
        list.single().filename.shouldBeNull()
    }

    test("a message with a broken part still maps (no attachments rather than an error)") {
        val broken = parse("$HEAD\nContent-Type: multipart/mixed; boundary=\"Z\"\n\n--Z\nContent-Type: image/gif\nContent-Disposition: attachment; filename=\"x.gif\"\n\nGIF89a")
        broken.attachments().single().filename shouldBe "x.gif"
    }

    test("part paths parse strictly") {
        PartPath.parse("2") shouldBe listOf(2)
        PartPath.parse("1.12.3") shouldBe listOf(1, 12, 3)
        for (bad in listOf("", "0", "1.0", "a", "1..2", ".1", "1.", "1.2.3.4.5.6.7.8.9", "01", "-1", "1/2", "../1", "*", "99999")) {
            PartPath.parse(bad).shouldBeNull()
        }
        PartPath.format(listOf(1, 3)) shouldBe "1.3"
    }

    test("partAt follows the same numbering as the listing") {
        val message = parse(richMail)
        message.partAt(listOf(1, 2, 2))!!.contentType.substringBefore(';') shouldBe "image/png"
        message.partAt(listOf(2))!!.fileName shouldBe "Q3 € plan.xlsx"
        message.partAt(listOf(9)).shouldBeNull()
        message.partAt(listOf(2, 1)).shouldBeNull()
        parse(wholePdf).partAt(listOf(1)).shouldNotBeNull()
        parse(wholePdf).partAt(listOf(2)).shouldBeNull()
        message.attachments().forEach { message.partAt(PartPath.parse(it.part)!!).shouldNotBeNull() }
    }

    test("the part's bytes are decoded") {
        val bytes = parse(withPdf).partAt(listOf(2))!!.inputStream.readBytes()
        bytes.decodeToString() shouldBe "%PDF-1.4\n"
    }

    test("limits") {
        tooLargeToFetch(100, 1_000) shouldBe false
        tooLargeToFetch(1_300, 1_000) shouldBe false
        tooLargeToFetch(1_500, 1_000) shouldBe true
        attachmentLimitFromEnv(emptyMap()) shouldBe 25L * 1024 * 1024
        attachmentLimitFromEnv(mapOf("CLARITY_ATTACHMENT_MAX_MB" to "5")) shouldBe 5L * 1024 * 1024
        attachmentLimitFromEnv(mapOf("CLARITY_ATTACHMENT_MAX_MB" to "0")) shouldBe 25L * 1024 * 1024
        attachmentLimitFromEnv(mapOf("CLARITY_ATTACHMENT_MAX_MB" to "lots")) shouldBe 25L * 1024 * 1024
    }
})
