package net.robog.clarity.backend.governance

import kotlinx.datetime.TimeZone
import net.robog.clarity.backend.persistence.wire
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.Decision
import net.robog.clarity.shared.governance.GuardVerdict
import net.robog.clarity.shared.governance.LineId
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.Provenance

/** Kinds that are hard lines: never executed on the guard's say-so, only reviewed (money, purchases). */
val HARD_LINE_KINDS: Set<ActionKind> = setOf(ActionKind.PAYMENT)

/** Human spelling of a kind for rationales, e.g. "send email". */
fun ActionKind.label(): String = wire().replace('_', ' ')

/** The tier that applies to [kind]: fixed never-alone kinds first, then the person's setting, else "ask first". */
fun tierOf(kind: ActionKind, constitution: Constitution): AutonomyTier = when {
    kind in Constitution.ALWAYS_NEVER_ALONE -> AutonomyTier.NEVER_ALONE
    else -> constitution.autonomy[kind] ?: AutonomyTier.ASK_FIRST
}

/** Actions that reach other people or outside systems; untrusted-content influence matters most here. */
fun isExternal(action: ProposedAction): Boolean = when (action) {
    is ProposedAction.SendEmail -> true
    is ProposedAction.ExternalTool -> true
    is ProposedAction.RespondToInvite -> true
    is ProposedAction.MoveEvent -> action.notifyAttendees
    is ProposedAction.CreateEvent -> action.attendees.isNotEmpty() || !action.hold
    is ProposedAction.SaveDraft, is ProposedAction.CreateReminder,
    is ProposedAction.SetDoNotDisturb, is ProposedAction.ReadDocument -> false
}

/** Distinct people an email or invitation goes out to (0 for other kinds). */
fun recipientCount(action: ProposedAction): Int = when (action) {
    is ProposedAction.SendEmail -> (action.email.to + action.email.cc).map { it.value.trim().lowercase() }.toSet().size
    is ProposedAction.CreateEvent -> action.attendees.map { it.value.trim().lowercase() }.toSet().size
    else -> 0
}

/** All free text in [action], lowercased, for the never-store topic check. */
fun textOf(action: ProposedAction): String = buildString {
    append(action.summary).append('\n')
    when (action) {
        is ProposedAction.SaveDraft -> append(action.email.subject).append('\n').append(action.email.body)
        is ProposedAction.SendEmail -> append(action.email.subject).append('\n').append(action.email.body)
        is ProposedAction.CreateEvent -> append(action.title)
        is ProposedAction.CreateReminder -> append(action.text)
        is ProposedAction.RespondToInvite -> append(action.comment.orEmpty())
        is ProposedAction.ExternalTool -> append(action.server).append(' ').append(action.tool).append(' ').append(action.input.toString())
        is ProposedAction.MoveEvent, is ProposedAction.SetDoNotDisturb, is ProposedAction.ReadDocument -> Unit
    }
}.lowercase()

private data class Finding(val decision: Decision, val reason: String)

/**
 * The deterministic half of the guard. Pure. Collects every rule that applies and takes the
 * strictest decision (DENY > ASK > ALLOW); the rationale lists the reasons behind it.
 *
 *  1. hard-line kinds (payments) -> DENY;
 *  2. the autonomy tier of the kind: "never alone" and "ask first" -> ASK, "do then tell" -> ALLOW;
 *  3. an email or invitation to more than one person, sent as the person -> at least ASK;
 *  4. moving an event and telling its attendees -> at least ASK;
 *  5. an external action in a run that saw untrusted content -> at least ASK, even if the person
 *     asked, because the content may have changed what is being done;
 *  6. a calendar write (other than a private hold) overlapping protected time -> at least ASK;
 *  7. the action mentions a `neverStore` topic -> at least ASK.
 */
