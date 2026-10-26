package net.robog.clarity.backend.governance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.Amendment
import net.robog.clarity.shared.governance.AmendmentId
import net.robog.clarity.shared.governance.LineId
import net.robog.clarity.shared.noun.Noun

class OutboxLogicTest : FunSpec({

    test("retry delay doubles from 15 s and is capped") {
        retryDelay(1) shouldBe 15.seconds
        retryDelay(2) shouldBe 30.seconds
        retryDelay(3) shouldBe 60.seconds
        retryDelay(20) shouldBe 30.minutes
    }

    test("transitions") {
        val retryable = ActionOutcome.Failed("timeout", retryable = true)
        transitionFor(1, ActionOutcome.Done(SourceId("x")), T0) shouldBe Transition.Finish
        transitionFor(1, retryable, T0) shouldBe Transition.Retry(T0 + 15.seconds)
        transitionFor(4, retryable, T0) shouldBe Transition.Retry(T0 + 2.minutes)
        transitionFor(MAX_ATTEMPTS, retryable, T0) shouldBe Transition.GiveUp
        transitionFor(1, ActionOutcome.Failed("no", retryable = false), T0) shouldBe Transition.GiveUp
    }

    test("connector selection: declared handler, preference, else oldest") {
        fun manifest(vararg kinds: ActionKind) = ConnectorManifest("k", "K", "1", setOf(Noun.MESSAGE), handles = kinds.toSet())
        val old = Candidate(ConnectorId("old"), manifest(ActionKind.SEND_EMAIL))
        val new = Candidate(ConnectorId("new"), manifest(ActionKind.SEND_EMAIL, ActionKind.SAVE_DRAFT))
        val other = Candidate(ConnectorId("other"), manifest(ActionKind.CREATE_EVENT))
        val all = listOf(old, new, other)
        selectConnector(ActionKind.SEND_EMAIL, all, null) shouldBe ConnectorId("old")
        selectConnector(ActionKind.SEND_EMAIL, all, ConnectorId("new")) shouldBe ConnectorId("new")
        selectConnector(ActionKind.SEND_EMAIL, all, ConnectorId("other")) shouldBe ConnectorId("old") // preference must be capable
        selectConnector(ActionKind.SAVE_DRAFT, all, null) shouldBe ConnectorId("new")
        selectConnector(ActionKind.PAYMENT, all, null) shouldBe null
    }

    test("amendments replace a line keeping its id, or add a new one") {
        val c = constitution(lines = listOf(line("a", "old a"), line("b")))
        fun amendment(target: String?, proposed: String) =
            Amendment(AmendmentId("1"), target?.let(::LineId), null, line(proposed, "new $proposed"), "because", null, Amendment.Status.PROPOSED)

        applyAmendment(c, amendment("a", "zzz")).getOrNull()!!.lines.map { it.id.value to it.text } shouldBe listOf("a" to "new zzz", "b" to "text b")
        applyAmendment(c, amendment(null, "c")).getOrNull()!!.lines.map { it.id.value } shouldBe listOf("a", "b", "c")
        applyAmendment(c, amendment(null, "a")).leftOrNull() shouldBe GovernanceError.InvalidConstitution("a line 'a' already exists")
        applyAmendment(c, amendment("ghost", "x")).leftOrNull() shouldBe GovernanceError.LineNotFound("ghost")
    }

    test("constitutions need unique, non-blank lines") {
        validateConstitution(constitution(lines = listOf(line("a"), line("a")))).isLeft() shouldBe true
        validateConstitution(constitution(lines = listOf(line("a", " ")))).isLeft() shouldBe true
        validateConstitution(constitution(lines = listOf(line("a"), line("b")))).isRight() shouldBe true
    }
})
