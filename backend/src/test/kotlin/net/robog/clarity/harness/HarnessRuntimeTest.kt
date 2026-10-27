package net.robog.clarity.harness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private fun req(
    vararg tools: Tool,
    runId: String = "run-1",
    sink: ToolResultSink? = null,
) = RunRequest(
    model = "anthropic/claude-opus-5.5",
    system = listOf(SystemBlock("static", cacheBreakpoint = true), SystemBlock("constitution")),
    messages = listOf(Message.user("go")),
    tools = tools.toList(),
    policy = ToolPolicy.AllowAll,
    runId = runId,
    resultSink = sink,
)

private val reasoning = Block.Raw(buildJsonObject { put("type", "openrouter_reasoning"); put("clarity_provider", "openrouter"); put("model", "m") })

class HarnessRuntimeTest : FunSpec({

    test("system message placement: after a user message or before an assistant turn, never elsewhere") {
        val u = Message.user("u")
        val a = Message(Role.ASSISTANT, listOf(Block.Text("a")))
        val s = Message.system("s")
        systemPlacementViolations(listOf(u, s, a)) shouldBe emptyList()
        systemPlacementViolations(listOf(u, s, s, a)) shouldBe emptyList()
        systemPlacementViolations(listOf(s, a)) shouldBe emptyList() // precedes an assistant turn
        systemPlacementViolations(listOf(u, a, s)) shouldBe listOf(2) // trails an assistant turn
        systemPlacementViolations(listOf(s)) shouldBe listOf(0)
        systemPlacementViolations(listOf(u, a, s, u)) shouldBe listOf(2) // between an assistant and a user turn
        systemPlacementViolations(listOf(u, s, s, u)) shouldBe listOf(1, 2) // right before a user turn: the API refuses it
        systemPlacementViolations(listOf(u, a, Message.effort("low"), u)) shouldBe emptyList() // effort-only goes anywhere
    }

    test("wireOrder moves a user message that follows system messages in front of them") {
        val a = Message(Role.ASSISTANT, listOf(Block.Text("a")))
        val placeholder = Message.user("(operator update follows)")
        val memory = Message.system("memory")
        val inbox = Message.system("inbox")
        val said = Message.user("can we look at hair appointments")
        // What the lead's journal held when the person spoke during an operator-update turn.
        val journal = listOf(Message.user("go"), a, placeholder, memory, inbox, said)
        val wire = wireOrder(journal)
        wire shouldBe listOf(
            Message.user("go"), a,
            Message(Role.USER, placeholder.content + said.content), memory, inbox,
        )
        systemPlacementViolations(wire) shouldBe emptyList()
        wireOrder(wire) shouldBe wire // already valid histories are untouched
        // With no user message before the system run, the user message goes in front of it.
        wireOrder(listOf(Message.system("s"), said)) shouldBe listOf(said, Message.system("s"))
    }

    test("a run resumed from a journal with a user message after system messages sends a valid request") {
        val gateway = ScriptedGateway(listOf(response(StopReason.END_TURN, Block.Text("ok"))))
        val history = listOf(
            Message.user("go"), Message(Role.ASSISTANT, listOf(Block.Text("a"))),
            Message.user("(operator update follows)"), Message.system("inbox"), Message.user("hello"),
        )
        Harness(gateway).run(req().copy(messages = history), kotlinx.coroutines.flow.emptyFlow()).toList()
        systemPlacementViolations(gateway.requests.single().messages) shouldBe emptyList()
    }

    test("planTools hides, sorts and drops duplicate names") {
        val a = fakeTool("a")
        val b = fakeTool("b")
        val c = fakeTool("c")
        planTools(listOf(c, b, a, fakeTool("a")), ToolPolicy.AllowAll).tools.map { it.name } shouldContainExactly listOf("a", "b", "c")
        planTools(listOf(a, c), ToolPolicy.only(setOf("a"))).tools.map { it.name } shouldContainExactly listOf("a")
    }

    test("request carries system blocks, all tools and the cache policy; raw blocks replay verbatim") {
        val gateway = ScriptedGateway(
            listOf(
                response(StopReason.TOOL_USE, reasoning, Block.Text("hm"), toolUse("1", "a")),
                response(StopReason.END_TURN, Block.Text("done")),
            ),
        )
        Harness(gateway).run(req(fakeTool("z"), fakeTool("a"))).toList()

        val first = gateway.requests[0]
        first.system.map { it.cacheBreakpoint } shouldContainExactly listOf(true, false)
        first.tools.map { it.name } shouldContainExactly listOf("a", "z")
        first.cache shouldBe CachePolicy()
        // Append verbatim: raw blocks come back in order, untouched.
        gateway.requests[1].messages[1].content.take(2) shouldContainExactly listOf(reasoning, Block.Text("hm"))
    }

    test("journal checkpoints the initial message, every assistant message and every tool-result message") {
        val journal = InMemoryRunJournal()
        val gateway = ScriptedGateway(
            listOf(response(StopReason.TOOL_USE, toolUse("1", "a")), response(StopReason.END_TURN, Block.Text("done"))),
        )
        val done = Harness(gateway, journal).run(req(fakeTool("a"))).toList().last().shouldBeInstanceOf<HarnessEvent.Finished>()
        journal.load("run-1") shouldBe done.transcript
        journal.load("run-1").map { it.role } shouldContainExactly listOf(Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT)
    }

    test("resume re-runs tools left unanswered by a crash and continues") {
        val journal = InMemoryRunJournal()
        journal.append("r", Message.user("go"))
        journal.append("r", Message(Role.ASSISTANT, listOf(Block.Text("checking"), toolUse("1", "a"))))
        var ran = 0
        val gateway = ScriptedGateway(listOf(response(StopReason.END_TURN, Block.Text("finished"))))

        val events = Harness(gateway, journal).resume("r", req(fakeTool("a") { ran++; ToolResult.Ok("A") })).toList()

        ran shouldBe 1
        events.filterIsInstance<HarnessEvent.Resumed>().single() shouldBe HarnessEvent.Resumed(messages = 2, rerunTools = 1)
        gateway.requests.single().messages.map { it.role } shouldContainExactly listOf(Role.USER, Role.ASSISTANT, Role.USER)
        gateway.requests.single().messages.last().content.single() shouldBe Block.Result("1", "A", false)
        journal.load("r").size shouldBe 4
    }

    test("resume after a clean checkpoint calls the model with no replay; a finished run just finishes") {
        val journal = InMemoryRunJournal()
        journal.append("r", Message.user("go"))
        val gateway = ScriptedGateway(listOf(response(StopReason.END_TURN, Block.Text("hi"))))
        Harness(gateway, journal).resume("r", req()).toList().last().shouldBeInstanceOf<HarnessEvent.Finished>()
        gateway.requests.size shouldBe 1

        val again = Harness(ScriptedGateway(emptyList()), journal).resume("r", req()).toList()
        again.last().shouldBeInstanceOf<HarnessEvent.Finished>().result.finalText shouldBe "hi"
    }

    test("interrupt during a model call cancels it, drops the partial response and continues with the new message") {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val requests = mutableListOf<ModelRequest>()
        val gateway = object : ModelGateway {
            override suspend fun generate(request: ModelRequest, onTextDelta: (String) -> Unit): ModelResponse {
                requests += request
                if (requests.size == 1) {
                    onTextDelta("partial")
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
                return response(StopReason.END_TURN, Block.Text("answer"))
            }
        }
        val interrupts = Channel<List<Message>>()
        val journal = InMemoryRunJournal()

        val events = withTimeout(5.seconds) {
            val run = async { Harness(gateway, journal).run(req(), interrupts.receiveAsFlow()).toList() }
            started.await()
            interrupts.send(listOf(Message.user("actually, stop")))
            run.await()
        }

        cancelled shouldBe true
        events.filterIsInstance<HarnessEvent.Preempted>().size shouldBe 1
        requests[1].messages.map { it.role } shouldContainExactly listOf(Role.USER, Role.USER)
        requests[1].messages.last().content shouldBe listOf(Block.Text("actually, stop"))
        val done = events.last().shouldBeInstanceOf<HarnessEvent.Finished>()
        done.transcript.none { it.role == Role.ASSISTANT && it.content.any { b -> b == Block.Text("partial") } } shouldBe true
        journal.load("run-1").size shouldBe 3
    }

    test("interrupt during tool execution waits for the tool and joins its result in the same user message") {
        val toolStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val slow = fakeTool("slow") { toolStarted.complete(Unit); release.await(); ToolResult.Ok("S") }
        val gateway = ScriptedGateway(
            listOf(response(StopReason.TOOL_USE, toolUse("1", "slow")), response(StopReason.END_TURN, Block.Text("ok"))),
        )
        val interrupts = Channel<List<Message>>()

        withTimeout(5.seconds) {
            val run = async { Harness(gateway).run(req(slow), interrupts.receiveAsFlow()).toList() }
            toolStarted.await()
            interrupts.send(listOf(Message.user("new input"), Message.system("operator note")))
            delay(100)
            release.complete(Unit)
            run.await()
        }

        gateway.requests.size shouldBe 2
        val messages = gateway.requests[1].messages
        messages[2].role shouldBe Role.USER
        messages[2].content shouldContainExactly listOf(Block.Result("1", "S", false), Block.Text("new input"))
        messages[3] shouldBe Message.system("operator note")
        systemPlacementViolations(messages) shouldBe emptyList()
    }

    test("foldInterrupts keeps system messages valid even after an assistant turn") {
        val folded = foldInterrupts(Role.ASSISTANT, listOf(Message.system("note")))
        folded.map { it.role } shouldContainExactly listOf(Role.USER, Role.SYSTEM)
        systemPlacementViolations(listOf(Message(Role.ASSISTANT, listOf(Block.Text("a")))) + folded) shouldBe emptyList()
        foldInterrupts(Role.USER, listOf(Message.system("note"))) shouldBe listOf(Message.system("note"))
        foldInterrupts(Role.ASSISTANT, listOf(Message.user("a"), Message.user("b"))) shouldBe
            listOf(Message(Role.USER, listOf(Block.Text("a"), Block.Text("b"))))
    }

    test("cache miss event fires for a later turn that read nothing from the cache, not for warm turns") {
        fun usage(read: Long) = Usage(inputTokens = 10, outputTokens = 5, cacheReadInputTokens = read)
        val cold = ScriptedGateway(
            listOf(
                response(StopReason.TOOL_USE, toolUse("1", "a"), usage = usage(0)),
                response(StopReason.TOOL_USE, toolUse("2", "a"), usage = usage(0)),
                response(StopReason.END_TURN, usage = usage(0)),
            ),
        )
        val coldEvents = Harness(cold).run(req(fakeTool("a"))).toList()
        coldEvents.filterIsInstance<HarnessEvent.CacheMiss>().map { it.turn } shouldContainExactly listOf(2, 3)

        val warm = ScriptedGateway(
            listOf(response(StopReason.TOOL_USE, toolUse("1", "a"), usage = usage(0)), response(StopReason.END_TURN, usage = usage(90))),
        )
        Harness(warm).run(req(fakeTool("a"))).toList().filterIsInstance<HarnessEvent.CacheMiss>() shouldBe emptyList()
    }

    test("usage accumulates cache read/write and the 5m/1h split across turns") {
        val gateway = ScriptedGateway(
            listOf(
                response(StopReason.TOOL_USE, toolUse("1", "a"), usage = Usage(10, 5, 100, 0, 40, 60)),
                response(StopReason.END_TURN, usage = Usage(2, 3, 0, 100)),
            ),
        )
        val done = Harness(gateway).run(req(fakeTool("a"))).toList().last().shouldBeInstanceOf<HarnessEvent.Finished>()
        done.usage shouldBe Usage(12, 8, 100, 100, 40, 60)
    }

    test("large tool results are stored via the sink and replaced by a preview and a reference") {
        val stored = mutableListOf<OverflowedResult>()
        val sink = ToolResultSink(maxChars = 50, previewChars = 10) { stored += it; "/workspace/out-${it.toolUseId}.txt" }
        val big = "x".repeat(200)
        val gateway = ScriptedGateway(
            listOf(response(StopReason.TOOL_USE, toolUse("1", "big"), toolUse("2", "small")), response(StopReason.END_TURN)),
        )
        Harness(gateway).run(
            req(fakeTool("big") { ToolResult.Ok(big) }, fakeTool("small") { ToolResult.Ok("tiny") }, sink = sink),
        ).toList()

        stored.single().content shouldBe big
        stored.single().runId shouldBe "run-1"
        val results = gateway.requests[1].messages.last().content.filterIsInstance<Block.Result>()
        results[0].content shouldContain "/workspace/out-1.txt"
        results[0].content shouldContain "200 characters"
        results[0].content.startsWith("x".repeat(10)) shouldBe true
        results[1].content shouldBe "tiny"
    }

    test("a truncated or invalid streamed input becomes an error result and never reaches the tool") {
        var ran = false
        val typed = tool<WeatherArgs>("weather", "Weather") { ran = true; ToolResult.Ok("sunny") }
        val gateway = ScriptedGateway(
            listOf(
                // `city` missing (input cut off) and `days` of the wrong type.
                response(StopReason.TOOL_USE, Block.ToolUse("1", "weather", buildJsonObject {}), Block.ToolUse("2", "weather", buildJsonObject { put("city", "Oslo"); put("days", "many") })),
                response(StopReason.END_TURN),
            ),
        )
        Harness(gateway).run(req(typed)).toList()

        ran shouldBe false
        val results = gateway.requests[1].messages.last().content.filterIsInstance<Block.Result>()
        results.map { it.isError } shouldContainExactly listOf(true, true)
        results[0].content shouldContain "missing required parameter(s): city"
        results[1].content shouldContain "'days' must be of type integer"
    }

    test("completeJson parses a structured one-shot call") {
        val gateway = ScriptedGateway(listOf(response(StopReason.END_TURN, Block.Text("""{"verdict":"allow"}"""))))
        val request = ModelRequest(
            "claude-haiku-5-5", listOf(SystemBlock("guard")), listOf(Message.user("x")), emptyList(), 100, "low",
            structuredOutput = buildJsonObject { put("type", "object") },
        )
        gateway.completeJson(request).toString() shouldBe """{"verdict":"allow"}"""
    }
})

@kotlinx.serialization.Serializable
private class WeatherArgs(val city: String, val days: Int = 1)
