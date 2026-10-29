package net.robog.clarity.harness

/** Why a run ended. */
enum class FinishReason { END_TURN, MAX_TOKENS, REFUSAL, MAX_TURNS, SPEND_LIMIT, OTHER }

/** What the loop does after a model turn. */
sealed interface Decision {
    /** Execute these tool calls (all of them, in parallel) and call the model again. */
    data class RunTools(val calls: List<Block.ToolUse>) : Decision

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
    StopReason.TOOL_USE -> when {
        turn >= maxTurns -> Decision.Stop(FinishReason.MAX_TURNS)
        response.toolUses.isNotEmpty() -> Decision.RunTools(response.toolUses)
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
    /** False: when the steps run out the requested tools get [notRun] results and the run just stops (no last call). */
    val finalCall: Boolean = true,
    val warning: String = "Note: you have about %d steps left. Wrap up: finish the most useful part, or stop and report where you got to.",
    val report: String = "You are out of steps; no more tools will run. Write your report now, short and plain: " +
        "what you found or did, exactly where you got to, and what is left.",
    val notRun: String = "Not run: out of steps.",
)

/** Pure: the note joined to the tool results after model call [turn], when only [OutOfSteps.warnAt] calls are left. */
fun stepsLeftNote(turn: Int, maxTurns: Int, config: OutOfSteps?): String? =
    if (config != null && config.warnAt > 0 && maxTurns - turn == config.warnAt) config.warning.format(config.warnAt) else null

/**
 * A run's spend cap: once what it spent ([spentBefore], recorded before this resume, plus the
 * reported cost of this resume's calls) reaches [maxCostMicros], the tools already asked for still
 * run, then one last call, whose tools never run, gets [report]; the run ends [FinishReason.SPEND_LIMIT].
 */
data class SpendCap(
    val maxCostMicros: Long,
    val spentBefore: Long = 0,
    /** False: at the cap the run just stops after the tools already asked for (no last call). */
    val finalCall: Boolean = true,
    val report: String = "You have reached this task's spend limit; no more tools will run. Write your report now, short and plain: " +
        "what you found or did, exactly where you got to (any report id), and what is left.",
    val notRun: String = "Not run: the spend limit is reached.",
)

/** Pure: whether a run with [cap] that has spent [usage] in this resume is at or over its cap. */
fun overSpend(cap: SpendCap?, usage: Usage): Boolean =
    cap != null && cap.spentBefore + (usage.reportedCostMicros ?: 0) >= cap.maxCostMicros

/** Characters a tool call id keeps: what every provider takes. */
private val TOOL_ID_CHARS = Regex("[^A-Za-z0-9_-]")

/**
 * Pure: [content] with every tool call's id unique among [used] (ids already in the run) and each
 * other. Providers number calls per response (`call_0`), so the same id comes back turn after turn;
 * a repeated, blank or odd id becomes `<id>_<n>`, the first free one. Others are kept as they are.
 * [used] gets every id of the result.
 */
fun uniqueToolIds(content: List<Block>, used: MutableSet<String>): List<Block> = content.map { block ->
    if (block !is Block.ToolUse) return@map block
    val clean = block.id.replace(TOOL_ID_CHARS, "").ifEmpty { "call" }
    val id = if (clean == block.id && block.id !in used) block.id
    else generateSequence(1) { it + 1 }.map { "${clean}_$it" }.first { it !in used }
    used += id
    if (id == block.id) block else block.copy(id = id)
}

/**
 * Pure: the model calls the current stretch of [history] already made, for a run resumed from its
 * journal: the assistant messages since the last message that began a stretch ([begins]; by
 * default the start), so a resume continues the step budget instead of starting a fresh one.
 */
fun turnsUsed(history: List<Message>, begins: (Message) -> Boolean = { false }): Int {
    val start = history.indexOfLast(begins).let { if (it < 0) 0 else it + 1 }
    return history.drop(start).count { it.role == Role.ASSISTANT }
}
