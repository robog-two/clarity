package net.robog.clarity.shared.assistant

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Something the agent wants to do that changes the world. Every WRITE/EXTERNAL tool call becomes
 * one of these and is checked against the constitution before it runs:
 * - allowed by the checker -> runs and is logged as [ActionStatus.EXECUTED] ("allowed actions"),
 * - in the ask-first tier -> [ActionStatus.AWAITING_REVIEW] ("needs your ok": drafts, proposals),
 * - hit a line -> [ActionStatus.HELD] ("held for you"; the user can amend the rule or dismiss it).
 *
 * `GET /api/v1/actions?status=held,awaiting_review&since=...`
 */
@Serializable
data class ProposedAction(
    val id: String,
    /** The tool that would run, e.g. `send_email`, `move_event`. */
    val tool: String,
    /** Short title, e.g. "reply to Dana". */
    val title: String,
    /** Kicker line, e.g. "needs your ok · reply to dana@…" or "8:12 · mail send · to pat@…". */
    val subtitle: String,
    /** The draft or change itself, rendered verbatim (mail body, "today 2:00 -> thursday 2:00"). */
    val body: String,
    /** "What I think": the agent's recommendation, in its voice. */
    val rationale: String? = null,
    /** "I'm asking because ..." or which line it hit. */
    val reason: String,
    /** The rule it hit or that requires review, if any. */
    val ruleId: String? = null,
    /** Source material the user may want beside the draft ("what Dana asked"). */
    val context: List<ContextItem> = emptyList(),
    val status: ActionStatus,
    val urgency: Urgency = Urgency.NORMAL,
    val createdAt: Instant,
    val decidedAt: Instant? = null,
    /** Result line after execution, e.g. "sent · Dana has the update". */
    val outcome: String? = null,
)

@Serializable
enum class ActionStatus {
    @SerialName("awaiting_review") AWAITING_REVIEW,
    @SerialName("held") HELD,
    @SerialName("approved") APPROVED,
    @SerialName("executed") EXECUTED,
    @SerialName("dismissed") DISMISSED,
    @SerialName("failed") FAILED,
}

/** `POST /api/v1/actions/{id}/decision` */
@Serializable
data class ActionDecision(val decision: Kind) {
    @Serializable
    enum class Kind {
        @SerialName("approve") APPROVE,
        @SerialName("dismiss") DISMISS,
    }
}

/**
 * `POST /api/v1/actions/{id}/revise`: "talk about it". The agent rewrites the draft and the
 * action comes back (same id) with a new [ProposedAction.body]; a [Outbound.Say] reply follows
 * on the event stream.
 */
@Serializable
data class ActionRevision(val utterance: String)

/** A small piece of source material: a message, an event, a file. */
@Serializable
data class ContextItem(
    /** e.g. "mail · yesterday 5:40" or "calendar · today". */
    val tag: String,
    val title: String,
    /** Optional longer excerpt. */
    val excerpt: String? = null,
    /** Canonical entity id when it refers to a record we hold. */
    val entityId: String? = null,
)

/**
 * Whether something is worth interrupting the user for. The backend decides; the app maps
 * [URGENT] to a heads-up notification, [NORMAL] to a quiet notification or the Today list, and
 * [DIGEST] to the evening batch.
 */
@Serializable
enum class Urgency {
    @SerialName("urgent") URGENT,
    @SerialName("normal") NORMAL,
    @SerialName("digest") DIGEST,
}
