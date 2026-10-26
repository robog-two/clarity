package net.robog.clarity.backend.sessions

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import net.robog.clarity.shared.session.Settings

private val berlin = TimeZone.of("Europe/Berlin")
private val newYork = TimeZone.of("America/New_York")
private val tokyo = TimeZone.of("Asia/Tokyo")

private fun call(at: LocalTime = LocalTime(9, 0), days: Set<Int> = setOf(1, 2, 3, 4, 5), headsUp: Int? = 10, enabled: Boolean = true) =
    Settings.MorningCall(enabled = enabled, at = at, days = days, headsUpMinutes = headsUp)

class CallScheduleTest : FunSpec({
    test("the next call is strictly after now, on a call day, on the home clock") {
        // Stored 08:00 = 09:00 Berlin. Friday 2026-10-23 09:00 CEST is 07:00Z; the next weekday call is Monday 09:00, after the clock change to CET.
        val friday = Instant.parse("2026-10-23T07:00:00Z")
        nextCallAfter(call(at = LocalTime(8, 0)), friday, berlin) shouldBe Instant.parse("2026-10-26T08:00:00Z")
        nextCallAfter(call(at = LocalTime(8, 0)), friday - 1.minutes, berlin) shouldBe friday
    }

    test("the home zone's daylight saving moves the moment, a zone without it does not") {
        // Stored 13:30 = 08:30 in New York; clocks go back on 2026-11-01.
        nextCallAfter(call(at = LocalTime(13, 30)), Instant.parse("2026-10-30T00:00:00Z"), newYork) shouldBe Instant.parse("2026-10-30T12:30:00Z")
        nextCallAfter(call(at = LocalTime(13, 30)), Instant.parse("2026-10-31T00:00:00Z"), newYork) shouldBe Instant.parse("2026-11-02T13:30:00Z")
        // Stored 00:00 = 09:00 in Tokyo all year.
        nextCallAfter(call(at = LocalTime(0, 0)), Instant.parse("2026-10-25T12:00:00Z"), tokyo) shouldBe Instant.parse("2026-10-26T00:00:00Z")
    }

    test("prep runs the heads-up time before the call, else ten minutes before") {
        val now = Instant.parse("2026-10-26T06:00:00Z")
        nextPrepAfter(call(at = LocalTime(8, 0), headsUp = 30), now, berlin) shouldBe Instant.parse("2026-10-26T07:30:00Z")
        nextPrepAfter(call(at = LocalTime(8, 0), headsUp = null), now, berlin) shouldBe Instant.parse("2026-10-26T07:50:00Z")
        nextPrepAfter(call(at = LocalTime(8, 0), headsUp = 10), now, berlin) shouldBe Instant.parse("2026-10-26T07:50:00Z")
        prepLead(call(headsUp = null)) shouldBe 10.minutes
    }

    test("after prep time but before the call, the next prep is the next call day's") {
        val between = Instant.parse("2026-10-26T07:55:00Z") // 08:55 CET, prep was 08:50
        nextPrepAfter(call(at = LocalTime(8, 0)), between, berlin) shouldBe Instant.parse("2026-10-27T07:50:00Z")
    }

    test("a prep before midnight belongs to the next day's call") {
        val early = call(at = LocalTime(0, 5), days = setOf(1))
        val sundayNoon = Instant.parse("2026-10-25T12:00:00Z")
        nextPrepAfter(early, sundayNoon, TimeZone.UTC) shouldBe Instant.parse("2026-10-25T23:55:00Z")
        nextCallAfter(early, sundayNoon, TimeZone.UTC) shouldBe Instant.parse("2026-10-26T00:05:00Z")
    }

    test("day sets: weekend only, single day, all days") {
        val wednesday = Instant.parse("2026-10-28T12:00:00Z")
        nextCallAfter(call(days = setOf(6, 7)), wednesday, TimeZone.UTC) shouldBe Instant.parse("2026-10-31T09:00:00Z")
        nextCallAfter(call(days = setOf(3)), wednesday, TimeZone.UTC) shouldBe Instant.parse("2026-11-04T09:00:00Z")
        nextCallAfter(call(days = (1..7).toSet()), wednesday, TimeZone.UTC) shouldBe Instant.parse("2026-10-29T09:00:00Z")
    }

    test("a disabled call or one without days never comes") {
        val now = Instant.parse("2026-10-26T06:00:00Z")
        nextCallAfter(call(enabled = false), now, berlin) shouldBe null
        nextPrepAfter(call(days = emptySet()), now, berlin) shouldBe null
    }
})
