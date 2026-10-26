package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.backend.config.AgentRoles
import net.robog.clarity.backend.governance.NotifyDecision
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Role
import net.robog.clarity.harness.ToolPolicy
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.Line
import net.robog.clarity.shared.governance.LineId
import net.robog.clarity.shared.governance.Person
import net.robog.clarity.shared.governance.Section
import net.robog.clarity.shared.noun.Handle
import kotlinx.serialization.json.buildJsonObject

private val now = Instant.parse("2026-10-25T10:00:00Z")

private val sample = Constitution(
    version = 4,
    lines = listOf(
        Line(LineId("v1"), Section.VALUES, "Family first", because = "they are what matters"),
        Line(LineId("h1"), Section.HARD_LINES, "Never email my boss", because = null, hard = true),
    ),
    autonomy = mapOf(ActionKind.SAVE_DRAFT to AutonomyTier.DO_THEN_TELL, ActionKind.SEND_EMAIL to AutonomyTier.NEVER_ALONE),
    getsThrough = listOf(Person("Mum", listOf(Handle.phone("+4912345")))),
    neverStore = listOf("health"),
    profile = listOf("Lives in Berlin", "Prefers short replies"),
    updatedAt = now,
)

class LeadPureTest : FunSpec({
    context("idle compaction timing") {
        val turn = now - 10.minutes
        test("quiet for over 3 minutes, warm cache (1h), long thread: compact") {
            shouldCompactNow(now, now - 5.minutes, turn, null, "1h", 80_000) shouldBe true
        }
        test("not while the person is still active") {
            shouldCompactNow(now, now - 2.minutes, turn, null, "1h", 80_000) shouldBe false
        }
        test("not once the 1h cache is about to expire (55 minute window)") {
            shouldCompactNow(now, now - 5.minutes, now - 56.minutes, null, "1h", 80_000) shouldBe false
            shouldCompactNow(now, now - 5.minutes, now - 54.minutes, null, "1h", 80_000) shouldBe true
        }
        test("5 minute cache: only inside 4 minutes of the last turn") {
            shouldCompactNow(now, now - 3.5.minutes, now - 3.6.minutes, null, "5m", 80_000) shouldBe true
            shouldCompactNow(now, now - 6.minutes, now - 5.minutes, null, "5m", 80_000) shouldBe false
        }
        test("not twice for the same turn") {
            shouldCompactNow(now, now - 5.minutes, turn, now - 1.minutes, "1h", 80_000) shouldBe false
            shouldCompactNow(now, now - 5.minutes, turn, now - 20.minutes, "1h", 80_000) shouldBe true
        }
        test("not for a short thread, not before any turn") {
            shouldCompactNow(now, now - 5.minutes, turn, null, "1h", 10_000) shouldBe false
            shouldCompactNow(now, null, null, null, "1h", 80_000) shouldBe false
        }
        test("falls back to the last turn when nobody ever typed") {
            shouldCompactNow(now, null, turn, null, "1h", 80_000) shouldBe true
        }
    }

    context("resuming") {
        val user = Message.user("hi")
        val toolCall = Message(Role.ASSISTANT, listOf(Block.ToolUse("t1", "send_to_user", buildJsonObject { })))
        test("needsResume") {
            needsResume(emptyList()) shouldBe false
            needsResume(listOf(user)) shouldBe true
            needsResume(listOf(user, toolCall)) shouldBe true
            needsResume(listOf(user, Message(Role.ASSISTANT, listOf(Block.Text("done"))))) shouldBe false
        }
        test("open tool calls are closed before new messages") {
            closeOpenToolCalls(listOf(user, toolCall))!!.content shouldBe
                listOf(Block.Result("t1", "Not run: the turn ended before this call could run.", isError = true))
            closeOpenToolCalls(listOf(user)) shouldBe null
            closeOpenToolCalls(listOf(user, Message(Role.ASSISTANT, listOf(Block.Text("done"))))) shouldBe null
        }
    }

    context("prompts and requests") {
        test("the constitution renders deterministically with reasons, tiers and profile") {
            val text = renderConstitution(sample)
            text shouldBe renderConstitution(sample)
            text shouldContain "version 4"
            text shouldContain "[v1] Family first Because: they are what matters"
            text shouldContain "[h1] Never email my boss (hard line)"
            text shouldContain "save_draft: do it, then tell me"
            text shouldContain "send_email: never alone"
            text shouldContain "Mum"
            text shouldContain "Never store: health"
            text shouldContain "- Lives in Berlin"
        }
        test("no constitution yet") {
            renderConstitution(null) shouldContain "none yet"
        }
        test("lead system: static block first with a breakpoint, constitution second") {
            val blocks = leadSystemBlocks(sample)
            blocks.size shouldBe 2
            blocks[0].text shouldBe AgentPrompts.LEAD_SYSTEM
            blocks[0].cacheBreakpoint shouldBe true
            blocks[1].cacheBreakpoint shouldBe true
            blocks[0].text shouldNotContain "Berlin"
        }
        test("the lead prompt states the rules the design depends on") {
            AgentPrompts.LEAD_SYSTEM shouldContain "send_to_user"
            AgentPrompts.LEAD_SYSTEM shouldContain "<untrusted"
            AgentPrompts.LEAD_SYSTEM shouldContain "Cheapest first"
            AgentPrompts.HELPER_SYSTEM shouldContain "cannot talk to the person"
        }
        test("role requests follow the role config and never switch models") {
            val roles = AgentRoles()
            val lead = roleRequest(roles.lead, "run", leadSystemBlocks(null), emptyList(), emptyList(), emptyList(), 30, null)
            lead.model shouldBe "claude-opus-5-5"
            lead.cache!!.ttl shouldBe "1h"
            (lead.compaction != null) shouldBe true
            lead.effort shouldBe "medium"
            lead.policy shouldBe ToolPolicy.Gated
            val helper = roleRequest(roles[AgentRole.HELPER], "h", helperSystemBlocks(), emptyList(), emptyList(), emptyList(), 30, null)
            helper.model shouldBe "claude-sonnet-5-5"
            helper.cache!!.ttl shouldBe "5m"
            helper.compaction shouldBe null
        }
        test("delegable roles") {
            delegableRole(null) shouldBe AgentRole.HELPER
            delegableRole("triage") shouldBe AgentRole.TRIAGE
            delegableRole("lead") shouldBe null
            delegableRole("guard") shouldBe null
        }
    }

    context("memory") {
        val today = LocalDate(2026, 10, 25)
        test("nothing to say renders nothing") { renderMemoryContext(today, emptyList(), emptyList()) shouldBe null }
        test("today, yesterday and scratch") {
            val text = renderMemoryContext(
                today,
                listOf(
                    JournalEntry(1, LocalDate(2026, 10, 24), "Decided to move the review"),
                    JournalEntry(2, today, "Morning call done"),
                    JournalEntry(3, LocalDate(2026, 10, 1), "too old"),
                ),
                listOf(ScratchNote(1, "waiting on Dana", now + 2.hours)),
            )!!
            text shouldContain "yesterday:\n- Decided to move the review"
            text shouldContain "today:\n- Morning call done"
            text shouldContain "waiting on Dana"
            text shouldNotContain "too old"
        }
        test("never-store topics") {
            neverStoreTopic("Has a Health condition", listOf("health")) shouldBe "health"
            neverStoreTopic("Likes tea", listOf("health", "")) shouldBe null
        }
    }

    context("small helpers") {
        test("handles") {
            parseHandle("Dana <Dana@Example.com>") shouldBe Handle.email("dana@example.com", "Dana")
            parseHandle("  a@b.co ") shouldBe Handle.email("a@b.co")
            parseHandle("+49 170 1234567") shouldBe Handle.phone("+491701234567")
            parseHandle("signal-user").kind shouldBe Handle.Kind.OTHER
        }
        test("result slices tell where to continue") {
            val text = "0123456789"
            sliceResult(text, 0, 4) shouldContain "0123\n[characters 0-4 of 10; call again with offset=4"
            sliceResult(text, 8, 100) shouldBe "89\n[end of result, 10 characters]"
            sliceResult(text, 99, 5) shouldBe "\n[end of result, 10 characters]"
        }
        test("sandbox result paths are safe") {
            sandboxResultPath("0123456789abcdef", "toolu_01/../x") shouldBe ".results/01234567-toolu_01x.txt"
        }
        test("notify decisions have a stable wire form") {
            NotifyDecision.PushNow.wire() shouldBe "push_now"
            NotifyDecision.ShowInApp.wire() shouldBe "in_app"
            NotifyDecision.HoldUntil(now).wire() shouldBe "hold_until:2026-10-25T10:00:00Z"
            NotifyDecision.EveningBatch(now).wire() shouldBe "evening_batch:2026-10-25T10:00:00Z"
        }
        test("token estimate grows with the journal") {
            estimateTokens(listOf(Message.user("x".repeat(4000)))) shouldBe estimateTokens(listOf(Message.user("x".repeat(4000))))
            (estimateTokens(listOf(Message.user("x".repeat(4000)))) > 900) shouldBe true
        }
    }
})
