package net.robog.clarity.shared.governance

import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlinx.datetime.LocalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.noun.Handle

/**
 * The constitution: the person's own rules for the agent, written from the values interview and
 * changed only by amendments they approve. Natural-language [lines] (each with its reason) guide
 * judgment; the structured fields are applied deterministically by the guard and the
 * notification policy.
 */
@Serializable
data class Constitution(
    val version: Int,
    val lines: List<Line>,
    /** Autonomy per action kind. Kinds not listed default to [AutonomyTier.ASK_FIRST]. */
    val autonomy: Map<ActionKind, AutonomyTier> = emptyMap(),
    /** People who may interrupt protected time ("who gets through"). */
    val getsThrough: List<Person> = emptyList(),
    val protectedTimes: List<TimeBlock> = emptyList(),
    /** Topics the agent must never store (e.g. "health"). */
    val neverStore: List<String> = emptyList(),
    /** Short durable facts about the person (always in the prompt). */
    val profile: List<String> = emptyList(),
    val updatedAt: Instant,
) {
    companion object {
        /**
         * Kinds that only the phone can approve, whatever [autonomy] says: a mechanism, not a
         * preference (secrets can only be opened on the phone). Every other boundary, including
         * payments and sending email, comes from the person's own [autonomy] settings.
         */
        val ALWAYS_NEVER_ALONE: Set<ActionKind> = setOf(ActionKind.USE_CREDENTIAL, ActionKind.TWO_FACTOR_CODE)
    }
}

@Serializable @JvmInline
value class LineId(val value: String) { override fun toString(): String = value }

@Serializable
data class Line(
    val id: LineId,
    val section: Section,
    val text: String,
    /** The person's reason, in their words. Null for fixed lines. */
    val because: String? = null,
    /** Hard lines are never crossed and cannot be amended by talking alone. */
    val hard: Boolean = false,
)

@Serializable
enum class Section {
    @SerialName("values") VALUES,
    @SerialName("hard_lines") HARD_LINES,
    @SerialName("autonomy") AUTONOMY,
    @SerialName("collisions") COLLISIONS,
    @SerialName("people") PEOPLE,
    @SerialName("repair") REPAIR,
}

@Serializable
enum class AutonomyTier {
    /** "do it, then tell me" */
    @SerialName("do_then_tell") DO_THEN_TELL,
    /** "ask me first" */
    @SerialName("ask_first") ASK_FIRST,
    /** "never alone": only with the person's yes on their phone (fingerprint or face). */
    @SerialName("never_alone") NEVER_ALONE,
    /** "never": a hard line; refused even if asked, until the person amends it. */
    @SerialName("never") NEVER,
}

@Serializable
data class Person(val name: String, val handles: List<Handle> = emptyList(), val until: Instant? = null)

/**
 * A recurring protected block, e.g. weekdays 09:00–12:00 "deep work". Days are ISO (1 = Monday).
 * Stored like the settings' times: [start], [end] and [days] are wall-clock times in
 * `Settings.homeZone`; convert with [net.robog.clarity.shared.time.ScheduleClock].
 */
@Serializable
data class TimeBlock(val label: String, val days: Set<Int>, val start: LocalTime, val end: LocalTime)

/** Kinds of effect an action has on the world. Connectors declare the kinds they handle. */
@Serializable
enum class ActionKind {
    @SerialName("save_draft") SAVE_DRAFT,
    @SerialName("send_email") SEND_EMAIL,
    @SerialName("create_event") CREATE_EVENT,
    @SerialName("move_event") MOVE_EVENT,
    @SerialName("respond_to_invite") RESPOND_TO_INVITE,
    @SerialName("create_reminder") CREATE_REMINDER,
    @SerialName("set_do_not_disturb") SET_DO_NOT_DISTURB,
    @SerialName("read_document") READ_DOCUMENT,
    @SerialName("payment") PAYMENT,
    /** Anything through a generic MCP tool (browser form submit, third-party server). */
    @SerialName("external_tool") EXTERNAL_TOOL,
    /** Releasing a stored credential's secret fields to the browser for one site (vault). Always asks, with biometric. */
    @SerialName("use_credential") USE_CREDENTIAL,
    /** Asking the person to type a two-factor code. */
    @SerialName("two_factor_code") TWO_FACTOR_CODE,
}

/**
 * An action the agent wants to take. Every variant has an [idempotencyKey] so a retried run
 * executes it at most once, and a [summary] for activity lists ("held 9:30–12 for the spec").
 */
@Serializable
sealed interface ProposedAction {
    val kind: ActionKind
    val idempotencyKey: String
    val summary: String

