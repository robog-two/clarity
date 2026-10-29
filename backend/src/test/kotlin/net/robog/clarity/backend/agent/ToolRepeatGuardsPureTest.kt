package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import net.robog.clarity.backend.triggers.ownOutgoing
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.Nudge
import net.robog.clarity.shared.feed.NudgeId
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Noun
import net.robog.clarity.shared.query.RecordQuery
import net.robog.clarity.shared.query.TriggerSpec

private val t0 = Instant.parse("2026-10-29T09:00:00Z")

private fun task(title: String, status: AgentTask.Status = AgentTask.Status.WORKING, id: String = "t-$title") =
    AgentTask(TaskId(id), title, "", status, null, t0)

private fun nudge(at: Instant, note: String, taskId: TaskId? = null) =
    Nudge(NudgeId("n-$note"), at, note, taskId, Nudge.Status.PENDING, t0)

/** The pure checks that keep repeated write calls from making the same thing twice. */
class ToolRepeatGuardsPureTest : FunSpec({
    // Guards against: trivially different spellings of one title counting as a new task.
    test("looseText ignores case, runs of spaces and trailing punctuation") {
        looseText("  Reply   to Dana. ") shouldBe "reply to dana"
        looseText("Reply to Dana!?") shouldBe "reply to dana"
        looseText("Reply to Dana") shouldBe looseText("reply to dana")
        (looseText("Reply to Dan") == looseText("Reply to Dana")) shouldBe false
    }

    // Guards against: a repeated create making a second open task, or a finished task blocking a new one.
    test("sameOpenTask finds an open task with the same title, never a finished one") {
        val open = task("Reply to Dana")
        sameOpenTask("reply to dana.", listOf(task("Other"), open)) shouldBe open
        sameOpenTask("Reply to Dana", listOf(task("Reply to Dana", AgentTask.Status.DONE))).shouldBeNull()
        sameOpenTask("Reply to Dana", listOf(task("Reply to Dana", AgentTask.Status.FAILED))).shouldBeNull()
        sameOpenTask("Reply to Dana", listOf(task("Reply to Dana", AgentTask.Status.CANCELLED))).shouldBeNull()
        sameOpenTask("   ", listOf(task(""))).shouldBeNull()
    }

    test("sameOpenTask treats every open status as open") {
        listOf(AgentTask.Status.WORKING, AgentTask.Status.DRAFTED, AgentTask.Status.WAITING_FOR_YOU, AgentTask.Status.SCHEDULED).forEach { status ->
            val t = task("Reply to Dana", status)
            sameOpenTask("Reply to Dana", listOf(t)) shouldBe t
        }
    }

    // Guards against: create_trigger duplicating an active trigger, or refusing one that differs.
    test("sameTrigger is the same noun and query on two enabled triggers; name and instruction do not matter") {
        val a = TriggerSpec("invoices", Noun.MESSAGE, RecordQuery.Contains("subject", "invoice"), "file it")
        sameTrigger(a, a.copy(name = "other", instruction = "other")) shouldBe true
        sameTrigger(a, a.copy(query = RecordQuery.Contains("subject", "receipt"))) shouldBe false
        sameTrigger(a, a.copy(query = RecordQuery.Contains("subject", "invoice", ignoreCase = false))) shouldBe false
        sameTrigger(a, a.copy(noun = Noun.DOCUMENT)) shouldBe false
        sameTrigger(a.copy(enabled = false), a) shouldBe false
        sameTrigger(a, a.copy(enabled = false)) shouldBe false
    }

    // Guards against: the same nudge being set twice; a nudge at another time or about another task is new.
    test("sameNudge matches only the same time, task and note") {
        val at = t0 + 60.minutes
        val pending = listOf(nudge(at, "Ask how the ML report is going"))
        sameNudge(at, "ask how the ML report is going ", null, pending) shouldBe pending.single()
        sameNudge(at + 1.minutes, "ask how the ML report is going", null, pending).shouldBeNull()
        sameNudge(at, "ask how the ML report is going", TaskId("t1"), pending).shouldBeNull()
        sameNudge(at, "something else", null, pending).shouldBeNull()
    }

    // Guards against: the agent's own sent mail (from the person's account) waking a trigger.
    test("ownOutgoing is a message whose sender is the account it was seen on") {
        fun msg(account: Handle, sender: Handle) = MessagePayload(MessagePayload.Channel.EMAIL, account, sender, emptyList(), "s", "b", t0)
        ownOutgoing(msg(Handle.email("me@x.org"), Handle.email("Me@X.org "))) shouldBe true
        ownOutgoing(msg(Handle.email("me@x.org"), Handle.email("boss@x.org"))) shouldBe false
        ownOutgoing(msg(Handle.phone("+15550100"), Handle.email("+15550100"))) shouldBe false
    }
})
