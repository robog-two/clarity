package net.robog.clarity.backend.reports

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.agent.DbUsageRecorder
import net.robog.clarity.backend.agent.LeadRig
import net.robog.clarity.backend.agent.RunScope
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.DevicePrincipal
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Role
import net.robog.clarity.harness.ToolCallContext
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.UrlCitation
import net.robog.clarity.harness.Usage
import net.robog.clarity.harness.citationsBlock
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.report.ReportDetail
import net.robog.clarity.shared.report.ReportFormat
import net.robog.clarity.shared.report.ReportKind
import net.robog.clarity.shared.report.ReportList
import net.robog.clarity.shared.report.ReportStyle
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

/** A renderer that never runs pandoc: it answers with the format and source length and counts its calls. */
class CountingRenderer(var currentVersion: String? = "v1") : Renderer {
    val calls = CopyOnWriteArrayList<Pair<ReportFormat, ReportStyle>>()
    var failure: RenderError? = null

    override suspend fun version(): String? = currentVersion

    override suspend fun render(source: String, style: ReportStyle, format: ReportFormat): Either<RenderError, Rendered> {
        failure?.let { return it.left() }
        calls += format to style
        return Rendered("${format.wireName}:${source.length}:${currentVersion}".encodeToByteArray(), currentVersion ?: "unknown").right()
    }
}

private fun args(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) ->
        when (v) {
            null -> Unit
            is String -> put(k, v)
            is Number -> put(k, v)
            is Boolean -> put(k, v)
            is JsonObject -> put(k, v)
            is List<*> -> put(k, JsonArray(v.map { JsonPrimitive(it.toString()) }))
            else -> error("unsupported $v")
        }
    }
}

private val REPORT_ID = Regex("Report ([0-9a-f-]{36}) created")

/**
 * Reports against real Postgres: the migration, revisions and edits through the agent's tools, the
 * rendition cache and its sweeper, the API routes (device auth included), the report card, citations
 * and the web-search usage column. The renderer is a fake; no model is ever called.
 */