fun deterministicVerdict(
    action: ProposedAction,
    constitution: Constitution,
    provenance: Provenance,
    zone: TimeZone = TimeZone.UTC,
): GuardVerdict {
    val findings = ArrayList<Finding>()
    val kindName = action.kind.label()

    if (action.kind in HARD_LINE_KINDS) {
        findings += Finding(Decision.DENY, "it would move money, which is a line I never cross")
    }
    when (tierOf(action.kind, constitution)) {
        AutonomyTier.NEVER_ALONE -> findings += Finding(Decision.ASK, "you said \"$kindName\" is never done alone")
        AutonomyTier.ASK_FIRST -> findings += Finding(Decision.ASK, "you want to be asked before I do \"$kindName\"")
        AutonomyTier.DO_THEN_TELL -> findings += Finding(Decision.ALLOW, "you let me do \"$kindName\" and tell you after")
    }
    val recipients = recipientCount(action)
    if (recipients > 1) findings += Finding(Decision.ASK, "it goes out as you to $recipients people")
    if (action is ProposedAction.MoveEvent && action.notifyAttendees) {
        findings += Finding(Decision.ASK, "it changes an event other people are on and tells them")
    }
    if (provenance.untrustedInput && isExternal(action)) {
        findings += Finding(Decision.ASK, "it was shaped by outside content (a mail, page or document) and reaches other people")
    }
    protectedOverlapOf(action, constitution, zone)?.let { block ->
        findings += Finding(Decision.ASK, "it lands in your protected time (${block.label})")
    }
    val text = textOf(action)
    constitution.neverStore.firstOrNull { it.isNotBlank() && text.contains(it.trim().lowercase()) }?.let { topic ->
        findings += Finding(Decision.ASK, "it mentions \"$topic\", which you asked me never to store")
    }

    val strictest = findings.maxOf { it.decision }
    if (strictest == Decision.ALLOW) {
        return GuardVerdict(Decision.ALLOW, emptyList(), "Within what you let me do on my own.")
    }
    val lead = if (strictest == Decision.DENY) "Blocked because " else "I'm asking because "
    val reasons = findings.filter { it.decision == strictest }.joinToString("; ") { it.reason }
    return GuardVerdict(strictest, emptyList<LineId>(), "$lead$reasons.")
}

private fun protectedOverlapOf(action: ProposedAction, constitution: Constitution, zone: TimeZone) =
    when (action) {
        // a private hold without attendees *protects* time; anything else intrudes on it
        is ProposedAction.CreateEvent ->
            if (action.hold && action.attendees.isEmpty()) null
            else protectedOverlap(constitution.protectedTimes, action.start, action.end, zone)
        is ProposedAction.MoveEvent -> protectedOverlap(constitution.protectedTimes, action.start, action.end, zone)
        else -> null
    }

/**
 * Combines the deterministic and the model verdict. The model can only tighten: the result is
 * the stricter decision, with the lines of both and the rationale of whichever decided.
 */
fun combine(deterministic: GuardVerdict, model: GuardVerdict): GuardVerdict {
    val lines = (deterministic.lines + model.lines).distinct()
    return when {
        model.decision > deterministic.decision -> {
            val why = if (deterministic.decision == Decision.ALLOW) model.rationale else "${deterministic.rationale} ${model.rationale}"
            GuardVerdict(model.decision, lines, why)
        }
        model.decision == deterministic.decision && model.decision != Decision.ALLOW -> {
            val why = if (model.rationale == deterministic.rationale) model.rationale else "${deterministic.rationale} ${model.rationale}"
            GuardVerdict(model.decision, lines, why)
        }
        else -> deterministic.copy(lines = lines)
    }
}

/**
 * Re-check of an approved (possibly edited) action: only hard lines can refuse a person who has
 * decided. Returns the reason, or null when approving is fine.
 */
fun hardLineViolation(action: ProposedAction, previous: GuardVerdict, constitution: Constitution): String? {
    if (action.kind in HARD_LINE_KINDS) return "\"${action.kind.label()}\" is a hard line; change the line first"
    val cited = constitution.lines.filter { it.hard && it.id in previous.lines }
    if (previous.decision == Decision.DENY && cited.isNotEmpty()) {
        return "it touches a hard line (${cited.joinToString { it.text }}); amend the line first"
    }
    return null
}
