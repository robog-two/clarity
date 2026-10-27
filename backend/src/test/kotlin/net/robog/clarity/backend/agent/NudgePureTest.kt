package net.robog.clarity.backend.agent

import arrow.core.left
import arrow.core.right
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.robog.clarity.backend.scheduler.ALREADY_UPDATED
import net.robog.clarity.backend.scheduler.NudgeExchange
import net.robog.clarity.backend.scheduler.NudgePlan
import net.robog.clarity.backend.scheduler.nudgeGateState
import net.robog.clarity.backend.scheduler.nudgeVerdictOf
import net.robog.clarity.backend.scheduler.planNudge
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.Nudge
import net.robog.clarity.shared.feed.NudgeId
import net.robog.clarity.shared.feed.TaskId

private val ny = TimeZone.of("America/New_York")

// 2026-10-27 10:00 in New York.
private val now = Instant.parse("2026-10-27T14:00:00Z")

private fun nudge(at: Instant, note: String = "ask about the ML report", task: String? = null, id: String = "n1") =
    Nudge(NudgeId(id), at, note, task?.let(::TaskId), Nudge.Status.PENDING, createdAt = now - 4.hours)

private fun task(status: AgentTask.Status) = AgentTask(TaskId("t1"), "ML report", "", status, updatedAt = now)

private fun decided(p: Double) = net.robog.clarity.backend.agent.DecisionResult(
    mapOf(ALREADY_UPDATED to DecisionAnswer.Noul(p)), "m", "p", DecisionUsage(1, 1, null),
).right()

