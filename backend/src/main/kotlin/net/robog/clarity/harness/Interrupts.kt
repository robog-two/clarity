package net.robog.clarity.harness

/**
 * Pure helpers for pre-emption: where do messages that arrive mid-run go?
 *
 * USER interrupts are folded into a single user message; SYSTEM (operator) interrupts follow as
 * separate system messages, which keeps them valid under [systemPlacementViolations].
 */

/** The user message that carries operator updates when nobody said anything. */
const val OPERATOR_UPDATE_PLACEHOLDER = "(operator update follows)"

private fun userBlocks(interrupts: List<Message>): List<Block> =
    interrupts.filter { it.role != Role.SYSTEM }.flatMap { it.content }

private fun systemMessages(interrupts: List<Message>): List<Message> = interrupts.filter { it.role == Role.SYSTEM }

/**
 * Interrupts that arrived while a model call was in flight (the partial response was discarded) or
 * after a run reached a stopping point. [lastRole] is the role of the last message in the history.
 */
fun foldInterrupts(lastRole: Role?, interrupts: List<Message>): List<Message> {
    val blocks = userBlocks(interrupts)
    val systems = systemMessages(interrupts)
    val user = blocks.takeIf { it.isNotEmpty() }?.let { Message(Role.USER, it) }
    // A system message needs a user message in front of it; if the history ends with an assistant
    // turn and nobody said anything, a placeholder user message carries it.
    val needsUser = user == null && systems.isNotEmpty() && lastRole != Role.USER
    return listOfNotNull(user ?: Message.user(OPERATOR_UPDATE_PLACEHOLDER).takeIf { needsUser }) + systems
}

/**
 * Interrupts that arrived while tools were running: the user content joins the tool results in the
 * SAME user message (tool results must stay together and first), system messages follow.
 */
fun mergeIntoResults(results: List<Block>, interrupts: List<Message>): List<Message> =
    listOf(Message(Role.USER, results + userBlocks(interrupts))) + systemMessages(interrupts)
