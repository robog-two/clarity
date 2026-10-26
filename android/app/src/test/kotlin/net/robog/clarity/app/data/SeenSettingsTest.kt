package net.robog.clarity.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import net.robog.clarity.shared.session.Settings

class SeenSettingsTest {
    private val newYork = TimeZone.of("America/New_York")
    private val losAngeles = TimeZone.of("America/Los_Angeles")
    private val now = Instant.parse("2026-10-26T12:00:00Z") // Monday, New York on daylight time
    private val weekdays = setOf(1, 2, 3, 4, 5)

    // 08:30 weekdays and an 18:00 summary in New York, as stored
    private val stored = Settings(
        morningCall = Settings.MorningCall(at = LocalTime(13, 30), days = weekdays),
        eveningSummaryAt = LocalTime(23, 0),
        homeZone = "America/New_York",
    )

    @Test fun atHomeTheTimesReadAsSet() {
        val seen = seenSettings(stored, newYork, now)
        assertEquals(LocalTime(8, 30), seen.morningCall.at)
        assertEquals(weekdays, seen.morningCall.days)
        assertEquals(LocalTime(18, 0), seen.eveningSummaryAt)
    }

    @Test fun onATripTheSameMomentReadsOnTheLocalClock() {
        val seen = seenSettings(stored, losAngeles, now)
        assertEquals(LocalTime(5, 30), seen.morningCall.at)
        assertEquals(LocalTime(15, 0), seen.eveningSummaryAt)
    }

    @Test fun changingSomethingElseOnATripKeepsTheStoredTimesAndHome() {
        val seen = seenSettings(stored, losAngeles, now).copy(micAlwaysOn = false)
        assertEquals(stored.copy(micAlwaysOn = false), storedSettings(seen, stored, losAngeles, now))
    }

    @Test fun settingATimeOnATripMakesThatZoneHome() {
        val seen = seenSettings(stored, losAngeles, now).let { it.copy(morningCall = it.morningCall.copy(at = LocalTime(7, 0))) }
        val saved = storedSettings(seen, stored, losAngeles, now)
        assertEquals("America/Los_Angeles", saved.homeZone)
        assertEquals(LocalTime(15, 0), saved.morningCall.at) // 07:00 PST
        assertEquals(LocalTime(23, 0), saved.eveningSummaryAt) // 15:00 there is the same moment as before
    }

    @Test fun nothingStoredYetConvertsFromThePhone() {
        val saved = storedSettings(Settings(), null, newYork, now)
        assertEquals(LocalTime(14, 0), saved.morningCall.at) // 09:00 New York
        assertEquals("America/New_York", saved.homeZone)
    }

    @Test fun seenAndStoredRoundTrip() {
        assertEquals(stored, storedSettings(seenSettings(stored, newYork, now), stored, newYork, now))
    }
}
