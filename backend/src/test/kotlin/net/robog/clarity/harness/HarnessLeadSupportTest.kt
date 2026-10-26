package net.robog.clarity.harness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val compactionBlock = Block.Raw(buildJsonObject { put("type", "compaction"); put("content", "summary") })

/** The two small harness additions the lead relies on: the tool-call context and compact-now runs. */
class HarnessLeadSupportTest : FunSpec({
    test("a tool can see which call it is running for (stable across a resume)") {
        val seen = mutableListOf<Pair<String, String>>()
        val tool = fakeTool("probe") {
            val call = currentCoroutineContext()[ToolCallContext]!!
            seen += call.runId to call.toolUseId
            ToolResult.Ok("ok")
        }
        val gateway = ScriptedGateway(
            listOf(
                response(StopReason.TOOL_USE, toolUse("toolu_9", "probe")),
                response(StopReason.END_TURN, Block.Text("done")),
            ),
        )
        val request = RunRequest(
            model = "m", system = listOf(SystemBlock("s")), messages = listOf(Message.user("go")),
            tools = listOf(tool), policy = ToolPolicy.AllowAll, runId = "run-7",
        )
        Harness(gateway).run(request).awaitTerminal().shouldBeInstanceOf<HarnessEvent.Finished>()
        seen shouldBe listOf("run-7" to "toolu_9")
    }

    test("compactNow asks for compaction at the minimum trigger, pauses, and stops at the compaction block") {
        val gateway = ScriptedGateway(listOf(response(StopReason.COMPACTION, compactionBlock)))
        val journal = InMemoryRunJournal()
        journal.append("t", Message.user("long history"))
        journal.append("t", Message(Role.ASSISTANT, listOf(Block.Text("ok"))))
        journal.append("t", Message.user("(housekeeping)"))
        val request = RunRequest(
            model = "m", system = listOf(SystemBlock("s")), messages = emptyList(), tools = listOf(fakeTool("a")),
            compaction = CompactionConfig(triggerInputTokens = 150_000), compactNow = true,
        )
        val end = Harness(gateway, journal).resume("t", request).awaitTerminal().shouldBeInstanceOf<HarnessEvent.Finished>()
        end.reason shouldBe FinishReason.COMPACTED
        gateway.requests.size shouldBe 1
        gateway.requests.single().compaction shouldBe CompactionConfig(
            triggerInputTokens = COMPACTION_MIN_TRIGGER_TOKENS, pauseAfterCompaction = true,
        )
        // the compaction block was journaled verbatim: the next conversation starts from it
        journal.load("t").last() shouldBe Message(Role.ASSISTANT, listOf(compactionBlock))
    }

    test("without compactNow a compaction pause just continues the run") {
        val gateway = ScriptedGateway(
            listOf(response(StopReason.COMPACTION, compactionBlock), response(StopReason.END_TURN, Block.Text("answer"))),
        )
        val request = RunRequest(
            model = "m", system = listOf(SystemBlock("s")), messages = listOf(Message.user("go")),
            tools = listOf(fakeTool("a")), compaction = CompactionConfig(),
        )
        Harness(gateway).run(request).awaitTerminal().shouldBeInstanceOf<HarnessEvent.Finished>().reason shouldBe FinishReason.END_TURN
        gateway.requests.first().compaction shouldBe CompactionConfig()
    }
})
