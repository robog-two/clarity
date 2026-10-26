package net.robog.clarity.app.vault

import android.util.Log
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.CancellationException
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.net.VaultApi
import net.robog.clarity.app.ui.held.ApprovalGate
import net.robog.clarity.app.ui.held.ApprovalInput
import net.robog.clarity.app.ui.held.ApprovalResult
import net.robog.clarity.app.ui.held.needsPhoneApproval
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.shared.governance.ApprovalPayload
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.SignedDecision
import net.robog.clarity.shared.governance.payloadFor
import net.robog.clarity.shared.vault.ReleaseCode
import net.robog.clarity.shared.vault.ReleaseGrant
import net.robog.clarity.shared.vault.Validators
import net.robog.clarity.shared.vault.VaultNames

private val b64 = Base64.getUrlEncoder().withoutPadding()

/** Pure: the payload the phone signs for [decision] on [held]; the backend rebuilds the same text. */
fun approvalPayload(held: HeldAction, decision: String = SignedDecision.APPROVE): String = ApprovalPayload.payloadFor(held, decision)

/** Signs [payload] with an already-authorized [signature] and returns the base64url DER signature. */
fun signPayload(signature: Signature, payload: String): String {
    signature.update(payload.toByteArray(Charsets.UTF_8))
    return b64.encodeToString(signature.sign())
}

/**
 * Approves the actions only the phone may approve. A yes runs the system biometric prompt bound to the
 * approval key, signs the decision (the backend verifies it against the registered key) and:
 *  - for a credential use, also opens the sealed credential with the vault key and releases the
 *    fields for five minutes;
 *  - for a two-factor code, sends the code the person typed;
 *  - for any other "never alone" kind, sends the signed decision.
 * Secrets are never logged; nothing about them goes into a message.
 */
class PhoneApprover(
    private val api: VaultApi,
    private val keys: VaultKeys,
    private val auth: PhoneAuth,
    private val vault: VaultRepository,
) : ApprovalGate {

    override suspend fun authorize(held: HeldAction, input: ApprovalInput): ApprovalResult {
        if (!held.needsPhoneApproval) return ApprovalResult.Proceed
        return try {
            approveOnPhone(held, input)
        } catch (e: CancellationException) {
            throw e
        } catch (_: KeysInvalidatedException) {
            ApprovalResult.Denied("This phone’s vault keys were reset. Open Settings → Vault to set them up again.")
        } catch (_: NotAuthenticatedException) {
            ApprovalResult.Denied("That took too long. Try again.")
        } catch (e: Exception) {
            // Only the exception's type is logged and nothing is echoed: its message could carry secret material.
            runCatching { Log.w(TAG, "approval failed: ${e::class.java.name}") }
            ApprovalResult.Denied("Couldn’t approve that on this phone.")
        }
    }

    private suspend fun approveOnPhone(listed: HeldAction, input: ApprovalInput): ApprovalResult {
        if (keys.status() == VaultKeyStatus.INVALIDATED) throw KeysInvalidatedException(IllegalStateException("invalidated"))
        // Sign the server's current version of the action, not a cached copy.
        val held = when (val r = api.heldAction(listed.id)) {
            is ApiResult.Success -> r.value
            is ApiResult.Failure -> return ApprovalResult.Denied(r.error.approvalSentence())
        }
        if (held.status != HeldAction.Status.HELD) return ApprovalResult.Denied("That one is already settled.")
        val action = held.action

        val code = (action as? ProposedAction.AskCode)?.let {
            val typed = input.code?.trim().orEmpty()
            Validators.otp(typed)?.let { problem -> return ApprovalResult.Denied(problem) }
            typed
        }
        val envelope = (action as? ProposedAction.UseCredential)?.let {
            when (val r = api.envelope(it.credentialId)) {
                is ApiResult.Success -> r.value
                is ApiResult.Failure -> return ApprovalResult.Denied(r.error.approvalSentence())
            }
        }
        (vault.register() as? ApiResult.Failure)?.let { return ApprovalResult.Denied(it.error.approvalSentence()) }

        val signature = when (val s = sign(held)) {
            is Signed.Ok -> s.signature
            is Signed.No -> return ApprovalResult.Denied(s.message)
        }

        return when (action) {
            is ProposedAction.UseCredential -> {
                val allowed = action.fields.toSet()
                // The fingerprint that just authorized the signature also unlocks the vault key for a few
                // seconds, so one prompt is enough. A second prompt is only shown when that window has
                // passed: two system prompts back to back often had the second one cancelled at once.
                val opened = try {
                    VaultCrypto.open(checkNotNull(envelope)) { keys.agree(it) }
                } catch (_: NotAuthenticatedException) {
                    when (val o = open(envelope!!, action.site)) {
                        is Opened.Ok -> o.fields
                        is Opened.No -> return ApprovalResult.Denied(o.message)
                    }
                }
                val release = ReleaseGrant(opened.filterKeys { it in allowed }, VaultNames.MAX_GRANT_SECONDS, signature)
                api.releaseGrant(held.id, release).toResult()
            }
            is ProposedAction.AskCode ->
                api.releaseCode(held.id, ReleaseCode(checkNotNull(code), VaultNames.MAX_GRANT_SECONDS, signature)).toResult()
            else -> api.signedDecision(held.id, SignedDecision(SignedDecision.APPROVE, signature)).toResult()
        }
    }

    private sealed interface Signed {
        data class Ok(val signature: String) : Signed
        data class No(val message: String) : Signed
    }

    private suspend fun sign(held: HeldAction): Signed {
        val prompt = AuthPrompt("Approve with your fingerprint or face", held.action.summary)
        return when (val r = auth.authenticate(prompt, keys.approvalSignature())) {
            is AuthResult.Authenticated -> {
                val authorized = r.signature ?: return Signed.No("Couldn’t approve that on this phone.")
                Signed.Ok(signPayload(authorized, approvalPayload(held)))
            }
            AuthResult.Cancelled -> Signed.No("Not approved.")
            is AuthResult.Unavailable -> Signed.No(r.message)
            is AuthResult.Failed -> Signed.No(r.message)
        }
    }

    private sealed interface Opened {
        data class Ok(val fields: Map<String, String>) : Opened
        data class No(val message: String) : Opened
    }

    private suspend fun open(envelope: net.robog.clarity.shared.vault.VaultEnvelope, site: String): Opened {
        kotlinx.coroutines.delay(PROMPT_GAP_MS) // let the first system prompt finish closing
        val prompt = AuthPrompt("Open your saved login", "Release it to the agent for $site, for five minutes")
        return when (val r = auth.authenticate(prompt)) {
            is AuthResult.Authenticated -> Opened.Ok(VaultCrypto.open(envelope) { keys.agree(it) })
            AuthResult.Cancelled -> Opened.No("Not approved.")
            is AuthResult.Unavailable -> Opened.No(r.message)
            is AuthResult.Failed -> Opened.No(r.message)
        }
    }

    private companion object {
        const val TAG = "ClarityVault"
        const val PROMPT_GAP_MS = 400L
    }

    private fun <T> ApiResult<T>.toResult(): ApprovalResult = when (this) {
        is ApiResult.Success -> ApprovalResult.Done
        is ApiResult.Failure -> ApprovalResult.Denied(error.approvalSentence())
    }
}

/** The server's own words when it refused an approval, else the usual sentence. */
fun ApiError.approvalSentence(): String =
    (this as? ApiError.Http)?.problem?.detail
        ?: (this as? ApiError.Forbidden)?.problem?.detail
        ?: sentence()