@EnabledIf(ContainerRuntimeAvailable::class)
class ReportDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database
    val dataDir = Files.createTempDirectory("clarity-reports").resolve("data")

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }

    suspend fun rig(): LeadRig = LeadRig(database).also { it.reset() }

    class Cache(database: Database, dir: java.nio.file.Path) {
        val store = ExposedReportStore(database, Clock.System)
        val renderer = CountingRenderer()
        val blobs = ReportBlobs(reportsAppConfig(dir))
        val service = ReportService(store, renderer, blobs, Clock.System)
    }

    suspend fun created(r: LeadRig, title: String = "The 1918 flu", source: String = "# Causes\n\nThe flu spread in 1918.\n\n# Spread\n\nIt spread fast.", style: JsonObject? = null): String {
        val tool = r.reportTools.createReport(RunScope("lead-thread", isLead = true))
        val out = tool.invoke(args("title" to title, "source" to source, "style" to style)).shouldBeInstanceOf<ToolResult.Ok>().content
        return REPORT_ID.find(out).shouldNotBeNull().groupValues[1]
    }

    test("V25 adds the report tables and a web search count on model_call") {
        val tables = database.tx {
            exec("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name LIKE 'report%'") { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }.orEmpty()
        tables.sorted() shouldContainExactly listOf("report", "report_rendition", "report_revision", "report_source")
        val column = database.tx {
            exec("SELECT column_default FROM information_schema.columns WHERE table_name = 'model_call' AND column_name = 'web_search_requests'") { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }
        column shouldBe "0"
        // revisions are unique per report and number
        val r = rig()
        val id = created(r)
        val duplicate = runCatching {
            database.tx { exec("INSERT INTO report_revision (report_id, n, source, style, created_at) VALUES ('$id', 1, 'x', '{}'::jsonb, now())") }
        }
        duplicate.isFailure shouldBe true
    }

    test("create_report makes revision 1 in the default style; a bad style is refused and creates nothing") {
        val r = rig()
        val id = created(r)
        val first = r.reportStore.revision(id).shouldNotBeNull()
        first.n shouldBe 1
        first.style shouldBe ReportStyle()
        r.reportStore.report(id)!!.let {
            it.kind shouldBe ReportKind.DOCUMENT
            it.createdByRun shouldBe "lead-thread"
        }
        val bad = r.reportTools.createReport(RunScope("lead-thread", isLead = true))
            .invoke(args("title" to "x", "source" to "y", "style" to buildJsonObject { put("fontSizePt", 500) }))
        bad.shouldBeInstanceOf<ToolResult.Error>().message shouldContain "fontSizePt"
        r.reportStore.list().shouldHaveSize(1)
        val mla = created(r, "MLA paper", "Body.", buildJsonObject { put("preset", "mla") })
        r.reportStore.revision(mla)!!.style shouldBe ReportStyle.MLA
    }

    test("edit_report makes a new revision from one exact match and keeps the old one; no match or several matches are errors") {
        val r = rig()
        val id = created(r)
        val edit = r.reportTools.editReport(RunScope("h1", isLead = false))
        edit.invoke(args("id" to id, "old" to "spread fast", "new" to "spread across the world")).shouldBeInstanceOf<ToolResult.Ok>().content shouldContain "revision 2"
        r.reportStore.revision(id)!!.source shouldContain "spread across the world"
        r.reportStore.revision(id, 1)!!.source shouldContain "It spread fast."
        r.reportStore.revision(id)!!.createdByRun shouldBe "h1"

        edit.invoke(args("id" to id, "old" to "pandemic", "new" to "x")).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "No match"
        edit.invoke(args("id" to id, "old" to "spread", "new" to "x")).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "matches"
        edit.invoke(args("id" to "00000000-0000-0000-0000-000000000000", "old" to "a", "new" to "b")).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "No report"
        r.reportStore.revisions(id).map { it.n } shouldContainExactly listOf(1, 2)
    }

    test("set_report_style changes only the look, as a new revision; the same style again changes nothing") {
        val r = rig()
        val id = created(r)
        val tool = r.reportTools.setReportStyle(RunScope("lead-thread", isLead = true))
        tool.invoke(args("id" to id, "style" to buildJsonObject { put("preset", "mla") })).shouldBeInstanceOf<ToolResult.Ok>()
        val second = r.reportStore.revision(id)!!
        second.n shouldBe 2
        second.style shouldBe ReportStyle.MLA
        second.source shouldBe r.reportStore.revision(id, 1)!!.source
        tool.invoke(args("id" to id, "style" to buildJsonObject { put("fontSizePt", 11) })).shouldBeInstanceOf<ToolResult.Ok>()
        r.reportStore.revision(id)!!.style shouldBe ReportStyle.MLA.copy(fontSizePt = 11.0)
        tool.invoke(args("id" to id, "style" to buildJsonObject { put("fontSizePt", 11) })).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "already"
    }

    test("read_report and list_reports give ids, lines and style, never more than a page") {
        val r = rig()
        val long = (1..2_000).joinToString("\n") { "Line $it of a long essay about the flu." }
        val id = created(r, "Long", long)
        val read = r.reportTools.readReport().invoke(args("id" to id)).shouldBeInstanceOf<ToolResult.Ok>().content
        read shouldContain "2000 lines"
        read shouldContain "Times New Roman 12 pt"
        read shouldContain "fromLine="
        (read.length < READ_REPORT_CHARS + 1_000) shouldBe true
        r.reportTools.readReport().invoke(args("id" to id, "fromLine" to 1999)).shouldBeInstanceOf<ToolResult.Ok>().content shouldContain "Line 2000 of"
        r.reportTools.listReports().invoke(JsonObject(emptyMap())).shouldBeInstanceOf<ToolResult.Ok>().content shouldContain "$id \"Long\" revision 1"
    }

    test("present_report posts a report card through the send_to_user path; send_to_user itself refuses report cards") {
        val r = rig()
        val id = created(r)
        val presented = r.reportTools.presentReport().invoke(args("id" to id, "message" to "Here is your essay."))
        presented.shouldBeInstanceOf<ToolResult.Ok>().content shouldContain "Delivered"
        val item = r.feed.page(0, 10).items.last()
        item.cards shouldBe listOf(Card.Text("Here is your essay."), Card.Report(id, "The 1918 flu", listOf("pdf", "docx", "odt", "txt", "md")))
        item.notify shouldBe "in_app"

        r.reportTools.presentReport().invoke(args("id" to "00000000-0000-0000-0000-000000000000")).shouldBeInstanceOf<ToolResult.Error>()
        r.own.sendToUser().invoke(buildJsonObject {
            put("cards", JsonArray(listOf(buildJsonObject { put("type", "report"); put("reportId", id); put("title", "x"); put("formats", JsonArray(emptyList())) })))
        }).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "present_report"
    }

    test("the lead presents and helpers draft: who gets which report tools") {
        val r = rig()
        val lead = r.toolbox.leadTools(RunScope("lead", isLead = true)).tools.map { it.name }
        (REPORT_TOOLS + PRESENT_REPORT).forEach { (it in lead) shouldBe true }
        (CITE_SOURCES in lead) shouldBe false
        val helper = r.toolbox.helperTools(RunScope("h", isLead = false), null).tools.tools.map { it.name }
        REPORT_TOOLS.forEach { (it in helper) shouldBe true }
        (PRESENT_REPORT in helper) shouldBe false
        (CITE_SOURCES in helper) shouldBe false
    }

    test("cite_sources attaches the URLs this run's answers cited, once each") {
        val r = rig()
        val id = created(r)
        val runId = "6f1c3c1e-6a43-4c84-9a6c-0d6b1b0e9a11"
        r.journal.append(runId, Message.user("research"))
        r.journal.append(
            runId,
            Message(
                Role.ASSISTANT,
                listOf(
                    Block.Text("found it"),
                    citationsBlock(listOf(UrlCitation("https://www.cdc.gov/flu/1918", "1918 Pandemic", "about 50 million"), UrlCitation("https://example.org/b"))),
                ),
            ),
        )
        val tool = r.reportTools.citeSources()
        withContext(ToolCallContext(runId, "t1")) {
            tool.invoke(args("id" to id, "urls" to listOf("https://www.cdc.gov/flu/1918"))).shouldBeInstanceOf<ToolResult.Ok>().content shouldContain "1 new source"
            tool.invoke(args("id" to id)).shouldBeInstanceOf<ToolResult.Ok>().content shouldContain "1 new source"
            tool.invoke(args("id" to id, "urls" to listOf("https://nowhere.example"))).shouldBeInstanceOf<ToolResult.Error>()
        }
        withContext(ToolCallContext("another-run", "t2")) {
            tool.invoke(args("id" to id)).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "search first"
        }
        r.reportStore.sources(id).map { it.url to it.citedText } shouldContainExactly listOf(
            "https://www.cdc.gov/flu/1918" to "about 50 million",
            "https://example.org/b" to null,
        )
    }

    test("downloads render once and come from the cache after; Markdown is never rendered") {
        val r = rig()
        val id = created(r)
        val c = Cache(database, dataDir)
        val first = c.service.download(id, null, ReportFormat.PDF).getOrNull().shouldNotBeNull()
        first.filename shouldBe "The 1918 flu.pdf"
        val again = c.service.download(id, null, ReportFormat.PDF).getOrNull().shouldNotBeNull()
        again.bytes.decodeToString() shouldBe first.bytes.decodeToString()
        c.renderer.calls.map { it.first } shouldContainExactly listOf(ReportFormat.PDF)
        val row = c.store.rendition(c.store.revision(id)!!.id, ReportFormat.PDF, "v1").shouldNotBeNull()
        c.blobs.exists(row.blobPath) shouldBe true
        row.sha256 shouldBe sha256Hex(first.bytes)

        c.service.download(id, null, ReportFormat.MD).getOrNull()!!.bytes.decodeToString() shouldStartWith "# Causes"
        c.renderer.calls shouldHaveSize 1

        // A new renderer version renders again; a renderer that is down serves the newest file it has.
        c.renderer.currentVersion = "v2"
        c.service.download(id, null, ReportFormat.PDF).getOrNull()!!.bytes.decodeToString() shouldContain ":v2"
        c.renderer.calls shouldHaveSize 2
        c.renderer.currentVersion = null
        c.service.download(id, null, ReportFormat.PDF).getOrNull()!!.bytes.decodeToString() shouldContain ":v2"
        c.renderer.calls shouldHaveSize 2

        // A row whose file is gone is rendered again.
        c.renderer.currentVersion = "v2"
        c.blobs.delete(c.store.rendition(c.store.revision(id)!!.id, ReportFormat.PDF, "v2")!!.blobPath)
        c.service.download(id, null, ReportFormat.PDF).getOrNull().shouldNotBeNull()
        c.renderer.calls shouldHaveSize 3

        // An older revision downloads under its own name; a missing one is not found.
        r.reportTools.editReport(RunScope("h", isLead = false)).invoke(args("id" to id, "old" to "It spread fast.", "new" to "It spread.")).shouldBeInstanceOf<ToolResult.Ok>()
        c.service.download(id, 1, ReportFormat.DOCX).getOrNull()!!.filename shouldBe "The 1918 flu-rev1.docx"
        c.service.download(id, 9, ReportFormat.DOCX).shouldBeInstanceOf<Either.Left<DownloadError>>().value.shouldBeInstanceOf<DownloadError.NotFound>()
        c.renderer.failure = RenderError.Failed("pandoc: bad")
        c.service.download(id, null, ReportFormat.ODT).shouldBeInstanceOf<Either.Left<DownloadError>>().value.shouldBeInstanceOf<DownloadError.Render>()
    }

    test("the sweeper drops old-version files and then the least recently downloaded down to the cap, never revisions") {
        val r = rig()
        val c = Cache(database, Files.createTempDirectory("clarity-sweep"))
        val ids = (1..3).map { created(r, "Report $it", "Text number $it.") }
        ids.forEach { c.service.download(it, null, ReportFormat.PDF).getOrNull().shouldNotBeNull() }
        // the first one downloaded again: now the most recent
        c.service.download(ids[0], null, ReportFormat.PDF)
        val all = c.store.renditionsByAccess()
        all shouldHaveSize 3
        all.first().revisionId shouldBe c.store.revision(ids[1])!!.id

        val keepOne = all.last().sizeBytes
        sweepRenditions(c.store, c.blobs, maxBytes = keepOne, currentVersion = "v1") shouldBe 2
        c.store.renditionsByAccess().map { it.revisionId } shouldContainExactly listOf(c.store.revision(ids[0])!!.id)
        all.dropLast(1).forEach { c.blobs.exists(it.blobPath) shouldBe false }
        ids.forEach { c.store.revision(it).shouldNotBeNull() }

        // a newer renderer makes the rest stale, so they go whatever the cap
        sweepRenditions(c.store, c.blobs, maxBytes = Long.MAX_VALUE, currentVersion = "v9") shouldBe 1
        c.store.renditionsByAccess().shouldBeEmpty()
        r.reportStore.list().shouldHaveSize(3)
    }

    test("the API: device auth on every route, the list, details with labels, the source, and downloads with a good file name") {
        val r = rig()
        val id = created(r)
        r.reportStore.addSources(id, listOf(UrlCitation("https://www.cdc.gov/flu/1918", "1918 Pandemic")), Clock.System.now())
        val c = Cache(database, Files.createTempDirectory("clarity-routes"))
        testApplication {
            application {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(ClarityJson) }
                install(Authentication) { bearer(AuthProviders.DEVICE) { authenticate { DevicePrincipal(DeviceId("d1"), null) } } }
                ReportRoutes(c.service, c.store).install(this)
            }
            val client = createClient { }
            suspend fun fetch(path: String, auth: Boolean = true) = client.get(path) { if (auth) header(HttpHeaders.Authorization, "Bearer device") }

            listOf("/api/v1/reports", "/api/v1/reports/$id", "/api/v1/reports/$id/source", "/api/v1/reports/$id/download/pdf").forEach { path ->
                fetch(path, auth = false).status shouldBe HttpStatusCode.Unauthorized
            }
            c.renderer.calls.shouldBeEmpty()

            val list = ClarityJson.decodeFromString(ReportList.serializer(), fetch("/api/v1/reports").bodyAsText())
            list.reports.single().id shouldBe id

            val detail = ClarityJson.decodeFromString(ReportDetail.serializer(), fetch("/api/v1/reports/$id").bodyAsText())
            detail.title shouldBe "The 1918 flu"
            detail.revisions.single().n shouldBe 1
            detail.sources.single().url shouldBe "https://www.cdc.gov/flu/1918"
            detail.formats.single { it.format == ReportFormat.DOCX.wireName }.label shouldBe "Word / Pages"

            val source = fetch("/api/v1/reports/$id/source?rev=1")
            source.status shouldBe HttpStatusCode.OK
            source.bodyAsText() shouldStartWith "# Causes"
            source.headers["X-Report-Revision"] shouldBe "1"

            val docx = fetch("/api/v1/reports/$id/download/docx")
            docx.status shouldBe HttpStatusCode.OK
            docx.headers[HttpHeaders.ContentType]!! shouldStartWith ReportFormat.DOCX.mimeType
            docx.headers[HttpHeaders.ContentDisposition]!! shouldContain "filename=\"The 1918 flu.docx\""
            docx.bodyAsBytes().decodeToString() shouldStartWith "docx:"

            fetch("/api/v1/reports/$id/download/pages").status shouldBe HttpStatusCode.BadRequest
            fetch("/api/v1/reports/$id/download/pdf?rev=0").status shouldBe HttpStatusCode.BadRequest
            fetch("/api/v1/reports/00000000-0000-0000-0000-000000000000").status shouldBe HttpStatusCode.NotFound
            fetch("/api/v1/reports/not-a-uuid/download/pdf").status shouldBe HttpStatusCode.NotFound
            c.renderer.failure = RenderError.Failed("pandoc: bad")
            fetch("/api/v1/reports/$id/download/odt").status shouldBe HttpStatusCode.UnprocessableEntity
            c.renderer.failure = RenderError.Unavailable("down")
            fetch("/api/v1/reports/$id/download/txt").status shouldBe HttpStatusCode.ServiceUnavailable
        }
    }

    test("a model call's web searches are recorded on model_call") {
        database.tx { exec("TRUNCATE model_call RESTART IDENTITY") }
        DbUsageRecorder(database, Clock.System).record("deepseek/deepseek-v4.1-flash-1017", Usage(100, 20, reportedCostMicros = 3_000, webSearchRequests = 3), AgentRole.HELPER)
        DbUsageRecorder(database, Clock.System).record("xiaomi/mimo-v2.6-pro", Usage(100, 20), AgentRole.LEAD)
        val rows = database.tx {
            exec("SELECT role, web_search_requests FROM model_call ORDER BY id") { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to rs.getInt(2)) }
            }
        }.orEmpty()
        rows shouldContainExactly listOf("helper" to 3, "lead" to 0)
    }
})

