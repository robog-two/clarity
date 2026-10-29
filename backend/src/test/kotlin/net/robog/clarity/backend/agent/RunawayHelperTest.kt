package net.robog.clarity.backend.agent

import arrow.core.Either
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.config.WebSearchConfig
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.CITATIONS_TYPE
import net.robog.clarity.harness.CurrentRun
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.ModelRequest
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.OpenRouterModelGateway
import net.robog.clarity.harness.SEARCH_NOTE_HEADING
import net.robog.clarity.harness.StopReason
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.UrlCitation
import net.robog.clarity.harness.Usage
import net.robog.clarity.harness.citationsBlock
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

private val CREATED_ID = Regex("Report ([0-9a-f-]{36}) created")

/** Every text the model was shown in [request]: message text and tool results. */
private fun seen(request: ModelRequest): String = request.messages.flatMap(Message::content).joinToString("\n") {
    when (it) {
        is Block.Text -> it.text
        is Block.Result -> it.content
        else -> ""
    }
}

/** The text of the last user message of [request] (tool results and notes of the step before). */
private fun lastUserText(request: ModelRequest): String = seen(request.copy(messages = listOf(request.messages.last())))

private val SILENCE_DOC = "# Le Roman de Silence: A Refresher\n\n" +
    "Heldris of Cornwall's thirteenth-century romance follows Silence, raised as a boy. ".repeat(30) +
    "\n\n## Works cited\n\n1. Roche-Mahdi, ed. Silence (1992)."

/** One step of today's runaway: it claims it never searched, searches again, and writes the whole document again. */
private fun runawayStep(n: Int, searched: Boolean) = ModelResponse(
    listOf(
        Block.Text("I have still not run any searches. Doing that now. … Real data at last. Writing the refresher."),
        citationsBlock(listOf(UrlCitation("https://example.org/silence/$n", "Silence source $n", "The romance survives in one manuscript ($n)."))),
        Block.ToolUse(
            "c$n", "create_report",
            buildJsonObject {
                put("title", "Le Roman de Silence: A Refresher")
                put("source", SILENCE_DOC)
            },
        ),
    ),
    StopReason.TOOL_USE,
    // One cent a call, and a search each call while the run still has search.
    Usage(10_000, 1_000, reportedCostMicros = 10_000, webSearchRequests = if (searched) 1 else 0),
)

/**
 * A research helper replaying the run that made one report 36 times (scripted model, real Postgres,
 * no network): its earlier searches are read back to it, its second create_report is refused, and
 * the search and spend caps stop it. Also the save nudge, and research without the sandbox.
 */
