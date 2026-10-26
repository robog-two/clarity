package net.robog.clarity.backend.governance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Decision
import net.robog.clarity.shared.governance.GuardVerdict
import net.robog.clarity.shared.governance.LineId
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.noun.Handle

private fun expected(kind: ActionKind, tier: AutonomyTier, untrusted: Boolean, external: Boolean): Decision = when {
    tier == AutonomyTier.NEVER -> Decision.DENY // the person's own hard line, for any kind
    kind in VAULT_KINDS -> Decision.ASK // vault releases are never done alone, whatever the tier
    tier != AutonomyTier.DO_THEN_TELL -> Decision.ASK
    untrusted && external -> Decision.ASK
    else -> Decision.ALLOW
}

/** Kinds whose benign [sample] reaches other people or outside systems. */
private val externalSamples = setOf(ActionKind.SEND_EMAIL, ActionKind.RESPOND_TO_INVITE, ActionKind.EXTERNAL_TOOL, ActionKind.PAYMENT)

class GuardRulesTest : FunSpec({

    context("every kind x tier x provenance") {
        for (kind in ActionKind.entries) for (tier in AutonomyTier.entries) for (untrusted in listOf(false, true)) {
            test("$kind / $tier / ${if (untrusted) "untrusted" else "trusted"}") {
                val verdict = deterministicVerdict(
                    sample(kind), constitution(tier = tier), if (untrusted) net.robog.clarity.backend.governance.untrusted else trusted(),
                )
                verdict.decision shouldBe expected(kind, tier, untrusted, kind in externalSamples)
            }
        }
    }

    test("kinds the person did not configure default to ask first") {
        deterministicVerdict(sample(ActionKind.CREATE_REMINDER), constitution(), trusted()).decision shouldBe Decision.ASK
    }

    test("boundaries come from the interview: payments follow the person's tier like any other kind") {
        fun pay(tier: AutonomyTier?) = deterministicVerdict(
            sample(ActionKind.PAYMENT),
            constitution(autonomy = tier?.let { mapOf(ActionKind.PAYMENT to it) } ?: emptyMap()),
            trusted(requestedByUser = true),
        )
        pay(null).decision shouldBe Decision.ASK // not discussed: ask first
        pay(AutonomyTier.NEVER_ALONE).decision shouldBe Decision.ASK
        pay(AutonomyTier.NEVER).also { it.decision shouldBe Decision.DENY; it.rationale shouldContain "never" }
        // someone may also rule out sending email entirely
        deterministicVerdict(sample(ActionKind.SEND_EMAIL), constitution(autonomy = mapOf(ActionKind.SEND_EMAIL to AutonomyTier.NEVER)), trusted())
            .decision shouldBe Decision.DENY
    }

    test("vault kinds always ask, even when the person lets everything run alone, and say why") {
        for (kind in VAULT_KINDS) {
            val v = deterministicVerdict(sample(kind), constitution(tier = AutonomyTier.DO_THEN_TELL), trusted(requestedByUser = true))
            v.decision shouldBe Decision.ASK
            v.rationale shouldContain "phone"
        }
    }

    test("using a payment card credential is asked about, not denied: the person can still approve on the phone") {
        val use = (sample(ActionKind.USE_CREDENTIAL) as ProposedAction.UseCredential).copy(summary = "Use card to pay at checkout")
        effectiveKind(use) shouldBe ActionKind.USE_CREDENTIAL
        deterministicVerdict(use, constitution(), trusted()).decision shouldBe Decision.ASK
    }

    test("an external tool is recognised as a payment by its name, input or summary") {
        fun tool(server: String, tool: String, summary: String = "do it") =
            ProposedAction.ExternalTool("k", summary, server, tool, JsonObject(emptyMap()))
        effectiveKind(tool("shop", "place_order")) shouldBe ActionKind.PAYMENT
        effectiveKind(tool("shop", "checkout")) shouldBe ActionKind.PAYMENT
        effectiveKind(tool("bank", "pay-bill")) shouldBe ActionKind.PAYMENT
        effectiveKind(tool("browser", "click", "Buy the flight")) shouldBe ActionKind.PAYMENT
        effectiveKind(tool("browser", "click", "open the docs")) shouldBe ActionKind.EXTERNAL_TOOL
    }

    test("an email to several people is asked about even when emailing alone is allowed") {
        val c = constitution(tier = AutonomyTier.DO_THEN_TELL)
        val one = ProposedAction.SendEmail("k", "s", email("a@example.com"))
        val two = ProposedAction.SendEmail("k", "s", email("a@example.com", "b@example.com"))
        val dup = ProposedAction.SendEmail("k", "s", email("a@example.com", " A@Example.com "))
        deterministicVerdict(one, c, trusted()).decision shouldBe Decision.ALLOW
        deterministicVerdict(dup, c, trusted()).decision shouldBe Decision.ALLOW
        deterministicVerdict(two, c, trusted()).also {
            it.decision shouldBe Decision.ASK
            it.rationale shouldContain "2 people"
        }
    }

    test("moving an event and notifying attendees is asked about") {
        val c = constitution(tier = AutonomyTier.DO_THEN_TELL)
        val move = sample(ActionKind.MOVE_EVENT) as ProposedAction.MoveEvent
        deterministicVerdict(move, c, trusted()).decision shouldBe Decision.ALLOW
        deterministicVerdict(move.copy(notifyAttendees = true), c, trusted()).decision shouldBe Decision.ASK
    }

    test("protected time: events with attendees ask, private holds do not, midnight-crossing blocks work") {
        val night = block("sleep", setOf(1, 2, 3, 4, 5, 6, 7), "22:00", "07:00")
        val c = constitution(tier = AutonomyTier.DO_THEN_TELL, protectedTimes = listOf(night))
        val start = kotlin.time.Instant.parse("2026-03-05T06:00:00Z") // Thursday 06:00, inside Wednesday's block
        val meeting = ProposedAction.CreateEvent("k", "s", null, "Call", start, start + 1.hours, listOf(Handle.email("a@example.com")), hold = false)
        val hold = ProposedAction.CreateEvent("k", "s", null, "Focus", start, start + 1.hours)
        deterministicVerdict(meeting, c, trusted()).also {
            it.decision shouldBe Decision.ASK
            it.rationale shouldContain "sleep"
        }
        deterministicVerdict(hold, c, trusted()).decision shouldBe Decision.ALLOW
        val afternoon = meeting.copy(start = start + 8.hours, end = start + 9.hours)
        deterministicVerdict(afternoon, c, trusted()).decision shouldBe Decision.ALLOW
    }

    test("never-store topics make anything that mentions them ask") {
        val c = constitution(tier = AutonomyTier.DO_THEN_TELL, neverStore = listOf("Health"))
        val reminder = ProposedAction.CreateReminder("k", "s", "book health check", T0)
        deterministicVerdict(reminder, c, trusted()).also {
            it.decision shouldBe Decision.ASK
            it.rationale shouldContain "Health"
        }
        deterministicVerdict(sample(ActionKind.CREATE_REMINDER), c, trusted()).decision shouldBe Decision.ALLOW
    }

    context("combine: the model can only tighten") {
        val allow = GuardVerdict(Decision.ALLOW, emptyList(), "fine")
        val ask = GuardVerdict(Decision.ASK, listOf(LineId("a")), "det asks")
        val deny = GuardVerdict(Decision.DENY, listOf(LineId("b")), "model denies")
        for (det in Decision.entries) for (model in Decision.entries) {
            test("det=$det model=$model") {
                val d = GuardVerdict(det, emptyList(), "d")
                val m = GuardVerdict(model, emptyList(), "m")
                combine(d, m).decision shouldBe maxOf(det, model)
            }
        }
        test("rationale and lines come from whoever decided") {
            combine(allow, deny).rationale shouldBe "model denies"
            combine(allow, deny).lines shouldBe listOf(LineId("b"))
            combine(ask, allow) shouldBe ask
            combine(ask, deny).rationale shouldBe "det asks model denies"
            combine(ask, ask.copy(lines = listOf(LineId("c")), rationale = "model asks")).lines shouldBe listOf(LineId("a"), LineId("c"))
        }
    }

    test("approval is re-checked against hard lines only") {
        val hard = line("h1", "never email my boss", hard = true)
        val c = constitution(lines = listOf(hard, line("v1")))
        val blockedByLine = GuardVerdict(Decision.DENY, listOf(LineId("h1")), "x")
        val askedOnly = GuardVerdict(Decision.ASK, listOf(LineId("h1")), "x")
        hardLineViolation(sample(ActionKind.SEND_EMAIL), blockedByLine, c) shouldContain "hard line"
        hardLineViolation(sample(ActionKind.SEND_EMAIL), askedOnly, c) shouldBe null
        hardLineViolation(sample(ActionKind.PAYMENT), askedOnly, c) shouldBe null
        val noPayments = constitution(lines = listOf(hard, line("v1")), autonomy = mapOf(ActionKind.PAYMENT to AutonomyTier.NEVER))
        hardLineViolation(sample(ActionKind.PAYMENT), askedOnly, noPayments) shouldContain "hard line"
        hardLineViolation(sample(ActionKind.SEND_EMAIL), GuardVerdict(Decision.DENY, listOf(LineId("v1")), "x"), c) shouldBe null
    }

    test("Guard: model failure asks, a DENY skips the model, a lenient model cannot loosen, unknown line ids are dropped") {
        runTest {
            val c = constitution(tier = AutonomyTier.DO_THEN_TELL, lines = listOf(line("v1")))
            var calls = 0
            fun guard(model: suspend () -> GuardVerdict) = Guard(GuardModel { calls++; model() })

            guard { error("boom") }.check(sample(ActionKind.SAVE_DRAFT), c, trusted()).also {
                it.decision shouldBe Decision.ASK
                it.rationale shouldContain "couldn't double-check"
            }
            calls shouldBe 1

            val never = constitution(tier = AutonomyTier.NEVER, lines = listOf(line("v1")))
            guard { GuardVerdict(Decision.ALLOW, emptyList(), "ok") }.check(sample(ActionKind.SAVE_DRAFT), never, trusted()).decision shouldBe Decision.DENY
            calls shouldBe 1 // not consulted

            guard { GuardVerdict(Decision.ALLOW, emptyList(), "ok") }.check(sample(ActionKind.SAVE_DRAFT), constitution(), trusted()).decision shouldBe Decision.ASK

            guard { GuardVerdict(Decision.ALLOW, emptyList(), "ok") }.check(sample(ActionKind.SAVE_DRAFT), c, trusted()).decision shouldBe Decision.ALLOW

            val tightened = guard { GuardVerdict(Decision.ASK, listOf(LineId("v1"), LineId("ghost")), "it touches v1") }
                .check(sample(ActionKind.SAVE_DRAFT), c, trusted())
            tightened.decision shouldBe Decision.ASK
            tightened.lines shouldBe listOf(LineId("v1"))
        }
    }

    test("the model answer is parsed from surrounding text") {
        val v = GatewayGuardModel.parseVerdict("Sure!\n```json\n{\"decision\":\"ask\",\"lines\":[\"v1\"],\"rationale\":\"r\"}\n```")
        v shouldBe GuardVerdict(Decision.ASK, listOf(LineId("v1")), "r")
        runCatching { GatewayGuardModel.parseVerdict("no json here") }.isFailure shouldBe true
    }

})
