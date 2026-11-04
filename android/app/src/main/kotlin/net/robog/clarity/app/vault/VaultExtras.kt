package net.robog.clarity.app.vault

import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.held.HeldExtras
import net.robog.clarity.app.ui.held.needsPhoneApproval
import net.robog.clarity.shared.governance.ProposedAction

/** Pure: what approving a held action on the phone will do, in words. Null when it needs nothing special. */
fun phoneApprovalNote(action: ProposedAction, phoneApproval: Boolean): String? = when {
    action is ProposedAction.UseCredential ->
        "Approving unlocks your saved login with fingerprint or face. It is used on ${action.site} for this task, up to an hour."
    action is ProposedAction.AskCode ->
        "Type the code for ${action.site}, then confirm with fingerprint or face. It is used once."
    phoneApproval ->
        "Set to “never alone”. Your approval is signed on this phone with fingerprint or face."
    else -> null
}

/** Shown on the review screen under the draft or summary. */
val vaultExtras: HeldExtras = { held ->
    phoneApprovalNote(held.action, held.needsPhoneApproval)?.let { Hint(it) }
}
