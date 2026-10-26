package net.robog.clarity.app.data

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.time.ScheduleClock
import net.robog.clarity.shared.time.WeeklyTime
import net.robog.clarity.shared.time.zoneOrUtc

/*
 * The server stores repeating times on the home zone's clock (see ScheduleClock). Screens work on
 * "seen" settings, with those times on the phone's clock, and convert only through these two functions.
 */

/** [stored] settings with the call and evening times as they read on a clock in [zone] around [now]. */
fun seenSettings(stored: Settings, zone: TimeZone, now: Instant): Settings {
    val home = zoneOrUtc(stored.homeZone)
    val call = ScheduleClock.toSeen(WeeklyTime(stored.morningCall.at, stored.morningCall.days), zone, home, now)
    return stored.copy(
        morningCall = stored.morningCall.copy(at = call.at, days = call.days),
        eveningSummaryAt = ScheduleClock.timeToSeen(stored.eveningSummaryAt, zone, home, now),
    )
}

/**
 * [seen] settings back to what the server stores. When the person did not change a time, the stored
 * times and home zone are kept as they were in [stored] (a phone on a trip changes nothing). When they
 * did, every time is stored from the phone's clock and [zone] becomes the home zone. [stored] is null
 * when there is nothing stored yet.
 */
fun storedSettings(seen: Settings, stored: Settings?, zone: TimeZone, now: Instant): Settings {
    if (stored != null) {
        val before = seenSettings(stored, zone, now)
        if (seen.morningCall.at == before.morningCall.at && seen.morningCall.days == before.morningCall.days &&
            seen.eveningSummaryAt == before.eveningSummaryAt
        ) {
            return seen.copy(
                morningCall = seen.morningCall.copy(at = stored.morningCall.at, days = stored.morningCall.days),
                eveningSummaryAt = stored.eveningSummaryAt,
                homeZone = stored.homeZone,
            )
        }
    }
    val call = ScheduleClock.toStored(WeeklyTime(seen.morningCall.at, seen.morningCall.days), zone, zone, now)
    return seen.copy(
        morningCall = seen.morningCall.copy(at = call.at, days = call.days),
        eveningSummaryAt = ScheduleClock.timeToStored(seen.eveningSummaryAt, zone, zone, now),
        homeZone = zone.id,
    )
}
