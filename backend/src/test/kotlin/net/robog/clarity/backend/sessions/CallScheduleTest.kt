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
    test("the next call is strictly after now, on a call day, in the person's zone") {
        // Friday 2026-10-23 09:00 CEST is 07:00Z; the next weekday call is Monday 09:00, after the clock change to CET.
        val friday = Instant.parse("2026-10-23T07:00:00Z")
        nextCallAfter(call(), friday, berlin) shouldBe Instant.parse("2026-10-26T08:00:00Z")
        nextCallAfter(call(), friday - 1.minutes, berlin) shouldBe friday
    }

    test("the same instant gives different next calls in different zones") {
        val now = Instant.parse("2026-10-26T00:00:00Z") // Monday 09:00 in Tokyo, Sunday 20:00 in New York, 01:00 in Berlin
        nextCallAfter(call(), now, tokyo) shouldBe Instant.parse("2026-10-27T00:00:00Z") // Tue 09:00 JST: Monday's call is exactly now
        nextCallAfter(call(), now, newYork) shouldBe Instant.parse("2026-10-26T13:00:00Z") // Mon 09:00 EDT
        nextCallAfter(call(), now, berlin) shouldBe Instant.parse("2026-10-26T08:00:00Z") // Mon 09:00 CET
    }

    test("prep runs the heads-up time before the call, else ten minutes before") {
        val now = Instant.parse("2026-10-26T06:00:00Z")
        nextPrepAfter(call(headsUp = 30), now, berlin) shouldBe Instant.parse("2026-10-26T07:30:00Z")
        nextPrepAfter(call(headsUp = null), now, berlin) shouldBe Instant.parse("2026-10-26T07:50:00Z")
        nextPrepAfter(call(headsUp = 10), now, berlin) shouldBe Instant.parse("2026-10-26T07:50:00Z")
        prepLead(call(headsUp = null)) shouldBe 10.minutes
    }

    test("after prep time but before the call, the next prep is the next call day's") {
        val between = Instant.parse("2026-10-26T07:55:00Z") // 08:55 CET, prep was 08:50
        nextPrepAfter(call(), between, berlin) shouldBe Instant.parse("2026-10-27T07:50:00Z")
    }

    test("a prep before midnight belongs to the next day's call") {
        val early = call(at = LocalTime(0, 5), days = setOf(1))
        val sundayNoon = Instant.parse("2026-10-25T12:00:00Z") // Sunday 13:00 CET
        nextPrepAfter(early, sundayNoon, berlin) shouldBe Instant.parse("2026-10-25T22:55:00Z") // Sunday 23:55 CET
        nextCallAfter(early, sundayNoon, berlin) shouldBe Instant.parse("2026-10-25T23:05:00Z")
    }

    test("day sets: weekend only, single day, all days") {
        val wednesday = Instant.parse("2026-10-28T12:00:00Z")
        nextCallAfter(call(days = setOf(6, 7)), wednesday, TimeZone.UTC) shouldBe Instant.parse("2026-10-31T09:00:00Z")
        nextCallAfter(call(days = setOf(3)), wednesday, TimeZone.UTC) shouldBe Instant.parse("2026-11-04T09:00:00Z")
        nextCallAfter(call(days = (1..7).toSet()), wednesday, TimeZone.UTC) shouldBe Instant.parse("2026-10-29T09:00:00Z")
    }

    test("a call time that does not exist on the clock change moves forward, and one that happens twice takes the first") {
        // 2026-03-29: Berlin jumps from 02:00 to 03:00; Sundays only.
        val sunday = call(at = LocalTime(2, 30), days = setOf(7))
        nextCallAfter(sunday, Instant.parse("2026-03-28T12:00:00Z"), berlin) shouldBe Instant.parse("2026-03-29T01:30:00Z") // 03:30 CEST
        // 2026-10-25: Berlin repeats 02:00-03:00.
        nextCallAfter(sunday, Instant.parse("2026-10-24T12:00:00Z"), berlin) shouldBe Instant.parse("2026-10-25T00:30:00Z") // first 02:30 (CEST)
    }

    test("a disabled call or one without days never comes") {
        val now = Instant.parse("2026-10-26T06:00:00Z")
        nextCallAfter(call(enabled = false), now, berlin) shouldBe null
        nextPrepAfter(call(days = emptySet()), now, berlin) shouldBe null
    }
})
