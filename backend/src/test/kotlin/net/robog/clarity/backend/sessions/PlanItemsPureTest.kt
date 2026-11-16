package net.robog.clarity.backend.sessions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import net.robog.clarity.backend.agent.CheckInChoices
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.scheduler.NudgePlan
import net.robog.clarity.backend.scheduler.planNudge
import net.robog.clarity.backend.agent.MAX_FOLLOW_UPS_PER_DAY
import net.robog.clarity.backend.agent.PLAN_CHECK_IN
import net.robog.clarity.backend.agent.PlanCheckIn
import net.robog.clarity.backend.agent.PlanMove
import net.robog.clarity.backend.agent.PlanOutcome
import net.robog.clarity.backend.agent.followUpRefusal
import net.robog.clarity.backend.agent.linkedItem
import net.robog.clarity.backend.agent.planAutoNudges
import net.robog.clarity.backend.agent.planCheckInNote
import net.robog.clarity.backend.agent.planMove
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.feed.Nudge
import net.robog.clarity.shared.feed.NudgeId

private val mon = LocalDate(2026, 3, 2)
private val tue = LocalDate(2026, 3, 3)
private val wed = LocalDate(2026, 3, 4)
private val thu = LocalDate(2026, 3, 5)

private fun item(id: String, text: String, last: LocalDate, days: Int = 1, state: PlanItemState = PlanItemState.OPEN) =
    PlanItem(id, text, last, last, days, state)

private fun ids() = generateSequence(1) { it + 1 }.map { "new$it" }.iterator().let { { it.next() } }

