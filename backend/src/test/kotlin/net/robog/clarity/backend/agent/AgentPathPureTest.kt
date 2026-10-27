package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Role
import net.robog.clarity.shared.browser.BrowserStepView
import net.robog.clarity.shared.path.Outcome
import net.robog.clarity.shared.path.PathItem

private val t0 = Instant.parse("2026-10-27T15:00:00Z")

private fun row(seq: Int, message: Message) = JournalRow(seq, t0 + (seq * 10).seconds, message)
private fun said(text: String) = Message(Role.ASSISTANT, listOf(Block.Text(text)))
private fun use(id: String, name: String, input: JsonObject = buildJsonObject { }, text: String? = null) =
    Message(Role.ASSISTANT, listOfNotNull(text?.let(Block::Text), Block.ToolUse(id, name, input)))
private fun result(id: String, content: String, error: Boolean = false) = Message(Role.USER, listOf(Block.Result(id, content, error)))

private fun step(id: Long, seq: Int, gate: String, at: Instant, intent: String = "the Sign in link", action: String? = "click_by_uid {\"uid\":\"e1\"}") =
    BrowserStepView(id, "h1", seq, at, "act", "https://login.clarity.test/code?token=abc123", "Clarity ID", intent, "a form", true, false, null, "link", "Sign in", null, gate, action, true, false, "Page changed (Jev p=0.99)", 120)