@EnabledIf(ContainerRuntimeAvailable::class)
class RunawayHelperTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec {
        scope.cancel()
        postgres.stop()
    }

    /** Serves the rig's helpers (open runs resume) until [block] is done. */
    suspend fun LeadRig.serving(block: suspend LeadRig.() -> Unit) {
        val job = scope.launch { helpers.run() }
        try {
            block()
        } finally {
            job.cancelAndJoin()
        }
    }

    suspend fun LeadRig.awaitRun(id: String) {
        withTimeout(30.seconds) { while (runs.get(id)?.status.let { it == null || it in RunStatus.OPEN }) delay(50) }
        helpers.joinAll()
    }

    suspend fun LeadRig.startResearch(): String {
        val started = helpers.start("Write a sourced refresher on Le Roman de Silence as a report.", listOf(WEB_SEARCH), null, RunScope("lead", isLead = true))
        return started.shouldBeInstanceOf<Either.Right<Delegated>>().value.id
    }

    test("a runaway research helper sees its searches, keeps one report, and the search and spend caps stop it") {
        val r = LeadRig(database, usageRecorder = DbUsageRecorder(database, Clock.System)).also { it.reset() }
        var n = 0
        r.model.handler = { request -> n++; runawayStep(n, searched = request.webSearch != null) }
        lateinit var id: String
        r.serving { id = startResearch(); awaitRun(id) }

        val requests = r.model.requestsFor(HELPER_MODEL)
        // $0.15 at a cent a call: 15 calls, then one wrap-up call whose tools never run.
        requests shouldHaveSize 16
        r.runs.costUsed(id) shouldBe 160_000L
        // The search cap counts the usage's searches: 10 requests carry search, then none.
        val cap = WebSearchConfig().maxSearchesPerRun
        requests.take(cap).forEach { it.webSearch.shouldNotBeNull() }
        requests.drop(cap).forEach { it.webSearch.shouldBeNull() }
        r.runs.webSearchesUsed(id) shouldBe cap.toLong()

        // From the second call on, the model reads what it searched before, as text, never the raw block.
        for ((i, request) in requests.withIndex().drop(1)) {
            val wire = OpenRouterModelGateway.buildBody(request).toString()
            wire shouldContain SEARCH_NOTE_HEADING
            wire shouldContain "https://example.org/silence/$i"
            wire shouldNotContain CITATIONS_TYPE
        }

        // One report; every later create_report was refused with the report to change instead.
        val reports = r.reportStore.createdBy(id)
        reports shouldHaveSize 1
        val report = reports.single()
        val results = r.journal.load(id).flatMap { it.content }.filterIsInstance<Block.Result>()
        results.first { it.toolUseId == "c1" }.isError shouldBe false
        for (k in 2..15) {
            val refused = results.first { it.toolUseId == "c$k" }
            refused.isError shouldBe true
            refused.content shouldContain "This run already created report ${report.id} (\"${report.title}\")"
        }
        // The wrap-up call was asked to report, and its create_report never ran.
        lastUserText(requests.last()) shouldContain "spend limit"
        results.first { it.toolUseId == "c16" }.content shouldContain "spend limit"

        // The lead hears the helper hit its spend limit; the save nudge never fired.
        val run = r.runs.get(id).shouldNotBeNull()
        run.status shouldBe RunStatus.FAILED
        run.result.shouldNotBeNull() shouldContain "spend limit"
        requests.none { HELPER_SAVE_NUDGE in seen(it) } shouldBe true
    }

    test("a resumed run that already spent its cap only wraps up") {
        val r = LeadRig(database, usageRecorder = DbUsageRecorder(database, Clock.System)).also { it.reset() }
        val id = "5a0c9e3d-1f7b-4c2a-9d4e-8b6f2a1c3e57"
        r.runs.create(id, null, "helper", "Research Silence.", listOf(WEB_SEARCH), Clock.System.now())
        withContext(CurrentRun(id)) { DbUsageRecorder(database, Clock.System).record(HELPER_MODEL, Usage(100, 10, reportedCostMicros = 200_000), AgentRole.HELPER) }
        r.model.handler = { end("Got as far as the outline.") }
        r.serving { awaitRun(id) }
        val requests = r.model.requestsFor(HELPER_MODEL)
        requests shouldHaveSize 1
        requests.single().webSearch.shouldBeNull()
        lastUserText(requests.single()) shouldContain "spend limit"
        r.runs.get(id)!!.result.shouldNotBeNull() shouldContain "spend limit"
    }

    test("a helper that wrote the document as its reply is nudged once to save it") {
        val r = LeadRig(database).also { it.reset() }
        r.model.handler = { request ->
            val text = seen(request)
            val created = CREATED_ID.find(text)?.groupValues?.get(1)
            when {
                created != null -> end("Report $created: a refresher on Silence.")
                HELPER_SAVE_NUDGE in text -> callTool(
                    "save", "create_report",
                    buildJsonObject {
                        put("title", "Le Roman de Silence: A Refresher")
                        put("source", SILENCE_DOC)
                    },
                )
                else -> end(SILENCE_DOC)
            }
        }
        lateinit var id: String
        r.serving {
            id = helpers.start("Write a refresher on Le Roman de Silence as a report.", null, null, RunScope("lead", isLead = true))
                .shouldBeInstanceOf<Either.Right<Delegated>>().value.id
            awaitRun(id)
        }
        val requests = r.model.requestsFor(HELPER_MODEL)
        requests shouldHaveSize 3
        requests.count { lastUserText(it).contains(HELPER_SAVE_NUDGE) } shouldBe 1
        r.reportStore.createdBy(id) shouldHaveSize 1
        r.runs.get(id)!!.status shouldBe RunStatus.DONE
        r.runs.get(id)!!.result.shouldNotBeNull() shouldContain "a refresher on Silence"
    }

    test("no save nudge when the report was saved, or the reply is not a document") {
        val r = LeadRig(database).also { it.reset() }
        // Saved first, then the whole document as the reply as well: the report exists, so no nudge.
        r.model.handler = { request ->
            if (CREATED_ID.find(seen(request)) == null) {
                callTool("save", "create_report", buildJsonObject { put("title", "Silence"); put("source", SILENCE_DOC) })
            } else {
                end(SILENCE_DOC)
            }
        }
        r.serving {
            val saved = helpers.start("Write a refresher as a report.", null, null, RunScope("lead", isLead = true))
                .shouldBeInstanceOf<Either.Right<Delegated>>().value.id
            awaitRun(saved)
        }
        r.model.requestsFor(HELPER_MODEL) shouldHaveSize 2
        r.model.requests.none { HELPER_SAVE_NUDGE in seen(it) } shouldBe true

        // A short answer is a result, not a document (a fresh rig: a helper manager serves once).
        val q = LeadRig(database)
        q.model.handler = { end("The romance survives in one manuscript, at Nottingham.") }
        lateinit var short: String
        q.serving {
            short = helpers.start("Where does the manuscript of Silence survive?", null, null, RunScope("lead", isLead = true))
                .shouldBeInstanceOf<Either.Right<Delegated>>().value.id
            awaitRun(short)
        }
        q.model.requestsFor(HELPER_MODEL) shouldHaveSize 1
        q.runs.get(short)!!.status shouldBe RunStatus.DONE
    }

    test("research does not start without the sandbox's fetch_page") {
        val r = LeadRig(database, sandboxTools = emptyList()).also { it.reset() }
        r.helpers.start("Research Silence.", listOf(WEB_SEARCH), null, RunScope("lead", isLead = true)) shouldBe Either.Left(RESEARCH_NEEDS_SANDBOX)
        r.runs.list(openOnly = false) shouldHaveSize 0
        // A helper without search does not need it.
        r.model.handler = { end("done") }
        r.serving {
            val id = helpers.start("Summarise this note.", listOf("read_report"), null, RunScope("lead", isLead = true))
                .shouldBeInstanceOf<Either.Right<Delegated>>().value.id
            awaitRun(id)
        }
    }

    test("a research helper gets fetch_page, and cite_sources records the pages it fetched") {
        val fetch = FakeFetchPage(mapOf("https://example.org/silence" to ("Silence (romance)" to "The romance survives in one manuscript.")))
        val r = LeadRig(database, sandboxTools = listOf(fetch)).also { it.reset() }
        r.model.handler = { request ->
            val text = seen(request)
            val created = CREATED_ID.find(text)?.groupValues?.get(1)
            when {
                "Title: Silence (romance)" !in text -> callTool("f1", FETCH_PAGE, buildJsonObject { put("url", "https://example.org/silence") })
                created == null -> callTool("save", "create_report", buildJsonObject { put("title", "Silence"); put("source", SILENCE_DOC) })
                "new source" !in text -> callTool("cite", net.robog.clarity.backend.reports.CITE_SOURCES, buildJsonObject { put("id", created) })
                else -> end("Report $created: a refresher, 1 source.")
            }
        }
        lateinit var id: String
        r.serving { id = startResearch(); awaitRun(id) }
        r.model.requestsFor(HELPER_MODEL).first().tools.map { it.name }.contains(FETCH_PAGE) shouldBe true
        fetch.fetched shouldBe listOf("https://example.org/silence")
        val report = r.reportStore.createdBy(id).single()
        val source = r.reportStore.sources(report.id).single()
        source.url shouldBe "https://example.org/silence"
        source.title shouldBe "Silence (romance)"
        source.fetchedAt shouldBe kotlin.time.Instant.parse("2026-10-29T15:00:00Z")
    }

    test("the lead may make several reports, but not the same title twice") {
        val r = LeadRig(database).also { it.reset() }
        val lead = RunScope("lead-thread", isLead = true)
        val create = r.reportTools.createReport(lead)
        fun input(title: String) = buildJsonObject { put("title", title); put("source", "# $title\n\nText.") }
        val first = create.invoke(input("Silence")).shouldBeInstanceOf<ToolResult.Ok>()
        val firstId = CREATED_ID.find(first.content)!!.groupValues[1]
        create.invoke(input(" silence ")).shouldBeInstanceOf<ToolResult.Error>().message shouldBe
            "This run already created report $firstId (\"Silence\"). Change it with edit_report, or read it with read_report."
        create.invoke(input("Another")).shouldBeInstanceOf<ToolResult.Ok>()
        r.reportStore.createdBy("lead-thread") shouldHaveSize 2
        // Another run is not affected.
        r.reportTools.createReport(RunScope("h2", isLead = false)).invoke(input("Silence")).shouldBeInstanceOf<ToolResult.Ok>()
    }
})
