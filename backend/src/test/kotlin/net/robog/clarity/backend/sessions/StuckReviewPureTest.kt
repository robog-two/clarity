package net.robog.clarity.backend.sessions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import net.robog.clarity.backend.agent.AutoNudge
import net.robog.clarity.backend.agent.PlanCheckIn
import net.robog.clarity.backend.agent.coveringCheckIn
import net.robog.clarity.backend.agent.isPlan
import net.robog.clarity.backend.agent.linkedItem
import net.robog.clarity.backend.agent.planAutoNudges
import net.robog.clarity.backend.agent.planCheckInNote
import net.robog.clarity.backend.agent.planItemWakeText
import net.robog.clarity.backend.agent.reconcileCheckIns
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.feed.Nudge
import net.robog.clarity.shared.feed.NudgeId

private val mon = LocalDate(2026, 3, 2)
private val tue = LocalDate(2026, 3, 3)
private val wed = LocalDate(2026, 3, 4)
private val zone = TimeZone.UTC
private val now = Instant.parse("2026-03-04T08:00:00Z")

private fun item(id: String, text: String, last: LocalDate, days: Int = 1) = PlanItem(id, text, last, last, days)

private fun ids() = generateSequence(1) { it + 1 }.map { "new$it" }.iterator().let { { it.next() } }

private fun pending(
    at: Instant,
    note: String = "note",
    item: String? = null,
    auto: Boolean = false,
    followUp: Boolean = false,
    id: String = "n${at.toEpochMilliseconds()}$note",
) = Nudge(NudgeId(id), at, note, null, Nudge.Status.PENDING, now, planItemId = item, autoCheckIn = auto, followUp = followUp)

