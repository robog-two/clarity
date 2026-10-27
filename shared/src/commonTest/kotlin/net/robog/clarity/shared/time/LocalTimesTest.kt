package net.robog.clarity.shared.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class LocalTimesTest {
    private val newYork = TimeZone.of("America/New_York")
    private val berlin = TimeZone.of("Europe/Berlin")
    private val kolkata = TimeZone.of("Asia/Kolkata")
    private fun at(s: String) = Instant.parse(s)
    private fun moment(raw: String, zone: TimeZone) = LocalTimes.parseMoment(raw, zone)

    @Test fun anEveningEventInNewYorkShowsAtSevenOnItsOwnDay() {
        // 23:00Z is 19:00 the same Monday in New York, not "Tuesday" as the UTC date suggests.
        assertEquals("2026-10-26T19:00-04:00 (Mon)", LocalTimes.format(at("2026-10-26T23:00:00Z"), newYork))
    }

    @Test fun midnightCrossingMovesTheDateBack() {
        // 02:30Z on Tuesday is still Monday evening in New York.
        assertEquals("2026-10-26T22:30-04:00 (Mon)", LocalTimes.format(at("2026-10-27T02:30:00Z"), newYork))
        // And forward east of UTC: 20:00Z Monday is 01:30 Tuesday in India.
        assertEquals("2026-10-27T01:30+05:30 (Tue)", LocalTimes.format(at("2026-10-26T20:00:00Z"), kolkata))
    }

    @Test fun secondsShowOnlyWhenNotZero() {
        assertEquals("2026-10-26T08:03:27-04:00 (Mon)", LocalTimes.format(at("2026-10-26T12:03:27.5Z"), newYork))
    }

    @Test fun theOffsetFollowsDaylightTime() {
        assertEquals("2026-10-31T19:00-04:00 (Sat)", LocalTimes.format(at("2026-10-31T23:00:00Z"), newYork))
        assertEquals("2026-11-01T19:00-05:00 (Sun)", LocalTimes.format(at("2026-11-02T00:00:00Z"), newYork))
        assertEquals("2026-10-26T12:00+00:00 (Mon)", LocalTimes.format(at("2026-10-26T12:00:00Z"), TimeZone.UTC))
    }

    @Test fun anInvalidZoneFallsBackToUtc() {
        assertEquals("2026-10-26T23:00+00:00 (Mon)", LocalTimes.format(at("2026-10-26T23:00:00Z"), zoneOrUtc("Mars/Olympus")))
        assertEquals(at("2026-10-26T19:00:00Z"), moment("2026-10-26T19:00", zoneOrUtc("not a zone")))
    }

    @Test fun formatRangeShortensTheEndOnTheSameDay() {
        assertEquals(
            "2026-10-26T19:00-04:00 (Mon) to 20:30",
            LocalTimes.formatRange(at("2026-10-26T23:00:00Z"), at("2026-10-27T00:30:00Z"), newYork),
        )
        assertEquals(
            "2026-10-26T23:00-04:00 (Mon) to 2026-10-27T01:00-04:00 (Tue)",
            LocalTimes.formatRange(at("2026-10-27T03:00:00Z"), at("2026-10-27T05:00:00Z"), newYork),
        )
        // Same date, but clocks went back in between: both offsets are needed.
        assertEquals(
            "2026-11-01T00:30-04:00 (Sun) to 2026-11-01T01:30-05:00 (Sun)",
            LocalTimes.formatRange(at("2026-11-01T04:30:00Z"), at("2026-11-01T06:30:00Z"), newYork),
        )
    }

    @Test fun nowNamesTheZone() {
        assertEquals("2026-10-26T15:03-04:00 (Mon), America/New_York", LocalTimes.now(at("2026-10-26T19:03:00Z"), newYork))
    }

    @Test fun formatDateAddsTheWeekday() {
        assertEquals("2026-10-26 (Mon)", LocalTimes.formatDate(LocalDate(2026, 10, 26)))
    }

    @Test fun allDayEventsAreReadAsUtcDates() {
        // Stored as UTC midnights: in New York the instant is the evening before, but the day is the 26th.
        assertEquals(
            LocalDate(2026, 10, 26) to LocalDate(2026, 10, 26),
            LocalTimes.allDayDates(at("2026-10-26T00:00:00Z"), at("2026-10-27T00:00:00Z")),
        )
        assertEquals(
            LocalDate(2026, 10, 26) to LocalDate(2026, 10, 28),
            LocalTimes.allDayDates(at("2026-10-26T00:00:00Z"), at("2026-10-29T00:00:00Z")),
        )
        assertEquals(
            LocalDate(2026, 10, 26) to LocalDate(2026, 10, 26),
            LocalTimes.allDayDates(at("2026-10-26T00:00:00Z"), at("2026-10-26T00:00:00Z")),
        )
    }

    @Test fun instantsWithAnOffsetOrZAreTakenAsTheyAre() {
        assertEquals(at("2026-10-26T23:00:00Z"), moment("2026-10-26T23:00:00Z", newYork))
        assertEquals(at("2026-10-26T23:00:00Z"), moment("2026-10-26T19:00-04:00", berlin))
        assertEquals(at("2026-10-26T23:00:00Z"), moment("2026-10-26T19:00:00-0400", berlin))
        assertEquals(at("2026-10-26T14:30:00Z"), moment("2026-10-26T20:00+05:30", newYork))
        assertEquals(at("2026-10-26T23:00:00.250Z"), moment("2026-10-26T23:00:00.25Z", newYork))
    }

    @Test fun whatIsShownCanBePassedBack() {
        val shown = LocalTimes.format(at("2026-10-26T23:00:00Z"), newYork)
        assertEquals(at("2026-10-26T23:00:00Z"), moment(shown, TimeZone.UTC))
        assertEquals(LocalDate(2026, 10, 26), LocalTimes.parseDate("2026-10-26 (Mon)"))
    }

    @Test fun localTimesWithoutAnOffsetAreOnTheHomeClock() {
        assertEquals(at("2026-10-26T23:00:00Z"), moment("2026-10-26T19:00", newYork))
        assertEquals(at("2026-10-26T23:00:00Z"), moment("2026-10-26 19:00:00", newYork))
        assertEquals(at("2026-10-26T18:00:00Z"), moment("2026-10-26T19:00", berlin))
        assertEquals(LocalTimes.Parsed.At(at("2026-10-26T13:00:00Z"), hadOffset = false), LocalTimes.parse("2026-10-26T9:00", newYork))
    }

    @Test fun aTimeSkippedWhenClocksGoForwardHappensThatMuchLater() {
        // New York skips 02:00-03:00 on 2027-03-14: 02:30 is 03:30 EDT, as ScheduleClock does it.
        assertEquals(at("2027-03-14T07:30:00Z"), moment("2027-03-14T02:30", newYork))
        assertEquals(ScheduleClock.occurrence(LocalDate(2027, 3, 14), kotlinx.datetime.LocalTime(2, 30), newYork), moment("2027-03-14T02:30", newYork))
    }

    @Test fun aTimeThatHappensTwiceWhenClocksGoBackIsTheFirst() {
        // 01:30 happens at 05:30Z (EDT) and again at 06:30Z (EST) on 2026-11-01.
        assertEquals(at("2026-11-01T05:30:00Z"), moment("2026-11-01T01:30", newYork))
        assertEquals(at("2026-11-01T06:30:00Z"), moment("2026-11-01T01:30-05:00", newYork))
    }

    @Test fun aPlainDateIsTheWholeLocalDay() {
        val day = LocalTimes.parse("2026-10-26", newYork)
        assertEquals(LocalTimes.Parsed.Day(LocalDate(2026, 10, 26), at("2026-10-26T04:00:00Z"), at("2026-10-27T04:00:00Z")), day)
        // The day clocks go back is 25 hours long.
        val long = LocalTimes.parse("2026-11-01", newYork) as LocalTimes.Parsed.Day
        assertEquals(25, (long.end - long.start).inWholeHours)
        assertEquals(at("2026-10-26T04:00:00Z"), moment("2026-10-26", newYork))
    }

    @Test fun nonsenseIsNotATime() {
        assertNull(LocalTimes.parse("tomorrow", newYork))
        assertNull(LocalTimes.parse("2026-13-01", newYork))
        assertNull(LocalTimes.parse("2026-10-26T25:00", newYork))
        assertNull(LocalTimes.parse("2026-10-26T19:00+25:00", newYork))
        assertNull(LocalTimes.parseDate("2026-10-26T19:00"))
    }
}
