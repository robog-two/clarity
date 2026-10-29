package net.robog.clarity.harness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.toList

private fun request(vararg tools: Tool, maxTurns: Int = 10, runId: String = "run-1") =
    RunRequest("deepseek/deepseek-v4.1-flash", "system", listOf(Message.user("go")), tools.toList(), ToolPolicy.Default, maxTurns).copy(runId = runId)

/** The tool call ids [tool] ran under (from its [ToolCallContext]). */
private class CountingTool(override val name: String, private val effectOf: ToolEffect = ToolEffect.WRITE) : Tool {
    val calls = CopyOnWriteArrayList<String>()
    override val description = "counts"
    override val inputSchema = kotlinx.serialization.json.buildJsonObject { put("type", kotlinx.serialization.json.JsonPrimitive("object")) }
    override val effect = effectOf
    override suspend fun invoke(input: kotlinx.serialization.json.JsonObject): ToolResult {
        calls += coroutineContext[ToolCallContext]!!.toolUseId
        return ToolResult.Ok("$name done #${calls.size}")
    }
}

/** Guards in the loop itself against runs that repeat or never stop; scripted gateways, no network. */
class RunawayGuardsTest : FunSpec({
    test("tool call ids are made unique across the run: a repeated, blank or odd id gets a free suffix") {
        val used = mutableSetOf("call_0", "call_0_1")
        val out = uniqueToolIds(
            listOf(Block.Text("x"), toolUse("call_0", "a"), toolUse("call_0", "b"), toolUse("", "c"), toolUse("call 9!", "d"), toolUse("fresh", "e")),
            used,
        )
        out.filterIsInstance<Block.ToolUse>().map { it.id } shouldContainExactly listOf("call_0_2", "call_0_3", "call_1", "call9_1", "fresh")
        out.first() shouldBe Block.Text("x")
        used.containsAll(listOf("call_0_2", "call_0_3", "call_1", "call9_1", "fresh")) shouldBe true
    }

    test("two turns that both use call_0 run the tool twice under different ids (an idempotency key never collides)") {
        val send = CountingTool("send")
        val gateway = ScriptedGateway(
            listOf(
                response(StopReason.TOOL_USE, toolUse("call_0", "send")),
                response(StopReason.TOOL_USE, toolUse("call_0", "send")),
                response(StopReason.END_TURN, Block.Text("sent twice")),
            ),
        )
        val journal = InMemoryRunJournal()
        Harness(gateway, journal).run(request(send)).toList().last().shouldBeInstanceOf<HarnessEvent.Finished>()
        send.calls shouldContainExactly listOf("call_0", "call_0_1")
        // The renamed id is what the wire sees, for the call and its result alike.
        val wire = OpenRouterModelGateway.buildBody(gateway.requests.last()).toString()
        wire.contains("\"id\":\"call_0_1\"") shouldBe true
        wire.contains("\"tool_call_id\":\"call_0_1\"") shouldBe true
    }

    test("a resumed run continues its step budget instead of starting a fresh one") {
        val tool = CountingTool("step", ToolEffect.READ)
        val journal = InMemoryRunJournal()
        // Before the crash: 3 model calls, each running the tool.
        journal.append("run-1", Message.user("go"))
        repeat(3) { i ->
            journal.append("run-1", Message(Role.ASSISTANT, listOf(toolUse("t$i", "step"))))
            journal.append("run-1", Message(Role.USER, listOf(Block.Result("t$i", "ok"))))
        }
        turnsUsed(journal.load("run-1")) shouldBe 3
        val gateway = ScriptedGateway(List(10) { response(StopReason.TOOL_USE, toolUse("n$it", "step")) })
        val done = Harness(gateway, journal)
            .resume("run-1", request(tool, maxTurns = 4).copy(countStepsSince = { false }))
            .toList().last().shouldBeInstanceOf<HarnessEvent.Finished>()
        // One call was left: it asked for a tool, which did not run.
        gateway.requests shouldHaveSize 1
        done.reason shouldBe FinishReason.MAX_TURNS
        tool.calls shouldHaveSize 0
    }

    test("a run resumed with its steps spent only wraps up, and never runs a tool") {
        val tool = CountingTool("step", ToolEffect.READ)
        val journal = InMemoryRunJournal()
        journal.append("run-1", Message.user("go"))
        repeat(5) { i ->
            journal.append("run-1", Message(Role.ASSISTANT, listOf(toolUse("t$i", "step"))))
            journal.append("run-1", Message(Role.USER, listOf(Block.Result("t$i", "ok"))))
        }
        val gateway = ScriptedGateway(listOf(response(StopReason.TOOL_USE, Block.Text("got this far"), toolUse("w", "step"))))
        val done = Harness(gateway, journal)
            .resume("run-1", request(tool, maxTurns = 5).copy(outOfSteps = OutOfSteps(), countStepsSince = { false }))
            .toList().last().shouldBeInstanceOf<HarnessEvent.Finished>()
        gateway.requests shouldHaveSize 1
        gateway.requests.single().messages.last().content.filterIsInstance<Block.Text>().single().text shouldBe OutOfSteps().report
        done.reason shouldBe FinishReason.MAX_TURNS
        done.result.finalText shouldBe "got this far"
        tool.calls shouldHaveSize 0
        // The wrap-up's call is closed too.
        journal.load("run-1").last().content.single().shouldBeInstanceOf<Block.Result>().isError shouldBe true
    }

    test("counting since a marker: only the stretch after it (a nudge, a new lead turn) counts") {
        val history = listOf(
            Message.user("go"),
            Message(Role.ASSISTANT, listOf(Block.Text("a"))),
            Message.user("nudge"),
            Message(Role.ASSISTANT, listOf(toolUse("1", "x"))),
            Message(Role.USER, listOf(Block.Result("1", "ok"))),
            Message(Role.ASSISTANT, listOf(Block.Text("b"))),
        )
        turnsUsed(history) shouldBe 3
        turnsUsed(history) { it.role == Role.USER && it.content.none { b -> b is Block.Result } } shouldBe 2
    }

    test("out of steps without a last call: the open calls are closed and the run stops (nothing left to run at the next boot)") {
        val tool = CountingTool("step", ToolEffect.READ)
        val gateway = ScriptedGateway(List(5) { response(StopReason.TOOL_USE, toolUse("c$it", "step")) })
        val journal = InMemoryRunJournal()
        val steps = OutOfSteps(finalCall = false, notRun = "Not run: the turn ran out of steps.")
        val done = Harness(gateway, journal).run(request(tool, maxTurns = 2).copy(outOfSteps = steps)).toList().last()
            .shouldBeInstanceOf<HarnessEvent.Finished>()
        done.reason shouldBe FinishReason.MAX_TURNS
        gateway.requests shouldHaveSize 2
        tool.calls shouldHaveSize 1
        val last = journal.load("run-1").last()
        last.role shouldBe Role.USER
        last.content.single().shouldBeInstanceOf<Block.Result>().content shouldBe "Not run: the turn ran out of steps."
    }

    test("a call the ledger already has a result for is not run again on resume; one it lacks is") {
        val send = CountingTool("send")
        val journal = InMemoryRunJournal()
        val ledger = InMemoryToolLedger()
        // The crash came after `done` returned (its result is in the ledger) but before the results were journaled.
        journal.append("run-1", Message.user("go"))
        journal.append("run-1", Message(Role.ASSISTANT, listOf(toolUse("done", "send"), toolUse("open", "send"))))
        ledger.record("run-1", "done", LedgerEntry("send", "Sent before the crash", isError = false))
        val gateway = ScriptedGateway(listOf(response(StopReason.END_TURN, Block.Text("ok"))))
        Harness(gateway, journal, ledger).resume("run-1", request(send)).toList().last().shouldBeInstanceOf<HarnessEvent.Finished>()
        send.calls shouldContainExactly listOf("open")
        val results = journal.load("run-1")[2].content.filterIsInstance<Block.Result>()
        results.map { it.toolUseId to it.content } shouldContainExactly listOf("done" to "Sent before the crash", "open" to "send done #1")
        // Every result is kept as soon as the tool returns.
        ledger.find("run-1", "open") shouldNotBe null
    }

    test("a crash after a tool returned, resumed with the same ledger, never runs it twice (simulated crash)") {
        val send = CountingTool("send")
        val journal = InMemoryRunJournal()
        val ledger = InMemoryToolLedger()
        // A journal that drops the tool-results message: the crash between the tool and the journal.
        val crashing = object : RunJournal by journal {
            override suspend fun append(runId: String, message: Message) {
                if (message.role == Role.USER && message.content.any { it is Block.Result }) error("crash")
                journal.append(runId, message)
            }
        }
        val first = ScriptedGateway(listOf(response(StopReason.TOOL_USE, toolUse("x", "send"))))
        runCatching { Harness(first, crashing, ledger).run(request(send)).toList() }
        send.calls shouldHaveSize 1
        val second = ScriptedGateway(listOf(response(StopReason.END_TURN, Block.Text("done"))))
        Harness(second, journal, ledger).resume("run-1", request(send)).toList().last().shouldBeInstanceOf<HarnessEvent.Finished>()
        send.calls shouldHaveSize 1
        journal.load("run-1").flatMap { it.content }.filterIsInstance<Block.Result>().single().content shouldBe "send done #1"
    }

    test("a spend cap without a last call stops right after the tools already asked for") {
        val tool = CountingTool("step", ToolEffect.READ)
        val costly = Usage(10, 5, reportedCostMicros = 60_000)
        val gateway = ScriptedGateway(List(5) { response(StopReason.TOOL_USE, toolUse("c$it", "step"), usage = costly) })
        val done = Harness(gateway).run(request(tool).copy(spendCap = SpendCap(100_000, finalCall = false))).toList().last()
            .shouldBeInstanceOf<HarnessEvent.Finished>()
        done.reason shouldBe FinishReason.SPEND_LIMIT
        gateway.requests shouldHaveSize 2
        tool.calls shouldHaveSize 2
    }

    test("a runaway loop is stopped by the step limit even when every call asks for more") {
        val tool = CountingTool("search", ToolEffect.READ)
        val gateway = ScriptedGateway(List(100) { response(StopReason.TOOL_USE, Block.Text("I have still not searched."), toolUse("call_0", "search")) })
        val done = Harness(gateway).run(request(tool, maxTurns = 6).copy(outOfSteps = OutOfSteps())).toList().last()
            .shouldBeInstanceOf<HarnessEvent.Finished>()
        done.reason shouldBe FinishReason.MAX_TURNS
        // 6 steps plus the one wrap-up call; 5 tool runs (the 6th step's call never ran), each under its own id.
        gateway.requests shouldHaveSize 7
        tool.calls shouldHaveSize 5
        tool.calls.toSet() shouldHaveSize 5
    }
})
