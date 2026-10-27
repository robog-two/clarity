package net.robog.clarity.backend.governance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import net.robog.clarity.backend.vault.slotOf
import net.robog.clarity.backend.vault.staleRequests
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.Amendment
import net.robog.clarity.shared.governance.AmendmentId
import net.robog.clarity.shared.governance.AutonomyChange
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.LineId
import net.robog.clarity.shared.vault.CredentialId
import net.robog.clarity.shared.vault.CredentialKind
import net.robog.clarity.shared.vault.CredentialMeta
import net.robog.clarity.shared.vault.CredentialRequest
import net.robog.clarity.shared.vault.CredentialRequestId
import net.robog.clarity.shared.vault.CredentialRequestStatus
import net.robog.clarity.shared.vault.FieldSpec
import net.robog.clarity.shared.vault.FieldType

private fun amendment(
    id: String,
    text: String,
    status: Amendment.Status = Amendment.Status.PROPOSED,
    lineId: String? = null,
    old: String? = null,
    held: String? = "h1",
    autonomy: AutonomyChange? = null,
) = Amendment(
    AmendmentId(id), lineId?.let(::LineId), old, line(lineId ?: "amended-$id", text), "r",
    held?.let(::HeldActionId), status, autonomy,
)

private fun facts(a: Amendment, at: Instant, sessionLive: Boolean? = null) = AmendmentFacts(a, at, sessionLive)

private val NOW = T0 + 1.hours

private fun request(id: String, name: String, site: String, at: Instant, status: CredentialRequestStatus = CredentialRequestStatus.OPEN, kind: CredentialKind = CredentialKind.LOGIN) =
    CredentialRequest(CredentialRequestId(id), name, kind, listOf(FieldSpec("password", "Password", FieldType.PASSWORD)), site, "p", status, createdAt = at)

private fun credential(name: String, site: String, at: Instant, kind: CredentialKind = CredentialKind.LOGIN) =
    CredentialMeta(CredentialId("c-$name"), name, kind, site, "p", emptyList(), createdAt = at)