class PlanItemsPureTest : FunSpec({
    context("carryOver") {
        test("a new item starts at day one") {
            val out = carryOver(emptyList(), listOf(Planned(" write the spec ")), mon, ids())
            out.single() shouldBe PlanItem("new1", "write the spec", mon, mon, 1)
        }

        test("the id the model passes wins, even over a closer wording") {
            val open = listOf(item("a", "write the spec", mon), item("b", "call the dentist", mon))
            val out = carryOver(open, listOf(Planned("write the spec again", continues = "b")), tue, ids())
            out.single().id shouldBe "b"
            out.single().daysCarried shouldBe 2
            out.single().text shouldBe "write the spec again"
        }

        test("close wording matches: case, punctuation, filler words and small rewording") {
            val open = listOf(item("a", "Write the onboarding spec", mon))
            carryOver(open, listOf(Planned("write onboarding spec!")), tue, ids()).single().id shouldBe "a"
            carryOver(open, listOf(Planned("the onboarding spec, write")), tue, ids()).single().id shouldBe "a"
            carryOver(open, listOf(Planned("call the dentist")), tue, ids()).single().id shouldBe "new1"
            // a shared word or two is not enough
            carryOver(listOf(item("a", "expense report", mon)), listOf(Planned("quarterly report")), tue, ids()).single().id shouldBe "new1"
        }

        test("an item is continued at most once") {
            val open = listOf(item("a", "write the spec", mon))
            val out = carryOver(open, listOf(Planned("write the spec"), Planned("write the spec")), tue, ids())
            out.map { it.id } shouldBe listOf("a", "new1")
        }

        test("each day adds one; planning twice in a day changes nothing") {
            val day1 = carryOver(emptyList(), listOf(Planned("spec work")), mon, ids())
            val day2 = carryOver(day1, listOf(Planned("spec work")), tue, ids())
            val day2again = carryOver(day2, listOf(Planned("spec work")), tue, ids())
            val day3 = carryOver(day2again, listOf(Planned("spec work")), wed, ids())
            day2.single().daysCarried shouldBe 2
            day2again shouldBe day2
            day3.single().daysCarried shouldBe 3
            day3.single().id shouldBe day1.single().id
        }

        test("a new day clears 'starting' but keeps the first step") {
            val before = item("a", "spec work", mon).copy(startedOn = mon, firstStep = "write the title")
            val after = carryOver(listOf(before), listOf(Planned("spec work")), tue, ids()).single()
            after.startedOn.shouldBeNull()
            after.firstStep shouldBe "write the title"
        }

        test("an item not planned for more than three days, or finished, is not carried") {
            val old = item("a", "spec work", mon)
            carryOver(listOf(old), listOf(Planned("spec work")), thu, ids()).single().id shouldBe "a" // three days
            carryOver(listOf(old), listOf(Planned("spec work")), LocalDate(2026, 3, 6), ids()).single().id shouldBe "new1"
            carryOver(listOf(item("a", "spec work", mon, state = PlanItemState.DONE)), listOf(Planned("spec work")), tue, ids()).single().id shouldBe "new1"
        }
    }

    context("stuck items") {
        test("an item on its third day if planned again is stuck; earlier ones are not") {
            val open = listOf(item("a", "spec", mon, days = 2), item("b", "dentist", mon, days = 1), item("c", "taxes", mon, days = 4))
            stuckItems(open, tue).map { it.id } shouldBe listOf("c", "a")
            stuckItems(open, tue).first().dayOn(tue) shouldBe 5
        }

        test("done, dropped, already planned today and long-forgotten items are not listed") {
            val open = listOf(
                item("a", "spec", mon, days = 3, state = PlanItemState.DONE),
                item("b", "dentist", tue, days = 3), // already on today's plan
                item("c", "taxes", LocalDate(2026, 2, 20), days = 5),
            )
            stuckItems(open, tue).shouldBeEmpty()
        }

        test("the preparation lists them without blame, with the plan item id") {
            val stuck = stuckItems(listOf(item("a", "write the spec", mon, days = 2).copy(firstStep = "open the doc")), tue)
            val text = prepInstruction(null, stuck.map { stuckLine(it, tue) })
            text shouldContain "stuck: write the spec, day 3 (plan item a, earlier first step: open the doc)"
            text shouldContain "without blame"
            text shouldContain "two-minute first step"
            prepInstruction(null) shouldNotContain "stuck"
        }
    }

    context("clock times in an if-then cue") {
        test("24 hour and am/pm forms") {
            clockTimeIn("it's 9:30") shouldBe LocalTime(9, 30)
            clockTimeIn("it's 14:05 and I'm at my desk") shouldBe LocalTime(14, 5)
            clockTimeIn("it's 3pm") shouldBe LocalTime(15, 0)
            clockTimeIn("it is 9:30 am") shouldBe LocalTime(9, 30)
            clockTimeIn("it's 12:15 PM") shouldBe LocalTime(12, 15)
            clockTimeIn("it's 12am") shouldBe LocalTime(0, 0)
        }

        test("no time, or something that is not one") {
            clockTimeIn("Dana emails").shouldBeNull()
            clockTimeIn("I have 3 emails open").shouldBeNull()
            clockTimeIn("it's 25:00").shouldBeNull()
            clockTimeIn("after lunch").shouldBeNull()
            clockTimeIn("it's 9:30 a meeting starts") shouldBe LocalTime(9, 30)
        }
    }
})

private val zone = TimeZone.UTC
private val now = Instant.parse("2026-03-04T08:00:00Z")

private fun pendingNudge(at: Instant, note: String, planItemId: String? = null, id: String = "n") =
    Nudge(NudgeId(id), at, note, null, Nudge.Status.PENDING, now, planItemId = planItemId)

