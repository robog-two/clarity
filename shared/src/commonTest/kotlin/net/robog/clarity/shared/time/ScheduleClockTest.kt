package net.robog.clarity.shared.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

class ScheduleClockTest {
    private val newYork = TimeZone.of("America/New_York")
    private val losAngeles = TimeZone.of("America/Los_Angeles")
    private val berlin = TimeZone.of("Europe/Berlin")
    private val utc = TimeZone.UTC
    private fun at(s: String) = Instant.parse(s)
    private val weekdays = setOf(1, 2, 3, 4, 5)

    // Monday 2026-10-26, New York on daylight time (UTC-4); clocks go back on Sunday 2026-11-01.
    private val mondayMorning = at("2026-10-26T12:00:00Z")

    @Test fun eightThirtyInNewYorkIsStoredAtTheStandardOffset() {
        val stored = ScheduleClock.toStored(WeeklyTime(LocalTime(8, 30), weekdays), newYork, newYork, mondayMorning)
        assertEquals(WeeklyTime(LocalTime(13, 30), weekdays), stored)
    }

    @Test fun storingIsTheSameInSummerAndWinter() {
        val seen = WeeklyTime(LocalTime(8, 30), weekdays)
        val winter = ScheduleClock.toStored(seen, newYork, newYork, at("2027-01-11T12:00:00Z"))
        assertEquals(ScheduleClock.toStored(seen, newYork, newYork, mondayMorning), winter)
    }

    @Test fun theCallStaysAtEightThirtyAcrossTheDaylightSavingChange() {
        val stored = WeeklyTime(LocalTime(13, 30), weekdays)
        // Friday before the change: 8:30 EDT
        assertEquals(at("2026-10-30T12:30:00Z"), ScheduleClock.nextOccurrence(stored, newYork, at("2026-10-30T00:00:00Z")))
        // Monday after the change: 8:30 EST
        assertEquals(at("2026-11-02T13:30:00Z"), ScheduleClock.nextOccurrence(stored, newYork, at("2026-10-31T00:00:00Z")))
    }

    @Test fun aPhoneInAnotherZoneSeesTheSameMoment() {
        val stored = WeeklyTime(LocalTime(13, 30), weekdays)
        assertEquals(WeeklyTime(LocalTime(5, 30), weekdays), ScheduleClock.toSeen(stored, losAngeles, newYork, mondayMorning))
        assertEquals(WeeklyTime(LocalTime(8, 30), weekdays), ScheduleClock.toSeen(stored, newYork, newYork, mondayMorning))
    }

    @Test fun seenAndStoredRoundTrip() {
        for (zone in listOf(newYork, losAngeles, berlin, utc)) {
            for (near in listOf(mondayMorning, at("2027-01-11T12:00:00Z"), at("2027-07-12T12:00:00Z"))) {
                val seen = WeeklyTime(LocalTime(7, 15), setOf(1, 3, 6))
                val stored = ScheduleClock.toStored(seen, zone, zone, near)
                assertEquals(seen, ScheduleClock.toSeen(stored, zone, zone, near), "$zone near $near")
            }
        }
    }

    @Test fun weekdaysMoveWhenTheTimeCrossesMidnightInUtc() {
        // Monday 21:00 in New York is Tuesday 02:00 UTC at the standard offset.
        val stored = ScheduleClock.toStored(WeeklyTime(LocalTime(21, 0), setOf(1)), newYork, newYork, mondayMorning)
        assertEquals(WeeklyTime(LocalTime(2, 0), setOf(2)), stored)
        assertEquals(WeeklyTime(LocalTime(21, 0), setOf(1)), ScheduleClock.toSeen(stored, newYork, newYork, mondayMorning))
    }

    @Test fun aDailyTimeConvertsBothWays() {
        val stored = ScheduleClock.timeToStored(LocalTime(18, 0), newYork, newYork, mondayMorning)
        assertEquals(LocalTime(23, 0), stored)
        assertEquals(LocalTime(18, 0), ScheduleClock.timeToSeen(stored, newYork, newYork, mondayMorning))
    }

    @Test fun utcIsTheIdentity() {
        val time = WeeklyTime(LocalTime(9, 0), weekdays)
        assertEquals(time, ScheduleClock.toStored(time, utc, utc, mondayMorning))
        assertEquals(at("2026-10-26T09:00:00Z"), ScheduleClock.occurrence(LocalDate(2026, 10, 26), LocalTime(9, 0), utc))
    }

    @Test fun leadMovesTheOccurrenceEarlierAndCanCrossIntoThePreviousDay() {
        val stored = WeeklyTime(LocalTime(0, 5), setOf(2))
        assertEquals(at("2026-10-26T23:55:00Z"), ScheduleClock.nextOccurrence(stored, utc, mondayMorning, lead = 10.minutes))
    }

    @Test fun noDaysMeansNoOccurrence() {
        assertNull(ScheduleClock.nextOccurrence(WeeklyTime(LocalTime(9, 0), emptySet()), utc, mondayMorning))
    }

    @Test fun homeDateIsTheCalendarDateInTheHomeZone() {
        // 23:00 EDT on Monday is already Tuesday in UTC.
        assertEquals(LocalDate(2026, 10, 26), ScheduleClock.homeDate(at("2026-10-27T03:00:00Z"), newYork))
    }
}
