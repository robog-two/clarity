package net.robog.clarity.shared.assistant

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The user's constitution ("How I Want to Work"): the rules every agent action is checked against
 * before it runs. Each rule keeps the user's own reason beside it; the reason is what lets the
 * agent use judgment where no rule applies. Versioned: every accepted amendment bumps [version].
 *
 * `GET /api/v1/constitution` (404 until the values interview is committed).
 */
@Serializable
data class Constitution(
    val version: Int,
    val sections: List<ConstitutionSection>,
    /** How much the agent may do alone, by tier. Drives the action checker. */
    val autonomy: List<AutonomyTier>,
    val updatedAt: Instant,
)

@Serializable
data class ConstitutionSection(val kind: SectionKind, val title: String, val rules: List<Rule>)

@Serializable
enum class SectionKind {
    @SerialName("value") VALUE,
    @SerialName("hard_line") HARD_LINE,
    @SerialName("priority") PRIORITY,
    @SerialName("people") PEOPLE,
    @SerialName("repair") REPAIR,
    @SerialName("memory") MEMORY,
}

/** One line. [hardLine] rules are never crossed and are not weighed against anything. */
@Serializable
data class Rule(
    /** Stable across versions so amendments and held actions can point at it, e.g. `r3`. */
    val id: String,
    val text: String,
    /** The user's reason, phrased to follow "because". */
    val because: String? = null,
    val hardLine: Boolean = false,
)

@Serializable
enum class AutonomyLevel {
    /** Do it, then tell me: holds on my calendar, reminders, drafts, lookups. */
    @SerialName("do_then_tell") DO_THEN_TELL,
    /** Ask me first: anything sent as me, replies to invites, moving other people's events. */
    @SerialName("ask_first") ASK_FIRST,
    /** Never alone: payments and purchases. */
    @SerialName("never_alone") NEVER_ALONE,
}

@Serializable
data class AutonomyTier(val level: AutonomyLevel, val examples: String)

// --- Values interview (onboarding step 2) ---------------------------------------------------

/** `GET /api/v1/interview/questions`: the fixed question list (seven by default). */
@Serializable
data class InterviewQuestion(
    val index: Int,
    /** Short theme shown as a kicker, e.g. "when help backfired". */
    val theme: String,
    val text: String,
    /** What the answer most likely becomes; the model may pick another kind. */
    val suggestedKind: SectionKind,
)

/** `POST /api/v1/interview/answers`: one spoken answer (already transcribed on device). */
@Serializable
data class InterviewAnswer(val index: Int, val answer: String)

/**
 * The model's reading of one answer. [alternative] is the "reword" option the app can toggle to.
 * [tiers] is set when the answer describes how much the agent may do alone.
 */
@Serializable
data class DraftRule(
    val index: Int,
    val kind: SectionKind,
    /** Kicker shown above the rule, e.g. "hard line" or "how much I do alone". */
    val label: String,
    val text: String,
    val alternative: String,
    val because: String,
    val hardLine: Boolean = false,
    val tiers: List<AutonomyTier> = emptyList(),
)

/** `POST /api/v1/interview/commit`: the drafts the user kept (with their choice of wording). */
@Serializable
data class CommitInterview(val rules: List<KeptRule>)

@Serializable
data class KeptRule(val draft: DraftRule, val useAlternative: Boolean = false)

// --- Amendments ("amend by talking", or from a held action) ----------------------------------

/** `POST /api/v1/constitution/amendments` */
@Serializable
data class AmendmentRequest(
    /** What the user said, transcribed. */
    val utterance: String,
    /** Set when amending from a held action: the action that hit a line. */
    val actionId: String? = null,
    /** Set when the user is amending a specific rule. */
    val ruleId: String? = null,
)

/** A proposed change the user can save or keep as is. */
@Serializable
data class AmendmentProposal(
    val id: String,
    val ruleId: String?,
    /** e.g. "line 1 · writing". */
    val label: String,
    val oldText: String?,
    val newText: String,
    val because: String? = null,
)

/** `POST /api/v1/constitution/amendments/{id}/accept` returns the new [Constitution]. */
@Serializable
data class AmendmentDecision(val accept: Boolean)
