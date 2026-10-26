package net.robog.clarity.app.vault

import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.testutil.FakeVaultApi
import net.robog.clarity.app.testutil.FakeVaultKeys
import net.robog.clarity.app.testutil.ScriptedAuth
import net.robog.clarity.app.testutil.heldAction
import net.robog.clarity.app.testutil.newKeyPair
import net.robog.clarity.app.testutil.sendEmail
import net.robog.clarity.app.testutil.spki
import net.robog.clarity.app.ui.held.ApprovalInput
import net.robog.clarity.app.ui.held.ApprovalResult
import net.robog.clarity.app.ui.held.needsCode
import net.robog.clarity.app.ui.held.needsPhoneApproval
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.api.Problem
import net.robog.clarity.shared.governance.ApprovalPayload
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.SignedDecision
import net.robog.clarity.shared.governance.actionHash
import net.robog.clarity.shared.vault.CredentialId
import net.robog.clarity.shared.vault.DeviceVaultKey

class PhoneApproverTest {
    private val keys = FakeVaultKeys()
    private val api = FakeVaultApi()
    private val vault = DefaultVaultRepository(api, keys)

    private fun approver(auth: ScriptedAuth) = PhoneApprover(api, keys, auth, vault)

    private fun held(action: ProposedAction = sendEmail(), phone: Boolean = true, id: String = "h1", status: HeldAction.Status = HeldAction.Status.HELD): HeldAction {
        val h = heldAction(id = id, action = action, status = status)
        return h.copy(verdict = h.verdict.copy(phoneApproval = phone)).also { api.held[id] = it }
    }

