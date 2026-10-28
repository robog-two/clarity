package net.robog.clarity.harness

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Everything one agent run needs; immutable so a run is reproducible from its request. */
data class RunRequest(
    val model: String,
    /** Static prefix block(s) first, then the constitution block. */
    val system: List<SystemBlock>,
    /** The conversation so far; usually a single user message. Ignored by [Harness.resume] (the journal wins). */
    val messages: List<Message>,
    /** Tools sent in full with every request. */
    val tools: List<Tool> = emptyList(),
    val policy: ToolPolicy = ToolPolicy.Default,
    val maxTurns: Int = 30,
    val maxTokens: Long = 64_000,
    val effort: String? = "medium",
    /** Journal key. Stable across a crash: resume with the same id. */
    val runId: String = UUID.randomUUID().toString(),
    /** Null sends no cache breakpoints (only models that take them use them, [usesCacheBreakpoints]). */
    val cache: CachePolicy? = CachePolicy(),
    /** Truncates and stores oversized tool results. */
    val resultSink: ToolResultSink? = null,
    /** Warn near [maxTurns] and, when the steps run out, ask for a report in one last call that runs no tools. Null: just stop. */
    val outOfSteps: OutOfSteps? = null,
    /**
     * OpenRouter's web search for this run; null (the default) sends none. [WebSearchTool.maxSearches]
     * caps the whole run: each request gets what is left, and none once it is spent ([searchFor]).
     */
    val webSearch: WebSearchTool? = null,
) {
    constructor(
        model: String,
        system: String,
        messages: List<Message>,
        tools: List<Tool> = emptyList(),
        policy: ToolPolicy = ToolPolicy.Default,
        maxTurns: Int = 30,
        maxTokens: Long = 64_000,
        effort: String? = "medium",
    ) : this(model, listOf(SystemBlock(system)), messages, tools, policy, maxTurns, maxTokens, effort)

    constructor(model: String, system: String, prompt: String, tools: List<Tool> = emptyList()) :
        this(model, system, listOf(Message.user(prompt)), tools)
}

data class RunResult(
    val reason: FinishReason,
    val usage: Usage,
    /** The full append-only history including the final assistant turn. */
    val transcript: List<Message>,
    /** Text of the final assistant turn. */
    val finalText: String,
)

sealed interface HarnessEvent {
    data class TurnStarted(val turn: Int) : HarnessEvent
    data class TextDelta(val text: String) : HarnessEvent

    /** The assistant's complete text for a turn (after its deltas). */
    data class AssistantText(val text: String) : HarnessEvent
    data class ToolCalled(val id: String, val name: String, val input: JsonObject) : HarnessEvent
    data class ToolCompleted(val id: String, val name: String, val content: String, val isError: Boolean) : HarnessEvent

    /** A run continued from its journal. [rerunTools] is the number of unanswered tool calls executed again. */
    data class Resumed(val messages: Int, val rerunTools: Int) : HarnessEvent

    /** An interrupt cancelled an in-flight model call; its partial response was discarded. */
    data class Preempted(val interruptMessages: Int) : HarnessEvent

    /** A model call finished; [usage] is that call's alone ([Usage.promptTokens] is the size of its prompt). */
    data class Called(val turn: Int, val usage: Usage) : HarnessEvent

    /** A turn after the first read nothing from the prompt cache although caching is on: investigate prefix drift. */
    data class CacheMiss(val turn: Int, val usage: Usage) : HarnessEvent

    /** Terminal events: exactly one is emitted unless the collector is cancelled. */
    sealed interface Terminal : HarnessEvent {
        val usage: Usage
        val transcript: List<Message>
    }

    data class Finished(val result: RunResult) : Terminal {
        val reason: FinishReason get() = result.reason
        override val usage: Usage get() = result.usage
        override val transcript: List<Message> get() = result.transcript
    }

    data class Failed(
        val message: String,
        override val usage: Usage,
        override val transcript: List<Message>,
    ) : Terminal
}

