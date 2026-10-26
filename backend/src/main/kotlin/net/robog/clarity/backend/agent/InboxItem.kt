package net.robog.clarity.backend.agent

import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.session.SessionId

/** How soon an inbox item needs the lead. [USER] pre-empts; [LOW] waits for an idle lead. */
@Serializable
enum class Priority {
    @SerialName("user") USER,
    @SerialName("normal") NORMAL,
    @SerialName("low") LOW,
}

/** Content the agent did not write (mail bodies, web pages, helper output): data, never instructions. */
@Serializable
data class UntrustedPart(val source: String, val content: String)

/**
 * Everything that can wake the lead thread. [summary] is text we wrote ourselves; anything that
 * came from outside travels in [untrusted] and is rendered inside `<untrusted>` quotes.
 */
@Serializable
sealed interface InboxItem {
    val priority: Priority
    val summary: String
    val untrusted: List<UntrustedPart> get() = emptyList()

    /** The person said or typed something. */
    @Serializable @SerialName("user_input")
    data class UserInput(
        val clientId: ClientInputId,
        val text: String,
        val source: InputSource = InputSource.TYPED,
        val sessionId: SessionId? = null,
    ) : InboxItem {
        override val priority get() = Priority.USER
        override val summary get() = text
    }

    /** A session (call, interview, amendment) changed state. */
    @Serializable @SerialName("session_event")
    data class SessionEvent(val sessionId: SessionId, val event: String) : InboxItem {
        override val priority get() = Priority.NORMAL
        override val summary get() = "Session ${sessionId.value}: $event"
    }

    /** A trigger matched a newly ingested record. */
    @Serializable @SerialName("trigger_fired")
    data class TriggerFired(
        val triggerId: String,
        val triggerName: String,
        val instruction: String,
        /** The record as JSON, tagged with where it came from. */
        val record: String,
        val recordSource: String,
    ) : InboxItem {
        override val priority get() = Priority.NORMAL
        override val summary get() = "Trigger \"$triggerName\" ($triggerId) matched a new record. Instruction: $instruction"
        override val untrusted get() = listOf(UntrustedPart(recordSource, record))
    }

    /** A scheduled job came due. */
    @Serializable @SerialName("job_due")
    data class JobDue(
        val job: String,
        val instruction: String,
        override val priority: Priority = Priority.LOW,
    ) : InboxItem {
        override val summary get() = "Scheduled job \"$job\" is due. $instruction"
    }

    /** A helper finished. Its output was derived from whatever it read, so it is quoted as untrusted. */
    @Serializable @SerialName("helper_result")
    data class HelperResult(val runId: String, val task: String, val ok: Boolean, val result: String) : InboxItem {
        override val priority get() = Priority.NORMAL
        override val summary get() = "Helper $runId ${if (ok) "finished" else "failed"} its task: ${task.take(200)}"
        override val untrusted get() = listOf(UntrustedPart("helper:$runId", result))
    }

    /** A connector finished (or gave up on) an action. */
    @Serializable @SerialName("action_result")
    data class ActionResult(val actionId: String, val actionSummary: String, val done: Boolean, val detail: String? = null) : InboxItem {
        override val priority get() = Priority.NORMAL
        override val summary get() =
            if (done) "Done: $actionSummary (action $actionId)." else "Could not do: $actionSummary (action $actionId)."
        override val untrusted get() = detail?.takeIf { it.isNotBlank() }?.let { listOf(UntrustedPart("connector", it)) }.orEmpty()
    }

    /** The person ruled on a held action. */
    @Serializable @SerialName("held_decided")
    data class HeldDecided(val heldId: String, val status: String, val actionSummary: String) : InboxItem {
        override val priority get() = Priority.NORMAL
        override val summary get() = "The person ruled on the held action \"$actionSummary\" ($heldId): $status."
    }

