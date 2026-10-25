package net.robog.clarity.harness

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Durable, append-only record of one run's conversation (the lead thread or a helper). The
 * harness appends the initial messages, every assistant message, every tool-result message and
 * every interrupt, in order, so a crashed run resumes with [Harness.resume] from its last
 * completed step. The Postgres implementation lives with the lead thread; this package ships
 * [InMemoryRunJournal].
 *
 * Implementations must make [append] durable before returning and [load] return messages in
 * append order.
 */
interface RunJournal {
    suspend fun append(runId: String, message: Message)

    suspend fun load(runId: String): List<Message>
}

class InMemoryRunJournal : RunJournal {
    private val runs = ConcurrentHashMap<String, CopyOnWriteArrayList<Message>>()

    override suspend fun append(runId: String, message: Message) {
        runs.computeIfAbsent(runId) { CopyOnWriteArrayList() }.add(message)
    }

    override suspend fun load(runId: String): List<Message> = runs[runId]?.toList().orEmpty()
}

/**
 * Keeps huge tool results out of the context window. A result longer than [maxChars] is handed to
 * [store] (e.g. written into the sandbox workspace), and the model gets a [previewChars] preview
 * plus the reference [store] returned (a path or id) instead of the full text.
 */
class ToolResultSink(
    val maxChars: Int = 20_000,
    val previewChars: Int = 2_000,
    val store: suspend (OverflowedResult) -> String,
) {
    init {
        require(previewChars in 0..maxChars) { "previewChars must be within 0..maxChars" }
    }

    /** Returns [content] unchanged when small enough, else the truncated text with a reference. */
    suspend fun limit(runId: String, toolUseId: String, toolName: String, content: String): String {
        if (content.length <= maxChars) return content
        val reference = store(OverflowedResult(runId, toolUseId, toolName, content))
        return truncatedResult(content, reference, previewChars)
    }
}

data class OverflowedResult(val runId: String, val toolUseId: String, val toolName: String, val content: String)

/** The pure shape of a truncated result: a preview, the original size and where the rest lives. */
fun truncatedResult(content: String, reference: String, previewChars: Int): String =
    content.take(previewChars) +
        "\n[truncated: ${content.length} characters in total; the full output is stored at $reference]"