/**
 * Pure: the web search a run's next request gets after [used] searches: what is left of the run's
 * [WebSearchTool.maxSearches], or null when there is no search or it is spent. The harness counts
 * from the start of this resume; a caller resuming a run passes a tool already reduced by what the
 * run used before (HelperManager does, from `model_call`).
 */
fun searchFor(search: WebSearchTool?, used: Long): WebSearchTool? {
    if (search == null) return null
    val left = search.maxSearches - used
    return if (left <= 0) null else search.copy(maxSearches = left.toInt(), maxTotalResults = (left * search.maxResults).toInt())
}

/** Collects a run to its terminal event. */
suspend fun Flow<HarnessEvent>.awaitTerminal(): HarnessEvent.Terminal =
    first { it is HarnessEvent.Terminal } as HarnessEvent.Terminal

/** The tools of a run after policy, sorted by name. */
data class ToolPlan(val tools: List<Tool>) {
    val byName: Map<String, Tool> = tools.associateBy { it.name }
}

/** Pure: hides what the policy hides, drops duplicate names and sorts (a byte-stable tool list is part of the cached prefix). */
fun planTools(tools: List<Tool>, policy: ToolPolicy): ToolPlan =
    ToolPlan(tools.filter { policy.accessFor(it) != Access.HIDDEN }.distinctBy { it.name }.sortedBy { it.name })

/**
 * The agent loop. It owns the conversation (so cost, data and approvals stay ours): stream a model
 * turn, run the requested tools in parallel, return every result in ONE user message, repeat until
 * the model finishes or a limit hits. History is append-only: assistant content is replayed
 * exactly as received (reasoning blocks included), which keeps the prompt cache warm and lets the
 * gateway hand reasoning back to the model that wrote it; only JSON key order is normalised
 * ([journalStable]), once, so a replay from a `jsonb` journal is byte-identical to one from memory.
 *
 * A client-side compaction summary ([Message.summary]) in the journal cuts what is sent: every
 * request starts at the latest one ([sinceSummary]), while the journal keeps everything.
 *
 * Durability: the initial messages, each assistant message, each tool-result message and each
 * interrupt are appended to the [journal]; [resume] continues a run from it.
 *
 * Pre-emption: `interrupts` delivers messages while the run is going. During a model call the call
 * is cancelled, its partial response discarded, and the messages appended; during tool execution
 * the running tools finish and the messages join their results. Cancelling the collector cancels
 * the in-flight model call and all running tools.
 */
