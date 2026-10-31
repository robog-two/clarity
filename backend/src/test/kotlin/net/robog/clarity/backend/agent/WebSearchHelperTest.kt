package net.robog.clarity.backend.agent

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.AgentRoles
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.config.WebSearchConfig
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.reports.CITE_SOURCES
import net.robog.clarity.backend.reports.PRESENT_REPORT
import net.robog.clarity.backend.reports.REPORT_TOOLS
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.FinishReason
import net.robog.clarity.harness.HarnessEvent
import net.robog.clarity.harness.ModelRequest
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.RunResult
import net.robog.clarity.harness.StopReason
import net.robog.clarity.harness.Usage
import net.robog.clarity.harness.WebSearchTool
import net.robog.clarity.shared.feed.ClientInputId
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

/** Every personal-data and outbound tool a search helper must never hold. */
private val PERSONAL = listOf(
    "list_messages", "list_calendar_events", "search_contacts", "search_memory", "read_memory", "open_collection", "read_journal",
    "read_profile", "read_result", "list_triggers", "save_draft", "send_email", "create_event", "move_event", "respond_to_invite",
    "create_reminder", "set_do_not_disturb", "request_credential", "list_credentials", "ask_code", "replace_profile", "save_note",
    "manage_collection", "browser__look", "browser__act",
)