class StuckNudgesPureTest : FunSpec({
    val spec = item("a", "write the onboarding spec", wed, days = 3).copy(firstStep = "open the doc and write the title", firstStepOn = wed)

    context("linking an if-then to its item") {
        val dentist = item("b", "call the dentist", wed)
        test("the id the model gave, when it is one of today's") {
            linkedItem(IfThen("it's 9:30", "something else"), "b", listOf(spec, dentist)) shouldBe dentist
            linkedItem(IfThen("it's 9:30", "something else"), "zzz", listOf(spec, dentist)).shouldBeNull()
        }
        test("else the item whose words it mentions, else the only item") {
            linkedItem(IfThen("it's 9:30", "write the onboarding spec at my desk"), null, listOf(dentist, spec)) shouldBe spec
            linkedItem(IfThen("it's 9:30", "at my desk until 12"), null, listOf(spec)).shouldBeNull()
            linkedItem(IfThen("it's 9:30", "at my desk until 12"), null, listOf(spec, dentist)).shouldBeNull()
        }
    }

    context("the check-in note") {
        test("names the item, the plan and the first step") {
            val note = planCheckInNote(IfThen("it's 9:30", "spec at my desk"), spec, wed)
            note shouldContain PLAN_CHECK_IN
            note shouldContain "plan item a"
            note shouldContain "If it's 9:30, then spec at my desk."
            note shouldContain "First step: open the doc and write the title."
            planCheckInNote(IfThen("it's 9:30", "spec", firstStep = "write the title"), null, wed) shouldContain "First step: write the title."
        }
    }

    context("planAutoNudges") {
        fun plan(vararg checks: PlanCheckIn, pending: List<Nudge> = emptyList(), sameDay: Int = 0, at: Instant = now) =
            planAutoNudges(checks.toList(), wed, zone, at, pending, sameDay)

        test("one nudge at each if-then with a clock time; others get none") {
            val out = plan(
                PlanCheckIn(IfThen("it's 9:30", "spec at my desk"), spec),
                PlanCheckIn(IfThen("Dana emails", "it waits until 12"), null),
                PlanCheckIn(IfThen("it's 3pm", "call the dentist"), null),
            )
            out.nudges.map { it.at } shouldBe listOf(Instant.parse("2026-03-04T09:30:00Z"), Instant.parse("2026-03-04T15:00:00Z"))
            out.nudges.first().planItemId shouldBe "a"
            out.nudges.first().note shouldContain "First step"
            out.skipped.shouldBeEmpty()
        }

        test("a time that has passed gets none, and says why") {
            val out = plan(PlanCheckIn(IfThen("it's 7:00 am", "spec"), spec))
            out.nudges.shouldBeEmpty()
            out.skipped.single() shouldContain "passed"
        }

        test("no duplicate when a check-in for the same item is already around that time, or an earlier one of these") {
            val at = Instant.parse("2026-03-04T09:30:00Z")
            plan(PlanCheckIn(IfThen("it's 9:30", "spec"), spec), pending = listOf(pendingNudge(at + 5.minutes, "$PLAN_CHECK_IN: spec", "a"))).nudges.shouldBeEmpty()
            plan(PlanCheckIn(IfThen("it's 9:30", "spec"), spec), pending = listOf(pendingNudge(at, "ask about it", planItemId = "a"))).nudges.shouldBeEmpty()
            // two if-then plans a few minutes apart for the same item: only the first
            plan(PlanCheckIn(IfThen("it's 9:30", "spec"), spec), PlanCheckIn(IfThen("it's 9:35", "spec again"), spec)).nudges shouldHaveSize 1
        }

        test("the nudge limits apply: the day's cap and the pending cap") {
            plan(PlanCheckIn(IfThen("it's 9:30", "spec"), spec), sameDay = 12).skipped.single() shouldContain "12 nudges"
            val full = (1..10).map { pendingNudge(Instant.parse("2026-03-05T12:00:00Z") + it.hours, "other $it", planItemId = "x$it", id = "p$it") }
            plan(PlanCheckIn(IfThen("it's 9:30", "spec"), spec), pending = full).skipped.single() shouldContain "already 10"
        }

        test("a cue with no clock time schedules nothing and says nothing") {
            val out = plan(PlanCheckIn(IfThen("Dana emails", "it waits"), null))
            out.nudges.shouldBeEmpty()
            out.skipped.shouldBeEmpty()
        }
    }

    context("answers to a check-in") {
        fun move(outcome: PlanOutcome, at: String? = null, step: String? = null, item: PlanItem = spec) =
            planMove(item, outcome, at, step, wed, now, zone)

        test("starting, done and dropped need nothing else") {
            move(PlanOutcome.STARTED).getOrNull() shouldBe PlanMove.Started
            move(PlanOutcome.DONE).getOrNull() shouldBe PlanMove.Done
            move(PlanOutcome.DROPPED).getOrNull() shouldBe PlanMove.Dropped
        }

        test("later needs the time the person picked") {
            move(PlanOutcome.LATER).leftOrNull().shouldNotBeNull() shouldContain "`at` is required"
            val ok = move(PlanOutcome.LATER, at = "13:30").getOrNull().shouldBeInstanceOf<PlanMove.FollowUp>()
            ok.at shouldBe Instant.parse("2026-03-04T13:30:00Z")
            ok.note shouldContain PLAN_CHECK_IN
            ok.note shouldContain "plan item a"
            ok.firstStep.shouldBeNull()
            move(PlanOutcome.LATER, at = "whenever").leftOrNull().shouldNotBeNull() shouldContain "not a time"
        }

        test("make it smaller needs the smaller step and checks in again in ten minutes by default") {
            move(PlanOutcome.SMALLER).leftOrNull().shouldNotBeNull() shouldContain "`firstStep` is required"
            val ok = move(PlanOutcome.SMALLER, step = " write the title ").getOrNull().shouldBeInstanceOf<PlanMove.FollowUp>()
            ok.at shouldBe now + 10.minutes
            ok.firstStep shouldBe "write the title"
            ok.note shouldContain "write the title"
            move(PlanOutcome.SMALLER, at = "11:00", step = "x").getOrNull().shouldBeInstanceOf<PlanMove.FollowUp>().at shouldBe Instant.parse("2026-03-04T11:00:00Z")
        }

        test("after starting, no more check-ins that day; a finished item takes no answer") {
            val started = spec.copy(startedOn = wed)
            move(PlanOutcome.LATER, at = "13:30", item = started).leftOrNull().shouldNotBeNull() shouldContain "no more check-ins"
            move(PlanOutcome.SMALLER, step = "x", item = started).leftOrNull().shouldNotBeNull() shouldContain "no more check-ins"
            // a start from another day does not count
            move(PlanOutcome.LATER, at = "13:30", item = spec.copy(startedOn = tue)).getOrNull().shouldBeInstanceOf<PlanMove.FollowUp>()
            move(PlanOutcome.STARTED, item = spec.copy(state = PlanItemState.DONE)).leftOrNull().shouldNotBeNull() shouldContain "already done"
            move(PlanOutcome.LATER, at = "13:30", item = spec.copy(state = PlanItemState.DROPPED)).leftOrNull().shouldNotBeNull() shouldContain "already dropped"
        }

        test("two follow-ups a day per item, no more") {
            followUpRefusal(0).shouldBeNull()
            followUpRefusal(MAX_FOLLOW_UPS_PER_DAY - 1).shouldBeNull()
            followUpRefusal(MAX_FOLLOW_UPS_PER_DAY).shouldNotBeNull() shouldContain "2 follow-up"
        }

        test("the buttons send exactly these words") {
            listOf(CheckInChoices.STARTING, CheckInChoices.LATER, CheckInChoices.SMALLER) shouldBe listOf("Starting", "Later", "Make it smaller")
        }
    }

    context("a due check-in about a plan item") {
        val nudge = pendingNudge(now, "$PLAN_CHECK_IN: spec", planItemId = "a")
        val due = now + 1.minutes
        test("fires while the item is open and not started today") {
            planNudge(nudge, null, due, 0, null, false, spec, wed) shouldBe NudgePlan.Fire
            planNudge(nudge, null, due, 0, null, false, spec.copy(startedOn = tue), wed) shouldBe NudgePlan.Fire
        }
        test("is skipped when the item is finished or the person said they are starting") {
            planNudge(nudge, null, due, 0, null, false, spec.copy(state = PlanItemState.DONE), wed).shouldBeInstanceOf<NudgePlan.Resolve>()
                .resolution.reason.shouldNotBeNull() shouldContain "done"
            planNudge(nudge, null, due, 0, null, false, spec.copy(startedOn = wed), wed).shouldBeInstanceOf<NudgePlan.Resolve>()
                .resolution.status shouldBe Nudge.Status.SKIPPED
        }
        test("the wake-up asks with the three buttons and does not ask for urgency") {
            val item = InboxItem.NudgeDue("n", "09:30", nudge.note, null, "id a, \"spec\", day 3, 2 follow-up check-ins left today")
            item.summary shouldContain "Starting"
            item.summary shouldContain "Later"
            item.summary shouldContain "Make it smaller"
            item.summary shouldContain "kind check_in"
            item.summary shouldNotContain "urgent"
            InboxItem.NudgeDue("n", "09:30", "ask", null).summary shouldNotContain "urgent"
        }
    }
})
