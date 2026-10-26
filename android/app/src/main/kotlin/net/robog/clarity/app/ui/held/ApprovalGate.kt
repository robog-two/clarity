package net.robog.clarity.app.ui.held

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.HeldAction

/** What the person typed for an approval that needs more than a yes (a two-factor code). */
data class ApprovalInput(val code: String? = null)

/** What the gate decided about an approve tap. */
sealed interface ApprovalResult {
    /** Nothing special: carry on with the generic decision route. */
    data object Proceed : ApprovalResult

    /** The gate already got the server to approve it (a signed decision or a vault release). */
    data object Done : ApprovalResult

    /** Not approved, with a sentence for the person ("Not approved." when they cancelled). */
    data class Denied(val message: String) : ApprovalResult
}

/**
 * Extension point for approvals. Reviewing an action asks the gate before it is approved. Actions the
 * person set to "never alone", and every vault action, are approved on the phone with a biometric
 * yes that the server verifies; for those the gate does the whole approval and answers [ApprovalResult.Done].
 */
fun interface ApprovalGate {
    suspend fun authorize(held: HeldAction, input: ApprovalInput): ApprovalResult
}

object AlwaysApprove : ApprovalGate {
    override suspend fun authorize(held: HeldAction, input: ApprovalInput): ApprovalResult = ApprovalResult.Proceed
}

/** Vault actions are always approved on the phone, whatever the guard said. */
private val VAULT_KINDS = setOf(ActionKind.USE_CREDENTIAL, ActionKind.TWO_FACTOR_CODE)

/** True when only a yes signed on this phone approves [held] (the server decided this from the person's interview). */
val HeldAction.needsPhoneApproval: Boolean get() = verdict.phoneApproval || action.kind in VAULT_KINDS

/** True when the person must type something before the yes (the two-factor code). */
val HeldAction.needsCode: Boolean get() = action.kind == ActionKind.TWO_FACTOR_CODE

/** Extra decision content a held action can bring along. */
typealias HeldExtras = @Composable ColumnScope.(HeldAction) -> Unit

/** What the review screen shows under the draft; the vault adds the "needs your phone" notes. */
val LocalHeldExtras = staticCompositionLocalOf<HeldExtras> { {} }
