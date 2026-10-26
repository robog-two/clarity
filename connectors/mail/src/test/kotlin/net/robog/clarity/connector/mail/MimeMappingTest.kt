package net.robog.clarity.connector.mail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.util.Properties
import kotlin.time.Instant
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload

private val session = Session.getInstance(Properties())
private val fallback = Instant.parse("2026-01-01T00:00:00Z")

private fun parse(raw: String) = MimeMessage(session, raw.replace("\n", "\r\n").byteInputStream())

private val plain = """
From: =?UTF-8?Q?Ren=C3=A9e_Dupont?= <renee@example.com>
To: Me <me@example.com>, bob@example.com
Cc: carol@example.com
Subject: =?UTF-8?B?TcO8bmNoZW4gdHJpcA==?=
Date: Tue, 03 Mar 2026 10:00:00 +0000
Message-ID: <m3@example.com>
In-Reply-To: <m2@example.com>
References: <m1@example.com> <m2@example.com>
Content-Type: text/plain; charset=UTF-8
Content-Transfer-Encoding: quoted-printable

Hi,=0A
the trip is on =E2=9C=93.
""".trimStart()

class MimeMappingTest : FunSpec({
    test("a plain message maps with decoded names, subject, recipients and thread root") {
        val p = parse(plain).toPayload("me@example.com", fallback)!!
        p.channel shouldBe MessagePayload.Channel.EMAIL
        p.account shouldBe Handle.email("me@example.com")
        p.sender shouldBe Handle.email("renee@example.com", "Renée Dupont")
        p.recipients.map { it.value } shouldBe listOf("me@example.com", "bob@example.com", "carol@example.com")
        p.subject shouldBe "München trip"
        p.body shouldBe "Hi,\n\nthe trip is on ✓."
        p.sentAt shouldBe Instant.parse("2026-03-03T10:00:00Z")
        p.threadRef shouldBe "<m1@example.com>"
    }

    test("threading falls back to In-Reply-To, then to the message's own id") {
        val replyOnly = parse("From: a@x.org\nTo: b@x.org\nMessage-ID: <b@x.org>\nIn-Reply-To: <a@x.org>\n\nhi")
        replyOnly.toPayload("b@x.org", fallback)!!.threadRef shouldBe "<a@x.org>"
        val first = parse("From: a@x.org\nTo: b@x.org\nMessage-ID: <a@x.org>\n\nhi")
        first.toPayload("b@x.org", fallback)!!.threadRef shouldBe "<a@x.org>"
        parse("From: a@x.org\nTo: b@x.org\n\nhi").toPayload("b@x.org", fallback)!!.threadRef shouldBe null
    }

    test("multipart/alternative prefers text/plain and skips attachments") {
        val raw = """
            From: a@x.org
            To: b@x.org
            Subject: files
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="outer"

            --outer
            Content-Type: multipart/alternative; boundary="inner"

            --inner
            Content-Type: text/plain; charset=utf-8

            The plain part
            --inner
            Content-Type: text/html; charset=utf-8

            <p>The <b>html</b> part</p>
            --inner--
            --outer
            Content-Type: text/plain; charset=utf-8
            Content-Disposition: attachment; filename="notes.txt"

            attachment text
            --outer--
        """.trimIndent()
        val body = parse(raw).toPayload("b@x.org", fallback)!!.body
        body shouldContain "The plain part"
        body shouldNotContain "html"
        body shouldNotContain "attachment text"
    }

    test("html-only mail becomes readable text") {
        val raw = "From: a@x.org\nTo: b@x.org\nContent-Type: text/html; charset=utf-8\n\n<html><body><p>Hello <b>there</b></p><p>Second&nbsp;line<br>third</p><script>x()</script></body></html>"
        parse(raw).toPayload("b@x.org", fallback)!!.body shouldBe "Hello there\nSecond line\nthird"
    }

    test("a message without a usable sender is skipped; a missing date uses the fallback") {
        parse("To: b@x.org\n\nhi").toPayload("b@x.org", fallback) shouldBe null
        parse("From: a@x.org\nTo: b@x.org\n\nhi").toPayload("b@x.org", fallback)!!.sentAt shouldBe fallback
    }

    test("Message-IDs derive deterministically from the idempotency key and the sender domain") {
        val a = messageIdFor("send-1", "me@example.com")
        a shouldBe messageIdFor("send-1", "me@example.com")
        (a == messageIdFor("send-2", "me@example.com")) shouldBe false
        a.endsWith("@example.com>") shouldBe true
        a.length shouldBe 1 + 32 + 1 + "example.com".length + 1
        messageIdFor("k", "no-at-sign").endsWith("@clarity.local>") shouldBe true
    }

    test("source ids round-trip, including folders with slashes") {
        val ref = MailRef("INBOX/Receipts", 1234, 56)
        MailRef.parse(ref.sourceId) shouldBe ref
        MailRef.parse("INBOX/x/y") shouldBe null
        MailRef.parse("INBOX") shouldBe null
    }

    test("UID sets encode as ranges and decode back") {
        val set = UidSet(listOf(1, 2, 3, 5, 9, 10, 11, 12, 20))
        set.encode() shouldBe "1-3,5,9-12,20"
        UidSet.decode("1-3,5,9-12,20").toSet() shouldBe setOf(1L, 2, 3, 5, 9, 10, 11, 12, 20)
        UidSet().encode() shouldBe ""
        UidSet.decode("").size shouldBe 0
        UidSet.decode("1-3,junk,7").toSet() shouldBe setOf(1L, 2, 3, 7)
    }
})
