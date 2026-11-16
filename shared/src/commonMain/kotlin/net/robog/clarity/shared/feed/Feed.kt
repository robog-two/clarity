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

/** The default [FeedItem.notify]: visible in the app, no interruption. */
const val NOTIFY_IN_APP: String = "in_app"

/** [FeedItem.notify] of an answer to the person: shown in the app, and the phone notifies only while the app is closed. */
const val NOTIFY_REPLY: String = "reply"

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
    /**
     * The notification decision recorded for this item: `push_now`, `in_app`, `hold_until:<iso>` or
     * `evening_batch:<iso>`. A hold becomes `released:<iso>` (the release time) once protected time
     * is over; the server then resends the item as [FeedEvent.ItemFinal] and the phone notifies once.
     * Optional on the wire; absent means "show in the app", so old stored JSON and old servers still decode.
     */
    val notify: String = NOTIFY_IN_APP,
    /**
     * Set on a topic marker: a 2–4 word title ("Ulta Hair Appt") for the stretch of the lead's
     * conversation that ended in a compaction. A marker has no cards, so apps that predate the
     * field show nothing for it.
     */
    val topic: String? = null,
    /**
     * Set on a reaction: the agent's tapback on one of the person's messages ([ItemReaction.itemId]).
     * A reaction has no cards and is never shown or notified as an item of its own; the app draws it
     * on its target, and the newest reaction (highest seq) on a message replaces any earlier one. It
     * comes as an item of its own so that a phone holding the target already gets it by seq.
     */
    val reaction: ItemReaction? = null,
    /**
     * What the agent meant by an agent message (see [MessageKind]); null on the person's own items,
     * on items from before kinds existed, and on notes the server posts itself. The notification
     * policy decides from it; the app only needs it to tell a reply from an interruption.
     */
    val kind: MessageKind? = null,
) {
    @Serializable
    enum class Author {
        @SerialName("agent") AGENT,
        @SerialName("user") USER,
    }
}

/**
 * Why the agent is talking to the person. It decides whether the message may interrupt (see the
 * notification policy in the backend, `deliveryOf`):
 *  - [REPLY] answers something the person just said or tapped. Decided by the SERVER, never by the
 *    model: a post made during a turn whose input included the person's message (or a tap on a
 *    [Card.Choices] option) is a reply, and a model-supplied reply outside such a turn becomes a
 *    [FINDING]. The phone notifies for it only while the app is closed, and it never counts against
 *    the daily push budget.
 *  - [CHECK_IN] asks how something is going, at the time a nudge was set for. Pushes, and counts.
 *  - [LOOK_FORWARD] offers something pleasant to plan (an outing). Pushes, and counts.
 *  - [FINDING] is anything the agent noticed on its own (mail, calendar). Pushes only when it is due
 *    within a day or the sender gets through; otherwise it waits for the evening summary.
 */
@Serializable
enum class MessageKind(val wire: String) {
    @SerialName("reply") REPLY("reply"),
    @SerialName("check_in") CHECK_IN("check_in"),
    @SerialName("look_forward") LOOK_FORWARD("look_forward"),
    @SerialName("finding") FINDING("finding"),
    ;

