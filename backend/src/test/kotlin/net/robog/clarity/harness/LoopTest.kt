package net.robog.clarity.harness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class LoopTest : FunSpec({
    val call = toolUse("t1", "list_messages")

    test("end_turn finishes") {
        decide(response(StopReason.END_TURN, Block.Text("hi")), turn = 1, maxTurns = 5) shouldBe Decision.Stop(FinishReason.END_TURN)
    }

    test("tool_use asks to run every requested tool") {
        val second = toolUse("t2", "search_contacts")
        decide(response(StopReason.TOOL_USE, call, second), 1, 5) shouldBe Decision.RunTools(listOf(call, second))
    }

    test("tool_use on the last allowed turn stops with MAX_TURNS instead of running tools") {
        decide(response(StopReason.TOOL_USE, call), turn = 5, maxTurns = 5) shouldBe Decision.Stop(FinishReason.MAX_TURNS)
    }

    test("refusal stops, even if the turn contains a tool_use") {
        decide(response(StopReason.REFUSAL, call), 1, 5) shouldBe Decision.Stop(FinishReason.REFUSAL)
    }

    test("max_tokens never executes a possibly truncated tool call") {
        decide(response(StopReason.MAX_TOKENS, call), 1, 5) shouldBe Decision.Stop(FinishReason.MAX_TOKENS)
    }

    test("pause_turn without tool calls resumes; with tool calls runs them") {
        decide(response(StopReason.PAUSE_TURN, Block.Text("...")), 1, 5) shouldBe Decision.Resume
        decide(response(StopReason.PAUSE_TURN, call), 1, 5).shouldBeInstanceOf<Decision.RunTools>()
    }

    test("tool_use stop without a tool_use block is treated as finished") {
        decide(response(StopReason.TOOL_USE, Block.Text("odd")), 1, 5) shouldBe Decision.Stop(FinishReason.OTHER)
    }

    test("routing: unknown, hidden and approval-gated tools are never run") {
        val read = fakeTool("read")
        val write = fakeTool("write", ToolEffect.WRITE)
        val tools = mapOf("read" to read, "write" to write)
        route(toolUse("1", "read"), tools, ToolPolicy.Default).shouldBeInstanceOf<Dispatch.Run>()
        route(toolUse("2", "write"), tools, ToolPolicy.Default).shouldBeInstanceOf<Dispatch.NeedsApproval>()
        route(toolUse("3", "nope"), tools, ToolPolicy.Default).shouldBeInstanceOf<Dispatch.Unknown>()
        route(toolUse("4", "read"), tools, ToolPolicy.only(setOf("write"))).shouldBeInstanceOf<Dispatch.Unknown>()
        route(toolUse("5", "write"), tools, ToolPolicy.AllowAll).shouldBeInstanceOf<Dispatch.Run>()
    }
})