class NudgePureTest : FunSpec({
    context("reading the time") {
        test("HH:MM is today on the person's clock") {
            readNudgeTime("13:30", ny, now) shouldBe Instant.parse("2026-10-27T17:30:00Z").right()
        }
        test("a local date-time is read like the other tools") {
            readNudgeTime("2026-10-28T09:00", ny, now) shouldBe Instant.parse("2026-10-28T13:00:00Z").right()
        }
        test("garbage and plain dates are refused with a hint") {
            readNudgeTime("after lunch", ny, now).leftOrNull().shouldNotBeNull() shouldContain "not a time"
            readNudgeTime("2026-10-28", ny, now).leftOrNull().shouldNotBeNull() shouldContain "time of day"
            readNudgeTime("25:00", ny, now).leftOrNull().shouldNotBeNull()
        }
    }

    context("scheduling validation") {
        fun refusal(at: Instant, note: String = "ask about it", task: TaskId? = null, pending: List<Nudge> = emptyList(), sameDay: Int = 0) =
            nudgeRefusal(at, note, task, now, pending, sameDay, ny)

        test("a time in the next week with a note is fine") {
            refusal(now + 3.hours).shouldBeNull()
            refusal(now + 7.days).shouldBeNull()
        }
        test("the past and more than 7 days ahead are refused") {
            refusal(now - 1.minutes).shouldNotBeNull() shouldContain "already passed"
            refusal(now).shouldNotBeNull() shouldContain "already passed"
            refusal(now + 7.days + 1.minutes).shouldNotBeNull() shouldContain "7 days"
        }
        test("an empty or very long note is refused") {
            refusal(now + 1.hours, note = "  ").shouldNotBeNull() shouldContain "note"
            refusal(now + 1.hours, note = "x".repeat(MAX_NUDGE_NOTE_CHARS + 1)).shouldNotBeNull() shouldContain "at most"
        }
        test("at most 10 pending") {
            val ten = (1..MAX_PENDING_NUDGES).map { nudge(now + it.hours, note = "n$it", id = "n$it") }
            refusal(now + 30.hours, pending = ten).shouldNotBeNull() shouldContain "already $MAX_PENDING_NUDGES"
            refusal(now + 30.hours, pending = ten.drop(1)).shouldBeNull()
        }
        test("at most 12 on one day") {
            refusal(now + 1.hours, sameDay = MAX_NUDGES_PER_DAY).shouldNotBeNull() shouldContain "$MAX_NUDGES_PER_DAY"
            refusal(now + 1.hours, sameDay = MAX_NUDGES_PER_DAY - 1).shouldBeNull()
        }
        test("the same task within 10 minutes is a duplicate; further apart or another task is not") {
            val existing = listOf(nudge(now + 2.hours, task = "t1"))
            refusal(now + 2.hours + 9.minutes, note = "other words", task = TaskId("t1"), pending = existing).shouldNotBeNull() shouldContain "n1"
            refusal(now + 2.hours - 9.minutes, task = TaskId("t1"), pending = existing).shouldNotBeNull()
            refusal(now + 2.hours + 11.minutes, task = TaskId("t1"), pending = existing).shouldBeNull()
            refusal(now + 2.hours, task = TaskId("t2"), pending = existing).shouldBeNull()
        }
        test("without a task, the same note within 10 minutes is a duplicate") {
            val existing = listOf(nudge(now + 2.hours, note = "Ask about the ML report"))
            refusal(now + 2.hours + 5.minutes, note = "ask about  the ML report", pending = existing).shouldNotBeNull()
            refusal(now + 2.hours + 5.minutes, note = "ask about dinner", pending = existing).shouldBeNull()
        }
    }

    context("planning a due nudge") {
        val due = now - 1.minutes

        test("an ordinary due nudge fires") {
            planNudge(nudge(due), null, now, 0, lastPersonMessageAt = now - 3.hours, sessionLive = false) shouldBe NudgePlan.Fire
        }
        test("a done or cancelled task skips without asking anyone") {
            listOf(AgentTask.Status.DONE, AgentTask.Status.CANCELLED).forEach { status ->
                val plan = planNudge(nudge(due, task = "t1"), task(status), now, 0, null, false).shouldBeInstanceOf<NudgePlan.Resolve>()
                plan.resolution.status shouldBe Nudge.Status.SKIPPED
                plan.resolution.reason.shouldNotBeNull() shouldContain status.name.lowercase()
            }
            planNudge(nudge(due, task = "t1"), task(AgentTask.Status.WORKING), now, 0, null, false) shouldBe NudgePlan.Fire
        }
        test("the day's cap skips") {
            planNudge(nudge(due), null, now, MAX_NUDGES_PER_DAY, null, false)
                .shouldBeInstanceOf<NudgePlan.Resolve>().resolution.status shouldBe Nudge.Status.SKIPPED
            planNudge(nudge(due), null, now, MAX_NUDGES_PER_DAY - 1, null, false) shouldBe NudgePlan.Fire
        }
        test("recent activity or a live session asks the decision model") {
            planNudge(nudge(due), null, now, 0, lastPersonMessageAt = now - 5.minutes, sessionLive = false) shouldBe NudgePlan.Ask
            planNudge(nudge(due), null, now, 0, lastPersonMessageAt = now - 25.minutes, sessionLive = false) shouldBe NudgePlan.Fire
            planNudge(nudge(due), null, now, 0, lastPersonMessageAt = null, sessionLive = true) shouldBe NudgePlan.Ask
        }
        test("catch-up: less than 2 hours late fires, more is missed") {
            planNudge(nudge(now - 119.minutes), null, now, 0, null, false) shouldBe NudgePlan.Fire
            planNudge(nudge(now - 121.minutes), null, now, 0, null, false)
                .shouldBeInstanceOf<NudgePlan.Resolve>().resolution.status shouldBe Nudge.Status.MISSED
        }
    }

    context("the decision gate") {
        test("p >= 0.8 skips, below fires") {
            nudgeVerdictOf(decided(0.8)).skip shouldBe true
            nudgeVerdictOf(decided(0.95)).skip shouldBe true
            nudgeVerdictOf(decided(0.79)).skip shouldBe false
        }
        test("fails open: no model, an error, a refusal") {
            nudgeVerdictOf(null).skip shouldBe false
            nudgeVerdictOf(DecisionError.Unreachable("down").left()).skip shouldBe false
            nudgeVerdictOf(DecisionResult(mapOf(ALREADY_UPDATED to DecisionAnswer.Refusal), null, null, DecisionUsage(0, 0, null)).right()).skip shouldBe false
        }
        test("the state carries the check-in and quotes the conversation as untrusted") {
            val state = nudgeGateState(
                nudge(now - 1.minutes, task = "t1"), task(AgentTask.Status.WORKING),
                listOf(NudgeExchange(now - 3.minutes, fromPerson = true, text = "report's done, sent it")), sessionLive = false, now, ny,
            )
            state["checkIn"]!!.jsonObject["note"]!!.jsonPrimitive.content shouldBe "ask about the ML report"
            state["checkIn"]!!.jsonObject["task"]!!.jsonPrimitive.content shouldBe "ML report"
            val line = state["conversationSinceSet"]!!.jsonArray.single().jsonObject
            line["from"]!!.jsonPrimitive.content shouldBe "person"
            line["untrusted"]!!.jsonPrimitive.content shouldBe "report's done, sent it"
        }
    }

    test("the inbox item says when, what and which task") {
        val item = InboxItem.NudgeDue("n1", "2026-10-27T13:30-04:00 (Tue)", "ask how the ML report is going", "ML report (working, id t1)")
        item.priority shouldBe Priority.NORMAL
        item.summary shouldContain "Nudge you set at 2026-10-27T13:30-04:00 (Tue): ask how the ML report is going"
        item.summary shouldContain "Task: ML report (working, id t1)"
    }
})
