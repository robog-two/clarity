package net.robog.clarity.shared.governance

import kotlinx.serialization.Serializable
import net.robog.clarity.shared.ClarityJson

/**
 * `POST /api/v1/held/{id}/signed-decision`: a decision signed on the phone with the device's
 * approval key (Keystore, biometric or device credential for every use). Required for held actions
 * whose [GuardVerdict.phoneApproval] is set. [decision] is [APPROVE] or [DECLINE]; [signature] is the
 * base64url (no padding) DER ECDSA signature (`SHA256withECDSA`) of [ApprovalPayload.payload].
 */
@Serializable
data class SignedDecision(val decision: String, val signature: String) {
    companion object {
        const val APPROVE = "approve"
        const val DECLINE = "decline"
    }
}

/** The exact text the phone signs and the backend verifies. */
object ApprovalPayload {
    const val PREFIX = "clarity-approval-v1"

    /** The action as canonical JSON (the shared serializer, declaration order, no edits). Hash this with SHA-256. */
    fun canonicalJson(action: ProposedAction): String = ClarityJson.encodeToString(ProposedAction.serializer(), action)

    /** `clarity-approval-v1|<heldId>|<lowercase hex sha256 of canonicalJson>|<decision>`. */
    fun payload(heldId: HeldActionId, actionSha256Hex: String, decision: String): String =
        "$PREFIX|${heldId.value}|$actionSha256Hex|$decision"
}