class Harness(
    private val gateway: ModelGateway,
    private val journal: RunJournal = InMemoryRunJournal(),
) {

    fun run(request: RunRequest, interrupts: Flow<List<Message>> = emptyFlow()): Flow<HarnessEvent> =
        channelFlow { loop(request, interrupts, resumed = false) }.buffer(Channel.UNLIMITED)

    /**
     * Continues run [runId] from its journal (`request.runId` is replaced; `request.messages` is
     * ignored unless the journal is empty). If the last journaled message is an assistant turn with
     * unanswered `tool_use` blocks, those tools run again (EXTERNAL tools are idempotent by design).
     */
    fun resume(runId: String, request: RunRequest, interrupts: Flow<List<Message>> = emptyFlow()): Flow<HarnessEvent> =
        channelFlow { loop(request.copy(runId = runId), interrupts, resumed = true) }.buffer(Channel.UNLIMITED)

    private suspend fun ProducerScope<HarnessEvent>.loop(request: RunRequest, interrupts: Flow<List<Message>>, resumed: Boolean) {
        val plan = planTools(request.tools, request.policy)
        val runId = request.runId

        var history: List<Message> = if (resumed) journal.load(runId) else emptyList()
        if (history.isEmpty()) {
            history = request.messages
            history.forEach { journal.append(runId, it) }
        }

        val pending = Channel<Message>(Channel.UNLIMITED)
        val collector = launch { interrupts.collect { batch -> batch.forEach { pending.send(it) } } }
        try {
            withContext(CurrentRun(runId)) { drive(request, plan, history, pending, resumed) }
        } finally {
            collector.cancel()
        }
    }

    private suspend fun ProducerScope<HarnessEvent>.drive(
        request: RunRequest,
        plan: ToolPlan,
        initial: List<Message>,
        pending: Channel<Message>,
        resumed: Boolean,
    ) {
        val runId = request.runId
        var history = initial
        var usage = Usage.ZERO
        var turn = 0
        var completedCalls = 0

        suspend fun append(messages: List<Message>) {
            messages.forEach { journal.append(runId, it) }
            history = history + messages
        }

        fun drain(): List<Message> = buildList { while (true) add(pending.tryReceive().getOrNull() ?: break) }

        // Resume: answer tool calls the crash left open, or recognise a run that had already finished.
        var calls: List<Block.ToolUse>? = null
        if (resumed) {
            val last = history.last()
            val open = if (last.role == Role.ASSISTANT) last.content.filterIsInstance<Block.ToolUse>() else emptyList()
            send(HarnessEvent.Resumed(history.size, open.size))
            if (open.isNotEmpty()) {
                calls = open
            } else if (last.role == Role.ASSISTANT) {
                val late = drain()
                if (late.isEmpty()) {
                    val text = last.content.filterIsInstance<Block.Text>().joinToString("") { it.text }
                    send(HarnessEvent.Finished(RunResult(FinishReason.END_TURN, usage, history, text)))
                    return
                }
                append(foldInterrupts(Role.ASSISTANT, late))
            }
        }

        while (true) {
            if (calls == null) {
                turn++
                send(HarnessEvent.TurnStarted(turn))
                when (val outcome = callModel(modelRequest(request, plan, history, searchFor(request.webSearch, usage.webSearchRequests)), pending)) {
                    is CallOutcome.Interrupted -> {
                        turn--
                        val messages = foldInterrupts(history.lastOrNull()?.role, listOf(outcome.first) + drain())
                        send(HarnessEvent.Preempted(messages.size))
                        append(messages)
                        continue
                    }
                    is CallOutcome.Failed -> {
                        send(HarnessEvent.Failed(outcome.error.message ?: outcome.error::class.simpleName ?: "model call failed", usage, history))
                        return
                    }
                    is CallOutcome.Done -> {
                        val response = outcome.response
                        usage += response.usage
                        completedCalls++
                        send(HarnessEvent.Called(turn, response.usage))
                        if (request.cache != null && completedCalls > 1 && response.usage.cacheReadInputTokens == 0L) {
                            send(HarnessEvent.CacheMiss(turn, response.usage))
                        }
                        // In the journal's (jsonb) key order, so this turn replays the same from memory and from the journal.
                        append(listOf(Message(Role.ASSISTANT, journalStable(response.content))))
                        if (response.text.isNotEmpty()) send(HarnessEvent.AssistantText(response.text))

                        when (val decision = decide(response, turn, request.maxTurns)) {
                            is Decision.Stop -> {
                                val late = drain()
                                if (late.isNotEmpty() && decision.reason == FinishReason.END_TURN) {
                                    append(foldInterrupts(Role.ASSISTANT, late))
                                    continue
                                }
                                val steps = request.outOfSteps
                                if (decision.reason == FinishReason.MAX_TURNS && steps != null) {
                                    // The requested tools never run; one last call (whose tools never run either) says where it got to.
                                    val notRun = response.toolUses.map { Block.Result(it.id, steps.notRun, isError = true) }
                                    append(mergeIntoResults(notRun + Block.Text(steps.report), late))
                                    turn++
                                    send(HarnessEvent.TurnStarted(turn))
                                    var report = ""
                                    val last = callModel(modelRequest(request, plan, history, search = null), Channel())
                                    if (last is CallOutcome.Done) {
                                        usage += last.response.usage
                                        send(HarnessEvent.Called(turn, last.response.usage))
                                        append(listOf(Message(Role.ASSISTANT, journalStable(last.response.content))))
                                        if (last.response.text.isNotEmpty()) send(HarnessEvent.AssistantText(last.response.text))
                                        val ignored = last.response.toolUses.map { Block.Result(it.id, steps.notRun, isError = true) }
                                        if (ignored.isNotEmpty()) append(listOf(Message(Role.USER, ignored)))
                                        report = last.response.text
                                    }
                                    send(HarnessEvent.Finished(RunResult(FinishReason.MAX_TURNS, usage, history, report)))
                                    return
                                }
                                send(HarnessEvent.Finished(RunResult(decision.reason, usage, history, response.text)))
                                return
                            }
                            is Decision.RunTools -> calls = decision.calls
                        }
                    }
                }
            }

            // Tools: always run to completion, then join the results with whatever arrived meanwhile.
            val toRun = calls ?: continue
            calls = null
            val results = executeAll(runId, toRun, plan, request)
            val note = stepsLeftNote(turn, request.maxTurns, request.outOfSteps)?.let { listOf(Block.Text(it)) }.orEmpty()
            append(mergeIntoResults(results + note, drain()))
        }
    }

    private fun modelRequest(request: RunRequest, plan: ToolPlan, history: List<Message>, search: WebSearchTool?) = ModelRequest(
        model = request.model,
        system = request.system,
        messages = wireOrder(sinceSummary(history)),
        tools = plan.tools.map { it.definition },
        maxTokens = request.maxTokens,
        effort = request.effort,
        cache = request.cache,
        webSearch = search,
    )

    private sealed interface CallOutcome {
        data class Done(val response: ModelResponse) : CallOutcome
        data class Interrupted(val first: Message) : CallOutcome
        data class Failed(val error: Exception) : CallOutcome
    }

    /** One model call raced against the interrupt channel; an interrupt cancels the call. */
    private suspend fun ProducerScope<HarnessEvent>.callModel(request: ModelRequest, pending: Channel<Message>): CallOutcome =
        coroutineScope {
            val call: Deferred<CallOutcome> = async {
                try {
                    CallOutcome.Done(gateway.generate(request) { delta -> trySend(HarnessEvent.TextDelta(delta)) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CallOutcome.Failed(e)
                }
            }
            select {
                call.onAwait { it }
                pending.onReceive { first ->
                    call.cancelAndJoin()
                    CallOutcome.Interrupted(first)
                }
            }
        }

    /** Runs all calls concurrently; results keep the model's call order. */
    private suspend fun ProducerScope<HarnessEvent>.executeAll(
        runId: String,
        calls: List<Block.ToolUse>,
        plan: ToolPlan,
        request: RunRequest,
    ): List<Block> = coroutineScope {
        calls.map { call -> async { execute(runId, route(call, plan.byName, request.policy), plan, request.resultSink) } }.awaitAll()
    }

    private suspend fun ProducerScope<HarnessEvent>.execute(
        runId: String,
        dispatch: Dispatch,
        plan: ToolPlan,
        sink: ToolResultSink?,
    ): Block.Result {
        val call = when (dispatch) {
            is Dispatch.Run -> dispatch.call
            is Dispatch.Unknown -> dispatch.call
        }
        val result: ToolResult = when (dispatch) {
            is Dispatch.Run -> {
                send(HarnessEvent.ToolCalled(call.id, call.name, call.input))
                // A truncated or malformed (eagerly streamed) input never reaches tool code.
                val problem = validateToolInput(dispatch.tool.inputSchema, call.input)
                if (problem != null) {
                    ToolResult.Error("Invalid input for ${call.name}: $problem")
                } else {
                    withContext(ToolCallContext(runId, call.id)) { dispatch.tool.invokeSafely(call.input) }
                }
            }
            is Dispatch.Unknown -> ToolResult.Error("Unknown tool: ${call.name}")
        }
        val (raw, isError) = when (result) {
            is ToolResult.Ok -> result.content to false
            is ToolResult.Error -> result.message to true
        }
        val content = sink?.limit(runId, call.id, call.name, raw) ?: raw
        send(HarnessEvent.ToolCompleted(call.id, call.name, content, isError))
        return Block.Result(call.id, content, isError)
    }
}