    @Serializable @SerialName("save_draft")
    data class SaveDraft(
        override val idempotencyKey: String,
        override val summary: String,
        val email: EmailDraft,
    ) : ProposedAction { override val kind: ActionKind get() = ActionKind.SAVE_DRAFT }

    @Serializable @SerialName("send_email")
    data class SendEmail(
        override val idempotencyKey: String,
        override val summary: String,
        val email: EmailDraft,
    ) : ProposedAction { override val kind: ActionKind get() = ActionKind.SEND_EMAIL }

    @Serializable @SerialName("create_event")
    data class CreateEvent(
        override val idempotencyKey: String,
        override val summary: String,
        val calendar: String? = null,
        val title: String,
        val start: Instant,
        val end: Instant,
        val attendees: List<Handle> = emptyList(),
        /** True for a private hold/focus block on the person's own calendar. */
        val hold: Boolean = true,
    ) : ProposedAction { override val kind: ActionKind get() = ActionKind.CREATE_EVENT }

    @Serializable @SerialName("move_event")
    data class MoveEvent(
        override val idempotencyKey: String,
        override val summary: String,
        val event: EntityId,
        val start: Instant,
        val end: Instant,
        /** Other people's events need the organizer's update to go out. */
        val notifyAttendees: Boolean,
    ) : ProposedAction { override val kind: ActionKind get() = ActionKind.MOVE_EVENT }

    @Serializable @SerialName("respond_to_invite")
    data class RespondToInvite(
        override val idempotencyKey: String,
        override val summary: String,
        val event: EntityId,
        val response: InviteResponse,
        val comment: String? = null,
    ) : ProposedAction { override val kind: ActionKind get() = ActionKind.RESPOND_TO_INVITE }

    /** Executed by the calendar connector as a short event with an alarm; there is no separate reminder store. */
    @Serializable @SerialName("create_reminder")
    data class CreateReminder(
        override val idempotencyKey: String,
        override val summary: String,
        val text: String,
        val at: Instant,
    ) : ProposedAction { override val kind: ActionKind get() = ActionKind.CREATE_REMINDER }

    @Serializable @SerialName("set_do_not_disturb")
    data class SetDoNotDisturb(
        override val idempotencyKey: String,
        override val summary: String,
        val until: Instant,
        /** People/apps still allowed through (from "who gets through"). */
        val allow: List<Handle> = emptyList(),
    ) : ProposedAction { override val kind: ActionKind get() = ActionKind.SET_DO_NOT_DISTURB }

    @Serializable @SerialName("read_document")
    data class ReadDocument(
        override val idempotencyKey: String,
        override val summary: String,
        val document: EntityId,
    ) : ProposedAction { override val kind: ActionKind get() = ActionKind.READ_DOCUMENT }

    @Serializable @SerialName("external_tool")
    data class ExternalTool(
        override val idempotencyKey: String,
        override val summary: String,
        val server: String,
        val tool: String,
        val input: JsonObject,
    ) : ProposedAction { override val kind: ActionKind get() = ActionKind.EXTERNAL_TOOL }

    /**
     * Use [fields] of a stored credential on [site] (the registrable domain of the page). Approved
     * only through `POST /api/v1/vault/grants/{heldId}` (biometric on the phone), never executed by a connector.
     */
    @Serializable @SerialName("use_credential")
    data class UseCredential(
        override val idempotencyKey: String,
        override val summary: String,
        val credentialId: net.robog.clarity.shared.vault.CredentialId,
        val site: String,
        val fields: List<String>,
    ) : ProposedAction { override val kind: ActionKind get() = ActionKind.USE_CREDENTIAL }

    /** Ask the person for a two-factor code for [site]; answered through `POST /api/v1/vault/codes/{heldId}`. */
    @Serializable @SerialName("ask_code")
    data class AskCode(
        override val idempotencyKey: String,
        override val summary: String,
        val site: String,
        val purpose: String,
    ) : ProposedAction { override val kind: ActionKind get() = ActionKind.TWO_FACTOR_CODE }
}

@Serializable
data class EmailDraft(
    val from: Handle? = null,
    val to: List<Handle>,
    val cc: List<Handle> = emptyList(),
    val subject: String,
    val body: String,
    /** The message this replies to, for threading headers. */
    val inReplyTo: EntityId? = null,
)

@Serializable
enum class InviteResponse {
    @SerialName("accept") ACCEPT,
    @SerialName("decline") DECLINE,
    @SerialName("tentative") TENTATIVE,
}