    companion object {
        fun fromWire(value: String?): MessageKind? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * A reaction on the wire: [reaction] is a [Reaction] wire name, kept as a string so a reaction added
 * later never breaks an older app's feed (it is just not drawn there).
 */
@Serializable
data class ItemReaction(val itemId: FeedItemId, val reaction: String) {
    /** The reaction, or null when this build does not know it. */
    val known: Reaction? get() = Reaction.fromWire(reaction)
}

/** The agent's tapbacks on the person's messages. */
@Serializable
enum class Reaction(val wire: String, val emoji: String) {
    @SerialName("thumbs_up") THUMBS_UP("thumbs_up", "👍"),
    @SerialName("heart") HEART("heart", "❤️"),
    @SerialName("laugh") LAUGH("laugh", "😂"),
    @SerialName("thumbs_down") THUMBS_DOWN("thumbs_down", "👎"),
    ;

    companion object {
        fun fromWire(value: String): Reaction? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The reaction now on each message among [items]: the one of the highest seq that targets it (a
 * later reaction replaces an earlier one). A message whose latest reaction this build does not
 * know shows none.
 */
fun latestReactions(items: List<FeedItem>): Map<FeedItemId, Reaction> =
    items.mapNotNull { item -> item.reaction?.let { item.seq to it } }.sortedBy { it.first }
        .associate { (_, r) -> r.itemId to r.known }
        .mapNotNull { (id, r) -> r?.let { id to it } }.toMap()

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

    /** A labelled list of short items ("5 captured", "today’s two", "I’ll do", "parked until tomorrow"). */
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

    /**
     * A finished document the agent made (`present_report`). The client fetches it with
     * `GET /api/v1/reports/{reportId}` and downloads a format with `…/download/{format}`. [formats]
     * are wire names (`pdf`, `docx`, `odt`, `txt`, `md`), kept as strings so a newer format never
     * breaks an older app. [fallbackText] is on the wire for apps that do not know this card.
     */
    @Serializable @SerialName("report")
    data class Report(
        val reportId: String,
        val title: String,
        val formats: List<String>,
        val fallbackText: String = "$title. In your Goods, to open or download.",
    ) : Card

    /**
     * The records and pages the agent's words rest on (`send_to_user` with `sources`). The server
     * fills each item's title and sub line from its own data; the app opens an entity with
     * `GET /api/v1/sources/{id}` and a web page in the browser. [fallbackText] is for apps that
     * do not know this card.
     */
    @Serializable @SerialName("sources")
    data class Sources(
        val items: List<net.robog.clarity.shared.source.SourceItem>,
        val fallbackText: String = "",
    ) : Card

    /**
     * A question with one-tap answers. The app draws [options] as buttons; a tap sends the option's
     * [Choice.reply] as the person's own input (`POST /api/v1/input` with `inReplyTo` set to this
     * item), which counts as the person writing (so the agent's answer is a reply). The server then
     * records the tapped reply in [chosen], and every device shows the buttons disabled with that one
     * marked. [fallbackText] is for apps that do not know this card: it lists the options so the
     * person can type one.
     */
    @Serializable @SerialName("choices")
    data class Choices(
        val prompt: String? = null,
        val options: List<Choice>,
        /** The [Choice.reply] the person tapped; null while unanswered. */
        val chosen: String? = null,
        val fallbackText: String = choicesFallback(prompt, options),
    ) : Card

    /**
     * A card of a type this build does not know (a newer server's): [ClarityJson] decodes any
     * unknown `type` to this instead of failing the whole feed. Card types added from now on carry
     * a `fallbackText` string on the wire for it; without one the card is not shown. (Apps built
     * before this existed still fail on unknown types; they cannot be fixed from here.)
     */
    @Serializable @SerialName("unknown")
    data class Unknown(val fallbackText: String? = null) : Card
}

/** One button of a [Card.Choices]: [label] is what it says, [reply] what is sent as the person's words when tapped. */
@Serializable
data class Choice(val label: String, val reply: String)

/** Pure: the text an app that does not know [Card.Choices] shows ("Starting now? Reply with Starting, Later or Make it smaller."). */
fun choicesFallback(prompt: String?, options: List<Choice>): String {
    val labels = options.map { it.label }
    val list = when (labels.size) {
        0 -> ""
        1 -> labels.single()
        else -> labels.dropLast(1).joinToString(", ") + " or " + labels.last()
    }
    return listOfNotNull(prompt?.trim()?.takeIf { it.isNotEmpty() }, list.takeIf { it.isNotEmpty() }?.let { "Reply with $it." }).joinToString(" ")
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
data class IfThen(
    val cue: String,
    val action: String,
    /** The two-minute first move for a task that keeps coming back; null when none was agreed. */
    val firstStep: String? = null,
) {
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
    /** The helper runs that worked on this task, oldest first (its Agent's Path follows them). */
    val runIds: List<String> = emptyList(),
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
    /** Set when the input is a tap on a [Card.Choices] option: the item that holds the card. The server marks the option chosen. */
    val inReplyTo: FeedItemId? = null,
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
