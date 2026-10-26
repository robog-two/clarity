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
    private val evening = WeeklyTime(LocalTime(19, 0), ScheduleClock.EVERY_DAY)

    // Monday 2026-10-26, New York on daylight time (UTC-4); clocks go back on Sunday 2026-11-01.
    private val mondayMorning = at("2026-10-26T12:00:00Z")

    @Test fun anEveningSummaryAtSevenInNewYorkFiresAtSevenThere() {
        // The bug: 19:00 read as UTC at the standard offset fired at 18:00Z, 2 pm in New York.
        assertEquals(at("2026-10-26T23:00:00Z"), ScheduleClock.nextOccurrence(evening, newYork, mondayMorning))
    }

    @Test fun newYorkEveningStaysAtSevenWhenClocksGoBack() {
        assertEquals(at("2026-10-31T23:00:00Z"), ScheduleClock.nextOccurrence(evening, newYork, at("2026-10-31T12:00:00Z"))) // EDT
        assertEquals(at("2026-11-02T00:00:00Z"), ScheduleClock.nextOccurrence(evening, newYork, at("2026-11-01T12:00:00Z"))) // EST
    }

    @Test fun newYorkEveningStaysAtSevenWhenClocksGoForward() {
        assertEquals(at("2027-03-14T00:00:00Z"), ScheduleClock.nextOccurrence(evening, newYork, at("2027-03-13T12:00:00Z"))) // EST
        assertEquals(at("2027-03-14T23:00:00Z"), ScheduleClock.nextOccurrence(evening, newYork, at("2027-03-14T12:00:00Z"))) // EDT
    }

    @Test fun berlinEveningStaysAtSevenAcrossBothChanges() {
        // Berlin goes back on 2026-10-25 and forward on 2027-03-28.
        assertEquals(at("2026-10-24T17:00:00Z"), ScheduleClock.nextOccurrence(evening, berlin, at("2026-10-24T08:00:00Z"))) // CEST
        assertEquals(at("2026-10-25T18:00:00Z"), ScheduleClock.nextOccurrence(evening, berlin, at("2026-10-25T08:00:00Z"))) // CET
        assertEquals(at("2027-03-27T18:00:00Z"), ScheduleClock.nextOccurrence(evening, berlin, at("2027-03-27T08:00:00Z"))) // CET
        assertEquals(at("2027-03-28T17:00:00Z"), ScheduleClock.nextOccurrence(evening, berlin, at("2027-03-28T08:00:00Z"))) // CEST
    }

    @Test fun theCallStaysAtEightThirtyAcrossTheDaylightSavingChange() {
        val call = WeeklyTime(LocalTime(8, 30), weekdays)
        // Friday before the change: 8:30 EDT
        assertEquals(at("2026-10-30T12:30:00Z"), ScheduleClock.nextOccurrence(call, newYork, at("2026-10-30T00:00:00Z")))
        // Monday after the change: 8:30 EST
        assertEquals(at("2026-11-02T13:30:00Z"), ScheduleClock.nextOccurrence(call, newYork, at("2026-10-31T00:00:00Z")))
    }

    @Test fun aTimeSkippedWhenClocksGoForwardHappensAnHourLater() {
        val night = WeeklyTime(LocalTime(2, 30), ScheduleClock.EVERY_DAY)
        assertEquals(at("2027-03-14T07:30:00Z"), ScheduleClock.nextOccurrence(night, newYork, at("2027-03-14T05:00:00Z"))) // 03:30 EDT
        assertEquals(at("2027-03-28T01:30:00Z"), ScheduleClock.nextOccurrence(night, berlin, at("2027-03-28T00:00:00Z"))) // 03:30 CEST
    }

    @Test fun aTimeThatHappensTwiceWhenClocksGoBackHappensTheFirstTime() {
        val night = WeeklyTime(LocalTime(1, 30), ScheduleClock.EVERY_DAY)
        assertEquals(at("2026-11-01T05:30:00Z"), ScheduleClock.nextOccurrence(night, newYork, at("2026-11-01T04:00:00Z"))) // 01:30 EDT
        assertEquals(at("2026-10-25T00:30:00Z"), ScheduleClock.nextOccurrence(WeeklyTime(LocalTime(2, 30), ScheduleClock.EVERY_DAY), berlin, at("2026-10-24T23:00:00Z"))) // 02:30 CEST
    }

    @Test fun atHomeStoredIsWhatWasSeen() {
        for (zone in listOf(newYork, berlin, utc)) {
            for (near in listOf(mondayMorning, at("2027-01-11T12:00:00Z"), at("2027-03-14T06:00:00Z"), at("2027-07-12T12:00:00Z"))) {
                val seen = WeeklyTime(LocalTime(2, 30), setOf(1, 3, 7)) // even a time skipped on a change day
                assertEquals(seen, ScheduleClock.toStored(seen, zone, zone, near), "$zone near $near")
                assertEquals(seen, ScheduleClock.toSeen(seen, zone, zone, near), "$zone near $near")
            }
        }
    }

    @Test fun aPhoneInAnotherZoneSeesTheSameMoment() {
        val stored = WeeklyTime(LocalTime(8, 30), weekdays)
        assertEquals(WeeklyTime(LocalTime(5, 30), weekdays), ScheduleClock.toSeen(stored, losAngeles, newYork, mondayMorning))
        assertEquals(LocalTime(16, 0), ScheduleClock.timeToSeen(LocalTime(19, 0), losAngeles, newYork, mondayMorning))
    }

    @Test fun aTimeSetOnATripRoundTrips() {
        for (near in listOf(mondayMorning, at("2026-11-03T12:00:00Z"), at("2027-03-20T12:00:00Z"), at("2027-07-12T12:00:00Z"))) {
            val seen = WeeklyTime(LocalTime(7, 15), setOf(1, 3, 6))
            val stored = ScheduleClock.toStored(seen, berlin, newYork, near)
            assertEquals(seen, ScheduleClock.toSeen(stored, berlin, newYork, near), "near $near")
        }
    }

    @Test fun weekdaysMoveWhenTheTimeCrossesMidnightBetweenZones() {
        // Monday 22:00 in New York (EDT) is Tuesday 03:00 in Berlin (CET, already changed back).
        val seenInBerlin = ScheduleClock.toSeen(WeeklyTime(LocalTime(22, 0), setOf(1)), berlin, newYork, mondayMorning)
        assertEquals(WeeklyTime(LocalTime(3, 0), setOf(2)), seenInBerlin)
        assertEquals(WeeklyTime(LocalTime(22, 0), setOf(1)), ScheduleClock.toStored(seenInBerlin, berlin, newYork, mondayMorning))
    }

    @Test fun occurrenceIsTheWallClockInTheZone() {
        assertEquals(at("2026-10-26T09:00:00Z"), ScheduleClock.occurrence(LocalDate(2026, 10, 26), LocalTime(9, 0), utc))
        assertEquals(at("2026-10-26T13:00:00Z"), ScheduleClock.occurrence(LocalDate(2026, 10, 26), LocalTime(9, 0), newYork))
        assertEquals(at("2027-01-11T14:00:00Z"), ScheduleClock.occurrence(LocalDate(2027, 1, 11), LocalTime(9, 0), newYork))
    }

    @Test fun leadMovesTheOccurrenceEarlierAndCanCrossIntoThePreviousDay() {
        val stored = WeeklyTime(LocalTime(0, 5), setOf(2))
        assertEquals(at("2026-10-26T23:55:00Z"), ScheduleClock.nextOccurrence(stored, utc, mondayMorning, lead = 10.minutes))
        // Tuesday 00:05 in Berlin, ten minutes early, is Monday 23:55 there.
        assertEquals(at("2026-10-26T22:55:00Z"), ScheduleClock.nextOccurrence(stored, berlin, mondayMorning, lead = 10.minutes))
    }

    @Test fun noDaysMeansNoOccurrence() {
        assertNull(ScheduleClock.nextOccurrence(WeeklyTime(LocalTime(9, 0), emptySet()), utc, mondayMorning))
    }

    @Test fun homeDateIsTheCalendarDateInTheHomeZone() {
        // 23:00 EDT on Monday is already Tuesday in UTC.
        assertEquals(LocalDate(2026, 10, 26), ScheduleClock.homeDate(at("2026-10-27T03:00:00Z"), newYork))
    }
}
