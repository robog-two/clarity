package net.robog.clarity.harness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

private fun Harness.collect(request: RunRequest) = run(request)

private fun request(vararg tools: Tool, policy: ToolPolicy = ToolPolicy.Default, maxTurns: Int = 10) =
    RunRequest("claude-opus-5-5", "system", listOf(Message.user("go")), tools.toList(), policy, maxTurns)

class HarnessTest : FunSpec({

    test("single turn: streams text, finishes with usage and transcript") {
        val gateway = ScriptedGateway(listOf(response(StopReason.END_TURN, Block.Text("hello"))))
        val events = Harness(gateway).run(request()).toList()

        events.map { it::class.simpleName } shouldContainExactly
            listOf("TurnStarted", "TextDelta", "AssistantText", "Finished")
        val done = events.last().shouldBeInstanceOf<HarnessEvent.Finished>()
        done.reason shouldBe FinishReason.END_TURN
        done.usage shouldBe Usage(inputTokens = 10, outputTokens = 5)
        done.result.finalText shouldBe "hello"
        done.transcript.map { it.role } shouldContainExactly listOf(Role.USER, Role.ASSISTANT)
    }

    test("tool round trip: two tools run in parallel and return in ONE user message, in call order") {
        val bStarted = CompletableDeferred<Unit>()
        // `a` can only finish after `b` has started, so a sequential executor would deadlock.
        val a = fakeTool("a") { bStarted.await(); ToolResult.Ok("A") }
        val b = fakeTool("b") { bStarted.complete(Unit); ToolResult.Ok("B") }
        val gateway = ScriptedGateway(
            listOf(
                response(StopReason.TOOL_USE, Block.Text("checking"), toolUse("1", "a"), toolUse("2", "b")),
                response(StopReason.END_TURN, Block.Text("done")),
            ),
        )

        val events = withTimeout(5.seconds) { Harness(gateway).run(request(b, a)).toList() }

        val results = gateway.requests[1].messages.last()
        results.role shouldBe Role.USER
        results.content shouldContainExactly listOf(Block.Result("1", "A", false), Block.Result("2", "B", false))
        // The assistant turn is replayed unchanged ahead of the results.
        gateway.requests[1].messages[1].content.size shouldBe 3
        events.filterIsInstance<HarnessEvent.ToolCompleted>().map { it.name }.toSet() shouldBe setOf("a", "b")
        events.last().shouldBeInstanceOf<HarnessEvent.Finished>().usage shouldBe Usage(20, 10)
    }

    test("tools are offered to the model sorted by name") {
        val gateway = ScriptedGateway(listOf(response(StopReason.END_TURN, Block.Text("x"))))
        Harness(gateway).run(request(fakeTool("zeta"), fakeTool("alpha"))).toList()
        gateway.requests.single().tools.map { it.name } shouldContainExactly listOf("alpha", "zeta")
    }

    test("a failing or throwing tool becomes an is_error result, not a crash") {
        val gateway = ScriptedGateway(
            listOf(
                response(StopReason.TOOL_USE, toolUse("1", "bad"), toolUse("2", "boom")),
                response(StopReason.END_TURN, Block.Text("recovered")),
            ),
        )
        val tools = arrayOf(
            fakeTool("bad") { ToolResult.Error("nope") },
            fakeTool("boom") { error("kaput") },
        )
        val events = Harness(gateway).run(request(*tools)).toList()

        val results = gateway.requests[1].messages.last().content.filterIsInstance<Block.Result>()
        results.map { it.isError } shouldContainExactly listOf(true, true)
        results[0].content shouldBe "nope"
        results[1].content shouldBe "Tool boom failed: kaput"
        events.last().shouldBeInstanceOf<HarnessEvent.Finished>().reason shouldBe FinishReason.END_TURN
    }

    test("approval-required: WRITE tool is not executed, event emitted, error result returned") {
        var ran = false
        val write = fakeTool("create_thing", ToolEffect.WRITE) { ran = true; ToolResult.Ok("done") }
        val gateway = ScriptedGateway(
            listOf(
                response(StopReason.TOOL_USE, toolUse("1", "create_thing")),
                response(StopReason.END_TURN, Block.Text("ok without it")),
            ),
        )
        val events = Harness(gateway).run(request(write)).toList()

        ran shouldBe false
        events.filterIsInstance<HarnessEvent.ApprovalRequired>().single().name shouldBe "create_thing"
        val result = gateway.requests[1].messages.last().content.single().shouldBeInstanceOf<Block.Result>()
        result.isError shouldBe true
        result.content.contains("pending") shouldBe true
    }

    test("a policy that allows writes lets them run") {
        val write = fakeTool("create_thing", ToolEffect.WRITE)
        val gateway = ScriptedGateway(
            listOf(response(StopReason.TOOL_USE, toolUse("1", "create_thing")), response(StopReason.END_TURN)),
        )
        Harness(gateway).run(request(write, policy = ToolPolicy.AllowAll)).toList()
        gateway.requests[1].messages.last().content.single() shouldBe Block.Result("1", "create_thing ok", false)
    }

    test("hidden and unknown tools are reported as unknown, and hidden ones are not offered") {
        val gateway = ScriptedGateway(
            listOf(response(StopReason.TOOL_USE, toolUse("1", "secret")), response(StopReason.END_TURN)),
        )
        Harness(gateway).run(request(fakeTool("secret"), fakeTool("open"), policy = ToolPolicy.only(setOf("open")))).toList()
        gateway.requests[0].tools.map { it.name } shouldContainExactly listOf("open")
        gateway.requests[1].messages.last().content.single().shouldBeInstanceOf<Block.Result>().content shouldBe "Unknown tool: secret"
    }

    test("max turns: stops instead of running tools on the last turn") {
        val gateway = ScriptedGateway(
            listOf(response(StopReason.TOOL_USE, toolUse("1", "a")), response(StopReason.TOOL_USE, toolUse("2", "a"))),
        )
        val events = Harness(gateway).run(request(fakeTool("a"), maxTurns = 2)).toList()
        events.last().shouldBeInstanceOf<HarnessEvent.Finished>().reason shouldBe FinishReason.MAX_TURNS
        gateway.requests.size shouldBe 2
    }

    test("refusal ends the run without running tools") {
        var ran = false
        val gateway = ScriptedGateway(listOf(response(StopReason.REFUSAL, toolUse("1", "a"))))
        val events = Harness(gateway).run(request(fakeTool("a") { ran = true; ToolResult.Ok("") })).toList()
        ran shouldBe false
        events.last().shouldBeInstanceOf<HarnessEvent.Finished>().reason shouldBe FinishReason.REFUSAL
    }

    test("gateway failure surfaces as Failed with the usage and transcript so far") {
        val gateway = object : ModelGateway {
            var calls = 0
            override suspend fun generate(request: ModelRequest, onTextDelta: (String) -> Unit): ModelResponse =
                if (calls++ == 0) response(StopReason.TOOL_USE, toolUse("1", "a")) else error("503 overloaded")
        }
        val failed = Harness(gateway).run(request(fakeTool("a"))).toList().last().shouldBeInstanceOf<HarnessEvent.Failed>()
        failed.message shouldBe "503 overloaded"
        failed.usage shouldBe Usage(10, 5)
        failed.transcript.size shouldBe 3
    }

    test("typed tools: bad input is a ToolResult.Error, good input runs") {
        @Serializable
        class Args(val city: String, val days: Int = 1)

        val weather = tool<Args>("weather", "Weather") { ToolResult.Ok("${it.city}:${it.days}") }
        weather.inputSchema["required"].toString() shouldBe """["city"]"""

        val gateway = ScriptedGateway(
            listOf(
                response(StopReason.TOOL_USE, toolUse("1", "weather", "city" to "Oslo"), toolUse("2", "weather")),
                response(StopReason.END_TURN),
            ),
        )
        Harness(gateway).run(request(weather)).toList()
        val results = gateway.requests[1].messages.last().content.filterIsInstance<Block.Result>()
        results[0] shouldBe Block.Result("1", "Oslo:1", false)
        results[1].isError shouldBe true
    }
})
