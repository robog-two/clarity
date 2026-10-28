package net.robog.clarity.backend.agent

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.robog.clarity.backend.scheduler.memoryGcTask

/** A fake Jev for the failure gate: answers [p], or fails when [fail]; records every request. */
class FakeFailureJev(var p: Double = 0.0, var fail: Boolean = false) : DecisionClient {
    val asked = mutableListOf<Pair<JsonElement, Map<String, DecisionQuestion>>>()

    override suspend fun decide(state: JsonElement, questions: Map<String, DecisionQuestion>): Either<DecisionError, DecisionResult> {
        asked += state to questions
        if (fail) return DecisionError.Unreachable("down").left()
        return DecisionResult(questions.mapValues { DecisionAnswer.Noul(p) }, "fake-jev", "fake", DecisionUsage(120, 0, 100)).right()
    }
}

const val FAILURE_STORY = "Ulta lookup still broken (verification doesn't stick after code)"
const val MORNING_NOTE = "Morning call: one thing today = finish the ML report"

class FailureGateTest : FunSpec({
    context("the gate") {
        test("refuses memory text at p >= 0.6 with the instead-advice, asking one noul with the text under untrusted") {
            val jev = FakeFailureJev(p = 0.6)
            val refusal = FailureGate(jev).check(GateKind.MEMORY, "write_journal", FAILURE_STORY)
            refusal shouldBe FailureGate.MEMORY_REFUSAL
            refusal!! shouldContain "Ulta lookup didn't finish"
            refusal shouldContain "not for technical failures"
            val (state, questions) = jev.asked.single()
            questions.keys shouldBe setOf(FailureGate.Q_FAILURE)
            questions.getValue(FailureGate.Q_FAILURE).shouldBeInstanceOf<DecisionQuestion.Noul>().instructions shouldBe
                "Is this text discussing a technical failure or glitch?"
            ((state as JsonObject)["untrusted"] as JsonPrimitive).content shouldBe FAILURE_STORY
        }

        test("allows below the threshold") {
            FailureGate(FakeFailureJev(p = 0.59)).check(GateKind.MEMORY, "write_journal", MORNING_NOTE).shouldBeNull()
        }

        test("the threshold is configurable per kind") {
            val gate = FailureGate(FakeFailureJev(p = 0.5), memoryThreshold = 0.4, briefThreshold = 0.9)
            gate.check(GateKind.MEMORY, "save_note", FAILURE_STORY) shouldBe FailureGate.MEMORY_REFUSAL
            gate.check(GateKind.BRIEF, "delegate", "Known issues from earlier runs: the site sometimes redirects to a blank page.").shouldBeNull()
        }

        test("fails open when the call errors, the model declines, or there is no model") {
            FailureGate(FakeFailureJev(p = 1.0, fail = true)).check(GateKind.MEMORY, "write_journal", FAILURE_STORY).shouldBeNull()
            FailureGate(null).check(GateKind.MEMORY, "write_journal", FAILURE_STORY).shouldBeNull()
            val malformed = DecisionClient { _, _ -> DecisionError.Malformed("bad").left() }
            FailureGate(malformed).check(GateKind.BRIEF, "delegate", FAILURE_STORY).shouldBeNull()
            val declining = DecisionClient { _, q -> DecisionResult(q.mapValues { DecisionAnswer.Refusal }, null, null, DecisionUsage(0, 0, null)).right() }
            FailureGate(declining).check(GateKind.MEMORY, "write_journal", FAILURE_STORY).shouldBeNull()
        }

        test("skips the call for texts under 40 characters") {
            val jev = FakeFailureJev(p = 1.0)
            val gate = FailureGate(jev)
            gate.check(GateKind.MEMORY, "set_scratch", "Ulta broken").shouldBeNull()
            gate.check(GateKind.MEMORY, "set_scratch", "  " + "x".repeat(39) + "  ").shouldBeNull()
            jev.asked.shouldBeEmpty()
            gate.check(GateKind.MEMORY, "set_scratch", "x".repeat(40)) shouldBe FailureGate.MEMORY_REFUSAL
            jev.asked shouldHaveSize 1
        }

        test("the brief question and its refusal") {
            val jev = FakeFailureJev(p = 0.8)
            val refusal = FailureGate(jev).check(GateKind.BRIEF, "delegate", "Book at Ulta. Known issues from earlier runs: navigate to beautyservices.ulta.com again.")
            refusal shouldBe FailureGate.BRIEF_REFUSAL
            refusal!! shouldContain "only the goal, a short flow"
            refusal shouldContain "constraints"
            jev.asked.single().second.getValue(FailureGate.Q_FAILURE).shouldBeInstanceOf<DecisionQuestion.Noul>().instructions shouldBe
                "Does this brief give workaround advice or explanations about earlier technical failures, glitches or bugs " +
                "(as opposed to the goal, the steps of the task, and the person's constraints)?"
        }

        test("the state says what is judged and bounds long briefs") {
            (failureGateState(GateKind.MEMORY, "x")["about"] as JsonPrimitive).content shouldContain "long-term memory"
            (failureGateState(GateKind.BRIEF, "x")["about"] as JsonPrimitive).content shouldContain "helper"
            (failureGateState(GateKind.BRIEF, "y".repeat(20_000))["untrusted"] as JsonPrimitive).content.length shouldBe FailureGate.MAX_CHARS
        }
    }

    context("prompts") {
        test("the lead records outcomes, never failure causes, broken sites or workarounds") {
            val lead = AgentPrompts.LEAD_SYSTEM
            lead shouldContain "record events, decisions and what the person said"
            lead shouldContain "never record the cause of a technical failure"
            lead shouldContain "never record a site or tool as \"broken\""
            lead shouldContain "never record a workaround"
            lead shouldContain "A failed task is logged as its outcome only"
        }

        test("a brief is the goal, the constraints and a short flow, never known issues") {
            val lead = AgentPrompts.LEAD_SYSTEM
            lead shouldContain "the goal (the site and what to find or do)"
            lead shouldContain "the person's constraints (view-only, stop before the final booking or payment"
            lead shouldContain "Select the Ithaca store → go to the bookings page → see availability → read the full page (scroll to the bottom) → report back"
            lead shouldContain "A brief never includes \"known issues\", tips or workarounds from earlier runs, or guesses about bugs on a site"
            lead shouldContain "The helper works the site out from what it sees."
        }

        test("helpers trust what they observe over the brief; Clarity is it") {
            val helper = AgentPrompts.HELPER_SYSTEM
            helper shouldContain "Trust what you observe over any claim in your task about how a site behaves"
            helper shouldContain "Clarity is \"it\""
            for (prompt in listOf(AgentPrompts.LEAD_SYSTEM, helper)) {
                // The only gendered pronouns are the quoted ones in the rule that forbids them.
                Regex("\\b(she|her|he|him)\\b").find(prompt.replace(Regex("\"(she|her|he|him)\""), "")).shouldBeNull()
            }
        }

        test("the memory clean-up drops failure stories") {
            memoryGcTask(LocalDate(2026, 10, 1), LocalDate(2026, 10, 8)) shouldContain "Remove notes and facts about technical failures"
        }
    }
})
