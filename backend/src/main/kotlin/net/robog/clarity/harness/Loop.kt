package net.robog.clarity.harness

/** Why a run ended. */
enum class FinishReason { END_TURN, MAX_TOKENS, REFUSAL, MAX_TURNS, OTHER, COMPACTED }

/** What the loop does after a model turn. */
sealed interface Decision {
    /** Execute these tool calls (all of them, in parallel) and call the model again. */
    data class RunTools(val calls: List<Block.ToolUse>) : Decision

    /** Nothing to execute but the model has not finished (server-side pause): call it again as-is. */
    data object Resume : Decision

    data class Stop(val reason: FinishReason) : Decision
}

/**
 * The loop's decision logic as a pure function (functional core): given the model's turn and how
 * many turns have been used, what happens next. Kept free of I/O so every stop-reason branch is
 * unit-testable without a model.
 *
 * Tool calls on a truncated (`max_tokens`) or refused turn are never executed: their input may be
 * cut off mid-parameter.
 */
fun decide(response: ModelResponse, turn: Int, maxTurns: Int): Decision = when (response.stopReason) {
    StopReason.REFUSAL -> Decision.Stop(FinishReason.REFUSAL)
    StopReason.MAX_TOKENS -> Decision.Stop(FinishReason.MAX_TOKENS)
    StopReason.END_TURN -> Decision.Stop(FinishReason.END_TURN)
    StopReason.OTHER -> Decision.Stop(FinishReason.OTHER)
    StopReason.TOOL_USE, StopReason.PAUSE_TURN, StopReason.COMPACTION -> when {
        turn >= maxTurns -> Decision.Stop(FinishReason.MAX_TURNS)
        response.toolUses.isNotEmpty() -> Decision.RunTools(response.toolUses)
        // Server-side pause, or paused right after a compaction block: call again as-is.
        response.stopReason != StopReason.TOOL_USE -> Decision.Resume
        // tool_use stop with no tool_use block is a provider anomaly; treat as finished.
        else -> Decision.Stop(FinishReason.OTHER)
    }
}

/** How one requested call is handled, decided before any tool code runs. */
sealed interface Dispatch {
    data class Run(val tool: Tool, val call: Block.ToolUse) : Dispatch
    data class Unknown(val call: Block.ToolUse) : Dispatch
}

/** Pure routing of a call through the visible tool set and policy. Hidden tools are "unknown" to the model. */
fun route(call: Block.ToolUse, tools: Map<String, Tool>, policy: ToolPolicy): Dispatch {
    val tool = tools[call.name] ?: return Dispatch.Unknown(call)
    return when (policy.accessFor(tool)) {
        Access.ALLOW -> Dispatch.Run(tool, call)
        Access.HIDDEN -> Dispatch.Unknown(call)
    }
}

/**
 * What a run does as it nears and reaches [RunRequest.maxTurns]: [warnAt] steps before the end the
 * tool results carry [warning]; when the steps run out the requested tools are not run and one
 * last call, whose tools are never run, gets [report] to say where it got to.
 */
data class OutOfSteps(
    val warnAt: Int = 3,
    val warning: String = "Note: you have about %d steps left. Wrap up: finish the most useful part, or stop and report where you got to.",
    val report: String = "You are out of steps; no more tools will run. Write your report now, short and plain: " +
        "what you found or did, exactly where you got to, and what is left.",
    val notRun: String = "Not run: out of steps.",
)

/** Pure: the note joined to the tool results after model call [turn], when only [OutOfSteps.warnAt] calls are left. */
fun stepsLeftNote(turn: Int, maxTurns: Int, config: OutOfSteps?): String? =
    if (config != null && config.warnAt > 0 && maxTurns - turn == config.warnAt) config.warning.format(config.warnAt) else null
