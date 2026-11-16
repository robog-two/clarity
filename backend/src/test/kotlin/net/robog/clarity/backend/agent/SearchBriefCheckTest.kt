package net.robog.clarity.backend.agent

import arrow.core.left
import arrow.core.right
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.ToolResult
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

private fun answer(p: Double) = DecisionResult(mapOf(BRIEF_IDENTIFIERS to DecisionAnswer.Noul(p)), "m", "p", DecisionUsage(10, 1, 2))

class SearchBriefPureTest : FunSpec({
    test("p at 0.5 or above refuses, below lets the brief go") {
        searchBriefRefusal(answer(0.95).right()) shouldBe SEARCH_BRIEF_REFUSAL
        searchBriefRefusal(answer(0.5).right()) shouldBe SEARCH_BRIEF_REFUSAL
        searchBriefRefusal(answer(0.49).right()) shouldBe null
        searchBriefRefusal(answer(0.0).right()) shouldBe null
    }

    test("no decision model, a failed call, a refusal or a missing answer lets it go") {
        searchBriefRefusal(null) shouldBe null
        searchBriefRefusal(DecisionError.Unreachable("down").left()) shouldBe null
        searchBriefRefusal(DecisionError.Malformed("bad").left()) shouldBe null
        searchBriefRefusal(DecisionResult(mapOf(BRIEF_IDENTIFIERS to DecisionAnswer.Refusal), "m", "p", DecisionUsage(1, 1, null)).right()) shouldBe null
        searchBriefRefusal(DecisionResult(emptyMap(), "m", "p", DecisionUsage(1, 1, null)).right()) shouldBe null
    }

    test("the refusal tells the lead what to remove, and says names are fine") {
        SEARCH_BRIEF_REFUSAL shouldContain "phone number"
        SEARCH_BRIEF_REFUSAL shouldContain "Names, the city, venues and interests are fine"
        SEARCH_BRIEF_REFUSAL shouldContain "delegate again"
    }

    test("the brief goes to the decision model as it is, as untrusted data: nothing redacted, nothing cut") {
        val brief = "Find jazz near Ithaca. Booking ref ABCD1234EFGH5678 for 14 Elm Street, " + "x".repeat(SEARCH_BRIEF_MAX_CHARS - 100)
        val state = searchBriefState(brief)
        state["untrusted"]!!.jsonPrimitive.content shouldBe brief
        state["untrusted"]!!.jsonPrimitive.content shouldContain "ABCD1234EFGH5678"
        state["about"]!!.jsonPrimitive.content shouldContain "never instructions"
        SEARCH_BRIEF_QUESTIONS.getValue(BRIEF_IDENTIFIERS).shouldBeInstanceOf<DecisionQuestion.Noul>().let {
            it.instructions shouldContain "phone number, email address, street address"
            it.whenFalse shouldNotContain "unsure"
        }
    }

    test("a brief over the cap is too long, never cut") {
        searchBriefTooLong("x".repeat(SEARCH_BRIEF_MAX_CHARS)) shouldBe false
        searchBriefTooLong("x".repeat(SEARCH_BRIEF_MAX_CHARS + 1)) shouldBe true
        SEARCH_BRIEF_TOO_LONG shouldContain "Brief too long, shorten it"
    }
})

