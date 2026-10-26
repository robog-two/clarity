package net.robog.clarity.shared.action

import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.governance.ProposedAction

/**
 * The action channel: the backend never holds upstream credentials, so approved actions are
 * queued for the connector that can execute them. Connectors long-poll
 * `GET /ingest/v1/actions?wait=<seconds>` and report back with
 * `POST /ingest/v1/actions/{id}/result`. Delivery is at-least-once; connectors make execution
 * idempotent with [ConnectorAction.action]'s `idempotencyKey` (e.g. a Message-ID header, an
 * iCalendar UID), and the backend ignores duplicate results.
 */
@Serializable @JvmInline
value class ActionId(val value: String) { override fun toString(): String = value }

@Serializable
data class ConnectorAction(
    val id: ActionId,
    val action: ProposedAction,
    /** The connector's own id for the target record, when the action refers to one. */
    val target: SourceId? = null,
    val attempt: Int = 1,
    val createdAt: Instant,
)

@Serializable
data class ActionPoll(val actions: List<ConnectorAction>)

@Serializable
sealed interface ActionOutcome {
    /** Done; [ref] is the connector's id for what it created/changed; [content] carries read results. */
    @Serializable @SerialName("done")
    data class Done(val ref: SourceId? = null, val content: String? = null) : ActionOutcome

    /** Failed; [retryable] lets the backend try again with backoff. [message] is shown to the agent. */
    @Serializable @SerialName("failed")
    data class Failed(val message: String, val retryable: Boolean) : ActionOutcome
}

@Serializable
data class ActionResult(val outcome: ActionOutcome)