/** Who may search the web, as pure functions: only a helper the lead names it for, and then without personal tools. */
class WebSearchPureTest : FunSpec({
    test("granting search keeps only the sandbox and the report tools, and always adds the report tools") {
        val (kept, dropped) = researchTools(listOf(WEB_SEARCH, "send_email", "list_messages", "sandbox__shell", "browser__look", "ask_code", "read_report"))
        kept shouldContainAll listOf("sandbox__shell", CITE_SOURCES) + REPORT_TOOLS
        PERSONAL.forEach { kept shouldNotContain it }
        (WEB_SEARCH in kept) shouldBe false
        dropped shouldContainExactly listOf("send_email", "list_messages", "browser__look", "ask_code")
        researchTools(PERSONAL + WEB_SEARCH).first.toSet() shouldBe (REPORT_TOOLS + CITE_SOURCES + FETCH_PAGE).toSet()
    }

    test("a run gets search only when its stored tool list names it, never by default") {
        val search = WebSearchTool()
        helperRunPlan(null, search) shouldBe HelperRunPlan(null, null)
        helperRunPlan(listOf("send_email", "search_memory"), search) shouldBe HelperRunPlan(listOf("send_email", "search_memory"), null)
        val granted = helperRunPlan(listOf(WEB_SEARCH, "send_email", "sandbox__shell"), search)
        granted.webSearch shouldBe search
        granted.tools.shouldNotBeNull() shouldNotContain "send_email"
        granted.tools!! shouldContain "sandbox__shell"
        // search switched off on the server: the run keeps the stripped tools and gets no search
        helperRunPlan(listOf(WEB_SEARCH), null).webSearch.shouldBeNull()
        (WEB_SEARCH in HELPER_BASE) shouldBe false
        REPORT_TOOLS.forEach { (it in HELPER_BASE) shouldBe true }
    }

    test("the role request carries search only when given it; the lead's call never passes it") {
        val role = AgentRoles().lead
        roleRequest(role, "lead", helperSystemBlocks(), emptyList(), emptyList(), 30, null).webSearch.shouldBeNull()
        roleRequest(role, "h", helperSystemBlocks(), emptyList(), emptyList(), 30, null, WebSearchTool()).webSearch.shouldNotBeNull()
    }

    test("the config: parallel, fast, 10 searches a run; off grants nothing") {
        val config = WebSearchConfig()
        config.tool().shouldNotBeNull().let {
            it.engine shouldBe "parallel"
            it.mode shouldBe "fast"
            it.maxSearches shouldBe 10
            it.maxCharacters shouldBe 400
        }
        WebSearchConfig(enabled = false).tool().shouldBeNull()
        ("firecrawl" in WebSearchConfig.ENGINES) shouldBe false
    }

    test("a call that searched taints the run; one that did not, does not") {
        searchedIn(HarnessEvent.Called(1, Usage(10, 5, webSearchRequests = 1))) shouldBe true
        searchedIn(HarnessEvent.Called(1, Usage(10, 5))) shouldBe false
        searchedIn(HarnessEvent.Finished(RunResult(FinishReason.END_TURN, Usage(webSearchRequests = 4), emptyList(), "x"))) shouldBe false
        searchGrantText(listOf("send_email")) shouldContain "left out: send_email"
        // The count OpenRouter streams (server_tool_use_details) reaches the taint.
        val streamed = net.robog.clarity.harness.OpenRouterModelGateway.toUsage(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"prompt_tokens":10,"completion_tokens":2,"server_tool_use_details":{"web_search_requests":2}}""",
            ) as kotlinx.serialization.json.JsonObject,
        )
        searchedIn(HarnessEvent.Called(1, streamed)) shouldBe true
    }
})

/** The lead delegating research with and without web search, against real Postgres; the model is scripted. */
@EnabledIf(ContainerRuntimeAvailable::class)
class WebSearchHelperTest : FunSpec({
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

    fun delegateCall(tools: List<String>?) = callTool(
        "toolu_d", "delegate",
        buildJsonObject {
            put("task", "Research the causes of the 1918 flu pandemic and draft a two-page essay as a report.")
            if (tools != null) put("tools", JsonArray(tools.map(::JsonPrimitive)))
        },
    )

    suspend fun delegateOnce(tools: List<String>?): Pair<LeadRig, ModelRequest> {
        val r = LeadRig(database).also { it.reset() }
        r.model.handler = { request ->
            when {
                request.model == HELPER_MODEL -> ModelResponse(listOf(Block.Text("Report ready: id r1.")), StopReason.END_TURN, Usage(100, 10, webSearchRequests = 2))
                // Once only: the reply nudge that follows (no send_to_user here) must not start a second helper.
                request.messages.any { m -> m.content.any { it is Block.ToolUse && it.name == "delegate" } } -> end("delegated")
                else -> delegateCall(tools)
            }
        }
        val helperJob = scope.launch { r.helpers.run() }
        r.inbox.enqueue(InboxItem.UserInput(ClientInputId("u1"), "write me an essay on the 1918 flu"))
        r.lead.start()
        r.lead.step() shouldBe true
        withTimeout(20.seconds) { while (r.runs.list(openOnly = false).none { it.status == RunStatus.DONE }) delay(50) }
        r.helpers.joinAll()
        helperJob.cancelAndJoin()
        return r to r.model.requestsFor(HELPER_MODEL).first()
    }

    test("a helper the lead names web_search for searches, without any personal-data or outbound tool") {
        val (r, helper) = delegateOnce(listOf(WEB_SEARCH, "send_email", "list_messages", "search_memory", "read_report"))
        val search = helper.webSearch.shouldNotBeNull()
        search.engine shouldBe "parallel"
        search.mode shouldBe "fast"
        search.maxSearches shouldBe 10
        val names = helper.tools.map { it.name }
        names shouldContainAll REPORT_TOOLS + CITE_SOURCES
        PERSONAL.forEach { names shouldNotContain it }
        names shouldNotContain PRESENT_REPORT
        names shouldNotContain "send_to_user"

        // The lead never searches, before or after.
        val lead = r.model.requestsFor(LEAD_MODEL)
        lead.map { it.webSearch }.distinct() shouldBe listOf(null)
        lead.flatMap { req -> req.tools.map { it.name } }.distinct() shouldNotContain WEB_SEARCH

        // The run remembers the grant (for a resume), and the lead was told what the helper lost.
        r.runs.list(openOnly = false).single().tools.shouldNotBeNull() shouldContain WEB_SEARCH
        val delegated = lead.last().messages.flatMap { it.content }.filterIsInstance<Block.Result>().single { it.toolUseId == "toolu_d" }.content
        delegated shouldContain "search the web"
        delegated shouldContain "send_email"
    }

    test("a helper with the default tools, or a list without web_search, never searches") {
        val (_, byDefault) = delegateOnce(null)
        byDefault.webSearch.shouldBeNull()
        byDefault.tools.map { it.name } shouldContainAll REPORT_TOOLS
        byDefault.tools.map { it.name } shouldNotContain CITE_SOURCES

        val (_, listed) = delegateOnce(listOf("search_memory", "create_report"))
        listed.webSearch.shouldBeNull()
        listed.tools.map { it.name } shouldContainExactly listOf("create_report", "search_memory")
    }

    test("the lead's own tools have no web search and no way to cite sources") {
        val r = LeadRig(database).also { it.reset() }
        val names = r.toolbox.leadTools(RunScope("lead", isLead = true)).tools.map { it.name }
        names shouldNotContain WEB_SEARCH
        names shouldNotContain CITE_SOURCES
        r.model.requests.shouldBeEmpty()
    }
})

