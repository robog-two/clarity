package net.robog.clarity.shared.report

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Reports ("goods"): documents the agent writes for the person. The source of truth is a revision's
 * Markdown (with optional YAML front matter) plus a [ReportStyle]; every file format is rendered from
 * them on demand and cached (`GET /api/v1/reports/{id}/download/{format}`).
 */
@Serializable
enum class ReportKind {
    @SerialName("document") DOCUMENT,

    /** Not made yet: reserved for slides, spreadsheets and small sites. */
    @SerialName("deck") DECK,
    @SerialName("sheet") SHEET,
    @SerialName("site") SITE,
    ;

    val wireName: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String): ReportKind? = entries.firstOrNull { it.wireName == value }
    }
}

/** A file format a document report downloads as. [label] is what the app shows on the button. */
@Serializable
enum class ReportFormat(val extension: String, val mimeType: String, val label: String) {
    @SerialName("pdf") PDF("pdf", "application/pdf", "PDF"),

    /** Pages opens Word files, so there is no separate `.pages` format. */
    @SerialName("docx") DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word / Pages"),
    @SerialName("odt") ODT("odt", "application/vnd.oasis.opendocument.text", "OpenDocument"),
    @SerialName("txt") TXT("txt", "text/plain; charset=utf-8", "Text"),
    @SerialName("md") MD("md", "text/markdown; charset=utf-8", "Markdown"),
    ;

    val wireName: String get() = extension

    companion object {
        fun fromWire(value: String): ReportFormat? = entries.firstOrNull { it.wireName == value.lowercase() }

        /** What a document report offers, in the order the app shows them. */
        val DOCUMENT: List<ReportFormat> = listOf(PDF, DOCX, ODT, TXT, MD)
    }
}

/**
 * How a document looks, chosen by the agent from what the person asked for ("MLA", "12 pt Arial,
 * double spaced") and stored with each revision. Defaults: Times New Roman 12 pt, single spaced,
 * 1 inch margins, US Letter. The family is named as is in Word and OpenDocument files; the PDF uses a
 * metric-compatible free font in its place (Liberation Serif for Times New Roman).
 */
@Serializable
data class ReportStyle(
    val fontFamily: String = DEFAULT_FONT,
    val fontSizePt: Double = 12.0,
    /** 1 single, 1.5, 2 double (MLA and APA papers are double spaced). */
    val lineSpacing: Double = 1.0,
    /** Every page margin, in inches. */
    val marginIn: Double = 1.0,
    /** First-line indent of body paragraphs, in inches (MLA: 0.5). */
    val paragraphIndentIn: Double = 0.0,
    /** Space after each body paragraph, in points (MLA: 0, the double spacing separates them). */
    val paragraphSpacingPt: Double = 8.0,
    /** Headings' family; null is the body's (the default: title and headings in the same family). */
    val headingFontFamily: String? = null,
    /** Heading sizes by level (`#`, `##`, `###`, ...); deeper levels take the last. */
    val headingSizesPt: List<Double> = listOf(16.0, 14.0, 12.0),
    /** The title's size (front matter `title`). */
    val titleSizePt: Double = 18.0,
    /** `left` (ragged right) or `justify`. */
    val align: String = "left",
    /** `letter` or `a4`. */
    val pageSize: String = "letter",
    val pageNumbers: Boolean = false,
    /** Title, author and date on a page of their own. */
    val titlePage: Boolean = false,
    /** `mla`, `apa` or `chicago` for a works-cited list; null when the person named none. */
    val citationStyle: String? = null,
) {
    /** Pure: what is wrong with this style, in plain words; empty when it is fine. */
    fun problems(): List<String> = buildList {
        if (!validFamily(fontFamily)) add("fontFamily must be a font name (letters, digits, spaces, hyphens; at most 64 characters)")
        headingFontFamily?.let { if (!validFamily(it)) add("headingFontFamily must be a font name (letters, digits, spaces, hyphens; at most 64 characters)") }
        if (fontSizePt !in 6.0..72.0) add("fontSizePt must be from 6 to 72")
        if (lineSpacing !in 0.8..3.0) add("lineSpacing must be from 0.8 to 3 (1 single, 2 double)")
        if (marginIn !in 0.25..3.0) add("marginIn must be from 0.25 to 3 inches")
        if (paragraphIndentIn !in 0.0..2.0) add("paragraphIndentIn must be from 0 to 2 inches")
        if (paragraphSpacingPt !in 0.0..72.0) add("paragraphSpacingPt must be from 0 to 72")
        if (headingSizesPt.isEmpty() || headingSizesPt.size > 6 || headingSizesPt.any { it !in 6.0..96.0 }) add("headingSizesPt must list 1 to 6 sizes from 6 to 96")
        if (titleSizePt !in 6.0..96.0) add("titleSizePt must be from 6 to 96")
        if (align !in ALIGNS) add("align must be one of ${ALIGNS.joinToString()}")
        if (pageSize !in PAGE_SIZES) add("pageSize must be one of ${PAGE_SIZES.joinToString()}")
        citationStyle?.let { if (it !in CITATION_STYLES) add("citationStyle must be one of ${CITATION_STYLES.joinToString()}") }
    }

    /** The size of a heading at [level] (1-based). */
    fun headingSize(level: Int): Double = headingSizesPt[(level - 1).coerceIn(0, headingSizesPt.lastIndex)]

    companion object {
        const val DEFAULT_FONT = "Times New Roman"
        val ALIGNS = listOf("left", "justify")
        val PAGE_SIZES = listOf("letter", "a4")
        val CITATION_STYLES = listOf("mla", "apa", "chicago")

        /** An MLA paper: Times New Roman 12, double spaced, 1 inch margins, half-inch indents, headings as body text. */
        val MLA = ReportStyle(
            lineSpacing = 2.0, paragraphIndentIn = 0.5, paragraphSpacingPt = 0.0,
            headingSizesPt = listOf(12.0), titleSizePt = 12.0, pageNumbers = true, citationStyle = "mla",
        )

        private val FAMILY = Regex("^[A-Za-z0-9][A-Za-z0-9 \\-]{0,63}$")

        fun validFamily(name: String): Boolean = FAMILY.matches(name)
    }
}

/** One item of `GET /api/v1/reports`. */
@Serializable
data class ReportSummary(
    val id: String,
    val kind: ReportKind,
    val title: String,
    val createdAt: Instant,
    /** The newest revision's number and time. */
    val latestRevision: Int,
    val updatedAt: Instant,
)

@Serializable
data class ReportList(val reports: List<ReportSummary>)

/** One revision's metadata (its source is `GET …/source?rev=`). */
@Serializable
data class ReportRevisionInfo(val n: Int, val createdAt: Instant, val style: ReportStyle, val chars: Int)

/** A web source a report cites (from search citations). */
@Serializable
data class ReportCitation(val url: String, val title: String? = null, val citedText: String? = null, val fetchedAt: Instant)

/**
 * A format the app can offer: its wire name ([ReportFormat.wireName], the `{format}` of the download
 * route) and its button label ("Word / Pages" for docx). A string, not the enum, so a format added
 * later never breaks an older app's viewer: it shows the server's label.
 */
@Serializable
data class ReportFormatOption(val format: String, val label: String)

/** `GET /api/v1/reports/{id}`: metadata, every revision (oldest first) and the sources. */
@Serializable
data class ReportDetail(
    val id: String,
    val kind: ReportKind,
    val title: String,
    val createdAt: Instant,
    val revisions: List<ReportRevisionInfo>,
    val sources: List<ReportCitation>,
    val formats: List<ReportFormatOption>,
)
