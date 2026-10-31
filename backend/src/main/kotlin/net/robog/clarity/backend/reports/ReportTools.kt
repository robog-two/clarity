package net.robog.clarity.backend.reports

import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import net.robog.clarity.backend.agent.OwnStateTools
import net.robog.clarity.backend.agent.RunScope
import net.robog.clarity.backend.agent.SettingsProvider
import net.robog.clarity.backend.agent.agentTime
import net.robog.clarity.backend.agent.homeZone
import net.robog.clarity.harness.Description
import net.robog.clarity.harness.RunJournal
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolCallContext
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.UrlCitation
import net.robog.clarity.harness.citationsIn
import net.robog.clarity.backend.agent.FETCH_PAGE
import kotlin.time.Instant
import net.robog.clarity.harness.tool
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.governance.Urgency
import net.robog.clarity.shared.report.ReportKind
import net.robog.clarity.shared.report.ReportStyle

/** The report tools every helper gets by default (and the lead too). */
val REPORT_TOOLS: List<String> = listOf("create_report", "edit_report", "set_report_style", "read_report", "list_reports")

/** Shows a finished report to the person: the lead only (helpers never reach the person). */
const val PRESENT_REPORT = "present_report"

/** Attaches the web sources a search helper's answers cited to a report: only helpers with web search. */
const val CITE_SOURCES = "cite_sources"

/** How much of a fetched page is kept as a source's excerpt. */
const val SOURCE_EXCERPT_CHARS = 300

/** A page a run opened with `fetch_page`: the source to cite and when it was fetched. */
data class FetchedSource(val citation: UrlCitation, val fetchedAt: Instant?)

/**
 * Pure: the pages [messages] opened with [FETCH_PAGE] that came back, first fetch of each URL, in
 * order. Read from the result's header (`Title:`, `URL:`, `Fetched:`, `Description:`); the excerpt is
 * the description, else the start of the page's text.
 */
fun fetchedPagesIn(messages: List<Message>): List<FetchedSource> {
    val blocks = messages.flatMap(Message::content)
    val fetches = blocks.filterIsInstance<Block.ToolUse>().filter { it.name == FETCH_PAGE }.map { it.id }.toSet()
    return blocks.filterIsInstance<Block.Result>()
        .filter { it.toolUseId in fetches && !it.isError }
        .mapNotNull { fetchedPage(it.content) }
        .distinctBy { it.citation.url }
}

private fun fetchedPage(content: String): FetchedSource? {
    val header = content.substringBefore("\n\n").lines()
    fun field(name: String) = header.firstOrNull { it.startsWith("$name: ") }?.removePrefix("$name: ")?.trim()?.ifEmpty { null }
    val url = field("URL")?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
    val body = content.substringAfter("\n\n", "").replace(Regex("\\s+"), " ").trim()
    val excerpt = field("Description") ?: body.take(SOURCE_EXCERPT_CHARS).ifEmpty { null }
    val at = field("Fetched")?.let { runCatching { Instant.parse(it) }.getOrNull() }
    return FetchedSource(UrlCitation(url, field("Title"), excerpt?.take(SOURCE_EXCERPT_CHARS)), at)
}

/** How much of a report `read_report` returns at once. */
const val READ_REPORT_CHARS = 12_000

/**
 * A style as the agent gives it: any subset of [ReportStyle]'s fields, laid over a base (the default
 * style for a new report, the current one for `set_report_style`). [preset] `mla` starts from
 * [ReportStyle.MLA], `default` from [ReportStyle]'s defaults.
 */
