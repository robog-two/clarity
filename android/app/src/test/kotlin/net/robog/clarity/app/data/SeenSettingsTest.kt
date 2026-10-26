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
    private val berlin = TimeZone.of("Europe/Berlin")
    private val now = Instant.parse("2026-10-26T12:00:00Z") // Monday, New York on daylight time
    private val weekdays = setOf(1, 2, 3, 4, 5)

    // 08:30 weekdays and a 19:00 summary in New York, as stored: on the New York clock
    private val stored = Settings(
        morningCall = Settings.MorningCall(at = LocalTime(8, 30), days = weekdays),
        eveningSummaryAt = LocalTime(19, 0),
        homeZone = "America/New_York",
    )

    // Either side of each zone's clock changes: Berlin goes back on 2026-10-25 and forward on
    // 2027-03-28; New York goes back on 2026-11-01 and forward on 2027-03-14.
    private val aroundTheChanges = listOf(
        "2026-10-24T12:00:00Z", "2026-10-26T12:00:00Z", "2026-11-02T12:00:00Z",
        "2027-03-13T12:00:00Z", "2027-03-20T12:00:00Z", "2027-03-29T12:00:00Z",
    ).map(Instant::parse)

    @Test fun atHomeTheTimesReadAsSet() {
        val seen = seenSettings(stored, newYork, now)
        assertEquals(LocalTime(8, 30), seen.morningCall.at)
        assertEquals(weekdays, seen.morningCall.days)
        assertEquals(LocalTime(19, 0), seen.eveningSummaryAt)
    }

    @Test fun onATripTheSameMomentReadsOnTheLocalClock() {
        val seen = seenSettings(stored, losAngeles, now)
        assertEquals(LocalTime(5, 30), seen.morningCall.at)
        assertEquals(LocalTime(16, 0), seen.eveningSummaryAt)
    }

    @Test fun changingSomethingElseOnATripKeepsTheStoredTimesAndHome() {
        val seen = seenSettings(stored, losAngeles, now).copy(micAlwaysOn = false)
        assertEquals(stored.copy(micAlwaysOn = false), storedSettings(seen, stored, losAngeles, now))
    }

    @Test fun settingATimeOnATripMakesThatZoneHome() {
        val seen = seenSettings(stored, losAngeles, now).let { it.copy(morningCall = it.morningCall.copy(at = LocalTime(7, 0))) }
        val saved = storedSettings(seen, stored, losAngeles, now)
        assertEquals("America/Los_Angeles", saved.homeZone)
        assertEquals(LocalTime(7, 0), saved.morningCall.at)
        assertEquals(LocalTime(16, 0), saved.eveningSummaryAt) // 16:00 there is the same moment as before
    }

    @Test fun nothingStoredYetStoresThePhonesClock() {
        val saved = storedSettings(Settings(), null, newYork, now)
        assertEquals(LocalTime(9, 0), saved.morningCall.at)
        assertEquals(LocalTime(18, 0), saved.eveningSummaryAt)
        assertEquals("America/New_York", saved.homeZone)
    }

    @Test fun anEveningPickedAtHomeIsStoredAsPickedAllYear() {
        for ((zone, home) in listOf(newYork to "America/New_York", berlin to "Europe/Berlin")) {
            for (near in aroundTheChanges) {
                val before = stored.copy(homeZone = home)
                val seen = seenSettings(before, zone, near).copy(eveningSummaryAt = LocalTime(19, 30))
                val saved = storedSettings(seen, before, zone, near)
                assertEquals(LocalTime(19, 30), saved.eveningSummaryAt, "$home near $near")
                assertEquals(LocalTime(8, 30), saved.morningCall.at, "$home near $near")
                assertEquals(home, saved.homeZone)
                assertEquals(LocalTime(19, 30), seenSettings(saved, zone, near).eveningSummaryAt, "$home near $near")
            }
        }
    }

    @Test fun seenAndStoredRoundTrip() {
        for ((zone, home) in listOf(newYork to "America/New_York", berlin to "Europe/Berlin")) {
            for (near in aroundTheChanges) {
                val s = stored.copy(homeZone = home)
                assertEquals(s, storedSettings(seenSettings(s, zone, near), s, zone, near), "$home near $near")
            }
        }
    }
}
