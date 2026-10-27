package net.robog.clarity.backend.agent

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.governance.Constitution
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

private fun gateArgs(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

private fun facts(vararg f: String): JsonObject = buildJsonObject { put("facts", JsonArray(f.map(::JsonPrimitive))) }

private const val BRIEF_WITH_ISSUES = "Look up Ulta salon availability in Ithaca. Known issues from earlier runs: after VERIFY the site sometimes " +
    "redirects to a blank page; navigate to beautyservices.ulta.com again."

/** Every tool that writes durable memory, and delegate, go through the failure gate (fake Jev, real database). */
@EnabledIf(ContainerRuntimeAvailable::class)
class FailureGateToolsTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }

    suspend fun rig(jev: FakeFailureJev): LeadRig = LeadRig(database, failureGate = FailureGate(jev)).also {
        it.reset()
        it.constitutions.create(Constitution(version = 1, lines = emptyList(), profile = listOf("Lives in Ithaca"), updatedAt = Clock.System.now()))
    }

    fun LeadRig.leadTool(name: String): Tool = toolbox.leadTools(RunScope("lead-test", isLead = true)).tools.first { it.name == name }

    /** Each gated memory write, as (tool, arguments) carrying [text]. */
    fun LeadRig.memoryWrites(text: String): List<Pair<Tool, JsonObject>> = listOf(
        own.writeJournal() to gateArgs("text" to text),
        own.rememberFact() to gateArgs("fact" to text),
        own.setScratch() to gateArgs("text" to text),
        memoryTools.saveNote() to gateArgs("collection" to "errands", "description" to "Errands and bookings", "text" to text),
        own.replaceProfile() to facts("Lives in Ithaca", text),
    )

    test("every memory tool refuses a failure story at high p and stores nothing") {
        val jev = FakeFailureJev(p = 0.92)
        val r = rig(jev)
        for ((tool, input) in r.memoryWrites(FAILURE_STORY)) {
            val result = tool.invoke(input).shouldBeInstanceOf<ToolResult.Error>()
            result.message shouldBe FailureGate.MEMORY_REFUSAL
        }
        jev.asked shouldHaveSize 5
        r.memory.journal(LocalDate(2000, 1, 1), LocalDate(2100, 1, 1)).shouldBeEmpty()
        r.constitutions.current()!!.profile shouldBe listOf("Lives in Ithaca")
        r.collections.find("errands") shouldBe null
    }

    test("a note edit and a collection description are gated too") {
        val jev = FakeFailureJev(p = 0.1)
        val r = rig(jev)
        r.memoryTools.saveNote().invoke(gateArgs("collection" to "errands", "description" to "Errands and bookings", "text" to MORNING_NOTE))
            .shouldBeInstanceOf<ToolResult.Ok>()
        jev.p = 0.95
        r.memoryTools.saveNote().invoke(buildJsonObject { put("id", 1); put("text", FAILURE_STORY) })
            .shouldBeInstanceOf<ToolResult.Error>().message shouldBe FailureGate.MEMORY_REFUSAL
        r.memoryTools.manageCollection().invoke(gateArgs("name" to "errands", "description" to "Ulta bookings: the site breaks after the SMS code step"))
            .shouldBeInstanceOf<ToolResult.Error>().message shouldBe FailureGate.MEMORY_REFUSAL
        r.memoryTools.saveNote().invoke(gateArgs("collection" to "ulta", "description" to "Ulta: verification is broken, re-navigate after the code", "text" to "x".repeat(10)))
            .shouldBeInstanceOf<ToolResult.Error>().message shouldBe FailureGate.MEMORY_REFUSAL
        r.collections.find("ulta") shouldBe null
    }

    test("every memory tool stores ordinary text at low p") {
        val jev = FakeFailureJev(p = 0.05)
        val r = rig(jev)
        for ((tool, input) in r.memoryWrites(MORNING_NOTE)) tool.invoke(input).shouldBeInstanceOf<ToolResult.Ok>()
        r.memory.journal(LocalDate(2000, 1, 1), LocalDate(2100, 1, 1)).map { it.text } shouldContain MORNING_NOTE
        r.constitutions.current()!!.profile shouldContain MORNING_NOTE
        r.collections.find("errands") shouldNotBe null
    }

    test("fails open: a decision error lets the write through") {
        val jev = FakeFailureJev(p = 1.0, fail = true)
        val r = rig(jev)
        for ((tool, input) in r.memoryWrites(FAILURE_STORY)) tool.invoke(input).shouldBeInstanceOf<ToolResult.Ok>()
        r.memory.journal(LocalDate(2000, 1, 1), LocalDate(2100, 1, 1)).map { it.text } shouldContain FAILURE_STORY
    }

    test("short texts are written without a call") {
        val jev = FakeFailureJev(p = 1.0)
        val r = rig(jev)
        r.own.writeJournal().invoke(gateArgs("text" to "Called Dana back.")).shouldBeInstanceOf<ToolResult.Ok>()
        r.own.setScratch().invoke(gateArgs("text" to "Waiting on Dana's reply")).shouldBeInstanceOf<ToolResult.Ok>()
        jev.asked.shouldBeEmpty()
    }

    test("replace_profile judges only the facts it adds") {
        val jev = FakeFailureJev(p = 1.0)
        val r = rig(jev)
        r.own.replaceProfile().invoke(facts("Lives in Ithaca"))
            .shouldBeInstanceOf<ToolResult.Ok>()
        jev.asked.shouldBeEmpty()
    }

    test("delegate and message_helper refuse a brief with known issues, and start no helper") {
        val jev = FakeFailureJev(p = 0.88)
        val r = rig(jev)
        r.leadTool("delegate").invoke(gateArgs("task" to BRIEF_WITH_ISSUES)).shouldBeInstanceOf<ToolResult.Error>().message shouldBe FailureGate.BRIEF_REFUSAL
        r.helpers.list(openOnly = false).shouldBeEmpty()
        r.leadTool("message_helper").invoke(gateArgs("id" to "nope", "message" to BRIEF_WITH_ISSUES))
            .shouldBeInstanceOf<ToolResult.Error>().message shouldBe FailureGate.BRIEF_REFUSAL
        val question = jev.asked.first().second.getValue(FailureGate.Q_FAILURE) as DecisionQuestion.Noul
        question shouldBe FailureGate.BRIEF_QUESTION
    }

    test("delegate passes a clean brief at low p, and fails open") {
        for (jev in listOf(FakeFailureJev(p = 0.1), FakeFailureJev(p = 1.0, fail = true))) {
            val r = rig(jev)
            val result = r.leadTool("message_helper").invoke(gateArgs("id" to "nope", "message" to "Also check Saturday afternoon; stop before booking."))
            // Past the gate: the helper manager answers (there is no helper "nope").
            result.shouldBeInstanceOf<ToolResult.Error>().message shouldNotBe FailureGate.BRIEF_REFUSAL
            jev.asked shouldHaveSize 1
        }
    }
})