@Serializable
class StyleInput(
    @Description("Start from a preset: mla (Times New Roman 12 throughout, double spaced, 1 in margins, 0.5 in indents, page numbers) or default (body Times New Roman 12, single spaced, 1 in margins; title and headings in Libre Caslon Text, title bold italic, headings bold). Fields given here override it.")
    val preset: String? = null,
    @Description("Body font, e.g. Times New Roman, Arial, Georgia. Named as is in Word files; PDFs use a free look-alike.") val fontFamily: String? = null,
    @Description("Body size in points, e.g. 12.") val fontSizePt: Double? = null,
    @Description("1 single, 1.5, 2 double.") val lineSpacing: Double? = null,
    @Description("Every page margin, in inches.") val marginIn: Double? = null,
    @Description("First-line indent of paragraphs, in inches (MLA 0.5; 0 none).") val paragraphIndentIn: Double? = null,
    @Description("Space after each paragraph, in points (0 when paragraphs are indented and double spaced).") val paragraphSpacingPt: Double? = null,
    @Description("Title and headings' font (default Libre Caslon Text, the app's serif); empty string = the body's font.") val headingFontFamily: String? = null,
    @Description("Heading sizes in points by level: [#, ##, ###].") val headingSizesPt: List<Double>? = null,
    @Description("Headings bold (default true).") val headingBold: Boolean? = null,
    @Description("Headings italic (default false).") val headingItalic: Boolean? = null,
    @Description("Title size in points (default 20).") val titleSizePt: Double? = null,
    @Description("Title bold (default true).") val titleBold: Boolean? = null,
    @Description("Title italic (default true).") val titleItalic: Boolean? = null,
    @Description("left or justify.") val align: String? = null,
    @Description("letter or a4.") val pageSize: String? = null,
    val pageNumbers: Boolean? = null,
    @Description("Title, author and date on a page of their own.") val titlePage: Boolean? = null,
    @Description("mla, apa or chicago, when the person asked for one.") val citationStyle: String? = null,
) {
    /** Pure: this over [base]; a preset replaces the base first. Null when [preset] is unknown. */
    fun over(base: ReportStyle): ReportStyle? {
        val start = when (preset?.trim()?.lowercase()) {
            null, "" -> base
            "mla" -> ReportStyle.MLA
            "default" -> ReportStyle()
            else -> return null
        }
        return start.copy(
            fontFamily = fontFamily?.trim() ?: start.fontFamily,
            fontSizePt = fontSizePt ?: start.fontSizePt,
            lineSpacing = lineSpacing ?: start.lineSpacing,
            marginIn = marginIn ?: start.marginIn,
            paragraphIndentIn = paragraphIndentIn ?: start.paragraphIndentIn,
            paragraphSpacingPt = paragraphSpacingPt ?: start.paragraphSpacingPt,
            headingFontFamily = when {
                headingFontFamily == null -> start.headingFontFamily
                headingFontFamily.isBlank() -> fontFamily?.trim() ?: start.fontFamily
                else -> headingFontFamily.trim()
            },
            headingSizesPt = headingSizesPt ?: start.headingSizesPt,
            headingBold = headingBold ?: start.headingBold,
            headingItalic = headingItalic ?: start.headingItalic,
            titleSizePt = titleSizePt ?: start.titleSizePt,
            titleBold = titleBold ?: start.titleBold,
            titleItalic = titleItalic ?: start.titleItalic,
            align = align?.trim()?.lowercase() ?: start.align,
            pageSize = pageSize?.trim()?.lowercase() ?: start.pageSize,
            pageNumbers = pageNumbers ?: start.pageNumbers,
            titlePage = titlePage ?: start.titlePage,
            citationStyle = citationStyle?.trim()?.lowercase()?.ifEmpty { null } ?: start.citationStyle,
        )
    }
}

/**
 * [StyleInput] that also takes a bare string as its preset (`"style": "mla"` is `{"preset": "mla"}`).
 * The advertised schema stays the object.
 */
object LenientStyleInput : JsonTransformingSerializer<StyleInput>(StyleInput.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonPrimitive && element.isString) JsonObject(mapOf("preset" to element)) else element
}

/**
 * Pure: the report a `create_report` titled [title] would repeat, from those its run already
 * [created]: a helper makes one report per run (it edits that one after), so any earlier one; the
 * lead's run lasts all day, so only one with the same title.
 */
fun repeatedReport(created: List<StoredReport>, title: String, isLead: Boolean): StoredReport? =
    if (isLead) created.firstOrNull { it.title.trim().equals(title.trim(), ignoreCase = true) } else created.firstOrNull()

/** Pure: why a repeated `create_report` is refused, naming the report to change instead. */
fun repeatedReportText(report: StoredReport): String =
    "This run already created report ${report.id} (\"${report.title}\"). Change it with edit_report, or read it with read_report."

/** Pure: [input] over [base] and checked; Left is the message for the model. */
fun resolveStyle(input: StyleInput?, base: ReportStyle): arrow.core.Either<String, ReportStyle> {
    if (input == null) return base.right()
    val style = input.over(base) ?: return "Unknown preset \"${input.preset}\"; use mla or default.".left()
    val problems = style.problems()
    return if (problems.isEmpty()) style.right() else ("Style not valid: " + problems.joinToString("; ") + ".").left()
}

/**
 * The agent's report tools: drafting (`create_report`, `edit_report`, `set_report_style`), reading
 * (`read_report`, `list_reports`), presenting a finished one (`present_report`, the lead's, through the
 * `send_to_user` path), and attaching web search citations (`cite_sources`, a search helper's).
 * Everything writes only Clarity's own state (effect `WRITE`); nothing leaves until the person
 * downloads a file.
 */
