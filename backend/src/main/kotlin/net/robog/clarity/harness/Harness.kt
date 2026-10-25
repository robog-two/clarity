package net.robog.clarity.harness

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject

/** Everything one agent run needs; immutable so a run is reproducible from its request. */
data class RunRequest(
    val model: String,
    val system: String,
    /** The conversation so far; usually a single user message. */
    val messages: List<Message>,
    val tools: List<Tool> = emptyList(),
    val policy: ToolPolicy = ToolPolicy.Default,
    val maxTurns: Int = 30,
    val maxTokens: Long = 64_000,
    val effort: String? = "medium",
) {
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

/**
 * The agent loop. It owns the conversation (so cost, data and approvals stay ours): stream a model
 * turn, run the requested tools in parallel, return every result in ONE user message, repeat until
 * the model finishes or a limit hits. History is append-only: assistant content is replayed
 * exactly as received, which preserved thinking requires and which keeps the prompt cache warm.
 *
 * Cancelling the collector cancels the in-flight model call and all running tools.
 */
class Harness(private val gateway: ModelGateway) {

    fun run(request: RunRequest): Flow<HarnessEvent> =
        channelFlow { loop(request) }.buffer(Channel.UNLIMITED)

    private suspend fun ProducerScope<HarnessEvent>.loop(request: RunRequest) {
        // Sorted: a byte-stable tool list is part of the cached prompt prefix.
        val visible = request.tools
            .filter { request.policy.accessFor(it) != Access.HIDDEN }
            .sortedBy { it.name }
        val byName = visible.associateBy { it.name }
        val definitions = visible.map { it.definition }

        var history = request.messages
        var usage = Usage.ZERO
        var turn = 0

        while (true) {
            turn++
            send(HarnessEvent.TurnStarted(turn))

            val response = try {
                gateway.generate(
                    ModelRequest(request.model, request.system, history, definitions, request.maxTokens, request.effort),
                ) { delta -> trySend(HarnessEvent.TextDelta(delta)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(HarnessEvent.Failed(e.message ?: e::class.simpleName ?: "model call failed", usage, history))
                return
            }

            usage += response.usage
            history = history + Message(Role.ASSISTANT, response.content)
            if (response.text.isNotEmpty()) send(HarnessEvent.AssistantText(response.text))

            when (val decision = decide(response, turn, request.maxTurns)) {
                is Decision.Stop -> {
                    send(HarnessEvent.Finished(RunResult(decision.reason, usage, history, response.text)))
                    return
                }
                Decision.Resume -> Unit
                is Decision.RunTools -> {
                    val results = executeAll(decision.calls, byName, request.policy)
                    history = history + Message(Role.USER, results)
                }
            }
        }
    }

    /** Runs all calls concurrently; results keep the model's call order. */
    private suspend fun ProducerScope<HarnessEvent>.executeAll(
        calls: List<Block.ToolUse>,
        tools: Map<String, Tool>,
        policy: ToolPolicy,
    ): List<Block> = coroutineScope {
        calls.map { call -> async { execute(route(call, tools, policy)) } }.awaitAll()
    }

    private suspend fun ProducerScope<HarnessEvent>.execute(dispatch: Dispatch): Block.Result {
        val call = when (dispatch) {
            is Dispatch.Run -> dispatch.call
            is Dispatch.NeedsApproval -> dispatch.call
            is Dispatch.Unknown -> dispatch.call
        }
        val result: ToolResult = when (dispatch) {
            is Dispatch.Run -> {
                send(HarnessEvent.ToolCalled(call.id, call.name, call.input))
                dispatch.tool.invokeSafely(call.input)
            }
            is Dispatch.NeedsApproval -> {
                send(HarnessEvent.ApprovalRequired(call.id, call.name, call.input))
                ToolResult.Error("Approval is pending for ${call.name}; it was not executed. Continue without it or tell the user what you wanted to do.")
            }
            is Dispatch.Unknown -> ToolResult.Error("Unknown tool: ${call.name}")
        }
        val (content, isError) = when (result) {
            is ToolResult.Ok -> result.content to false
            is ToolResult.Error -> result.message to true
        }
        send(HarnessEvent.ToolCompleted(call.id, call.name, content, isError))
        return Block.Result(call.id, content, isError)
    }
}
