package net.robog.clarity.backend.sessions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import net.robog.clarity.backend.agent.AGENT_CANCEL_REASON
import net.robog.clarity.backend.agent.AutoNudge
import net.robog.clarity.backend.agent.CheckInTime
import net.robog.clarity.backend.agent.PlanCheckIn
import net.robog.clarity.backend.agent.PlanOutcome
import net.robog.clarity.backend.agent.SAVED_AGAIN_REASON
import net.robog.clarity.backend.agent.STARTING_REASON
import net.robog.clarity.backend.agent.combinedRefusal
import net.robog.clarity.backend.agent.eligibleCheckIns
import net.robog.clarity.backend.agent.linkableToday
import net.robog.clarity.backend.agent.planAutoNudges
import net.robog.clarity.backend.agent.planMove
import net.robog.clarity.backend.agent.readCheckInTime
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

private fun at(time: String?, cue: String = "it's $time", action: String = "spec") = IfThen(cue, action, at = time)

class StuckReview2PureTest : FunSpec({
    val spec = item("a", "write the onboarding spec", wed, 3)

    context("1. the start time is a structured field the lead fills in") {
        test("well formed, today, and ahead: a time") {
            readCheckInTime("09:30", wed, zone, now) shouldBe CheckInTime.Valid(at930)
            readCheckInTime(" 9:30 ", wed, zone, now) shouldBe CheckInTime.Valid(at930)
            readCheckInTime("23:59", wed, zone, now).shouldBeInstanceOf<CheckInTime.Valid>()
        }
        test("nothing given is missing, whatever the cue says") {
            readCheckInTime(null, wed, zone, now) shouldBe CheckInTime.Missing
            readCheckInTime("  ", wed, zone, now) shouldBe CheckInTime.Missing
        }
        test("anything but HH:MM is malformed") {
            for (raw in listOf("25:00", "9.30", "9:30pm", "noon", "9", "09:3", "09:60", "2026-03-04T09:30", "by 5pm")) {
                readCheckInTime(raw, wed, zone, now).shouldBeInstanceOf<CheckInTime.Malformed>()
            }
        }
        test("now or earlier has passed, and nothing is moved to another time") {
            readCheckInTime("08:00", wed, zone, now) shouldBe CheckInTime.Passed(now)
            readCheckInTime("07:15", wed, zone, now).shouldBeInstanceOf<CheckInTime.Passed>()
            readCheckInTime("09:30", wed, zone, Instant.parse("2026-03-04T21:00:00Z")).shouldBeInstanceOf<CheckInTime.Passed>()
        }
        test("a check-in is made only for an if-then with a valid at") {
            val out = planAutoNudges(
                listOf(
                    PlanCheckIn(at("09:30"), spec),
                    PlanCheckIn(IfThen("Dana emails", "it waits"), null),
                    PlanCheckIn(IfThen("by 5pm", "spec is sent"), spec), // a deadline: the lead leaves `at` empty
                    PlanCheckIn(at("25:00", action = "bad"), spec),
                    PlanCheckIn(at("07:00", action = "early"), spec),
                ),
                wed, zone, now, emptyList(), 0,
            )
            out.nudges.map { it.at } shouldBe listOf(at930)
            out.unscheduled shouldBe listOf("If Dana emails, then it waits.", "If by 5pm, then spec is sent.")
            out.skipped shouldHaveSize 2
            out.skipped[0] shouldContain "25:00"
            out.skipped[0] shouldContain "HH:MM"
            out.skipped[1] shouldContain "already passed"
        }
        test("only what is new is reported: an if-then of the earlier save that was reported then stays quiet") {
            val old = at("07:00", action = "early")
            val missing = IfThen("Dana emails", "it waits")
            val fresh = at("07:30", action = "later one")
            val out = planAutoNudges(
                listOf(PlanCheckIn(old, spec), PlanCheckIn(missing, null), PlanCheckIn(fresh, spec)),
                wed, zone, now, emptyList(), 0, previous = listOf(old, missing),
            )
            out.skipped.single() shouldContain "07:30"
            out.unscheduled.shouldBeEmpty()
        }
    }

    context("2. saving the plan again after the day's check-in has happened changes nothing") {
        val wanted = listOf(AutoNudge(at930, "note", "a"))
        val early = at930 - 60.minutes
        test("a check-in that fired") {
            val r = reconcileCheckIns(wanted, listOf(nudge(at930, "note", Nudge.Status.FIRED)), now)
            r.create.shouldBeEmpty()
            r.cancel.shouldBeEmpty()
        }
        test("a check-in that was skipped or missed") {
            reconcileCheckIns(wanted, listOf(nudge(at930, "note", Nudge.Status.SKIPPED)), now).create.shouldBeEmpty()
            reconcileCheckIns(wanted, listOf(nudge(at930, "note", Nudge.Status.MISSED)), now).create.shouldBeEmpty()
        }
        test("an item started today gets none, and the Starting that ended its check-in counts") {
            val started = spec.copy(startedOn = wed)
            eligibleCheckIns(listOf(PlanCheckIn(at("09:30"), started), PlanCheckIn(at("09:30"), spec)), wed) shouldHaveSize 1
            reconcileCheckIns(wanted, listOf(nudge(at930, "note", Nudge.Status.CANCELLED, reason = STARTING_REASON)), now).create.shouldBeEmpty()
        }
        test("a check-in the agent cancelled counts as settled and is never made again") {
            reconcileCheckIns(wanted, listOf(nudge(at930, "note", Nudge.Status.CANCELLED, reason = AGENT_CANCEL_REASON)), now).create.shouldBeEmpty()
            AGENT_CANCEL_REASON shouldBe "cancelled by the agent"
        }
        test("one this plan's own re-save cancelled does not block the next re-save") {
            val old = nudge(at930, "old", Nudge.Status.CANCELLED, reason = SAVED_AGAIN_REASON)
            reconcileCheckIns(wanted, listOf(old), now).create shouldHaveSize 1
            reconcileCheckIns(wanted, listOf(nudge(at930, "old", Nudge.Status.CANCELLED, reason = "no longer on today's plan")), now).create shouldHaveSize 1
        }
        test("a fired check-in does not stop one for another item") {
            reconcileCheckIns(listOf(AutoNudge(at930, "n", "b")), listOf(nudge(at930, "note", Nudge.Status.FIRED, item = "a")), now).create shouldHaveSize 1
        }
        test("a waiting check-in whose time has come is left to fire: not cancelled, and not replaced") {
            val due = nudge(early, "old", Nudge.Status.PENDING)
            val r = reconcileCheckIns(listOf(AutoNudge(at930, "new", "a")), listOf(due), now = early + 20.seconds())
            r.cancel.shouldBeEmpty()
            r.create.shouldBeEmpty() // the item is about to be asked
            // a later one is cancelled as before
            reconcileCheckIns(listOf(AutoNudge(at930 + 60.minutes, "new", "a")), listOf(nudge(at930, "old", Nudge.Status.PENDING)), now).cancel shouldHaveSize 1
        }
    }

    context("3. caps are not used up by check-ins that were never going to be set") {
        test("a check-in blocked because the item already had one today does not count toward the pending cap") {
            val full = (1..9).map { Nudge(NudgeId("p$it"), Instant.parse("2026-03-05T12:00:00Z") + (it * 20).minutes, "c$it", null, Nudge.Status.PENDING, now, planItemId = "x$it", autoCheckIn = true) }
            val fired = nudge(at930, "note", Nudge.Status.FIRED, item = "a")
            val out = planAutoNudges(
                listOf(PlanCheckIn(at("09:30"), spec), PlanCheckIn(at("11:00", action = "other"), item("b", "call the dentist"))),
                wed, zone, now, full, 0, existing = listOf(fired),
            )
            out.skipped.shouldBeEmpty()
            out.nudges.map { it.planItemId } shouldBe listOf("b")
        }
    }

    context("4. a replaced item is not called dropped") {
        test("the answer says it was replaced") {
            val msg = planMove(item("a", "spec").copy(state = PlanItemState.REPLACED), PlanOutcome.STARTED, null, null, wed, now, zone).leftOrNull().shouldNotBeNull()
            msg shouldContain "replaced"
            msg shouldNotContain "dropped"
        }
    }

    context("5. one ceiling over the person's and the plan's nudges") {
        test("20 waiting or 20 on a day, whoever owns them") {
            combinedRefusal(19, 19).shouldBeNull()
            combinedRefusal(20, 0).shouldNotBeNull() shouldContain "20"
            combinedRefusal(0, 20).shouldNotBeNull() shouldContain "20"
        }
        test("a check-in is refused when the person's nudges already fill the day") {
            val out = planAutoNudges(listOf(PlanCheckIn(at("09:30"), spec)), wed, zone, now, emptyList(), 0, personPending = 5, personDay = 20)
            out.nudges.shouldBeEmpty()
            out.skipped.single() shouldContain "20"
            planAutoNudges(listOf(PlanCheckIn(at("09:30"), spec)), wed, zone, now, emptyList(), 0, personPending = 5, personDay = 19).nudges shouldHaveSize 1
        }
    }

    context("6. sources do not mix") {
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

private fun Int.seconds() = kotlin.time.Duration.parse("${this}s")
