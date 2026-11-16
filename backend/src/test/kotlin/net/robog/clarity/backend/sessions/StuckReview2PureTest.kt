package net.robog.clarity.backend.sessions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import net.robog.clarity.backend.agent.AutoNudge
import net.robog.clarity.backend.agent.PlanCheckIn
import net.robog.clarity.backend.agent.PlanOutcome
import net.robog.clarity.backend.agent.combinedRefusal
import net.robog.clarity.backend.agent.eligibleCheckIns
import net.robog.clarity.backend.agent.linkableToday
import net.robog.clarity.backend.agent.planAutoNudges
import net.robog.clarity.backend.agent.planMove
import net.robog.clarity.backend.agent.reconcileCheckIns
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.feed.Nudge
import net.robog.clarity.shared.feed.NudgeId

private val tue = LocalDate(2026, 3, 3)
private val wed = LocalDate(2026, 3, 4)
private val zone = TimeZone.UTC
private val now = Instant.parse("2026-03-04T08:00:00Z")
private val at930 = Instant.parse("2026-03-04T09:30:00Z")

private fun item(id: String, text: String, last: LocalDate = wed, days: Int = 1) = PlanItem(id, text, last, last, days)

private fun nudge(at: Instant, note: String, status: Nudge.Status, item: String? = "a", reason: String? = null) =
    Nudge(NudgeId("n-$status-${at.toEpochMilliseconds()}"), at, note, null, status, now, reason = reason, planItemId = item, autoCheckIn = true)

private fun ids() = generateSequence(1) { it + 1 }.map { "new$it" }.iterator().let { { it.next() } }

