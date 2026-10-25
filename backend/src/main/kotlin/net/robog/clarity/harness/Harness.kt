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
    /** Tools sent with `defer_loading` behind the tool search tool. Ignored when [tools] is empty (never all deferred). */
    val deferredTools: List<Tool> = emptyList(),
    val serverTools: Set<ServerTool> = emptySet(),
    val cache: CachePolicy? = CachePolicy(),
    val compaction: CompactionConfig? = null,
    val fallbacks: Boolean = true,
    val thinkingDisplay: String? = null,
    /** Truncates and stores oversized tool results. */
    val resultSink: ToolResultSink? = null,
    /**
     * Housekeeping run: ask the provider to compact the history right now and stop at the
     * compaction block ([FinishReason.COMPACTED]) instead of answering. Used while the cache is warm.
     */
    val compactNow: Boolean = false,
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
    data class ApprovalRequired(val id: String, val name: String, val input: JsonObject) : HarnessEvent

    /** A run continued from its journal. [rerunTools] is the number of unanswered tool calls executed again. */
    data class Resumed(val messages: Int, val rerunTools: Int) : HarnessEvent

    /** An interrupt cancelled an in-flight model call; its partial response was discarded. */
    data class Preempted(val interruptMessages: Int) : HarnessEvent

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

/** Collects a run to its terminal event. */
suspend fun Flow<HarnessEvent>.awaitTerminal(): HarnessEvent.Terminal =
    first { it is HarnessEvent.Terminal } as HarnessEvent.Terminal

/** The tools of a run after policy and the "never all deferred" rule, each list sorted by name. */
data class ToolPlan(val loaded: List<Tool>, val deferred: List<Tool>) {
    val byName: Map<String, Tool> = (loaded + deferred).associateBy { it.name }
}

/**
 * Pure: hides what the policy hides, sorts (a byte-stable tool list is part of the cached prefix),
 * and refuses to defer everything: with no loaded tool the deferred ones are loaded after all.
 */
fun planTools(tools: List<Tool>, deferred: List<Tool>, policy: ToolPolicy): ToolPlan {
    fun visible(list: List<Tool>) = list.filter { policy.accessFor(it) != Access.HIDDEN }.sortedBy { it.name }
    val loaded = visible(tools)
    val later = visible(deferred).filter { d -> loaded.none { it.name == d.name } }
    return if (loaded.isEmpty()) ToolPlan(later, emptyList()) else ToolPlan(loaded, later)
}

/**
 * The agent loop. It owns the conversation (so cost, data and approvals stay ours): stream a model
 * turn, run the requested tools in parallel, return every result in ONE user message, repeat until
 * the model finishes or a limit hits. History is append-only: assistant content is replayed
 * exactly as received (thinking, compaction and server-tool blocks included), which preserved
 * thinking requires and which keeps the prompt cache warm.
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
        val plan = planTools(request.tools, request.deferredTools, request.policy)
        val runId = request.runId

        var history: List<Message> = if (resumed) journal.load(runId) else emptyList()
        if (history.isEmpty()) {
            history = request.messages
            history.forEach { journal.append(runId, it) }
        }

        val pending = Channel<Message>(Channel.UNLIMITED)
        val collector = launch { interrupts.collect { batch -> batch.forEach { pending.send(it) } } }
        try {
            drive(request, plan, history, pending, resumed)
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
                val modelRequest = ModelRequest(
                    model = request.model,
                    system = request.system,
                    messages = history,
                    tools = plan.loaded.map { it.definition },
                    maxTokens = request.maxTokens,
                    effort = request.effort,
                    deferredTools = plan.deferred.map { it.definition },
                    serverTools = request.serverTools,
                    cache = request.cache,
                    compaction = if (request.compactNow) compactNowConfig(request.compaction) else request.compaction,
                    fallbacks = request.fallbacks,
                    thinkingDisplay = request.thinkingDisplay,
                )

                when (val outcome = callModel(modelRequest, pending)) {
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
                        if (request.cache != null && completedCalls > 1 && response.usage.cacheReadInputTokens == 0L) {
                            send(HarnessEvent.CacheMiss(turn, response.usage))
                        }
                        append(listOf(Message(Role.ASSISTANT, response.content)))
                        if (response.text.isNotEmpty()) send(HarnessEvent.AssistantText(response.text))

                        if (request.compactNow && response.stopReason == StopReason.COMPACTION) {
                            send(HarnessEvent.Finished(RunResult(FinishReason.COMPACTED, usage, history, response.text)))
                            return
                        }
                        when (val decision = decide(response, turn, request.maxTurns)) {
                            is Decision.Stop -> {
                                val late = drain()
                                if (late.isNotEmpty() && decision.reason == FinishReason.END_TURN) {
                                    append(foldInterrupts(Role.ASSISTANT, late))
                                    continue
                                }
                                send(HarnessEvent.Finished(RunResult(decision.reason, usage, history, response.text)))
                                return
                            }
                            Decision.Resume -> continue
                            is Decision.RunTools -> calls = decision.calls
                        }
                    }
                }
            }

            // Tools: always run to completion, then join the results with whatever arrived meanwhile.
            val toRun = calls ?: continue
            calls = null
            val results = executeAll(runId, toRun, plan, request)
            append(mergeIntoResults(results, drain()))
        }
    }

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
            is Dispatch.NeedsApproval -> dispatch.call
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
            is Dispatch.NeedsApproval -> {
                send(HarnessEvent.ApprovalRequired(call.id, call.name, call.input))
                ToolResult.Error("Approval is pending for ${call.name}; it was not executed. Continue without it or tell the user what you wanted to do.")
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
