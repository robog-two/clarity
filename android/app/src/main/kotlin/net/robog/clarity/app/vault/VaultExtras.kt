package net.robog.clarity.app.vault

import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.held.HeldExtras
import net.robog.clarity.app.ui.held.needsPhoneApproval
import net.robog.clarity.shared.governance.ProposedAction

/** Pure: what approving a held action on the phone will do, in words. Null when it needs nothing special. */
fun phoneApprovalNote(action: ProposedAction, phoneApproval: Boolean): String? = when {
    action is ProposedAction.UseCredential ->
        "Approving opens your saved login with your fingerprint or face and lets me use ${action.fields.joinToString().ifEmpty { "it" }} " +
            "on ${action.site} for five minutes. I never see the values."
    action is ProposedAction.AskCode ->
        "Type the code you got for ${action.site}, then confirm with your fingerprint or face. It is used once and not kept."
    phoneApproval ->
        "You set this to “never alone”, so your yes is signed on this phone with your fingerprint or face, and your server checks it."
    else -> null
}

/** Shown on the review screen under the draft or summary. */
val vaultExtras: HeldExtras = { held ->
    phoneApprovalNote(held.action, held.needsPhoneApproval)?.let {
        Kicker("needs your phone")
        Hint(it)
    }
}
