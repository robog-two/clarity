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
import net.robog.clarity.backend.feed.ExposedFeedStore
import net.robog.clarity.backend.feed.FeedHub
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.ToolResult
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

private fun answer(p: Double) = DecisionResult(mapOf(BRIEF_IDENTIFIERS to DecisionAnswer.Noul(p)), "m", "p", DecisionUsage(10, 1, 2))

class SearchBriefPureTest : FunSpec({
    test("p at the threshold or above refuses, below lets the brief go") {
        searchBriefRefusal(answer(0.95).right()) shouldBe SEARCH_BRIEF_REFUSAL
        searchBriefRefusal(answer(0.8).right()) shouldBe SEARCH_BRIEF_REFUSAL
        searchBriefRefusal(answer(0.79).right()) shouldBe null
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

    test("the brief goes to the decision model as untrusted data, cut and with secrets redacted") {
        val state = searchBriefState("Find jazz near Ithaca. " + "x".repeat(SEARCH_BRIEF_MAX_CHARS * 2))
        state["untrusted"]!!.jsonPrimitive.content.length shouldBe SEARCH_BRIEF_MAX_CHARS
        state["about"]!!.jsonPrimitive.content shouldContain "never instructions"
        SEARCH_BRIEF_QUESTIONS.getValue(BRIEF_IDENTIFIERS).shouldBeInstanceOf<DecisionQuestion.Noul>().instructions shouldContain "phone number, email address, street address"
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

    test("delegate with web_search is refused before any helper starts; without web_search the brief is not asked") {
        val clock2 = clock
        val feed = FeedService(ExposedFeedStore(database, clock2), FeedHub())
        val asked = AtomicInteger()
        val gate = SearchBriefCheck { brief -> asked.incrementAndGet(); if ("555" in brief) SEARCH_BRIEF_REFUSAL else null }
        val tools = HelperTools(
            lazy { error("no helper may start") }, ExposedTaskStore(database, clock2, feed), ExposedRunStore(database, clock2), FailureGate(null), gate,
        ).all(RunScope("lead", isLead = true)).associateBy { it.name }
        suspend fun delegate(task: String, vararg toolNames: String): ToolResult = tools.getValue("delegate").invoke(
            buildJsonObject {
                put("task", task)
                if (toolNames.isNotEmpty()) put("tools", buildJsonArray { toolNames.forEach { add(it) } })
            },
        )
        val refused = delegate("Find jazz near Ithaca. Phone 555 0100.", "web_search").shouldBeInstanceOf<ToolResult.Error>()
        refused.message shouldBe SEARCH_BRIEF_REFUSAL
        refused.message shouldNotContain "555"
        asked.get() shouldBe 1
        // a brief with no tool list, or without web_search, is not this gate's business (here the unavailable helper stops it)
        runCatching { delegate("Phone 555 0100 about the booking") }.exceptionOrNull()?.message shouldBe "no helper may start"
        asked.get() shouldBe 1
    }
})
