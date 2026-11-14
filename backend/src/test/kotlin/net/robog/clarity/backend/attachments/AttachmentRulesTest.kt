package net.robog.clarity.backend.attachments

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
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

    test("a long name is cut but keeps its extension") {
        val name = sanitizeFilename("x".repeat(300) + ".pdf", "1", "application/pdf")
        name.length shouldBe 120
        name.endsWith(".pdf") shouldBe true
        sanitizeFilename("y".repeat(300), "1", "text/plain").length shouldBe 120
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