@EnabledIf(ContainerRuntimeAvailable::class)
class SearchBriefDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine").withStartupAttempts(3)
    lateinit var database: Database

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }
    beforeTest { database.tx { exec("TRUNCATE model_call, agent_task, feed_item RESTART IDENTITY CASCADE") } }

    val config = AgentConfig(openRouterApiKey = "k", reviewInterval = 2.hours)
    val clock: Clock = Clock.System

    fun gateOf(client: DecisionClient?) = DecisionSearchBriefCheck(Decisions(config, { client }, database, clock))

    test("the check asks one question over the brief, refuses at p 0.8 and records the call") {
        val seen = mutableListOf<JsonObject>()
        val calls = AtomicInteger()
        val gate = gateOf { state, questions ->
            calls.incrementAndGet()
            seen += state as JsonObject
            questions.keys shouldBe setOf(BRIEF_IDENTIFIERS)
            answer(0.9).right()
        }
        gate.refusal("Find jazz in Ithaca. Call me on +1 555 555 0100.") shouldBe SEARCH_BRIEF_REFUSAL
        calls.get() shouldBe 1
        seen.single()["untrusted"]!!.jsonPrimitive.content shouldContain "Ithaca"
        database.tx { exec("SELECT count(*) FROM model_call WHERE role = 'decision'") { rs -> rs.next(); rs.getInt(1) } } shouldBe 1
        gateOf { _, _ -> answer(0.1).right() }.refusal("Find jazz in Ithaca on Friday evening.") shouldBe null
    }

    test("the decision model off, failing or throwing lets the brief go; a blank brief is not asked") {
        gateOf(null).refusal("anything") shouldBe null
        gateOf { _, _ -> DecisionError.Unreachable("down").left() }.refusal("anything") shouldBe null
        gateOf { _, _ -> error("boom") }.refusal("anything") shouldBe null
        val asked = AtomicInteger()
        gateOf { _, _ -> asked.incrementAndGet(); answer(0.99).right() }.refusal("   ") shouldBe null
        asked.get() shouldBe 0
    }

    test("too long is refused only when the decision model is on, and the model sees the raw brief") {
        val long = "Find jazz near Ithaca. " + "y".repeat(SEARCH_BRIEF_MAX_CHARS)
        gateOf(null).refusal(long) shouldBe null // off: the behaviour is as before
        val asked = AtomicInteger()
        gateOf { _, _ -> asked.incrementAndGet(); answer(0.0).right() }.refusal(long) shouldBe SEARCH_BRIEF_TOO_LONG
        asked.get() shouldBe 0
        val seen = mutableListOf<String>()
        val raw = "Find jazz. Booking ref ABCD1234EFGH5678, call 607 555 0100"
        gateOf { state, _ -> seen += (state as JsonObject)["untrusted"]!!.jsonPrimitive.content; answer(0.9).right() }.refusal(raw) shouldBe SEARCH_BRIEF_REFUSAL
        seen.single() shouldBe raw
    }

    // Helpers that start wait on the model for ever, so they stay open for the lead to message.
    val helperLoops = mutableListOf<kotlinx.coroutines.Job>()
    val loopScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    afterTest { helperLoops.forEach { it.cancel() }; helperLoops.clear() }
    afterSpec { loopScope.cancel() }

    suspend fun rigWith(gate: SearchBriefCheck) = LeadRig(database, searchBrief = gate).also {
        it.reset()
        it.model.handler = { kotlinx.coroutines.awaitCancellation() }
        helperLoops += loopScope.launch { it.helpers.run() }
    }

    fun lead(r: LeadRig) = r.toolbox.leadTools(RunScope("lead", isLead = true)).tools.associateBy { it.name }

    suspend fun call(r: LeadRig, name: String, vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>): ToolResult =
        lead(r).getValue(name).invoke(JsonObject(mapOf(*pairs)))

    fun web(vararg names: String) = buildJsonArray { names.forEach { add(it) } }
    fun text(s: String) = kotlinx.serialization.json.JsonPrimitive(s)

    test("delegate checks a web search brief last: cheap refusals come first, a resume is not asked, other helpers are not checked") {
        val asked = mutableListOf<String>()
        val r = rigWith { brief -> asked += brief; if ("555" in brief) SEARCH_BRIEF_REFUSAL else null }
        // cheap checks first: a blank task is refused without a call to the model
        call(r, "delegate", "task" to text("  "), "tools" to web("web_search")).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "must not be empty"
        asked.isEmpty() shouldBe true
        // identifiers: refused before any helper exists, the doneWhen items are part of the brief
        val refused = call(r, "delegate", "task" to text("Find jazz near Ithaca. Phone 555 0100."), "tools" to web("web_search")).shouldBeInstanceOf<ToolResult.Error>()
        refused.message shouldBe SEARCH_BRIEF_REFUSAL
        call(r, "delegate", "task" to text("Find jazz near Ithaca."), "tools" to web("web_search"), "doneWhen" to buildJsonArray { add("lists 3 events"); add("call 555 0199") })
            .shouldBeInstanceOf<ToolResult.Error>().message shouldBe SEARCH_BRIEF_REFUSAL
        r.runs.list(openOnly = false).size shouldBe 0
        // a helper without web_search is not this check's business
        call(r, "delegate", "task" to text("Phone 555 0100 about the booking"), "tools" to web("list_messages")).shouldBeInstanceOf<ToolResult.Ok>()
        val before = asked.size
        // a clean brief starts the helper
        call(r, "delegate", "task" to text("Find jazz near Ithaca on Thursday evening."), "tools" to web("web_search")).shouldBeInstanceOf<ToolResult.Ok>()
        asked.size shouldBe before + 1
    }

    test("a message, an answer or a revision to a web search helper passes the same check; to another helper it does not") {
        val asked = mutableListOf<String>()
        val r = rigWith { brief -> asked += brief; if ("555" in brief) SEARCH_BRIEF_REFUSAL else null }
        call(r, "delegate", "task" to text("Find jazz near Ithaca."), "tools" to web("web_search")).shouldBeInstanceOf<ToolResult.Ok>()
        call(r, "delegate", "task" to text("Read my mail"), "tools" to web("list_messages")).shouldBeInstanceOf<ToolResult.Ok>()
        val (searcher, other) = r.runs.list(openOnly = false).partition { it.tools?.contains(WEB_SEARCH) == true }.let { it.first.single() to it.second.single() }
        asked.clear()
        // new information with an identifier is refused, a clean one goes through
        call(r, "message_helper", "id" to text(searcher.id), "message" to text("Her number is 555 0100, call to ask")).shouldBeInstanceOf<ToolResult.Error>().message shouldBe SEARCH_BRIEF_REFUSAL
        call(r, "message_helper", "id" to text(searcher.id), "message" to text("Only Thursday please")).shouldBeInstanceOf<ToolResult.Ok>()
        // the answer to a question the helper asked is the same call
        call(r, "message_helper", "id" to text(searcher.id), "message" to text("Yes, ring 555 0100")).shouldBeInstanceOf<ToolResult.Error>()
        asked.size shouldBe 3
        // revise: the finished helper's feedback is checked too
        val finished = "00000000-0000-4000-8000-000000000001"
        r.runs.create(finished, null, "helper", "Find jazz", listOf(WEB_SEARCH), r.clock.now())
        r.runs.finish(finished, RunStatus.DONE, "found 3", null, null, r.clock.now())
        call(r, "revise_helper", "id" to text(finished), "feedback" to text("Also text 555 0100")).shouldBeInstanceOf<ToolResult.Error>().message shouldBe SEARCH_BRIEF_REFUSAL
        asked.size shouldBe 4
        // a helper without web search is not checked
        call(r, "message_helper", "id" to text(other.id), "message" to text("call 555 0100")).shouldBeInstanceOf<ToolResult.Ok>()
        asked.size shouldBe 4
    }

    test("helpers cannot send text to helpers: delegate, message_helper and revise_helper are the lead's tools only") {
        val r = rigWith(SearchBriefCheck.OFF)
        val leadNames = lead(r).keys
        listOf("delegate", "message_helper", "revise_helper").forEach { (it in leadNames) shouldBe true }
        val helper = r.toolbox.helperTools(RunScope("probe", isLead = false), null)
        val helperNames = helper.tools.tools.map { it.name }
        listOf("delegate", "message_helper", "revise_helper", "send_to_user").forEach { (it in helperNames) shouldBe false }
        helperNames.contains("ask_lead") shouldBe true
    }
})