    private fun verifies(signature: String, payload: String): Boolean =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(keys.approval.public)
            update(payload.toByteArray())
            verify(Base64.getUrlDecoder().decode(signature))
        }

    private val useCredential = ProposedAction.UseCredential("k2", "log in to the bank", CredentialId("c1"), "bank.example.com", listOf("username", "password"))
    private val askCode = ProposedAction.AskCode("k3", "type the code", "bank.example.com", "log in")

    @Test fun ordinaryActionsAreNotTheGatesBusiness() = runTest {
        val h = held(phone = false)
        val auth = ScriptedAuth()
        assertEquals(ApprovalResult.Proceed, approver(auth).authorize(h, ApprovalInput()))
        assertTrue(auth.prompts.isEmpty())
        assertTrue(api.signed.isEmpty())
    }

    @Test fun phoneApprovalComesFromTheServersVerdictOrTheVaultKinds() {
        assertTrue(held(sendEmail(), phone = true).needsPhoneApproval)
        assertFalse(held(sendEmail(), phone = false).needsPhoneApproval)
        assertTrue(held(useCredential, phone = false).needsPhoneApproval)
        assertTrue(held(askCode, phone = false).needsCode)
        assertFalse(held(sendEmail(), phone = true).needsCode)
    }

    @Test fun aYesOnANeverAloneActionIsSignedWithTheBoundPromptAndSentAsASignedDecision() = runTest {
        val h = held()
        val auth = ScriptedAuth()
        val result = approver(auth).authorize(h, ApprovalInput())
        assertEquals(ApprovalResult.Done, result)
        // one prompt, bound to the signing key
        assertEquals(listOf(true), auth.boundToSignature)
        val (id, decision) = api.signed.single()
        assertEquals(HeldActionId("h1"), id)
        assertEquals(SignedDecision.APPROVE, decision.decision)
        // the signature is over exactly the text the backend rebuilds
        val payload = "clarity-approval-v1|h1|${ApprovalPayload.actionHash(h.action)}|approve"
        assertEquals(payload, approvalPayload(h))
        assertTrue(verifies(decision.signature, payload))
        assertFalse(verifies(decision.signature, payload.replace("approve", "decline")))
        // and the keys were registered with the server first
        assertEquals(spki(keys.approval), api.registered.last().approvalKey)
        assertEquals(spki(keys.vault), api.registered.last().publicKey)
    }

    @Test fun cancellingTheBiometricApprovesNothing() = runTest {
        val h = held()
        val result = approver(ScriptedAuth(AuthResult.Cancelled)).authorize(h, ApprovalInput())
        assertEquals(ApprovalResult.Denied("Not approved."), result)
        assertTrue(api.signed.isEmpty())
    }

    @Test fun noScreenLockMeansNoApproval() = runTest {
        val result = approver(ScriptedAuth(AuthResult.Unavailable("Set a screen lock"))).authorize(held(), ApprovalInput())
        assertEquals(ApprovalResult.Denied("Set a screen lock"), result)
        assertTrue(api.signed.isEmpty())
    }

    @Test fun signsTheServersCurrentVersionNotACachedOne() = runTest {
        val cached = held()
        val edited = cached.copy(action = sendEmail("A different body"))
        api.held["h1"] = edited
        approver(ScriptedAuth()).authorize(cached, ApprovalInput())
        val signature = api.signed.single().second.signature
        assertTrue(verifies(signature, approvalPayload(edited)))
        assertFalse(verifies(signature, approvalPayload(cached)))
    }

    @Test fun anAlreadySettledActionIsNotSigned() = runTest {
        val h = held(status = HeldAction.Status.DECLINED)
        val auth = ScriptedAuth()
        assertIs<ApprovalResult.Denied>(approver(auth).authorize(h, ApprovalInput()))
        assertTrue(auth.prompts.isEmpty())
    }

    @Test fun theServersRefusalIsShownInItsOwnWords() = runTest {
        val h = held()
        api.failWith = ApiError.Forbidden(Problem(title = "Approval not verified", status = 403, detail = "the signature does not match this action"))
        val result = approver(ScriptedAuth()).authorize(h, ApprovalInput())
        assertEquals(ApprovalResult.Denied("the signature does not match this action"), result)
    }

    @Test fun invalidatedKeysSayWhatToDo() = runTest {
        keys.status = VaultKeyStatus.INVALIDATED
        val auth = ScriptedAuth()
        val result = approver(auth).authorize(held(), ApprovalInput())
        assertIs<ApprovalResult.Denied>(result)
        assertTrue("Settings" in result.message)
        assertTrue(auth.prompts.isEmpty())
    }

    @Test fun releasingACredentialOpensTheEnvelopeAndSendsOnlyTheRequestedFields() = runTest {
        val h = held(useCredential, phone = false, id = "h2")
        val device = DeviceId("me")
        val fields = mapOf("username" to "dana@example.com", "password" to "hunter2!-Zebra", "pin" to "1234")
        api.envelopes["c1"] = VaultCrypto.seal(device, spki(keys.vault), "req-1", fields)
        val auth = ScriptedAuth()
        val result = approver(auth).authorize(h, ApprovalInput())
        assertEquals(ApprovalResult.Done, result)
        // one prompt: the fingerprint that authorizes the signature also opens the vault key
        assertEquals(listOf(true), auth.boundToSignature)
        val (id, release) = api.grants.single()
        assertEquals(HeldActionId("h2"), id)
        assertEquals(mapOf("username" to "dana@example.com", "password" to "hunter2!-Zebra"), release.fields)
        assertEquals(300, release.ttlSeconds)
        assertTrue(verifies(checkNotNull(release.signature), approvalPayload(h)))
        assertTrue(api.signed.isEmpty())
    }

    @Test fun whenTheUnlockWindowHasPassedASecondPromptOpensTheVaultKey() = runTest {
        val h = held(useCredential, phone = false, id = "h2")
        api.envelopes["c1"] = VaultCrypto.seal(DeviceId("me"), spki(keys.vault), "req-1", mapOf("username" to "a", "password" to "b"))
        keys.staleAgreements = 1
        val auth = ScriptedAuth()
        assertEquals(ApprovalResult.Done, approver(auth).authorize(h, ApprovalInput()))
        assertEquals(listOf(true, false), auth.boundToSignature)
        assertEquals(mapOf("username" to "a", "password" to "b"), api.grants.single().second.fields)
    }

    @Test fun cancellingTheSecondPromptReleasesNothing() = runTest {
        val h = held(useCredential, phone = false, id = "h2")
        keys.staleAgreements = 1
        api.envelopes["c1"] = VaultCrypto.seal(DeviceId("me"), spki(keys.vault), "req-1", mapOf("username" to "a", "password" to "b"))
        val result = approver(ScriptedAuth(null, AuthResult.Cancelled)).authorize(h, ApprovalInput())
        assertEquals(ApprovalResult.Denied("Not approved."), result)
        assertTrue(api.grants.isEmpty())
    }

    @Test fun aCredentialSealedToAnotherKeyIsNotReleased() = runTest {
        val h = held(useCredential, phone = false, id = "h2")
        api.envelopes["c1"] = VaultCrypto.seal(DeviceId("me"), spki(newKeyPair()), "req-1", mapOf("username" to "a", "password" to "b"))
        assertIs<ApprovalResult.Denied>(approver(ScriptedAuth()).authorize(h, ApprovalInput()))
        assertTrue(api.grants.isEmpty())
    }

    @Test fun aTwoFactorCodeIsSentAfterTheYesAndNeverBeforeItIsValid() = runTest {
        val h = held(askCode, phone = false, id = "h3")
        val auth = ScriptedAuth()
        val tooShort = approver(auth).authorize(h, ApprovalInput(code = "1"))
        assertIs<ApprovalResult.Denied>(tooShort)
        assertTrue(auth.prompts.isEmpty())
        assertTrue(api.codes.isEmpty())

        assertEquals(ApprovalResult.Done, approver(auth).authorize(h, ApprovalInput(code = " 482913 ")))
        val (id, release) = api.codes.single()
        assertEquals(HeldActionId("h3"), id)
        assertEquals("482913", release.code)
        assertTrue(verifies(checkNotNull(release.signature), approvalPayload(h)))
    }

    @Test fun deviceKeyListIsNotNeededToApprove() = runTest {
        api.devices = listOf(DeviceVaultKey(DeviceId("me"), spki(keys.vault)))
        assertEquals(ApprovalResult.Done, approver(ScriptedAuth()).authorize(held(), ApprovalInput()))
    }
}
