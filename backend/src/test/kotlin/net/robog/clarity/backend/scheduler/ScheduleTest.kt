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

    test("daily follows the person's zone, including the clock change") {
        val daily = Schedule.DailyAt(LocalTime(18, 0))
        // 2026-10-25 is the day Europe/Berlin returns to standard time (UTC+1 from 03:00 local)
        daily.nextAfter(Instant.parse("2026-10-24T20:00:00Z"), berlin) shouldBe Instant.parse("2026-10-25T17:00:00Z")
        daily.nextAfter(Instant.parse("2026-10-23T20:00:00Z"), berlin) shouldBe Instant.parse("2026-10-24T16:00:00Z")
    }

    test("a catch-up after downtime moves to the next slot after now, not through every missed one") {
        val daily = Schedule.DailyAt(LocalTime(3, 30))
        daily.nextAfter(Instant.parse("2026-10-28T09:00:00Z"), TimeZone.UTC) shouldBe Instant.parse("2026-10-29T03:30:00Z")
    }
})
