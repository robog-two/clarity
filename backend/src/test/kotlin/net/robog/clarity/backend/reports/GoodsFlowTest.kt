package net.robog.clarity.backend.reports

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.testing.testApplication
import java.nio.file.Files
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.agent.DbUsageRecorder
import net.robog.clarity.backend.agent.HELPER_MODEL
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.LEAD_MODEL
import net.robog.clarity.backend.agent.LeadRig
import net.robog.clarity.backend.agent.RunStatus
import net.robog.clarity.backend.agent.WEB_SEARCH
import net.robog.clarity.backend.agent.callTool
import net.robog.clarity.backend.agent.end
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.DevicePrincipal
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.CurrentRun
import net.robog.clarity.harness.WebSearchTool
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.ModelRequest
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.StopReason
import net.robog.clarity.harness.UrlCitation
import net.robog.clarity.harness.Usage
import net.robog.clarity.harness.citationsBlock
import net.robog.clarity.harness.searchFor
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.report.ReportList
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

private const val ASK = "Create a report about background for The Devils and put it in the my goods tab"
private val CREATED = Regex("Report ([0-9a-f-]{36}) created")
private val READY = Regex("Report ([0-9a-f-]{36}) ready")

/** Every text the model was shown in [request]: message text and tool results. */
private fun shown(request: ModelRequest): String = request.messages.flatMap(Message::content).joinToString("\n") {
    when (it) {
        is Block.Text -> it.text
        is Block.Result -> it.content
        else -> ""
    }
}

private fun called(request: ModelRequest, name: String): Boolean =
    request.messages.any { m -> m.content.any { it is Block.ToolUse && it.name == name } }

private val CITED = listOf(
    UrlCitation("https://en.wikipedia.org/wiki/The_Devils_(film)", "The Devils (film)", "directed by Ken Russell"),
    UrlCitation("https://www.bfi.org.uk/features/devils", "The Devils at 50"),
)

/**
 * "Put it in my goods": the person's message, the lead delegating to a helper with web search, the
 * helper's create_report and cite_sources, the lead's present_report, and the report in the Goods
 * list (the reports route). Real Postgres; the model is scripted and never called.
 */
