package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val t0 = Instant.parse("2026-10-27T14:00:00Z")
private val until = t0 + 8.hours

private fun ev(from: Int, to: Int) = BusyEvent(t0 + from.minutes, t0 + to.minutes)
private fun free(events: List<BusyEvent>, after: Instant = t0, minGap: kotlin.time.Duration = 15.minutes, limit: Instant = until) =
    nextFreeStart(events, after, minGap, limit)

class FreeSlotTest : FunSpec({
    test("an empty calendar is free now") { free(emptyList()) shouldBe t0 }

    test("a gap before the first event is used") { free(listOf(ev(30, 60))) shouldBe t0 }

    test("an event spanning after pushes to its end") { free(listOf(ev(-30, 45))) shouldBe t0 + 45.minutes }

    test("a gap of exactly minGap is enough") { free(listOf(ev(-10, 0), ev(15, 60))) shouldBe t0 }

    test("a gap one minute too short is skipped") {
        free(listOf(ev(-10, 5), ev(19, 60))) shouldBe t0 + 60.minutes
        free(listOf(ev(-10, 5), ev(20, 60))) shouldBe t0 + 5.minutes
    }

    test("back-to-back events leave no gap") { free(listOf(ev(-5, 30), ev(30, 60), ev(60, 90))) shouldBe t0 + 90.minutes }

    test("overlapping events merge, whatever the order") {
        free(listOf(ev(40, 120), ev(-5, 50), ev(10, 20))) shouldBe t0 + 120.minutes
    }

    test("a gap between events is found") { free(listOf(ev(-5, 30), ev(60, 90))) shouldBe t0 + 30.minutes }

    test("no slot when the only gap starts after until") { free(listOf(ev(-5, 4 * 60), ev(4 * 60, 8 * 60 + 1))) shouldBe null }

    test("a slot starting exactly at until is allowed") { free(listOf(ev(-5, 4 * 60), ev(4 * 60, 8 * 60))) shouldBe until }

    test("an event ended before after does not matter") { free(listOf(ev(-120, -60))) shouldBe t0 }

    test("all-day events never count, single-day or multi-day") {
        val day = Instant.parse("2026-10-27T00:00:00Z")
        free(listOf(BusyEvent(day, day + 24.hours, allDay = true))) shouldBe t0
        free(listOf(BusyEvent(day - 24.hours, day + 72.hours, allDay = true))) shouldBe t0
    }

    test("an event marked free does not count") { free(listOf(BusyEvent(t0, t0 + 1.hours, free = true))) shouldBe t0 }

    test("a timed event of exactly 5h is busy, 5h01m is not") {
        LONG_EVENT shouldBe 5.hours
        free(listOf(BusyEvent(t0 - 1.hours, t0 - 1.hours + LONG_EVENT))) shouldBe t0 + 4.hours
        free(listOf(BusyEvent(t0 - 1.hours, t0 - 1.hours + LONG_EVENT + 1.minutes))) shouldBe t0
    }

    test("empty and backwards events are ignored") { free(listOf(ev(10, 10), ev(30, 20))) shouldBe t0 }

    test("DST: gaps are elapsed time across the fall-back hour") {
        // New York falls back at 2026-11-01T06:00Z.
        val after = Instant.parse("2026-11-01T05:00:00Z")
        val busy = listOf(
            BusyEvent(after, Instant.parse("2026-11-01T06:30:00Z")),
            BusyEvent(Instant.parse("2026-11-01T06:40:00Z"), Instant.parse("2026-11-01T08:00:00Z")),
        )
        nextFreeStart(busy, after, 15.minutes, after + 8.hours) shouldBe Instant.parse("2026-11-01T08:00:00Z")
        nextFreeStart(busy, after, 10.minutes, after + 8.hours) shouldBe Instant.parse("2026-11-01T06:30:00Z")
    }

    test("DST: spring-forward does not shift the result") {
        val after = Instant.parse("2026-03-08T06:30:00Z")
        val busy = listOf(BusyEvent(after, Instant.parse("2026-03-08T07:30:00Z")))
        nextFreeStart(busy, after, 15.minutes, after + 8.hours) shouldBe Instant.parse("2026-03-08T07:30:00Z")
    }
})