class StaleProposalsPureTest : FunSpec({
    test("rule text is compared without case, punctuation, quote styles or spacing") {
        normalizeRuleText("Clicking “Next” is fine;  don’t ask.") shouldBe normalizeRuleText("clicking \"next\" is fine - dont ask")
        normalizeRuleText("Creating events is okay.") shouldBe "creating events is okay"
        (normalizeRuleText("Creating events is okay") == normalizeRuleText("Creating events is not okay")) shouldBe false
    }

    test("an equal proposal still waiting: only the newest of a duplicate pair stays") {
        val a = amendment("a", "Browsing is fine.")
        val b = amendment("b", "browsing is FINE")
        val closings = staleAmendments(listOf(facts(a, T0), facts(b, T0 + 1.minutes)), constitution(), NOW)
        closings.map { it.id.value to it.status } shouldBe listOf("a" to ClosedStatus.SUPERSEDED)
    }

    test("different held actions, lines or tier changes are different proposals") {
        val a = amendment("a", "Browsing is fine.", held = "h1")
        val b = amendment("b", "Browsing is fine.", held = "h2")
        val c = amendment("c", "Browsing is fine.", held = "h1", autonomy = AutonomyChange(ActionKind.EXTERNAL_TOOL, AutonomyTier.DO_THEN_TELL))
        staleAmendments(listOf(facts(a, T0), facts(b, T0), facts(c, T0)), constitution(), NOW).shouldBeEmpty()
    }

    test("a proposal whose text is now a line of the constitution is superseded") {
        val a = amendment("a", "Creating calendar events is okay: go ahead and add them, then tell me.")
        val c = constitution(lines = listOf(line("amended-x", "Creating calendar events is okay: go ahead and add them, then tell me.")))
        staleAmendments(listOf(facts(a, T0)), c, NOW).single().reason shouldContain "already in the constitution"
    }

    test("a tier change that is not in place yet keeps the proposal, even when the text is") {
        val change = AutonomyChange(ActionKind.CREATE_EVENT, AutonomyTier.DO_THEN_TELL)
        val a = amendment("a", "Events are fine.", autonomy = change)
        val without = constitution(lines = listOf(line("x", "Events are fine.")), autonomy = mapOf(ActionKind.CREATE_EVENT to AutonomyTier.ASK_FIRST))
        staleAmendments(listOf(facts(a, T0)), without, NOW).shouldBeEmpty()
        val with = without.copy(autonomy = mapOf(ActionKind.CREATE_EVENT to AutonomyTier.DO_THEN_TELL))
        staleAmendments(listOf(facts(a, T0)), with, NOW).single().status shouldBe ClosedStatus.SUPERSEDED
    }

    test("a proposal for a line that changed or went away since is superseded") {
        val a = amendment("a", "Family first, except release days", lineId = "v1", old = "Family first")
        staleAmendments(listOf(facts(a, T0)), constitution(lines = listOf(line("v1", "Family first."))), NOW).shouldBeEmpty()
        staleAmendments(listOf(facts(a, T0)), constitution(lines = listOf(line("v1", "Work first"))), NOW).single().reason shouldContain "changed since"
        staleAmendments(listOf(facts(a, T0)), constitution(lines = emptyList()), NOW).single().reason shouldContain "removed"
    }

    test("a later saved amendment with the same text supersedes an older proposal, an earlier one does not") {
        val proposed = amendment("p", "Ask before buying.")
        val savedLater = amendment("s", "Ask before buying", status = Amendment.Status.SAVED)
        staleAmendments(listOf(facts(proposed, T0), facts(savedLater, T0 + 1.minutes)), null, NOW).single().reason shouldContain "later change was saved"
        staleAmendments(listOf(facts(proposed, T0), facts(savedLater, T0 - 1.minutes)), null, NOW).shouldBeEmpty()
    }

    test("a proposal whose conversation ended expires; one in a live conversation or without one stays") {
        val a = amendment("a", "One")
        val b = amendment("b", "Two")
        val c = amendment("c", "Three")
        val closings = staleAmendments(listOf(facts(a, T0, sessionLive = false), facts(b, T0, sessionLive = true), facts(c, T0, sessionLive = null)), constitution(), NOW)
        closings.map { it.id.value to it.status } shouldBe listOf("a" to ClosedStatus.EXPIRED)
    }

    test("a proposal nobody acted on for a week expires") {
        val a = amendment("a", "One")
        staleAmendments(listOf(facts(a, T0, sessionLive = true)), constitution(), T0 + 6.days).shouldBeEmpty()
        staleAmendments(listOf(facts(a, T0, sessionLive = true)), constitution(), T0 + 7.days + 1.minutes).single().status shouldBe ClosedStatus.EXPIRED
    }

    test("saved and discarded amendments are never touched") {
        val saved = amendment("s", "One", status = Amendment.Status.SAVED)
        val discarded = amendment("d", "Two", status = Amendment.Status.DISCARDED)
        staleAmendments(listOf(facts(saved, T0, false), facts(discarded, T0 - 30.days, false)), constitution(), NOW).shouldBeEmpty()
    }

    test("credential requests: same site means the registrable domain, per kind") {
        slotOf("https://www.ulta.com/login", CredentialKind.LOGIN) shouldBe slotOf("ulta.com", CredentialKind.LOGIN)
        (slotOf("ulta.com", CredentialKind.LOGIN) == slotOf("ulta.com", CredentialKind.PAYMENT_CARD)) shouldBe false
    }

    test("credential requests: a credential saved later supersedes; a newer open one supersedes; old ones expire") {
        val v2 = request("v2", "ulta_login_v2", "ulta.com", T0)
        val saved = credential("ulta_login", "www.ulta.com", T0 + 2.hours)
        staleRequests(listOf(v2), listOf(saved), NOW + 2.hours).single().let {
            it.status shouldBe ClosedStatus.SUPERSEDED
            it.reason shouldContain "ulta_login"
        }
        // a credential saved before the request (a re-entry with replace) does not close it
        staleRequests(listOf(v2), listOf(credential("ulta_login", "ulta.com", T0 - 1.hours)), NOW).shouldBeEmpty()
        // another kind on the same site does not close it
        staleRequests(listOf(v2), listOf(credential("ulta_card", "ulta.com", T0 + 1.hours, CredentialKind.PAYMENT_CARD)), NOW).shouldBeEmpty()

        val newer = request("n", "ulta_login", "ulta.com", T0 + 1.minutes)
        staleRequests(listOf(v2, newer), emptyList(), NOW).map { it.id.value } shouldBe listOf("v2")

        val fulfilledLater = request("f", "ulta_login", "ulta.com", T0 + 1.minutes, CredentialRequestStatus.FULFILLED)
        staleRequests(listOf(v2, fulfilledLater), emptyList(), NOW).single().reason shouldContain "ulta_login"

        staleRequests(listOf(v2), emptyList(), T0 + 7.days + 1.minutes).single().status shouldBe ClosedStatus.EXPIRED
        staleRequests(listOf(request("x", "x", "ulta.com", T0, CredentialRequestStatus.CANCELLED)), emptyList(), T0 + 30.days).shouldBeEmpty()
    }
})
