package net.robog.clarity.app.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.testutil.FakeHeldRepo
import net.robog.clarity.app.testutil.FakeVault
import net.robog.clarity.app.testutil.RecordingGate
import net.robog.clarity.app.testutil.ScreenFeed
import net.robog.clarity.app.testutil.ScreenSpeech
import net.robog.clarity.app.testutil.ScriptedSessions
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.heldAction
import net.robog.clarity.app.testutil.loginFields
import net.robog.clarity.app.testutil.sendEmail
import net.robog.clarity.app.testutil.withMain
import net.robog.clarity.app.ui.held.ApprovalInput
import net.robog.clarity.app.ui.held.ApprovalResult
import net.robog.clarity.app.ui.held.DraftReviewViewModel
import net.robog.clarity.app.ui.held.heldRows
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.HeldDecision
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.vault.CredentialId
import net.robog.clarity.shared.vault.CredentialKind
import net.robog.clarity.shared.vault.CredentialMeta

class VaultScreenTest {
    private fun meta(id: String, kind: CredentialKind, hints: Map<String, String>) =
        CredentialMeta(CredentialId(id), "name_$id", kind, "bank.example.com", "x", loginFields, hints, T0)

    @Test fun rowsShowOnlyMetadata() {
        val card = credentialRow(meta("c1", CredentialKind.PAYMENT_CARD, mapOf("brand" to "visa", "last4" to "4242", "expiry_month" to "3")))
        assertEquals("Visa ·· 4242 expires 3", card.detail)
        assertEquals("card", card.kind)
        assertEquals("dana", credentialRow(meta("c2", CredentialKind.LOGIN, mapOf("username" to "dana"))).detail)
        assertEquals("", credentialRow(meta("c3", CredentialKind.NOTE, emptyMap())).detail)
    }

    @Test fun theVaultScreenListsRegistersDeletesAndRekeys() = withMain {
        val vault = FakeVault(list = ApiResult.Success(listOf(meta("c1", CredentialKind.LOGIN, mapOf("username" to "dana")))))
        val vm = VaultViewModel(vault)
        assertEquals(1, vm.state.value.rows.size)
        assertEquals(1, vault.registers)
        assertEquals(VaultKeyStatus.READY, vm.state.value.keys)

        vm.delete(CredentialId("c1"))
        assertEquals(listOf(CredentialId("c1")), vault.deleted)
        assertTrue(vm.state.value.rows.isEmpty())
    }

    @Test fun invalidatedKeysAreOfferedAResetAndTheOldEntriesAreFlagged() = withMain {
        val vault = FakeVault(status = VaultKeyStatus.INVALIDATED)
        val vm = VaultViewModel(vault)
        assertEquals(VaultKeyStatus.INVALIDATED, vm.state.value.keys)
        assertEquals(0, vault.registers) // never register dead keys
        vm.rekey()
        assertEquals(1, vault.rekeys)
        assertEquals(VaultKeyStatus.READY, vm.state.value.keys)
        assertNotNull(vm.state.value.notice)
    }

    @Test fun aServerErrorIsShownNotThrown() = withMain {
        val vm = VaultViewModel(FakeVault(list = ApiResult.Failure(ApiError.Unreachable("x"))))
        assertNotNull(vm.state.value.message)
        assertFalse(vm.state.value.loading)
    }

    // --- the review screen with the phone gate ---

    private class Rig(held: HeldAction) {
        val feed = ScreenFeed().also { it.heldFlow.value = listOf(held) }
        val gate = RecordingGate()
        val vm = DraftReviewViewModel(feed, FakeHeldRepo(mapOf(held.id.value to held)), ScriptedSessions(), gate, ScreenSpeech())
        init { vm.load(held.id) }
    }

    @Test fun aSignedApprovalNeverUsesTheGenericDecisionRoute() = withMain {
        val r = Rig(heldAction())
        r.gate.result = ApprovalResult.Done
        r.vm.approve()
        assertTrue(r.feed.decisions.isEmpty())
        assertTrue(r.vm.state.value.finished)
    }

    @Test fun aRefusedApprovalShowsTheReasonAndKeepsTheScreenOpen() = withMain {
        val r = Rig(heldAction())
        r.gate.result = ApprovalResult.Denied("Not approved.")
        r.vm.approve()
        assertTrue(r.feed.decisions.isEmpty())
        assertFalse(r.vm.state.value.finished)
        assertEquals("Not approved.", r.vm.state.value.message)
    }

    @Test fun theTypedCodeReachesTheGateAndIsClearedAfterwards() = withMain {
        val ask = heldAction(action = ProposedAction.AskCode("k", "type the code", "bank.example.com", "log in"))
        val r = Rig(ask)
        r.vm.editCode("482913")
        r.gate.result = ApprovalResult.Done
        r.vm.approve()
        assertEquals("482913", r.gate.inputs.single().code)
        assertEquals("", r.vm.state.value.code)
        assertTrue(r.vm.state.value.finished)
    }

    @Test fun phoneRowsOpenTheReviewScreen() {
        val plain = heldAction(id = "a", action = sendEmail())
        val phone = heldAction(id = "b", action = sendEmail()).let { it.copy(verdict = it.verdict.copy(phoneApproval = true)) }
        val vault = heldAction(id = "c", action = ProposedAction.AskCode("k", "code", "bank.example.com", "log in"))
        val rows = heldRows(listOf(plain, phone, vault), TimeZone.UTC).associateBy { it.id }
        assertFalse(rows.getValue(HeldActionId("a")).phone)
        assertTrue(rows.getValue(HeldActionId("b")).phone)
        assertTrue(rows.getValue(HeldActionId("c")).phone)
    }

    @Test fun approvalNotesSaySoPlainly() {
        val use = ProposedAction.UseCredential("k", "log in", CredentialId("c1"), "bank.example.com", listOf("username", "password"))
        assertTrue("five minutes" in checkNotNull(phoneApprovalNote(use, false)))
        assertTrue("never alone" in checkNotNull(phoneApprovalNote(sendEmail(), true)))
        assertNull(phoneApprovalNote(sendEmail(), false))
    }

    @Test fun declineStaysAPlainDecision() = withMain {
        val r = Rig(heldAction().let { it.copy(verdict = it.verdict.copy(phoneApproval = true)) })
        r.vm.decline()
        assertEquals(listOf<Pair<HeldActionId, HeldDecision>>(HeldActionId("h1") to HeldDecision.Decline), r.feed.decisions)
    }
}