/** Where an action came from: the guard treats anything influenced by untrusted content as riskier. */
@Serializable
data class Provenance(
    val runId: String,
    /** True if untrusted content (mail bodies, web pages, documents) was in the run's context. */
    val untrustedInput: Boolean,
    /** The person explicitly asked for this action in their own words, this session. */
    val requestedByUser: Boolean = false,
)

@Serializable
enum class Decision {
    @SerialName("allow") ALLOW,
    @SerialName("ask") ASK,
    @SerialName("deny") DENY,
}

@Serializable
data class GuardVerdict(
    val decision: Decision,
    val lines: List<LineId> = emptyList(),
    /** One sentence: "I’m asking because it touches money (line 3) and goes out as you." */
    val rationale: String,
    /**
     * True when the person's own settings put this kind at "never alone": only a yes signed on the
     * phone (`POST /api/v1/held/{id}/signed-decision`) approves it; the generic decision route refuses.
     */
    val phoneApproval: Boolean = false,
)

@Serializable @JvmInline
value class HeldActionId(val value: String) { override fun toString(): String = value }

/** An action waiting for the person (Held → "Things I tried that hit one of your lines"). */
@Serializable
data class HeldAction(
    val id: HeldActionId,
    val createdAt: Instant,
    val action: ProposedAction,
    val verdict: GuardVerdict,
    /** The agent's view ("what I think"). */
    val agentView: String,
    /** What prompted it, e.g. the message being replied to. */
    val context: List<net.robog.clarity.shared.feed.ContextItem> = emptyList(),
    val urgency: Urgency,
    val status: Status,
    val decidedAt: Instant? = null,
    /** "Approve automatically from now on", written ahead by a small model; the person edits it before saving. */
    val ruleDraft: RuleDraft? = null,
) {
    @Serializable
    enum class Status {
        @SerialName("held") HELD,
        /** The guard said DENY; the person can still review it and amend a line. */
        @SerialName("blocked") BLOCKED,
        @SerialName("approved") APPROVED,
        @SerialName("declined") DECLINED,
        @SerialName("kept") KEPT,
        @SerialName("rule_updated") RULE_UPDATED,
        @SerialName("executed") EXECUTED,
        @SerialName("failed") FAILED,
        @SerialName("expired") EXPIRED,
    }
}

@Serializable
enum class Urgency {
    /** Push now (subject to who-gets-through and protected time). */
    @SerialName("urgent") URGENT,
    /** Visible in the app, no push. */
    @SerialName("normal") NORMAL,
    /** Waits for the evening batch. */
    @SerialName("batch") BATCH,
}

/** `POST /api/v1/held/{id}/decision`. */
@Serializable
sealed interface HeldDecision {
    /** Approve, optionally with an edited version (e.g. the person changed the draft text). */
    @Serializable @SerialName("approve")
    data class Approve(val edited: ProposedAction? = null) : HeldDecision

    @Serializable @SerialName("decline")
    data object Decline : HeldDecision

    /** The line was right; keep it ("keep as is"). */
    @Serializable @SerialName("keep")
    data object Keep : HeldDecision
}

@Serializable @JvmInline
value class AmendmentId(val value: String) { override fun toString(): String = value }

/** A proposed change to one line (or a new line when [lineId] is null). */
@Serializable
data class Amendment(
    val id: AmendmentId,
    val lineId: LineId?,
    val old: String?,
    val proposed: Line,
    val reason: String,
    val fromHeld: HeldActionId? = null,
    val status: Status,
    /** How much the agent may do alone for one kind of action, changed along with the line. */
    val autonomy: AutonomyChange? = null,
) {
    @Serializable
    enum class Status {
        @SerialName("proposed") PROPOSED,
        @SerialName("saved") SAVED,
        @SerialName("discarded") DISCARDED,
    }
}

/** A constitution line drafted for a held action's kind, in the person's voice. */
@Serializable
data class RuleDraft(val text: String, val because: String)

/**
 * Kinds that never get "approve automatically": money, saved logins and codes always need the
 * person, and browser steps are judged step by step on the page, so one tier cannot cover them.
 */
val NEVER_AUTOMATIC: Set<ActionKind> = setOf(ActionKind.PAYMENT, ActionKind.USE_CREDENTIAL, ActionKind.TWO_FACTOR_CODE, ActionKind.EXTERNAL_TOOL)

/** One kind of action moved to another autonomy tier ("add calendar events, then tell me"). */
@Serializable
data class AutonomyChange(val kind: ActionKind, val tier: AutonomyTier)

/** An action taken alone ("see allowed actions"). */
@Serializable
data class ActivityEntry(val at: Instant, val summary: String, val kind: ActionKind, val ok: Boolean)
