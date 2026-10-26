package net.robog.clarity.app.ui.held

import kotlinx.datetime.TimeZone
import net.robog.clarity.app.ui.call.formatClock
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.EmailDraft
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldDecision
import net.robog.clarity.shared.governance.ProposedAction

/** The email inside a draft-or-send action; null for every other kind. */
fun emailOf(action: ProposedAction): EmailDraft? = when (action) {
    is ProposedAction.SaveDraft -> action.email
    is ProposedAction.SendEmail -> action.email
    else -> null
}

/** [action] with its email body replaced (other kinds are returned unchanged). */
fun withBody(action: ProposedAction, body: String): ProposedAction = when (action) {
    is ProposedAction.SaveDraft -> action.copy(email = action.email.copy(body = body))
    is ProposedAction.SendEmail -> action.copy(email = action.email.copy(body = body))
    else -> action
}

/** The approval for [held] with the text the person has in the field: edited only if they changed it. */
fun approvalFor(held: HeldAction, body: String): HeldDecision.Approve {
    val original = emailOf(held.action)?.body
    return if (original == null || body == original) HeldDecision.Approve() else HeldDecision.Approve(withBody(held.action, body))
}

/** "Dana" for a draft addressed to Dana <dana@example.com>; the address's local part if there is no name. */
fun recipientName(email: EmailDraft): String? {
    val first = email.to.firstOrNull() ?: return null
    return first.displayName?.takeIf { it.isNotBlank() }?.substringBefore(' ') ?: first.value.substringBefore('@')
}

/** The line shown once an approved action ran: "Sent to Dana." */
fun executedToast(held: HeldAction): String {
    val email = emailOf(held.action)
    val who = email?.let(::recipientName)
    return when (held.action.kind) {
        ActionKind.SEND_EMAIL -> if (who != null) "Sent to $who." else "Sent."
        ActionKind.SAVE_DRAFT -> if (who != null) "Draft for $who saved." else "Draft saved."
        ActionKind.MOVE_EVENT, ActionKind.CREATE_EVENT -> "Calendar updated."
        ActionKind.RESPOND_TO_INVITE -> "Reply sent."
        else -> "Done."
    }
}

/** Toasts for held actions that just reached a final state, comparing the previous and next lists. */
fun heldOutcomeToasts(previous: List<HeldAction>, next: List<HeldAction>): List<String> {
    val before = previous.associateBy { it.id }
    return next.mapNotNull { now ->
        val was = before[now.id] ?: return@mapNotNull null
        when {
            was.status == now.status -> null
            now.status == HeldAction.Status.EXECUTED -> executedToast(now)
            now.status == HeldAction.Status.FAILED -> "Couldn’t finish: ${now.action.summary}"
            else -> null
        }
    }
}

/** Held rows are "when · what kind", e.g. "8:12 · send email". */
fun heldWhen(held: HeldAction, zone: TimeZone): String =
    "${formatClock(held.createdAt, zone)} · ${held.action.kind.name.lowercase().replace('_', ' ')}"

/** The right-hand word of a held row: an arrow while it waits, else how it ended. */
fun heldRowStatus(status: HeldAction.Status): String = when (status) {
    HeldAction.Status.HELD, HeldAction.Status.BLOCKED -> "→"
    HeldAction.Status.APPROVED, HeldAction.Status.EXECUTED -> "sent"
    HeldAction.Status.DECLINED -> "not now"
    HeldAction.Status.KEPT -> "kept"
    HeldAction.Status.RULE_UPDATED -> "rule updated"
    HeldAction.Status.FAILED -> "failed"
    HeldAction.Status.EXPIRED -> "expired"
}

val HeldAction.waiting: Boolean get() = status == HeldAction.Status.HELD || status == HeldAction.Status.BLOCKED

/** Newest first; the ones still waiting before the ones that are settled. */
fun orderedHeld(held: List<HeldAction>): List<HeldAction> =
    held.sortedWith(compareByDescending<HeldAction> { it.waiting }.thenByDescending { it.createdAt })

/** "checked against your lines · 2 apply" / "· none apply". */
fun checkedLine(held: HeldAction): String {
    val n = held.verdict.lines.size
    return "checked against your lines · " + if (n == 0) "none apply" else "$n apply"
}