class StuckReviewPureTest : FunSpec({
    val at930 = Instant.parse("2026-03-04T09:30:00Z")
    val spec = item("a", "write the onboarding spec", wed, 3)
    val dentist = item("b", "call the dentist", wed)

    context("1. two items at the same time each get their check-in") {
        test("covered means the same plan item") {
            val out = planAutoNudges(
                listOf(PlanCheckIn(IfThen("it's 9:30", "spec"), spec), PlanCheckIn(IfThen("it's 9:30", "dentist call"), dentist)),
                wed, zone, now, emptyList(), 0,
            )
            out.nudges.map { it.planItemId } shouldBe listOf("a", "b")
        }
        test("an earlier check-in for another item does not cover, one for the same item does") {
            val other = pending(at930, "x", item = "b", auto = true)
            planAutoNudges(listOf(PlanCheckIn(IfThen("it's 9:30", "spec"), spec)), wed, zone, now, listOf(other), 0).nudges shouldHaveSize 1
            planAutoNudges(listOf(PlanCheckIn(IfThen("it's 9:30", "spec"), spec)), wed, zone, now, listOf(pending(at930, "x", item = "a", auto = true)), 0).nudges.shouldBeEmpty()
        }
    }

    context("2. the model's own nudge") {
        val auto = pending(at930, "Plan check-in", item = "a", auto = true)
        val items = mapOf("a" to spec)
        test("a reminder about something else near a check-in is not covered") {
            coveringCheckIn(at930 + 5.minutes, null, listOf("remind me to call the dentist"), listOf(auto), items).shouldBeNull()
        }
        test("the same plan item, by id or by subject, is covered") {
            coveringCheckIn(at930 + 5.minutes, "a", emptyList(), listOf(auto), items) shouldBe auto
            coveringCheckIn(at930 + 5.minutes, null, listOf("ask how the onboarding spec is going", "write the onboarding spec"), listOf(auto), items) shouldBe auto
            coveringCheckIn(at930 + 11.minutes, "a", emptyList(), listOf(auto), items).shouldBeNull()
            coveringCheckIn(at930, "b", emptyList(), listOf(auto), items).shouldBeNull()
        }
    }

    context("3. a second set_plan replaces the check-ins of the first") {
        fun wanted(at: Instant, note: String, item: String? = "a") = AutoNudge(at, note, item)
        val old = pending(at930, "old note", item = "a", auto = true)
        test("the same check-in stays, nothing changes") {
            val r = reconcileCheckIns(listOf(wanted(at930, "old note")), listOf(old))
            r.cancel.shouldBeEmpty()
            r.create.shouldBeEmpty()
        }
        test("replace at the same time: the old one is cancelled, the new one made") {
            val r = reconcileCheckIns(listOf(wanted(at930, "new note")), listOf(old))
            r.cancel shouldBe listOf(old)
            r.create.single().note shouldBe "new note"
        }
        test("replace at a new time") {
            val r = reconcileCheckIns(listOf(wanted(at930 + 60.minutes, "old note")), listOf(old))
            r.cancel shouldBe listOf(old)
            r.create.single().at shouldBe at930 + 60.minutes
        }
        test("a check-in no longer wanted is cancelled; follow-ups and the person's nudges are not in the set") {
            reconcileCheckIns(emptyList(), listOf(old)).cancel shouldBe listOf(old)
            (pending(at930, "later", item = "a", followUp = true).isPlan) shouldBe true
            (pending(at930, "mine").isPlan) shouldBe false
            (pending(at930, "x", auto = true).isPlan) shouldBe true
        }
    }

    context("4/5. reading the time of a cue") {
        test("more ways to say it") {
            clockTimeIn("it's 9.30") shouldBe LocalTime(9, 30)
            clockTimeIn("at noon") shouldBe LocalTime(12, 0)
            clockTimeIn("at midnight") shouldBe LocalTime(0, 0)
            clockTimeIn("between 9 and 10am") shouldBe LocalTime(9, 0)
            clockTimeIn("between 9:30 and 11 am") shouldBe LocalTime(9, 30)
            clockTimeIn("it's 3:30") shouldBe LocalTime(15, 30) // a bare 1 to 6 is afternoon
            clockTimeIn("it's 7:30") shouldBe LocalTime(7, 30)
            clockTimeIn("it's 14:00") shouldBe LocalTime(14, 0)
            clockTimeIn("afternoon").shouldBeNull()
            clockTimeIn("version 3.14.2 ships").shouldBeNull()
        }
        test("the cues with no time are listed so the lead can nudge itself") {
            val out = planAutoNudges(
                listOf(PlanCheckIn(IfThen("Dana emails", "it waits"), null), PlanCheckIn(IfThen("after lunch", "spec"), spec), PlanCheckIn(IfThen("it's 9:30", "spec"), spec)),
                wed, zone, now, emptyList(), 0,
            )
            out.unscheduled shouldBe listOf("If Dana emails, then it waits.", "If after lunch, then spec.")
            out.nudges shouldHaveSize 1
        }
    }

    context("6. wording that is close, not just similar") {
        test("different report parts and different numbers are different things") {
            sameWording("write report intro", "write report summary") shouldBe false
            sameWording("prepare Q3 budget", "prepare Q4 budget") shouldBe false
            sameWording("prepare Q3 budget", "prepare the Q3 budget") shouldBe true
        }
        test("up to three words all must match, longer wordings need four in five") {
            sameWording("write the onboarding spec", "finish the onboarding spec") shouldBe false
            sameWording("write onboarding spec", "write the onboarding spec!") shouldBe true
            sameWording("call the dentist", "call the dentist today") shouldBe true
            sameWording("spec", "spec") shouldBe true
            sameWording("spec", "write spec") shouldBe false
            sameWording("review the quarterly sales report for the board", "review quarterly sales report for board meeting") shouldBe true
            sameWording("review the quarterly sales report for the board", "review quarterly marketing budget board meeting") shouldBe false
        }
        test("the best candidate wins, whatever the order, and ties go to the lower id") {
            val open = listOf(item("z", "prepare the budget review", mon), item("b", "prepare the budget review meeting notes", mon), item("a", "prepare the budget review", mon))
            carryOver(open, listOf(Planned("prepare the budget review")), tue, ids()).single().id shouldBe "a"
            carryOver(open.reversed(), listOf(Planned("prepare the budget review")), tue, ids()).single().id shouldBe "a"
            val close = listOf(item("a", "plan team offsite agenda", mon), item("b", "plan team offsite", mon))
            carryOver(close, listOf(Planned("plan team offsite")), tue, ids()).single().id shouldBe "b"
        }
    }

    context("7. replaced priorities do not stay planned") {
        test("an item the person dropped from a redone list goes back to where it was") {
            val yesterday = item("a", "write the spec", mon, 2)
            val first = carryOver(listOf(yesterday), listOf(Planned("write the spec")), tue, ids())
            first.single().let { it.lastDate shouldBe tue; it.prevLastDate shouldBe mon; it.prevDaysCarried shouldBe 2 }
            val second = carryOver(first, listOf(Planned("call the dentist")), tue, { "other" })
            val reverted = replaceUnplanned(first, second, tue, PlanSource.PRIORITY)
            reverted.single().let { it.lastDate shouldBe mon; it.daysCarried shouldBe 2; it.state shouldBe PlanItemState.OPEN }
        }
        test("a new item that was replaced the same day is marked replaced") {
            val fresh = carryOver(emptyList(), listOf(Planned("write the spec")), tue, ids())
            val second = carryOver(fresh, listOf(Planned("call the dentist")), tue, { "other" })
            replaceUnplanned(fresh, second, tue, PlanSource.PRIORITY).single().state shouldBe PlanItemState.REPLACED
        }
        test("items that are kept, other days' items and parked items are left alone") {
            val parked = item("p", "expense report", tue).copy(source = PlanSource.PARKED)
            val kept = item("k", "write the spec", tue)
            val older = item("o", "taxes", mon)
            replaceUnplanned(listOf(parked, kept, older), listOf(kept), tue, PlanSource.PRIORITY).shouldBeEmpty()
        }
    }

    context("8. an if-then links only by id or by real overlap") {
        test("an unrelated call does not attach to the only priority") {
            linkedItem(IfThen("it's 3pm", "call the dentist"), null, listOf(spec)).shouldBeNull()
            linkedItem(IfThen("it's 9:30", "at my desk until 12"), null, listOf(spec)).shouldBeNull()
        }
        test("the words of the item, nearly all of them, link it") {
            linkedItem(IfThen("it's 9:30", "write the onboarding spec at my desk"), null, listOf(dentist, spec)) shouldBe spec
            linkedItem(IfThen("it's 9:30", "write the spec"), null, listOf(spec)).shouldBeNull() // onboarding is missing
        }
        test("ties link nothing; an explicit id always works") {
            val a = item("a", "call the dentist", wed)
            val b = item("b", "call the dentist office", wed)
            linkedItem(IfThen("it's 9:30", "call the dentist office"), null, listOf(a, b)) shouldBe b
            linkedItem(IfThen("it's 9:30", "call the dentist"), null, listOf(a.copy(id = "c"), a.copy(id = "d"))).shouldBeNull()
            linkedItem(IfThen("it's 9:30", "anything"), "b", listOf(a, b)) shouldBe b
        }
    }

    context("9. plan check-ins have their own caps") {
        val person = (1..10).map { pending(Instant.parse("2026-03-05T12:00:00Z") + it.minutes * 20, "mine $it") }
        test("the person's ten pending reminders do not block a check-in, and are not counted in its day") {
            val planOnly = person.filter { it.isPlan }
            planAutoNudges(listOf(PlanCheckIn(IfThen("it's 9:30", "spec"), spec)), wed, zone, now, planOnly, 0).nudges shouldHaveSize 1
        }
        test("check-ins have a cap of their own") {
            val full = (1..10).map { pending(Instant.parse("2026-03-05T12:00:00Z") + it.minutes * 20, "c$it", item = "x$it", auto = true) }
            planAutoNudges(listOf(PlanCheckIn(IfThen("it's 9:30", "spec"), spec)), wed, zone, now, full, 0).skipped.single() shouldContain "already 10"
        }
    }

    context("11. a first step belongs to the day it was agreed") {
        val stepped = spec.copy(firstStep = "write the title", firstStepOn = tue)
        test("the same day it is the first step, later it is an earlier one") {
            firstStepNote(stepped, tue) shouldBe "first step: write the title"
            firstStepNote(stepped, wed) shouldBe "earlier first step: write the title"
            firstStepNote(spec, wed).shouldBeNull()
        }
        test("the prep line, the check-in note and the wake-up say so") {
            stuckLine(stepped, wed) shouldContain "earlier first step: write the title"
            planCheckInNote(IfThen("it's 9:30", "spec"), stepped, wed) shouldContain "Earlier first step: write the title."
            planCheckInNote(IfThen("it's 9:30", "spec"), stepped.copy(firstStepOn = wed), wed) shouldContain "First step: write the title."
            planCheckInNote(IfThen("it's 9:30", "spec", firstStep = "open the doc"), stepped, wed) shouldContain "First step: open the doc."
        }
    }

    context("12. the wake-up text for a plan item") {
        test("id, text, day, step and follow-ups left") {
            val text = planItemWakeText(spec.copy(firstStep = "open the doc", firstStepOn = wed), wed, 2)
            text shouldBe "id a, \"write the onboarding spec\", day 3, first step: open the doc, 2 follow-up check-ins left today"
            planItemWakeText(spec.copy(firstStep = "open the doc", firstStepOn = tue), LocalDate(2026, 3, 5), 1) shouldBe
                "id a, \"write the onboarding spec\", day 4, earlier first step: open the doc, 1 follow-up check-in left today"
            planItemWakeText(spec, wed, 0) shouldNotContain "step"
        }
    }
})

