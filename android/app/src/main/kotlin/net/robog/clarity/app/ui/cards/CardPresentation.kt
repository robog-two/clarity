package net.robog.clarity.app.ui.cards

import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.HeldAction

/** Short status words on the right of a task row (`.rs`). */
fun taskStatusLabel(status: AgentTask.Status): String = when (status) {
    AgentTask.Status.WORKING -> "working"
    AgentTask.Status.DRAFTED -> "drafted"
    AgentTask.Status.WAITING_FOR_YOU -> "waits for you"
    AgentTask.Status.SCHEDULED -> "scheduled"
    AgentTask.Status.DONE -> "done"
    AgentTask.Status.FAILED -> "failed"
    AgentTask.Status.CANCELLED -> "cancelled"
}

/** Label of the confirming button on a proposal. */
fun approveLabel(kind: ActionKind): String = when (kind) {
    ActionKind.MOVE_EVENT -> "send update"
    ActionKind.SEND_EMAIL -> "send"
    ActionKind.RESPOND_TO_INVITE -> "send reply"
    ActionKind.CREATE_EVENT -> "add to calendar"
    ActionKind.CREATE_REMINDER -> "set reminder"
    ActionKind.SAVE_DRAFT -> "save draft"
    ActionKind.PAYMENT -> "review"
    ActionKind.USE_CREDENTIAL -> "approve"
    ActionKind.TWO_FACTOR_CODE -> "send code"
    else -> "ok"
}

/** What a proposal box shows in place of its buttons once it is no longer waiting. */
fun heldStatusLabel(status: HeldAction.Status): String? = when (status) {
    HeldAction.Status.HELD -> null
    HeldAction.Status.BLOCKED -> "blocked by your lines"
    HeldAction.Status.APPROVED, HeldAction.Status.EXECUTED -> "done"
    HeldAction.Status.DECLINED -> "not now"
    HeldAction.Status.KEPT -> "kept as is"
    HeldAction.Status.RULE_UPDATED -> "line updated"
    HeldAction.Status.FAILED -> "failed"
    HeldAction.Status.EXPIRED -> "expired"
}

/** Caption above a proposal: "needs your ok · sends Dana an update". */
fun proposalKicker(held: HeldAction): String = "needs your ok · ${held.action.summary}"