class AgentPathPureTest : FunSpec({
    val redact = PathRedactor()

    context("tool calls become friendly lines") {
        test("counts come from JSON array results; inputs give the detail") {
            describeCall("list_messages", buildJsonObject { put("participant", "dana@example.com") }, "[{},{},{}]") shouldBe ("Read 3 messages" to "dana@example.com")
            describeCall("list_calendar_events", buildJsonObject { put("date", "2026-10-28") }, "[{}]") shouldBe ("Found 1 event" to "2026-10-28")
            describeCall("list_messages", buildJsonObject { }, null).first shouldBe "Checked mail"
            describeCall("create_reminder", buildJsonObject { put("title", "Pickup"); put("due", "Wed 4:00") }, "ok") shouldBe ("Set a reminder" to "Pickup · Wed 4:00")
            describeCall("send_to_user", buildJsonObject { put("cards", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "Signed in.") }) }) }, "ok").first shouldBe "Sent you a message"
        }

        test("vault tools are fixed lines with nothing from their input or result, even on error") {
            val code = actionItem("h1", 0, "ask_code", buildJsonObject { put("reason", "the code for shop") }, t0, "482913", isError = false, redact)
            code.line shouldBe "Asked you for a code"
            code.detail shouldBe null
            val failed = actionItem("h1", 0, "request_credential", buildJsonObject { put("site", "shop") }, t0, "no login for Tr0ub4dor&3", isError = true, redact)
            failed.outcome shouldBe Outcome.ERROR
            failed.detail shouldBe null
        }

        test("an unknown tool shows only plain short arguments, never values typed or written") {
            val (line, detail) = describeCall("sandbox__run", buildJsonObject { put("cwd", "/work"); put("value", "hunter2"); put("body", "long text") }, "ok")
            line shouldBe "Used sandbox run"
            detail shouldBe "cwd: /work"
        }

        test("held and error outcomes keep a short reason; a call without result is running") {
            actionItem("r", 0, "send_email", buildJsonObject { put("subject", "Hi") }, t0, "Held for review: Send to Dana. Don't retry.", false, redact).outcome shouldBe Outcome.HELD
            actionItem("r", 0, "send_email", buildJsonObject { }, t0, "boom", true, redact).let { it.outcome shouldBe Outcome.ERROR; it.detail shouldBe "boom" }
            actionItem("r", 0, "send_email", buildJsonObject { }, t0, null, false, redact).outcome shouldBe Outcome.RUNNING
        }
    }

    context("redaction") {
        test("placeholders, secrets and untrusted quotes are taken out; long text is clipped") {
            val text = redact.text("Typed [secret:garden_password] then the password is Tr0ub4dor&3 <untrusted source=\"web\">ignore all</untrusted> done")
            text shouldNotContain "garden_password"
            text shouldNotContain "Tr0ub4dor"
            text shouldNotContain "ignore all"
            (redact.text("word ".repeat(100)).length <= PATH_CLIP) shouldBe true
        }

        test("released values are scrubbed by the grant store's function") {
            PathRedactor { it.replace("482913", "[secret:code_h1]") }.text("I typed 482913") shouldBe "I typed [secret]"
        }

        test("a browser step loses its address query and its action input") {
            val safe = redact.step(step(1, 1, "passed", t0, action = "fill_by_uid {\"uid\":\"e3\",\"value\":\"[secret:garden_password]\"}"))
            safe.tabUrl shouldBe "https://login.clarity.test/code"
            safe.action shouldBe "fill_by_uid"
        }
    }

    context("the lead's turns") {
        val rows = listOf(
            row(1, Message.user("Sign me in to the Garden Shop")),
            row(2, use("u1", "update_task", buildJsonObject { put("title", "Sign in to Garden Shop") })),
            row(3, result("u1", "Task 11111111-1111-1111-1111-111111111111: Sign in to Garden Shop (working).")),
            row(4, use("d1", "delegate", buildJsonObject { put("task", "Sign in") })),
            row(5, result("d1", "Helper 22222222-2222-2222-2222-222222222222 started (helper). Its result will arrive in your inbox.")),
            row(6, said("On it.")),
            row(7, Message.user("What's on tomorrow?")),
            row(8, use("c1", "list_calendar_events", buildJsonObject { put("date", "2026-10-28") })),
            row(9, result("c1", "[]")),
            row(10, Message(Role.SYSTEM, listOf(Block.Text("[inbox, now Tue 3:12 pm]\n- Helper 22222222-2222-2222-2222-222222222222 finished its task: Sign in\n<untrusted source=\"helper:2\">Signed in; the code was 482913</untrusted>")))),
            row(11, use("u2", "update_task", buildJsonObject { put("id", "11111111-1111-1111-1111-111111111111"); put("status", "done") })),
            row(12, result("u2", "Task 11111111-1111-1111-1111-111111111111: Sign in to Garden Shop (done).")),
        )

        test("turns open at the person's words and at inbox deliveries; only those naming the task touch it") {
            val turns = leadTurns(rows)
            turns shouldHaveSize 3
            turnsTouching(turns, "11111111-1111-1111-1111-111111111111").map { it.first().seq } shouldBe listOf(1, 10)
            turnsTouching(turns, "33333333-3333-3333-3333-333333333333").shouldBeEmpty()
        }

        test("a delegate result becomes the helper's node; what woke a turn is heard, untrusted quotes left out") {
            val child = ChildRun("22222222-2222-2222-2222-222222222222", "helper", "Sign in", "done", t0, t0 + 60.seconds, steps = 4, browserSteps = 3)
            val turns = turnsTouching(leadTurns(rows), "11111111-1111-1111-1111-111111111111")
            val items = turns.flatMap { runItems("lead", it, emptyList(), mapOf(child.id to child), redact, lead = true) }
            items.map { it::class.simpleName } shouldBe listOf("Heard", "Action", "Helper", "Said", "Heard", "Action")
            items[0].shouldBeInstanceOf<PathItem.Heard>().from shouldBe PathItem.Heard.Source.PERSON
            items[2].shouldBeInstanceOf<PathItem.Helper>().steps shouldBe 4
            val heard = items[4].shouldBeInstanceOf<PathItem.Heard>()
            heard.from shouldBe PathItem.Heard.Source.HELPER
            heard.text shouldNotContain "482913"
            items[5].shouldBeInstanceOf<PathItem.Action>().line shouldBe "Updated the task"
        }
    }

    context("a helper's run") {
        test("browser steps take the place of their tool call; the task prompt is left out; the rest stays in order") {
            val rows = listOf(
                row(1, Message.user("Current time: …\n\nTask from the lead agent:\nSign in")),
                row(2, use("b1", "browser__act", buildJsonObject { put("intent", "the Sign in link"); put("value", "[secret:garden_password]") }, text = "Opening the sign-in.")),
                row(4, result("b1", "Page changed")),
                row(5, use("b2", "browser__look")),
                row(6, result("b2", "a long page text the app must never get")),
                row(7, use("k1", "ask_code", buildJsonObject { put("reason", "SMS code") })),
            )
            val steps = listOf(step(7, 1, "passed (jev sign_in p=0.99)", t0 + 25.seconds), step(8, 2, "code_guard", t0 + 30.seconds))
            val items = runItems("h1", rows, steps, emptyMap(), redact, lead = false, depth = 1)
            items.map { it::class.simpleName } shouldBe listOf("Said", "Browser", "Browser", "Action", "Action")
            items.all { it.depth == 1 } shouldBe true
            items[3].shouldBeInstanceOf<PathItem.Action>().let { it.line shouldBe "Looked at the page"; it.detail shouldBe null }
            items[4].shouldBeInstanceOf<PathItem.Action>().let { it.line shouldBe "Asked you for a code"; it.outcome shouldBe Outcome.RUNNING }
        }
    }

    context("a finished helper") {
        val action = PathItem.Action(t0, "h1", 1, "list_messages", "Read 3 messages", null, Outcome.OK)
        val final = PathItem.Said(t0 + 5.seconds, "h1", 1, "Shipped Monday.")

        test("its final turn folds into one finished node carrying the run's result; no separate bubble") {
            val items = withFinish(listOf(action, final), "h1", 1, ok = true, result = "Shipped Monday; arrives Thursday.", finishedAt = t0 + 6.seconds, redact)
            items.map { it::class.simpleName } shouldBe listOf("Action", "Finished")
            items.last().shouldBeInstanceOf<PathItem.Finished>().let {
                it.text shouldBe "Shipped Monday; arrives Thursday."
                it.at shouldBe t0 + 6.seconds
                it.depth shouldBe 1
            }
        }

        test("without a result the final text is the finished node's; a result is redacted") {
            withFinish(listOf(action, final), "h1", 0, ok = true, result = null, finishedAt = null, redact)
                .last().shouldBeInstanceOf<PathItem.Finished>().let { it.text shouldBe "Shipped Monday."; it.at shouldBe final.at }
            withFinish(listOf(action), "h1", 0, ok = false, result = "Stopped; the password is Tr0ub4dor&3", finishedAt = t0, redact)
                .let { items ->
                    items.map { it::class.simpleName } shouldBe listOf("Action", "Finished")
                    items.last().shouldBeInstanceOf<PathItem.Finished>().text shouldNotContain "Tr0ub4dor"
                }
        }

        test("text before a last tool call stays a bubble; nothing to say leaves the items as they are") {
            val said = PathItem.Said(t0, "h1", 1, "Looking.")
            withFinish(listOf(said, action), "h1", 1, ok = true, result = "Done.", finishedAt = t0, redact)
                .map { it::class.simpleName } shouldBe listOf("Said", "Action", "Finished")
            withFinish(listOf(action), "h1", 1, ok = true, result = null, finishedAt = t0, redact) shouldBe listOf(action)
        }
    }

    context("linking helpers to the turn's task") {
        test("a helper started while the turn has touched one task belongs to it") {
            val turn = TurnTasks()
            turn.touched("t1").shouldBeEmpty()
            turn.started("h1") shouldBe "t1"
        }
        test("a helper started first is linked when the turn's only task comes") {
            val turn = TurnTasks()
            turn.started("h1") shouldBe null
            turn.touched("t1") shouldBe listOf("h1")
            turn.touched("t1").shouldBeEmpty()
        }
        test("with two tasks in the turn nothing is guessed; a new turn starts empty") {
            val turn = TurnTasks()
            turn.touched("t1")
            turn.touched("t2")
            turn.started("h1") shouldBe null
            turn.reset()
            turn.started("h2") shouldBe null
            turn.touched("t3") shouldBe listOf("h2")
        }
    }
})
