package net.robog.clarity.backend.scheduler

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

private val berlin = TimeZone.of("Europe/Berlin")

class ScheduleTest : FunSpec({
    test("an interval is measured from the previous run") {
        val at = Instant.parse("2026-10-25T10:00:00Z")
        Schedule.Every(2.hours).nextAfter(at, TimeZone.UTC) shouldBe Instant.parse("2026-10-25T12:00:00Z")
    }

    test("daily: later today if the time has not come, else tomorrow") {
        val daily = Schedule.DailyAt(LocalTime(18, 0))
        daily.nextAfter(Instant.parse("2026-10-25T10:00:00Z"), TimeZone.UTC) shouldBe Instant.parse("2026-10-25T18:00:00Z")
        daily.nextAfter(Instant.parse("2026-10-25T18:00:00Z"), TimeZone.UTC) shouldBe Instant.parse("2026-10-26T18:00:00Z")
        daily.nextAfter(Instant.parse("2026-10-25T20:00:00Z"), TimeZone.UTC) shouldBe Instant.parse("2026-10-26T18:00:00Z")
    }

    test("a stored daily time stays on the home clock through the clock change") {
        // 18:00 on the Berlin clock
        val daily = Schedule.DailyAt(LocalTime(18, 0))
        // 2026-10-25 is the day Europe/Berlin returns to standard time (UTC+1 from 03:00 local)
        daily.nextAfter(Instant.parse("2026-10-24T20:00:00Z"), berlin) shouldBe Instant.parse("2026-10-25T17:00:00Z")
        daily.nextAfter(Instant.parse("2026-10-23T20:00:00Z"), berlin) shouldBe Instant.parse("2026-10-24T16:00:00Z")
    }

    test("the evening batch at 19:00 in New York fires at 7 pm there on both sides of the clock change") {
        val evening = Schedule.DailyAt(LocalTime(19, 0))
        val newYork = TimeZone.of("America/New_York")
        // the reported bug: it fired at 18:00Z (2 pm EDT) on 2026-10-26
        evening.nextAfter(Instant.parse("2026-10-26T12:00:00Z"), newYork) shouldBe Instant.parse("2026-10-26T23:00:00Z")
        // clocks go back on 2026-11-01: 19:00 EST is midnight UTC
        evening.nextAfter(Instant.parse("2026-10-31T23:00:00Z"), newYork) shouldBe Instant.parse("2026-11-02T00:00:00Z")
        // and forward on 2027-03-14
        evening.nextAfter(Instant.parse("2027-03-13T12:00:00Z"), newYork) shouldBe Instant.parse("2027-03-14T00:00:00Z")
        evening.nextAfter(Instant.parse("2027-03-14T00:00:00Z"), newYork) shouldBe Instant.parse("2027-03-14T23:00:00Z")
    }

    test("a catch-up after downtime moves to the next slot after now, not through every missed one") {
        val daily = Schedule.DailyAt(LocalTime(3, 30))
        daily.nextAfter(Instant.parse("2026-10-28T09:00:00Z"), TimeZone.UTC) shouldBe Instant.parse("2026-10-29T03:30:00Z")
    }
})