@EnabledIf(ContainerRuntimeAvailable::class)
class GoodsFlowTest : FunSpec({
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

    test("a researched report goes into the Goods list and is handed over in the feed") {
        val r = LeadRig(database, usageRecorder = DbUsageRecorder(database, Clock.System)).also { it.reset() }
        r.model.handler = { request ->
            val seen = shown(request)
            if (request.model == HELPER_MODEL) {
                val id = CREATED.find(seen)?.groupValues?.get(1)
                when {
                    id == null -> ModelResponse(
                        listOf(
                            Block.Text("Ken Russell directed The Devils (1971), from Huxley's The Devils of Loudun."),
                            citationsBlock(CITED),
                            Block.ToolUse(
                                "h_create", "create_report",
                                buildJsonObject {
                                    put("title", "The Devils (1971): background")
                                    put("source", "# Background\n\nKen Russell's *The Devils* (1971) adapts Huxley.\n\n## Works Cited\n\n1. The Devils (film).")
                                },
                            ),
                        ),
                        StopReason.TOOL_USE,
                        Usage(200, 40, webSearchRequests = 3),
                    )
                    !called(request, CITE_SOURCES) -> callTool("h_cite", CITE_SOURCES, buildJsonObject { put("id", id) })
                    else -> end("Report $id ready: background on The Devils, 2 sources.")
                }
            } else {
                val ready = READY.find(seen)?.groupValues?.get(1)
                when {
                    ready != null && !called(request, PRESENT_REPORT) -> callTool(
                        "l_present", PRESENT_REPORT,
                        buildJsonObject {
                            put("id", ready)
                            put("message", "The background on The Devils is in your Goods.")
                        },
                    )
                    ready != null -> end("handed over")
                    !called(request, "delegate") -> callTool(
                        "l_delegate", "delegate",
                        buildJsonObject {
                            put("task", "Research the background of Ken Russell's film The Devils (1971) and write it up as a report; cite your sources.")
                            put("tools", JsonArray(listOf(JsonPrimitive(WEB_SEARCH))))
                        },
                    )
                    else -> end("on it")
                }
            }
        }
        val helperJob = scope.launch { r.helpers.run() }
        r.inbox.enqueue(InboxItem.UserInput(ClientInputId("u1"), ASK))
        r.lead.start()
        r.lead.step() shouldBe true
        withTimeout(20.seconds) { while (r.runs.list(openOnly = false).none { it.status == RunStatus.DONE }) delay(50) }
        r.helpers.joinAll()
        r.lead.step() shouldBe true
        helperJob.cancelAndJoin()

        // The lead was told what "my goods" means, and never searched itself.
        val lead = r.model.requestsFor(LEAD_MODEL)
        lead.first().system.joinToString("\n") { it.text } shouldContain "put it in my goods"
        shown(lead.first()) shouldContain ASK
        lead.map { it.webSearch }.distinct() shouldBe listOf(null)

        // The helper searched (counted from model_call, the way a resumed run counts it) and cited.
        val helper = r.runs.list(openOnly = false).single()
        r.model.requestsFor(HELPER_MODEL).first().webSearch.shouldNotBeNull()
        r.runs.webSearchesUsed(helper.id) shouldBe 3L
        val report = r.reportStore.list().single()
        report.title shouldBe "The Devils (1971): background"
        r.reportStore.sources(report.id).map { it.url } shouldContainExactlyInAnyOrder CITED.map { it.url }

        // Handed over in the feed ...
        val cards = r.feedStore.page(0, 50).items.flatMap { it.cards }
        cards shouldContain Card.Report(report.id, report.title, formatsFor(report.kind).map { it.wireName })

        // ... and in the Goods list the app shows.
        val c = ReportService(r.reportStore, CountingRenderer(), ReportBlobs(reportsAppConfig(Files.createTempDirectory("goods"))), Clock.System)
        testApplication {
            application {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(ClarityJson) }
                install(Authentication) { bearer(AuthProviders.DEVICE) { authenticate { DevicePrincipal(DeviceId("d1"), null) } } }
                ReportRoutes(c, r.reportStore, ExposedReportLinkStore(database, Clock.System)).install(this)
            }
            val body = createClient { }.get("/api/v1/reports") { header(HttpHeaders.Authorization, "Bearer device") }.bodyAsText()
            ClarityJson.decodeFromString(ReportList.serializer(), body).reports.map { it.id } shouldBe listOf(report.id)
        }
    }

    test("a search helper resumed after a restart keeps counting the searches it already made") {
        val r = LeadRig(database, usageRecorder = DbUsageRecorder(database, Clock.System)).also { it.reset() }
        val id = "0b8e6d2a-3f41-4d7c-9a55-6a1f0c2e7b10"
        r.runs.create(id, null, "helper", "Research The Devils.", listOf(WEB_SEARCH), Clock.System.now())
        // Before the restart the run made 7 searches (recorded per call in model_call).
        withContext(CurrentRun(id)) {
            DbUsageRecorder(database, Clock.System).record(HELPER_MODEL, Usage(100, 10, webSearchRequests = 4), AgentRole.HELPER)
            DbUsageRecorder(database, Clock.System).record(HELPER_MODEL, Usage(100, 10, webSearchRequests = 3), AgentRole.HELPER)
        }
        r.runs.webSearchesUsed(id) shouldBe 7L
        r.model.handler = { end("Report ready.") }
        val helperJob = scope.launch { r.helpers.run() }
        withTimeout(20.seconds) { while (r.runs.get(id)?.status != RunStatus.DONE) delay(50) }
        helperJob.cancelAndJoin()
        val cap = net.robog.clarity.backend.config.WebSearchConfig().maxSearchesPerRun
        r.model.requestsFor(HELPER_MODEL).first().webSearch.shouldNotBeNull().maxSearches shouldBe cap - 7
        searchFor(WebSearchTool(maxSearches = 10), 10) shouldBe null
    }
})
