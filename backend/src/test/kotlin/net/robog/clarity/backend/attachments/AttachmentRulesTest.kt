package net.robog.clarity.backend.attachments

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import net.robog.clarity.shared.noun.AttachmentMeta
import kotlin.time.Instant

class AttachmentRulesTest : FunSpec({
    val now = Instant.parse("2026-11-13T12:00:00Z")
    val ttl = 24.hours

    test("part paths are strict; the list marker is valid but not a file") {
        for (ok in listOf("1", "2", "1.3", "10.2.1")) { validPart(ok) shouldBe true; isFilePart(ok) shouldBe true }
        validPart("*") shouldBe true
        isFilePart("*") shouldBe false
        for (bad in listOf("", "0", "1.", ".1", "1..2", "a", "../1", "1/2", "01", "1 2", "-1", "1.0")) validPart(bad) shouldBe false
    }

    test("a size is refused only when known and over the cap") {
        sizeVerdict(null, 100) shouldBe SizeVerdict.OK
        sizeVerdict(100, 100) shouldBe SizeVerdict.OK
        sizeVerdict(101, 100) shouldBe SizeVerdict.TOO_LARGE
    }

    test("file names lose paths, control characters and leading dots") {
        sanitizeFilename("../../etc/passwd", "2", "text/plain") shouldBe "passwd"
        sanitizeFilename("C:\\Users\\sam\\report.pdf", "2", "application/pdf") shouldBe "report.pdf"
        sanitizeFilename("a\u0000b\nc.txt", "1", "text/plain") shouldBe "a_b_c.txt"
        sanitizeFilename(".bashrc", "1", "text/plain") shouldBe "bashrc"
        sanitizeFilename("  spaced   out .pdf ", "1", "application/pdf") shouldBe "spaced out .pdf"
        sanitizeFilename("Q3 € plan.xlsx", "2", "x") shouldBe "Q3 € plan.xlsx"
        sanitizeFilename("a<b>:c|d?.txt", "1", "text/plain") shouldBe "a_b__c_d_.txt"
    }

    test("a missing or empty name falls back to the part and the type's extension") {
        sanitizeFilename(null, "1.3", "application/pdf") shouldBe "attachment-1-3.pdf"
        sanitizeFilename("", "2", "image/png") shouldBe "attachment-2.png"
        sanitizeFilename("...", "2", "message/rfc822") shouldBe "attachment-2.eml"
        sanitizeFilename("///", "2", "application/x-weird") shouldBe "attachment-2"
        sanitizeFilename(null, "2", "image/jpeg; charset=x") shouldBe "attachment-2.jpg"
    }

    test("a long name is cut to 200 bytes of UTF-8, on a character boundary, and keeps its extension") {
        val ascii = sanitizeFilename("x".repeat(300) + ".pdf", "1", "application/pdf")
        ascii.toByteArray().size shouldBe 200
        ascii.endsWith(".pdf") shouldBe true
        val accents = sanitizeFilename("é".repeat(300) + ".pdf", "1", "application/pdf")
        (accents.toByteArray().size <= 200) shouldBe true
        accents.endsWith(".pdf") shouldBe true
        accents.dropLast(4).all { it == 'é' } shouldBe true
        val emoji = sanitizeFilename("\uD83D\uDE00".repeat(100) + ".txt", "1", "text/plain")
        (emoji.toByteArray().size <= 200) shouldBe true
        emoji.endsWith(".txt") shouldBe true
        emoji.removeSuffix(".txt").codePoints().toArray().all { it == 0x1F600 } shouldBe true
        val cjk = sanitizeFilename("報告書".repeat(100), "1", "text/plain")
        (cjk.toByteArray().size <= 200) shouldBe true
        cjk.all { it in "報告書" } shouldBe true
        sanitizeFilename("y".repeat(300), "1", "text/plain").toByteArray().size shouldBe 200
        // a "extension" that is really most of the name is not kept
        (sanitizeFilename("a." + "z".repeat(300), "1", "text/plain").toByteArray().size <= 200) shouldBe true
    }

    test("invisible formatting characters and a leading hyphen are removed") {
        sanitizeFilename("\u202Egpj.exe", "1", "x") shouldBe "gpj.exe"
        sanitizeFilename("a\u200Bb\u2066c\uFEFF.txt", "1", "text/plain") shouldBe "abc.txt"
        sanitizeFilename("\uDB40\uDC41tag.txt", "1", "text/plain") shouldBe "tag.txt"
        sanitizeFilename("-rf.txt", "1", "text/plain") shouldBe "rf.txt"
        sanitizeFilename("--help", "1", "text/plain") shouldBe "help"
        sanitizeFilename("\u202E-.", "2", "application/pdf") shouldBe "attachment-2.pdf"
    }

    test("two attachments with the same name get the part in their names; a unique name stays") {
        val a = AttachmentMeta("2", "scan.pdf", "application/pdf")
        val b = AttachmentMeta("3", "Scan.PDF", "application/pdf")
        val c = AttachmentMeta("1.4", "other.pdf", "application/pdf")
        distinctFilename("scan.pdf", "2", listOf(a, b, c)) shouldBe "scan-p2.pdf"
        distinctFilename("Scan.PDF", "3", listOf(a, b, c)) shouldBe "Scan-p3.PDF"
        distinctFilename("other.pdf", "1.4", listOf(a, b, c)) shouldBe "other.pdf"
        distinctFilename("scan.pdf", "2", listOf(a)) shouldBe "scan.pdf"
        val nameless = listOf(AttachmentMeta("1", null, "image/png"), AttachmentMeta("2", null, "image/png"))
        distinctFilename(sanitizeFilename(null, "1", "image/png"), "1", nameless) shouldBe "attachment-1.png"
        (distinctFilename(sanitizeFilename("x".repeat(300) + ".pdf", "2", "application/pdf"), "2", listOf(AttachmentMeta("3", "x".repeat(300) + ".pdf"))).toByteArray().size <= 200) shouldBe true
    }

    test("which records list their attachments") {
        attachmentsKnown("2") shouldBe true
        attachmentsKnown("10") shouldBe true
        attachmentsKnown("1") shouldBe false
        attachmentsKnown("0") shouldBe false
        attachmentsKnown("\"etag-5\"") shouldBe false
        attachmentsKnown("") shouldBe false
    }

    test("a 20 MB file whose encoding is about 27 MB is not refused; a file that cannot fit is") {
        val max = 25L * 1024 * 1024
        val encoded = 20L * 1024 * 1024 * 4 / 3 + 20L * 1024 * 1024 / 57 * 2 // base64 with line breaks
        (encoded > 27_000_000L) shouldBe true
        declaredTooLarge(encoded, max) shouldBe false
        declaredTooLarge(null, max) shouldBe false
        declaredTooLarge(max, max) shouldBe false
        declaredTooLarge(40L * 1024 * 1024, max) shouldBe true
        declaredTooLarge(36L * 1024 * 1024, max) shouldBe true // 25.2 MB decoded
    }

    test("the wait for a connector grows with the declared size, up to two minutes") {
        fetchWait(30.seconds, null) shouldBe 30.seconds
        fetchWait(30.seconds, 100L * 1024) shouldBe 30.seconds
        fetchWait(30.seconds, 10L * 1024 * 1024) shouldBe 60.seconds
        fetchWait(30.seconds, 25L * 1024 * 1024) shouldBe 105.seconds
        fetchWait(30.seconds, 200L * 1024 * 1024) shouldBe 120.seconds
        fetchWait(5.minutes, 25L * 1024 * 1024) shouldBe 5.minutes // never below what is configured
    }

    test("the sandbox path uses the first characters of the id and never a separator from the name") {
        sandboxAttachmentPath("0f3a9c1e-1111-2222-3333-444455556666", "report.pdf") shouldBe "attachments/0f3a9c1e/report.pdf"
        sandboxAttachmentPath("../..", "a.txt") shouldNotContain ".."
    }

    test("the plan for a request depends on the row that exists") {
        planFor(null, now, ttl) shouldBe Plan.ENQUEUE
        planFor(RowState(FetchStatus.PENDING, null, null), now, ttl) shouldBe Plan.WAIT
        planFor(RowState(FetchStatus.LEASED, null, now + 1.minutes), now, ttl) shouldBe Plan.WAIT
        planFor(RowState(FetchStatus.LEASED, null, now - 1.minutes), now, ttl) shouldBe Plan.REQUEUE
        planFor(RowState(FetchStatus.LEASED, null, null), now, ttl) shouldBe Plan.REQUEUE
        planFor(RowState(FetchStatus.FAILED, now, null), now, ttl) shouldBe Plan.REQUEUE
        planFor(RowState(FetchStatus.DONE, now - 23.hours, null), now, ttl) shouldBe Plan.SERVE
        planFor(RowState(FetchStatus.DONE, now - 25.hours, null), now, ttl) shouldBe Plan.REQUEUE
        planFor(RowState(FetchStatus.DONE, null, null), now, ttl) shouldBe Plan.REQUEUE
    }

    test("freshness ends at the ttl exactly") {
        isFresh(now - ttl + 1.minutes, now, ttl) shouldBe true
        isFresh(now - ttl, now, ttl) shouldBe false
    }

    test("the cap evicts the oldest first and only as many as needed") {
        val entries = listOf(
            CacheEntry("new", 40, now),
            CacheEntry("old", 40, now - 3.hours),
            CacheEntry("mid", 40, now - 1.hours),
        )
        evictionsFor(entries, 120) shouldBe emptyList()
        evictionsFor(entries, 100) shouldBe listOf("old")
        evictionsFor(entries, 50) shouldBe listOf("old", "mid")
        evictionsFor(entries, 0) shouldBe listOf("old", "mid", "new")
        evictionsFor(emptyList(), 0) shouldBe emptyList()
    }

    test("status names round trip") {
        FetchStatus.entries.forEach { FetchStatus.fromWire(it.wire) shouldBe it }
    }
})
