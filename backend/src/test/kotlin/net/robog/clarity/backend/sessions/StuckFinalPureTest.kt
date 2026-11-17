package net.robog.clarity.backend.sessions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import net.robog.clarity.backend.agent.PlanCheckIn
import net.robog.clarity.backend.agent.planAutoNudges
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.feed.Nudge
import net.robog.clarity.shared.feed.NudgeId

private val wed = LocalDate(2026, 3, 4)
private val zone = TimeZone.UTC
private val now = Instant.parse("2026-03-04T08:00:00Z")
private val at930 = Instant.parse("2026-03-04T09:30:00Z")

private fun settled(at: Instant, item: String) =
    Nudge(NudgeId("f"), at, "note", null, Nudge.Status.FIRED, now, planItemId = item, autoCheckIn = true)

class StuckFinalPureTest : FunSpec({
    val spec = PlanItem("a", "write the onboarding spec", wed, wed, 3)
    val base = IfThen("it's 9:30", "spec", at = "09:30")

    context("what was reported before is compared whole") {
        val full = (1..10).map { Nudge(NudgeId("p$it"), Instant.parse("2026-03-05T12:00:00Z").plus(kotlin.time.Duration.parse("${it * 20}m")), "c$it", null, Nudge.Status.PENDING, now, planItemId = "x$it", autoCheckIn = true) }
        fun plan(ifThen: IfThen, previous: List<IfThen>, pending: List<Nudge> = emptyList(), existing: List<Nudge> = emptyList()) =
            planAutoNudges(listOf(PlanCheckIn(ifThen, spec)), wed, zone, now, pending, 0, existing = existing, previous = previous)

        test("a cap that refused it is reported once, not on every save") {
            plan(base, emptyList(), pending = full).skipped.single() shouldContain "already 10"
            plan(base, listOf(base), pending = full).skipped.shouldBeEmpty()
        }
        test("the same time and cue with another first step or another item is new again") {
            val old = base.copy(item = "a")
            plan(base.copy(item = "a"), listOf(old), pending = full).skipped.shouldBeEmpty()
            plan(base.copy(item = "a", firstStep = "open the doc"), listOf(old), pending = full).skipped shouldHaveSize 1
            plan(base.copy(item = "b"), listOf(old), pending = full).skipped shouldHaveSize 1
        }
    }

    context("moving the time of an item whose check-in already fired") {
        val fired = settled(at930, "a")
        test("says it kept the earlier check-in, once") {
            val moved = base.copy(at = "11:00", cue = "it's 11:00")
            val out = planAutoNudges(listOf(PlanCheckIn(moved, spec)), wed, zone, now, emptyList(), 0, existing = listOf(fired), previous = listOf(base))
            out.nudges.shouldBeEmpty()
            out.skipped.single() shouldContain "kept the earlier check-in"
            planAutoNudges(listOf(PlanCheckIn(moved, spec)), wed, zone, now, emptyList(), 0, existing = listOf(fired), previous = listOf(moved)).skipped.shouldBeEmpty()
        }
        test("an unchanged re-save stays silent") {
            planAutoNudges(listOf(PlanCheckIn(base, spec)), wed, zone, now, emptyList(), 0, existing = listOf(fired), previous = listOf(base)).skipped shouldBe emptyList()
        }
    }

    context("the wording says what happened to the earlier check-in") {
        test("cancelled, about to fire, fired") {
            val moved = base.copy(at = "11:00", cue = "it's 11:00")
            fun say(n: Nudge) = planAutoNudges(listOf(PlanCheckIn(moved, spec)), wed, zone, now, emptyList(), 0, existing = listOf(n)).skipped.single()
            say(settled(at930, "a").copy(status = Nudge.Status.CANCELLED, reason = "cancelled by the agent")) shouldContain "was cancelled (cancelled by the agent)"
            say(settled(at930, "a").copy(status = Nudge.Status.PENDING, at = now - kotlin.time.Duration.parse("1m"))) shouldContain "about to fire"
            say(settled(at930, "a")) shouldContain "kept the earlier check-in"
        }
    }

    context("what the lead is told about a due check-in") {
        test("a check-in is not counted against the daily notifications, and a plan item's card carries its ref") {
            val plain = net.robog.clarity.backend.agent.InboxItem.NudgeDue("n", "09:30", "ask", null).summary
            plain shouldContain "never counted"
            plain shouldNotContain "counts toward"
            val plan = net.robog.clarity.backend.agent.InboxItem.NudgeDue("n", "09:30", "ask", null, "id abc, \"spec\", day 3").summary
            plan shouldContain "ref set to \"plan:<the plan item id above>\""
            plan shouldContain "Tap answers card ref plan:<id>"
            plan shouldNotContain "counts toward"
        }
    }
})
