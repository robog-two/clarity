package net.robog.clarity.app.ui.held

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import net.robog.clarity.shared.governance.HeldAction

/**
 * Extension points for held actions. Reviewing an action asks the gate before it is approved, and
 * the review screen shows [HeldExtras] under the draft. Both default to nothing; the credential
 * vault replaces them (a biometric prompt before approving, a credential form card) without
 * touching the review screens.
 */
fun interface ApprovalGate {
    /** True when [held] may be approved now (e.g. after a successful biometric prompt). */
    suspend fun authorize(held: HeldAction): Boolean
}

object AlwaysApprove : ApprovalGate {
    override suspend fun authorize(held: HeldAction): Boolean = true
}

/** Extra decision content a held action can bring along. */
typealias HeldExtras = @Composable ColumnScope.(HeldAction) -> Unit

/** Empty until a feature provides some; the review screen renders it under the draft. */
val LocalHeldExtras = staticCompositionLocalOf<HeldExtras> { {} }