class StuckReview2PureTest : FunSpec({
    val spec = item("a", "write the onboarding spec", wed, 3)

    context("1. a time that has passed is skipped and reported, never moved to the evening") {
        test("bare or not, a past time is reported as passed") {
            val late = Instant.parse("2026-03-04T10:00:00Z")
            cueMoment("it's 9:30", wed, zone, late) shouldBe at930
            for (cue in listOf("it's 9:30", "it's 9:30 am", "it's 8pm", "at noon")) {
                val out = planAutoNudges(listOf(PlanCheckIn(IfThen(cue, "spec"), spec)), wed, zone, Instant.parse("2026-03-04T21:00:00Z"), emptyList(), 0)
                out.nudges.shouldBeEmpty()
                out.skipped.single() shouldContain "passed"
            }
        }
    }

    context("2. saving the plan again after the day's check-in has happened changes nothing") {
        val wanted = listOf(AutoNudge(at930, "note", "a"))
        test("a check-in that fired") {
            val r = reconcileCheckIns(wanted, listOf(nudge(at930, "note", Nudge.Status.FIRED)))
            r.create.shouldBeEmpty()
            r.cancel.shouldBeEmpty()
        }
        test("a check-in that was skipped or missed") {
            reconcileCheckIns(wanted, listOf(nudge(at930, "note", Nudge.Status.SKIPPED))).create.shouldBeEmpty()
            reconcileCheckIns(wanted, listOf(nudge(at930, "note", Nudge.Status.MISSED))).create.shouldBeEmpty()
        }
        test("one the person's Starting cancelled, and an item started today gets none") {
            val started = spec.copy(startedOn = wed)
            eligibleCheckIns(listOf(PlanCheckIn(IfThen("it's 9:30", "spec"), started), PlanCheckIn(IfThen("it's 9:30", "spec"), spec)), wed) shouldHaveSize 1
            reconcileCheckIns(wanted, listOf(nudge(at930, "note", Nudge.Status.CANCELLED, reason = "the person is starting it"))).create.shouldBeEmpty()
        }
        test("one this plan's own re-save cancelled does not block the next re-save") {
            val old = nudge(at930, "old", Nudge.Status.CANCELLED, reason = "the plan was saved again")
            reconcileCheckIns(wanted, listOf(old)).create shouldHaveSize 1
        }
        test("a fired check-in does not stop one for another item") {
            reconcileCheckIns(listOf(AutoNudge(at930, "n", "b")), listOf(nudge(at930, "note", Nudge.Status.FIRED, item = "a"))).create shouldHaveSize 1
        }
    }

    context("3. dotted times need a time word or am/pm") {
        test("accepted") {
            clockTimeIn("it's 9.30") shouldBe LocalTime(9, 30)
            clockTimeIn("at 14.15") shouldBe LocalTime(14, 15)
            clockTimeIn("from 10.45 on") shouldBe LocalTime(10, 45)
            clockTimeIn("around 7.30") shouldBe LocalTime(7, 30)
            clockTimeIn("spec 9.30am") shouldBe LocalTime(9, 30)
            clockTimeIn("after 3.15") shouldBe LocalTime(15, 15)
        }
        test("versions, sections, prices and dates are not times") {
            clockTimeIn("upgrade to v1.25").shouldBeNull()
            clockTimeIn("move to Python 3.12").shouldBeNull()
            clockTimeIn("rewrite section 4.10").shouldBeNull()
            clockTimeIn("pay €2.50").shouldBeNull()
            clockTimeIn("pay $2.50").shouldBeNull()
            clockTimeIn("due on 11.16").shouldBeNull()
            clockTimeIn("release 2.0.10").shouldBeNull()
        }
    }

    context("4. ranges give their start") {
        test("dash, from-to and between-and") {
            clockTimeIn("9-11am") shouldBe LocalTime(9, 0)
            clockTimeIn("from 9 to 11am") shouldBe LocalTime(9, 0)
            clockTimeIn("between 11 and 1pm") shouldBe LocalTime(11, 0)
            clockTimeIn("from 10 to 12pm") shouldBe LocalTime(10, 0)
            clockTimeIn("from 12 to 2pm") shouldBe LocalTime(12, 0)
            clockTimeIn("between 1 and 3pm") shouldBe LocalTime(13, 0)
            clockTimeIn("between 9:30 and 11am") shouldBe LocalTime(9, 30)
        }
    }

    context("5. deadlines are not start times") {
        test("by, before, until and till name an end") {
            for (cue in listOf("finish by 5pm", "before 3pm", "work until 4:00", "till 6pm", "it's done by 5.30pm")) {
                parseCueTime(cue).shouldNotBeNull().deadline shouldBe true
            }
            parseCueTime("it's 9:30, finish by 11am").shouldNotBeNull().deadline shouldBe false
            parseCueTime("after 3pm").shouldNotBeNull().deadline shouldBe false
        }
        test("they get no check-in and are reported as deadlines") {
            val out = planAutoNudges(listOf(PlanCheckIn(IfThen("by 5pm", "spec is sent"), spec), PlanCheckIn(IfThen("it's 9:30", "spec"), spec)), wed, zone, now, emptyList(), 0)
            out.nudges.map { it.at } shouldBe listOf(at930)
            out.deadlines shouldBe listOf("If by 5pm, then spec is sent.")
            out.unscheduled.shouldBeEmpty()
        }
    }

    context("6. a bare 1 to 6 is the afternoon unless the cue is about the morning") {
        test("morning words keep it am") {
            clockTimeIn("it's 6:30") shouldBe LocalTime(18, 30)
            clockTimeIn("wake up at 6:30") shouldBe LocalTime(6, 30)
            clockTimeIn("it's 5:15 and the alarm goes") shouldBe LocalTime(5, 15)
            clockTimeIn("early start, 5:00") shouldBe LocalTime(5, 0)
            clockTimeIn("morning run at 6.30") shouldBe LocalTime(6, 30)
            clockTimeIn("breakfast at 6:45") shouldBe LocalTime(6, 45)
            clockTimeIn("pick up the kids at 4:00") shouldBe LocalTime(16, 0)
        }
    }

    context("7. a replaced item is not called dropped") {
        test("the answer says it was replaced") {
            val msg = planMove(item("a", "spec").copy(state = PlanItemState.REPLACED), PlanOutcome.STARTED, null, null, wed, now, zone).leftOrNull().shouldNotBeNull()
            msg shouldContain "replaced"
            msg shouldNotContain "dropped"
        }
    }

    context("8. one ceiling over the person's and the plan's nudges") {
        test("20 waiting or 20 on a day, whoever owns them") {
            combinedRefusal(19, 19).shouldBeNull()
            combinedRefusal(20, 0).shouldNotBeNull() shouldContain "20"
            combinedRefusal(0, 20).shouldNotBeNull() shouldContain "20"
        }
        test("a check-in is refused when the person's nudges already fill the day") {
            val out = planAutoNudges(listOf(PlanCheckIn(IfThen("it's 9:30", "spec"), spec)), wed, zone, now, emptyList(), 0, personPending = 5, personDay = 20)
            out.nudges.shouldBeEmpty()
            out.skipped.single() shouldContain "20"
            planAutoNudges(listOf(PlanCheckIn(IfThen("it's 9:30", "spec"), spec)), wed, zone, now, emptyList(), 0, personPending = 5, personDay = 19).nudges shouldHaveSize 1
        }
    }

    context("9. sources do not mix") {
        test("a parked item does not claim a priority item of the same day, but takes yesterday's priority") {
            val priority = item("p", "write the spec", wed).copy(source = PlanSource.PRIORITY)
            carryOver(listOf(priority), listOf(Planned("write the spec")), wed, ids(), PlanSource.PARKED).single().id shouldBe "new1"
            val yesterday = item("y", "write the spec", tue)
            carryOver(listOf(yesterday), listOf(Planned("write the spec")), wed, ids(), PlanSource.PARKED).single().id shouldBe "y"
        }
        test("only today's open priorities can be linked to an if-then") {
            val parked = item("k", "call the dentist", wed).copy(source = PlanSource.PARKED)
            val priority = item("p", "write the onboarding spec", wed)
            val old = item("o", "taxes", tue)
            val gone = item("g", "renew the passport", wed).copy(state = PlanItemState.REPLACED)
            linkableToday(listOf(parked, priority, old, gone), wed) shouldBe listOf(priority)
        }
    }
})
