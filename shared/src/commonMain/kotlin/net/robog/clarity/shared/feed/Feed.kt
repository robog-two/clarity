package net.robog.clarity.shared.feed

import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.session.SessionId

/**
 * The feed is everything the person sees from the agent: an append-only list of [FeedItem]s,
 * each with a server-assigned, strictly increasing [FeedItem.seq]. Clients keep the last seq they
 * have, fetch `GET /api/v1/feed?after=<seq>`, and listen to `GET /api/v1/events` (SSE) for
 * [FeedEvent]s. The agent produces agent items only through its `send_to_user` tool.
 */
@Serializable @JvmInline
value class FeedItemId(val value: String) { override fun toString(): String = value }

/** Client-generated id (UUID) for user input, so retries never create duplicates. */
@Serializable @JvmInline
value class ClientInputId(val value: String) { override fun toString(): String = value }

@Serializable
data class FeedItem(
    val id: FeedItemId,
    val seq: Long,
    val at: Instant,
    val author: Author,
    /** Set when the item belongs to a call, interview, ask or amend session. */
    val sessionId: SessionId? = null,
    val cards: List<Card>,
    /** True while the agent is still streaming text into this item (see [FeedEvent.TextDelta]). */
    val streaming: Boolean = false,
) {
    @Serializable
    enum class Author {
        @SerialName("agent") AGENT,
        @SerialName("user") USER,
    }
}

/**
 * The typed building blocks the client renders. The model never sends free-form UI: every
 * visual element in the design (context lookups, captured chips, plans, notes, proposals) is a card.
 */
@Serializable
sealed interface Card {
    /** Agent prose ("Morning. What's on your mind?"). */
    @Serializable @SerialName("text")
    data class Text(val text: String) : Card

    /** What the person said or typed. */
    @Serializable @SerialName("said")
    data class Said(val text: String, val source: InputSource = InputSource.TYPED) : Card

    /** Records the agent looked up for something the person mentioned ("“Dana’s contract” · 2 found"). */
    @Serializable @SerialName("context")
    data class Context(val label: String, val items: List<ContextItem>) : Card

    /** A labelled list of short items ("captured · 5", "today’s two", "I’ll do", "parked until tomorrow"). */
    @Serializable @SerialName("chips")
    data class Chips(val label: String, val items: List<Chip>) : Card

    /** If-then plans (implementation intentions). */
    @Serializable @SerialName("plan")
    data class Plan(val items: List<IfThen>) : Card

    /** A highlighted aside ("Nothing goes out as you without your review."). */
    @Serializable @SerialName("note")
    data class Note(val text: String) : Card

    /** An action that needs the person's OK; the client fetches/updates it via the held API. */
    @Serializable @SerialName("proposal")
    data class Proposal(val heldActionId: HeldActionId) : Card

    /** Progress of tasks the agent is doing ("setting up" after a call). */
    @Serializable @SerialName("tasks")
    data class Tasks(val label: String, val taskIds: List<TaskId>) : Card

    /** A form asking the person to enter a credential; the client fetches the request via `GET /api/v1/vault/requests/{id}`. */
    @Serializable @SerialName("credential_request")
    data class CredentialRequest(val requestId: net.robog.clarity.shared.vault.CredentialRequestId) : Card
}

@Serializable
enum class InputSource {
    @SerialName("typed") TYPED,
    @SerialName("voice") VOICE,
}

@Serializable
data class ContextItem(
    /** Short provenance line, e.g. "mail · yesterday" or "calendar · today". */
    val tag: String,
    val title: String,
    val entity: EntityId? = null,
)

@Serializable
data class Chip(val text: String, val sub: String = "")

@Serializable
data class IfThen(val cue: String, val action: String) {
    /** "If it’s 9:30, then spec at my desk until 12." */
    fun sentence(): String = "If $cue, then $action."
}

@Serializable @JvmInline
value class TaskId(val value: String) { override fun toString(): String = value }

/** Something the agent is doing (Today → "I’m doing"). */
@Serializable
data class AgentTask(
    val id: TaskId,
    val title: String,
    /** e.g. "mail · waits for your review", "nudge at 11:55". */
    val detail: String,
    val status: Status,
    val heldActionId: HeldActionId? = null,
    val updatedAt: Instant,
) {
    @Serializable
    enum class Status {
        @SerialName("working") WORKING,
        @SerialName("drafted") DRAFTED,
        @SerialName("waiting_for_you") WAITING_FOR_YOU,
        @SerialName("scheduled") SCHEDULED,
        @SerialName("done") DONE,
        @SerialName("failed") FAILED,
        @SerialName("cancelled") CANCELLED,
    }
}

/** `POST /api/v1/input`. Replays with the same [clientId] return the original [InputAccepted]. */
@Serializable
data class UserInput(
    val clientId: ClientInputId,
    val text: String,
    val source: InputSource = InputSource.TYPED,
    val sessionId: SessionId? = null,
)

@Serializable
data class InputAccepted(val item: FeedItem)

@Serializable
data class FeedPage(val items: List<FeedItem>, val hasMore: Boolean)

/** SSE events on `GET /api/v1/events?after=<seq>`. The SSE `event:` field is the type name. */
@Serializable
sealed interface FeedEvent {
    @Serializable @SerialName("item")
    data class ItemAppended(val item: FeedItem) : FeedEvent

    /** Streaming agent text for an item whose last card is [Card.Text]. */
    @Serializable @SerialName("delta")
    data class TextDelta(val itemId: FeedItemId, val text: String) : FeedEvent

    /** The item is final (replaces any locally accumulated deltas). */
    @Serializable @SerialName("item_final")
    data class ItemFinal(val item: FeedItem) : FeedEvent

    @Serializable @SerialName("task")
    data class TaskChanged(val task: AgentTask) : FeedEvent

    @Serializable @SerialName("held")
    data class HeldChanged(val heldActionId: HeldActionId) : FeedEvent

    @Serializable @SerialName("constitution")
    data class ConstitutionChanged(val version: Int) : FeedEvent

    @Serializable @SerialName("session")
    data class SessionChanged(val sessionId: SessionId) : FeedEvent

    /** The agent is thinking or working (drives the "working…" indicator). */
    @Serializable @SerialName("busy")
    data class Busy(val busy: Boolean) : FeedEvent
}
