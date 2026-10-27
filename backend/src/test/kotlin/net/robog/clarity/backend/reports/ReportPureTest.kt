package net.robog.clarity.backend.reports

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.FeedItemId
import net.robog.clarity.shared.report.ReportFormat
import net.robog.clarity.shared.report.ReportKind
import net.robog.clarity.shared.report.ReportStyle

private fun rendition(rev: Long, version: String, size: Long, at: Long) = StoredRendition(
    rev, ReportFormat.PDF, version, "renditions/$rev/pdf.pdf", size, "sha", Instant.fromEpochSeconds(at), Instant.fromEpochSeconds(at),
)

/** Reports without a database or a renderer: edits, styles, file names, the cache's choices, the wire. */
class ReportPureTest : FunSpec({

    test("edit_report replaces exactly one occurrence, like a text editor") {
        replaceUnique("The flu of 1918 spread fast.", "1918", "1918-1920") shouldBe Either.Right("The flu of 1918-1920 spread fast.")
        replaceUnique("a b a", "b", "") shouldBe Either.Right("a  a")
    }

    test("edit_report refuses no match, several matches, an empty old text and no change") {
        replaceUnique("one two", "three", "x").shouldBeInstanceOf<Either.Left<String>>().value shouldContain "No match"
        replaceUnique("the cat and the dog", "the", "a").shouldBeInstanceOf<Either.Left<String>>().value shouldContain "matches 2 places"
        replaceUnique("aaa", "aa", "b").shouldBeInstanceOf<Either.Left<String>>().value shouldContain "matches 2 places"
        replaceUnique("text", "", "x").shouldBeInstanceOf<Either.Left<String>>().value shouldContain "must not be empty"
        replaceUnique("text", "text", "text").shouldBeInstanceOf<Either.Left<String>>().value shouldContain "nothing to change"
        occurrences("aaaa", "aa") shouldBe 3
    }

    test("read_report shows a page of lines and says where to continue") {
        val source = (1..100).joinToString("\n") { "line $it with some words" }
        val first = clipSource(source, 1, 200)
        first shouldStartWith "line 1 with"
        first shouldContain "call read_report again with fromLine="
        clipSource(source, 95, 10_000) shouldContain "[lines 95-100 of 100, end of the report]"
        clipSource("short", 1, 100) shouldContain "[lines 1-1 of 1, end of the report]"
    }

    test("styles: the default is Times New Roman 12, single spaced, 1 inch margins; MLA is double spaced with indents") {
        val default = ReportStyle()
        default.fontFamily shouldBe "Times New Roman"
        default.fontSizePt shouldBe 12.0
        default.lineSpacing shouldBe 1.0
        default.marginIn shouldBe 1.0
        default.problems().shouldBeEmpty()
        ReportStyle.MLA.lineSpacing shouldBe 2.0
        ReportStyle.MLA.paragraphIndentIn shouldBe 0.5
        ReportStyle.MLA.problems().shouldBeEmpty()
    }

    test("the agent's style input lays over a base; presets, bad values and unknown presets") {
        resolveStyle(null, ReportStyle()) shouldBe Either.Right(ReportStyle())
        resolveStyle(StyleInput(fontFamily = "Arial", fontSizePt = 11.0), ReportStyle()).getOrNull()!!.let {
            it.fontFamily shouldBe "Arial"
            it.fontSizePt shouldBe 11.0
            it.lineSpacing shouldBe 1.0
        }
        resolveStyle(StyleInput(preset = "mla"), ReportStyle(fontFamily = "Arial")) shouldBe Either.Right(ReportStyle.MLA)
        resolveStyle(StyleInput(preset = "MLA", marginIn = 1.25), ReportStyle()).getOrNull()!!.marginIn shouldBe 1.25
        // Only the fields given change: the current double spacing stays.
        resolveStyle(StyleInput(fontSizePt = 14.0), ReportStyle.MLA).getOrNull()!!.lineSpacing shouldBe 2.0
        resolveStyle(StyleInput(headingFontFamily = ""), ReportStyle(headingFontFamily = "Arial")).getOrNull()!!.headingFontFamily shouldBe null
        resolveStyle(StyleInput(preset = "apa7"), ReportStyle()).shouldBeInstanceOf<Either.Left<String>>().value shouldContain "Unknown preset"
        resolveStyle(StyleInput(fontSizePt = 200.0), ReportStyle()).shouldBeInstanceOf<Either.Left<String>>().value shouldContain "fontSizePt"
        resolveStyle(StyleInput(fontFamily = "Evil\"/><x"), ReportStyle()).shouldBeInstanceOf<Either.Left<String>>().value shouldContain "fontFamily"
        resolveStyle(StyleInput(pageSize = "legal"), ReportStyle()).shouldBeInstanceOf<Either.Left<String>>().value shouldContain "pageSize"
    }

    test("downloads are named after the title, with the revision when it is not the latest") {
        downloadFilename("The 1918 Flu: causes & spread", 3, latest = true, ReportFormat.DOCX) shouldBe "The 1918 Flu causes spread.docx"
        downloadFilename("Essay", 2, latest = false, ReportFormat.PDF) shouldBe "Essay-rev2.pdf"
        downloadFilename("///", 1, latest = true, ReportFormat.TXT) shouldBe "report.txt"
        val header = contentDisposition("Café notes.pdf")
        header shouldStartWith "attachment; filename=\"Caf_ notes.pdf\""
        header shouldContain "filename*=UTF-8''Caf%C3%A9%20notes.pdf"
    }

    test("formats: a document offers PDF, Word / Pages, OpenDocument, text and Markdown; no .pages") {
        formatsFor(ReportKind.DOCUMENT) shouldContainExactly listOf(ReportFormat.PDF, ReportFormat.DOCX, ReportFormat.ODT, ReportFormat.TXT, ReportFormat.MD)
        formatOptions(ReportKind.DOCUMENT).single { it.format == ReportFormat.DOCX }.label shouldBe "Word / Pages"
        formatsFor(ReportKind.DECK).shouldBeEmpty()
        ReportFormat.fromWire("pages") shouldBe null
        ReportFormat.fromWire("DOCX") shouldBe ReportFormat.DOCX
    }

    test("the sweeper drops old renderer versions first, then the least recently used, down to the cap") {
        val stale = listOf(rendition(1, "old", 100, 5))
        val current = listOf(rendition(2, "v2", 300, 1), rendition(3, "v2", 300, 2), rendition(4, "v2", 300, 3))
        renditionsToDrop(stale, current, maxBytes = 650).map { it.revisionId } shouldContainExactly listOf(1L, 2L)
        renditionsToDrop(emptyList(), current, maxBytes = 10_000).shouldBeEmpty()
        renditionsToDrop(emptyList(), current, maxBytes = 0).map { it.revisionId } shouldContainExactly listOf(2L, 3L, 4L)
    }

    test("the render request carries the source, the whole style and the format's wire name") {
        val body = ClarityJson.parseToJsonElement(renderRequestBody("# Hi", ReportStyle.MLA, ReportFormat.DOCX)).jsonObject
        body["source"]!!.jsonPrimitive.content shouldBe "# Hi"
        body["format"]!!.jsonPrimitive.content shouldBe "docx"
        val style = body["style"]!!.jsonObject
        style["fontFamily"]!!.jsonPrimitive.content shouldBe "Times New Roman"
        style["lineSpacing"]!!.jsonPrimitive.content shouldBe "2.0"
        style["paragraphIndentIn"]!!.jsonPrimitive.content shouldBe "0.5"
        parseRenderVersion("""{"status":"ok","version":"pandoc 3.12.1; typst 0.15.1; renderd abc"}""") shouldBe "pandoc 3.12.1; typst 0.15.1; renderd abc"
        parseRenderVersion("not json") shouldBe null
    }

    test("the report card round-trips on the wire and has a fallback text") {
        val card: Card = Card.Report("r1", "The 1918 flu", listOf("pdf", "docx"))
        val json = ClarityJson.encodeToString(Card.serializer(), card)
        json shouldContain "\"type\":\"report\""
        ClarityJson.decodeFromString(Card.serializer(), json) shouldBe card
        (card as Card.Report).fallbackText shouldContain "The 1918 flu"
        net.robog.clarity.backend.feed.headline(
            FeedItem(FeedItemId("i"), 1, Instant.fromEpochSeconds(0), FeedItem.Author.AGENT, cards = listOf(card)),
        ) shouldBe "The 1918 flu"
    }
})