@Inject
@SingleIn(AppScope::class)
class ReportTools(
    private val store: ReportStore,
    private val own: OwnStateTools,
    private val journal: RunJournal,
    private val settings: SettingsProvider,
    private val clock: Clock,
) {
    @Serializable
    class CreateInput(
        @Description("Short title, e.g. \"The causes of the 1918 flu pandemic\".") val title: String,
        @Description("The whole document in Markdown: # headings, paragraphs, lists, tables, > quotes. Optional YAML front matter (title, author, date) at the top.")
        val source: String,
        @Description("How it should look, from what the person asked for (fonts, sizes, spacing, margins, heading font/bold/italic/size, MLA...). Omit for the default: body Times New Roman 12, single spaced, 1 in margins; title and headings in Libre Caslon Text.")
        @Serializable(with = LenientStyleInput::class) val style: StyleInput? = null,
    )

    @Serializable
    class EditInput(
        val id: String,
        @Description("Text to replace, copied exactly from read_report; must occur exactly once (add surrounding text if it repeats).") val old: String,
        @Description("The replacement (empty to delete).") val new: String,
    )

    @Serializable
    class StyleChangeInput(val id: String, @Description("Only the fields to change; the rest stay as they are.") @Serializable(with = LenientStyleInput::class) val style: StyleInput)

    @Serializable
    class ReadInput(val id: String, @Description("First line to show (default 1).") val fromLine: Int = 1, @Description("A revision number; default the latest.") val revision: Int? = null)

    @Serializable
    class NoInput

    @Serializable
    class PresentInput(
        val id: String,
        @Description("One or two lines to the person about it (optional).") val message: String? = null,
        @Description("normal (default) shows it in the app; urgent notifies now; batch waits for the evening summary.") val urgency: Urgency = Urgency.NORMAL,
    )

    @Serializable
    class CiteInput(
        val id: String,
        @Description("Only these URLs (pages you fetched); omit to attach every page fetched in this task so far.") val urls: List<String>? = null,
    )

    /** The lead's report tools: drafting, reading and presenting. */
    fun leadTools(scope: RunScope): List<Tool> = drafting(scope) + presentReport()

    /** A helper's report tools: drafting, reading, and citing its search sources. */
    fun helperTools(scope: RunScope): List<Tool> = drafting(scope) + citeSources()

    private fun drafting(scope: RunScope): List<Tool> = listOf(createReport(scope), editReport(scope), setReportStyle(scope), readReport(), listReports())

    fun createReport(scope: RunScope): Tool = tool<CreateInput>(
        name = "create_report",
        description = "Create a document for the person from Markdown; it shows in their Goods tab at once. Returns its id; keep the id, not the text. " +
            "A document you are asked for goes here, not into your reply. " +
            "It downloads as PDF, Word / Pages, OpenDocument and plain text.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        val title = input.title.trim()
        when {
            title.isEmpty() || title.length > MAX_REPORT_TITLE_CHARS -> ToolResult.Error("`title` must be 1-$MAX_REPORT_TITLE_CHARS characters.")
            input.source.isBlank() -> ToolResult.Error("`source` must not be empty.")
            input.source.length > MAX_REPORT_SOURCE_CHARS -> ToolResult.Error("`source` is longer than $MAX_REPORT_SOURCE_CHARS characters; split it into several reports.")
            else -> repeatedReport(store.createdBy(scope.runId), title, scope.isLead)?.let { ToolResult.Error(repeatedReportText(it)) }
                ?: resolveStyle(input.style, ReportStyle()).fold({ ToolResult.Error(it) }) { style ->
                    val (report, revision) = store.create(ReportKind.DOCUMENT, title, input.source, style, scope.runId)
                    ToolResult.Ok("Report ${report.id} created: \"${report.title}\", revision 1, ${revision.source.lines().size} lines. Style: ${describeStyle(style)}.")
                }
        }
    }

    fun editReport(scope: RunScope): Tool = tool<EditInput>(
        name = "edit_report",
        description = "Change a report by replacing one exact piece of text in its latest revision (like a text editor). Makes a new revision; old ones are kept.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        store.addRevision(input.id.trim(), scope.runId) { latest ->
            replaceUnique(latest.source, input.old, input.new).map { RevisionChange(it, latest.style) }
        }.fold(
            { ToolResult.Error(it) },
            { ToolResult.Ok("Report ${it.reportId}: revision ${it.n} saved (${it.source.lines().size} lines).") },
        )
    }

    fun setReportStyle(scope: RunScope): Tool = tool<StyleChangeInput>(
        name = "set_report_style",
        description = "Change how a report looks (body font and size, spacing, margins, indent, the title's and headings' font, size, bold and italic, page size, MLA...). Makes a new revision with the same text.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        store.addRevision(input.id.trim(), scope.runId) { latest ->
            resolveStyle(input.style, latest.style).flatMapStyle(latest)
        }.fold(
            { ToolResult.Error(it) },
            { ToolResult.Ok("Report ${it.reportId}: revision ${it.n} saved. Style: ${describeStyle(it.style)}.") },
        )
    }

    fun readReport(): Tool = tool<ReadInput>(
        name = "read_report",
        description = "Read a report's Markdown (a page at a time, with line numbers to continue from) and its style.",
        json = ClarityJson,
    ) { input ->
        val id = input.id.trim()
        val report = store.report(id) ?: return@tool ToolResult.Error("No report with id $id.")
        val revision = store.revision(id, input.revision) ?: return@tool ToolResult.Error("Report $id has no revision ${input.revision}.")
        ToolResult.Ok(
            "Report $id \"${report.title}\", revision ${revision.n}, ${revision.source.lines().size} lines, ${revision.source.length} characters.\n" +
                "Style: ${describeStyle(revision.style)}.\n\n" + clipSource(revision.source, input.fromLine, READ_REPORT_CHARS),
        )
    }

    fun listReports(): Tool = tool<NoInput>(
        name = "list_reports",
        description = "List the documents made for the person (their Goods), newest first (id, title, revision, when).",
        json = ClarityJson,
    ) {
        val reports = store.list(limit = 30)
        val zone = settings.homeZone()
        ToolResult.Ok(
            if (reports.isEmpty()) "(none)"
            else reports.joinToString("\n") { "${it.id} \"${it.title}\" revision ${it.latestRevision}, updated ${agentTime(it.updatedAt, zone)}" },
        )
    }

    fun presentReport(): Tool = tool<PresentInput>(
        name = PRESENT_REPORT,
        description = "Hand a finished report to the person: a card in the feed they can open and download (PDF, Word / Pages, ...). " +
            "It is already in their Goods tab; this tells them. Same delivery rules as send_to_user; this counts as answering them.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        val id = input.id.trim()
        val report = store.report(id) ?: return@tool ToolResult.Error("No report with id $id.")
        val formats = formatsFor(report.kind)
        if (formats.isEmpty()) return@tool ToolResult.Error("A ${report.kind.wireName} report cannot be shown yet.")
        val cards = listOfNotNull(
            input.message?.trim()?.takeIf { it.isNotEmpty() }?.let { Card.Text(it) },
            Card.Report(report.id, report.title, formats.map { it.wireName }),
        )
        own.post(cards, input.urgency, sessionId = null, about = emptyList())
    }

    fun citeSources(): Tool = tool<CiteInput>(
        name = CITE_SOURCES,
        description = "Attach the pages you opened with fetch_page (in this task) to a report, so its works-cited list comes from pages you read.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        val id = input.id.trim()
        if (store.report(id) == null) return@tool ToolResult.Error("No report with id $id.")
        val runId = coroutineContext[ToolCallContext]?.runId ?: return@tool ToolResult.Error("No search results to cite here.")
        val messages = journal.load(runId)
        // Pages the run fetched itself come first; search citations only when it fetched none.
        val fetched = fetchedPagesIn(messages)
        val cited = fetched.map { it.citation }.ifEmpty { citationsIn(messages) }
        val wanted = input.urls?.map { it.trim() }?.toSet()
        val chosen = if (wanted == null) cited else cited.filter { it.url in wanted }
        when {
            cited.isEmpty() -> ToolResult.Error("You have opened no pages yet; open the ones you rely on with fetch_page first.")
            chosen.isEmpty() -> ToolResult.Error("None of those URLs were fetched. Fetched so far: ${cited.take(20).joinToString { it.url }}")
            else -> {
                val times = fetched.mapNotNull { page -> page.fetchedAt?.let { page.citation.url to it } }.toMap()
                val added = store.addSources(id, chosen, clock.now(), times)
                ToolResult.Ok("Report $id: $added new source(s) attached (${chosen.size - added} already there).")
            }
        }
    }
}

/** The style change as a new revision of the same text; Left when nothing changes. */
private fun arrow.core.Either<String, ReportStyle>.flatMapStyle(latest: StoredRevision): arrow.core.Either<String, RevisionChange> =
    when (this) {
        is arrow.core.Either.Left -> this
        is arrow.core.Either.Right -> if (value == latest.style) "That is already the report's style; nothing changed.".left()
        else RevisionChange(latest.source, value).right()
    }
