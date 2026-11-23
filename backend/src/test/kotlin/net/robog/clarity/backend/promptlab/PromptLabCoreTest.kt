package net.robog.clarity.backend.promptlab

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Role
import net.robog.clarity.harness.contextSince

private fun send(text: String, kind: String? = null, session: String? = null, id: String = "s1") = Message(
    Role.ASSISTANT,
    listOf(
        Block.ToolUse(
            id, SEND_TO_USER,
            buildJsonObject {
                put("cards", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
                kind?.let { put("kind", it) }
                session?.let { put("sessionId", it) }
            },
        ),
    ),
)

private fun result(id: String, text: String) = Message(Role.USER, listOf(Block.Result(id, text)))
private fun call(id: String, name: String, args: JsonObject = JsonObject(emptyMap())) = Message(Role.ASSISTANT, listOf(Block.ToolUse(id, name, args)))
private val t0 = Instant.parse("2026-11-20T12:00:00Z")

class PromptLabCoreTest : FunSpec({
    test("the trigger skips tool results, memory context, the reply nudge and clock lines") {
        val messages = listOf(
            Message.user("Is there anything on Canvas for the week?"),
            call("a", "search"),
            result("a", "found"),
            Message.system("[memory, 2026-11-20] nothing"),
            Message.system("[clarity] The person wrote to you and has had no reply"),
            Message.system("[now 2026-11-20T08:00-05:00 (Fri), America/New_York]"),
        )
        triggerOf(messages)?.index shouldBe 0
        triggerOf(messages)?.role shouldBe Role.USER
    }

    test("an operator update is the trigger when it came last") {
        val messages = listOf(Message.user("hi"), send("hello"), Message.system("[inbox, now x]\n- Helper abc finished its task: Goal: find"))
        triggerOf(messages)?.index shouldBe 2
    }

    test("classification by trigger, kind and session") {
        val person = Trigger(0, Role.USER, "Is there anything on Canvas for the week?")
        classify(null, null, person) shouldBe ScenarioType.REPLY
        classify(null, null, Trigger(0, Role.USER, "yep, thanks")) shouldBe ScenarioType.CHAT
        classify(null, "abc", person) shouldBe ScenarioType.SESSION
        classify("check_in", null, null) shouldBe ScenarioType.CHECK_IN
        classify("look_forward", null, null) shouldBe ScenarioType.LOOK_FORWARD
        classify("finding", null, null) shouldBe ScenarioType.FINDING
        classify(null, null, Trigger(0, Role.SYSTEM, "- Nudge you set at 2026-11-08T17:00: Ask")) shouldBe ScenarioType.CHECK_IN
        classify(null, null, Trigger(0, Role.SYSTEM, "- Helper 1 finished its task: Goal")) shouldBe ScenarioType.HELPER_RESULT
        classify(null, null, Trigger(0, Role.SYSTEM, "- Helper 1 failed its task: Goal")) shouldBe ScenarioType.UNFINISHED
        classify(null, null, Trigger(0, Role.SYSTEM, "- Could not do: Cancel x")) shouldBe ScenarioType.HELD
        classify(null, null, Trigger(0, Role.SYSTEM, "- The person ruled on the held action \"x\" (1): declined.")) shouldBe ScenarioType.HELD
        classify(null, null, Trigger(0, Role.SYSTEM, "- Scheduled job \"periodic-review\" is due.")) shouldBe ScenarioType.FINDING
    }

    test("candidates are cut before the send, skip retries of refused input, and see only the context since a summary") {
        val journal = listOf(
            1 to Message.user("old stuff"),
            2 to Message.summary("earlier"),
            3 to Message.user("Is there anything on Canvas?"),
            4 to send("No."),
            5 to result("s1", "Invalid input for send_to_user: bad"),
            6 to send("Fixed.", id = "s2"),
        )
        val times = journal.associate { (seq, _) -> seq to t0 + seq.hours }
        val candidates = candidatesIn(journal, times, t0, ::contextSince)
        candidates.map { it.seq } shouldContainExactly listOf(4)
        candidates.single().type shouldBe ScenarioType.REPLY
    }

    test("selection spreads over time, keeps moments apart and reports shortfalls") {
        fun c(seq: Int, type: ScenarioType, minutes: Int, trigger: Int = seq, session: String? = null) =
            Candidate(seq, t0 + minutes.minutes, type, session, trigger, "")
        val candidates = (1..10).map { c(it, ScenarioType.REPLY, it * 100) } +
            c(20, ScenarioType.REPLY, 100, trigger = 1) +
            c(30, ScenarioType.SESSION, 5000, session = "a") + c(31, ScenarioType.SESSION, 6000, session = "a") + c(32, ScenarioType.SESSION, 7000, session = "b")
        val selection = selectScenarios(candidates, mapOf(ScenarioType.REPLY to 4, ScenarioType.SESSION to 2, ScenarioType.HELD to 1), recentSince = t0)
        selection.picked.count { it.type == ScenarioType.REPLY } shouldBe 4
        selection.picked.filter { it.type == ScenarioType.SESSION }.map { it.sessionId }.toSet() shouldBe setOf("a", "b")
        selection.picked.map { it.triggerKey }.distinct().size shouldBe selection.picked.size
        selection.shortfalls shouldBe mapOf(ScenarioType.HELD to 1)
        selection.picked.filter { it.type == ScenarioType.REPLY }.any { it.seq >= 8 } shouldBe true
    }

    test("cards render as text with choices, kind and urgency") {
        val input = buildJsonObject {
            put(
                "cards",
                buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", "Starting now?") })
                    add(
                        buildJsonObject {
                            put("type", "choices"); put("prompt", "When?")
                            put(
                                "options",
                                buildJsonArray {
                                    add(buildJsonObject { put("label", "Now"); put("reply", "now") })
                                    add(buildJsonObject { put("label", "Later"); put("reply", "later") })
                                },
                            )
                        },
                    )
                },
            )
            put("kind", "check_in"); put("urgency", "normal")
        }
        renderCards(input) shouldBe "Starting now?\n\n[choices] When? (Now) | (Later)\n\n(kind: check_in, urgency: normal)"
        proseWords(input) shouldBe 5
    }

    test("the given context shows the trigger in full, earlier lines condensed and tool results cut") {
        val long = "x".repeat(900)
        val messages = listOf(
            Message.user("first question"),
            send("first answer"),
            Message.user("Can you check the schedule for Tuesday please?"),
            call("c1", "search_calendar", buildJsonObject { put("day", "Tuesday") }),
            result("c1", long),
        )
        val given = renderGiven(messages)
        given shouldContain "Person: first question"
        given shouldContain "Clarity sent: first answer"
        given shouldContain "Can you check the schedule for Tuesday please?"
        given shouldContain "Clarity called search_calendar(day=Tuesday)"
        given shouldContain "-> search_calendar"
        given shouldNotContain long
    }

    test("answers are read from their blocks; empty blocks are skipped") {
        val md = scenariosMarkdown(
            listOf(
                ScenarioView(1, ScenarioType.REPLY, "11-08 08:34", 28, "given", "orig", yours = "The one I wish for.\n\nTwo paragraphs."),
                ScenarioView(2, ScenarioType.CHAT, "11-08 09:00", null, "g <!-- yours:99 --> x", "o"),
            ),
        )
        parseAnswers(md) shouldBe mapOf(1 to "The one I wish for.\n\nTwo paragraphs.")
        md shouldContain "<details><summary>What it sent</summary>"
    }

    test("the report shows yours, the original and every replay with word counts") {
        val md = reportMarkdown(
            "T",
            listOf(
                ReportEntry(
                    1, ScenarioType.REPLY, "11-08 08:34", "Mine here", "Original text", 2,
                    listOf(ReplayOutput("One two three", 3), ReplayOutput("", 0, error = "boom"), ReplayOutput("", 0, otherTools = listOf("recall"))),
                ),
            ),
        )
        md shouldContain "**Yours** (2 words)"
        md shouldContain "**Original** (2 words)"
        md shouldContain "**Replay 1** (3 words)"
        md shouldContain "**Sampling** 1 sent, 1 looked up first: recall, 1 failed (3 samples)"
        md shouldNotContain "**Replay 2**"
    }

    test("sampling continues until enough sent, capped at three times the runs") {
        val sent = ReplayOutput("hi", 1)
        val lookup = ReplayOutput("", 0, otherTools = listOf("list_calendar_events"))
        moreSamplesNeeded(emptyList(), 3) shouldBe 3
        moreSamplesNeeded(listOf(sent, lookup, lookup), 3) shouldBe 2
        moreSamplesNeeded(listOf(sent, sent, sent), 3) shouldBe 0
        moreSamplesNeeded(List(8) { lookup } + sent, 3) shouldBe 0
        moreSamplesNeeded(List(7) { lookup } + sent, 3) shouldBe 1
        samplingSummary(listOf(sent, sent, sent, lookup, lookup)) shouldBe "3 sent, 2 looked up first: list_calendar_events x2 (5 samples)"
    }

    test("only read-only SQL reaches psql") {
        requireReadOnlySql("select 1;") shouldBe "select 1"
        runCatching { requireReadOnlySql("delete from agent_journal") }.isFailure shouldBe true
        runCatching { requireReadOnlySql("select 1; drop table x") }.isFailure shouldBe true
        runCatching { requireReadOnlySql("with x as (select 1) update y set a=1") }.isFailure shouldBe true
    }

    test("the constitution live at a time is the highest version created by then") {
        val rows = listOf(1, 2, 3).map { ConstitutionRow(it, t0 + it.hours, JsonPrimitive(it)) }
        constitutionAt(rows, t0 + 150.minutes)?.version shouldBe 2
        constitutionAt(rows, t0) shouldBe null
    }

    test("a replayed answer shows only send_to_user and names the other tools") {
        val out = interpret(listOf(Block.Text("thinking"), send("Hello there friend").content.single(), Block.ToolUse("x", "recall", JsonObject(emptyMap()))))
        out.text shouldBe "Hello there friend"
        out.words shouldBe 3
        out.otherTools shouldBe listOf("recall")
    }
})
