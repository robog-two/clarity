package net.robog.clarity.harness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.currentCoroutineContext

/** What the lead relies on in the harness: the tool-call context. */
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
})