    @Serializable @SerialName("constitution_changed")
    data class ConstitutionChanged(val version: Int) : InboxItem {
        override val priority get() = Priority.NORMAL
        override val summary get() = "The constitution changed; it is now version $version (see the system prompt for the current text)."
    }

    @Serializable @SerialName("system")
    data class System(val text: String, override val priority: Priority = Priority.NORMAL) : InboxItem {
        override val summary get() = text
    }
}

/** An inbox row as the planner sees it. [seq] is the store's strictly increasing order. */
data class PendingItem(val seq: Long, val item: InboxItem, val at: Instant)

/** What the lead does next. */
sealed interface TurnPlan {
    data object Wait : TurnPlan

    /** Run one turn over [items] (in arrival order). [userTurn] means they are the person's own input. */
    data class Turn(val items: List<PendingItem>, val userTurn: Boolean) : TurnPlan
}

/** A low priority item that has waited this long runs even if the lead never goes idle. */
val LOW_PRIORITY_MAX_WAIT = 1.hours

/**
 * Pure: what goes into the next turn.
 *  - the person's input pre-empts everything: it forms a turn on its own, the rest keeps waiting;
 *  - otherwise all normal-priority items are batched into one turn;
 *  - low-priority items join (or form) a turn only when the lead is [idle] or they have waited
 *    longer than [LOW_PRIORITY_MAX_WAIT].
 */
fun planNextTurn(pending: List<PendingItem>, now: Instant, idle: Boolean): TurnPlan {
    val ordered = pending.sortedBy { it.seq }
    val users = ordered.filter { it.item.priority == Priority.USER }
    if (users.isNotEmpty()) return TurnPlan.Turn(users, userTurn = true)
    val normal = ordered.filter { it.item.priority == Priority.NORMAL }
    val low = ordered.filter { it.item.priority == Priority.LOW && (idle || now - it.at >= LOW_PRIORITY_MAX_WAIT) }
    val batch = (normal + low).sortedBy { it.seq }
    return if (batch.isEmpty()) TurnPlan.Wait else TurnPlan.Turn(batch, userTurn = false)
}

/** One message handed to the lead and the inbox rows it carries (consumed once it is journaled). */
data class Delivery(val seqs: List<Long>, val message: Message)

/**
 * Pure: renders a planned turn. Each user input becomes its own user message (the harness folds
 * them into one); everything else becomes ONE mid-conversation system message, with untrusted
 * parts quoted so the model can tell data from instructions.
 */
fun renderDeliveries(items: List<PendingItem>, now: Instant): List<Delivery> {
    val users = items.filter { it.item is InboxItem.UserInput }
    val others = items.filter { it.item !is InboxItem.UserInput }
    val userDeliveries = users.map { Delivery(listOf(it.seq), Message.user((it.item as InboxItem.UserInput).text)) }
    if (others.isEmpty()) return userDeliveries
    val text = buildString {
        append("[inbox, ").append(now).append(']')
        others.forEach { pending ->
            append("\n- ").append(pending.item.summary)
            pending.item.untrusted.forEach { part -> append('\n').append(quoteUntrusted(part)) }
        }
    }
    return userDeliveries + Delivery(others.map { it.seq }, Message(net.robog.clarity.harness.Role.SYSTEM, listOf(Block.Text(text))))
}

private val UNTRUSTED_TAG = Regex("<(/?)\\s*untrusted", RegexOption.IGNORE_CASE)

/** `<untrusted source="…">…</untrusted>`; any untrusted tag inside the content is defanged so it cannot close the quote. */
fun quoteUntrusted(part: UntrustedPart): String {
    val source = part.source.replace("\"", "'").replace(Regex("[<>\\r\\n]"), " ")
    return "<untrusted source=\"$source\">${part.content.replace(UNTRUSTED_TAG, "&lt;$1untrusted")}</untrusted>"
}

/** Whether any item in the turn carried content from outside. */
fun hasUntrusted(items: List<PendingItem>): Boolean = items.any { it.item.untrusted.isNotEmpty() }
