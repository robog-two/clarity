package net.robog.clarity.connector.dav

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.DayOfWeek
import java.time.Instant as JInstant
import java.time.LocalDate
import java.time.ZoneId
import net.robog.clarity.shared.calendar.Frequency
import net.robog.clarity.shared.calendar.NthWeekday
import net.robog.clarity.shared.calendar.Recurrence
import kotlinx.datetime.LocalDate as KDate
import kotlinx.datetime.DayOfWeek as KDay

/** New events: every shape of repeat, all-day, and the rest of the fields, read back through [Ical.expand]. */

internal val NY: ZoneId = ZoneId.of("America/New_York")
internal val WINDOW = TimeWindow(JInstant.parse("2026-09-01T00:00:00Z"), JInstant.parse("2027-06-01T00:00:00Z"))

/** Local start of every occurrence in [ics], as `2026-10-26 07:00` on [zone]'s clock. */
internal fun localStarts(ics: String, zone: ZoneId = NY): List<String> =
    Ical.expand(ics, "Home", WINDOW).occurrences.map { it.payload.start }
        .sorted().map { java.time.Instant.ofEpochSecond(it.epochSeconds).atZone(zone).toLocalDateTime().toString().replace('T', ' ') }

internal fun utcStarts(ics: String): List<String> =
    Ical.expand(ics, "Home", WINDOW).occurrences.map { it.payload.start.toString() }.sorted()

internal fun at(local: String, zone: ZoneId = NY): JInstant = java.time.LocalDateTime.parse(local.replace(' ', 'T')).atZone(zone).toInstant()

internal fun create(
    start: String, end: String, repeat: Recurrence? = null, allDay: Boolean = false,
    title: String = "Gym", uid: String = "u1@clarity", zone: ZoneId = NY,
): String = Ical.newEvent(NewEvent(uid, title, at(start, if (allDay) ZoneId.of("UTC") else zone), at(end, if (allDay) ZoneId.of("UTC") else zone), repeat = repeat, allDay = allDay, zone = zone))

class IcalCreateTest : FunSpec({
    test("a daily series every 3 days, counted") {
        val ics = create("2026-10-26 07:00", "2026-10-26 08:00", Recurrence(Frequency.DAILY, interval = 3, count = 4))
        localStarts(ics) shouldContainExactly listOf("2026-10-26 07:00", "2026-10-29 07:00", "2026-11-01 07:00", "2026-11-04 07:00")
    }

    test("a weekly series on Monday Wednesday Friday until a date keeps 7:00 local across the November clock change") {
        val ics = create("2026-10-26 07:00", "2026-10-26 08:00", Recurrence(Frequency.WEEKLY, days = listOf(KDay.MONDAY, KDay.WEDNESDAY, KDay.FRIDAY), until = KDate(2026, 12, 20)))
        val dates = generateSequence(LocalDate.parse("2026-10-26")) { it.plusDays(1) }.takeWhile { it <= LocalDate.parse("2026-12-20") }
            .filter { it.dayOfWeek in setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY) }.toList()
        localStarts(ics) shouldContainExactly dates.map { "$it 07:00" }
        // 07:00 is 11:00Z before the change on 2026-11-01 and 12:00Z after it
        utcStarts(ics).first() shouldBe "2026-10-26T11:00:00Z"
        utcStarts(ics).last() shouldBe "2026-12-18T12:00:00Z"
        ics shouldContain "TZID=America/New_York"
        ics shouldContain "BEGIN:VTIMEZONE"
    }

    test("a weekly series every second week on the start's weekday") {
        val ics = create("2026-10-27 09:00", "2026-10-27 09:30", Recurrence(Frequency.WEEKLY, interval = 2, count = 3))
        localStarts(ics) shouldContainExactly listOf("2026-10-27 09:00", "2026-11-10 09:00", "2026-11-24 09:00")
    }

    test("a monthly series falls on the start's day of the month") {
        val ics = create("2026-10-15 12:00", "2026-10-15 13:00", Recurrence(Frequency.MONTHLY, count = 4))
        localStarts(ics) shouldContainExactly listOf("2026-10-15 12:00", "2026-11-15 12:00", "2026-12-15 12:00", "2027-01-15 12:00")
    }

    test("a monthly series on the 2nd Tuesday and on the last Friday") {
        val second = create("2026-10-13 10:00", "2026-10-13 11:00", Recurrence(Frequency.MONTHLY, nthWeekday = NthWeekday(2, KDay.TUESDAY), count = 3))
        localStarts(second) shouldContainExactly listOf("2026-10-13 10:00", "2026-11-10 10:00", "2026-12-08 10:00")
        val last = create("2026-10-30 10:00", "2026-10-30 11:00", Recurrence(Frequency.MONTHLY, nthWeekday = NthWeekday(NthWeekday.LAST, KDay.FRIDAY), count = 3))
        localStarts(last) shouldContainExactly listOf("2026-10-30 10:00", "2026-11-27 10:00", "2026-12-25 10:00")
    }

    test("a yearly series, counted and open ended") {
        val counted = create("2026-11-05 18:00", "2026-11-05 19:00", Recurrence(Frequency.YEARLY, count = 3))
        localStarts(counted) shouldContainExactly listOf("2026-11-05 18:00")  // later years fall outside the window
        val wide = TimeWindow(JInstant.parse("2026-01-01T00:00:00Z"), JInstant.parse("2031-01-01T00:00:00Z"))
        Ical.expand(counted, "Home", wide).occurrences.size shouldBe 3
        Ical.expand(create("2026-11-05 18:00", "2026-11-05 19:00", Recurrence(Frequency.YEARLY)), "Home", wide).occurrences.size shouldBe 5
    }

    test("until ends the series on that local day, inclusive, and count is exact") {
        val until = create("2026-10-26 23:00", "2026-10-27 00:00", Recurrence(Frequency.DAILY, until = KDate(2026, 10, 29)))
        localStarts(until) shouldContainExactly listOf("2026-10-26 23:00", "2026-10-27 23:00", "2026-10-28 23:00", "2026-10-29 23:00")
        localStarts(create("2026-10-26 23:00", "2026-10-27 00:00", Recurrence(Frequency.DAILY, count = 2))).size shouldBe 2
    }

    test("an all-day event is date only with an exclusive end") {
        val ics = create("2026-11-05 00:00", "2026-11-07 00:00", allDay = true, title = "Offsite")
        ics shouldContain "DTSTART;VALUE=DATE:20261105"
        ics shouldContain "DTEND;VALUE=DATE:20261107"
        val event = Ical.expand(ics, "Home", WINDOW).occurrences.single().payload
        event.allDay shouldBe true
        event.start.toString() shouldBe "2026-11-05T00:00:00Z"
        event.end.toString() shouldBe "2026-11-07T00:00:00Z"
    }

    test("an all-day weekly series with an end date") {
        val ics = create("2026-11-02 00:00", "2026-11-03 00:00", Recurrence(Frequency.WEEKLY, until = KDate(2026, 11, 16)), allDay = true)
        ics shouldContain "UNTIL=20261116"
        Ical.expand(ics, "Home", WINDOW).occurrences.map { it.payload.start.toString().take(10) } shouldContainExactly listOf("2026-11-02", "2026-11-09", "2026-11-16")
    }

    test("a single timed event stays in UTC and carries its description location and link") {
        val ics = Ical.newEvent(
            NewEvent("u2@clarity", "Lunch", JInstant.parse("2026-10-26T16:00:00Z"), JInstant.parse("2026-10-26T17:00:00Z"),
                description = "Bring the notes", location = "Cafe, 1 Main St", url = "https://example.com/menu"),
        )
        ics shouldContain "DTSTART:20261026T160000Z"
        ics shouldNotContain "TZID"
        val payload = Ical.expand(ics, "Home", WINDOW).occurrences.single().payload
        payload.location shouldBe "Cafe, 1 Main St"
        payload.description shouldBe "Bring the notes"
        payload.url shouldBe "https://example.com/menu"
        payload.repeats shouldBe null
    }

    test("the payload of a series says how it repeats") {
        val ics = create("2026-10-26 07:00", "2026-10-26 08:00", Recurrence(Frequency.WEEKLY, days = listOf(KDay.MONDAY, KDay.WEDNESDAY, KDay.FRIDAY), until = KDate(2026, 12, 20)))
        Ical.expand(ics, "Home", WINDOW).occurrences.map { it.payload.repeats }.toSet() shouldBe setOf("every Mon, Wed and Fri until Dec 20")
        val nth = create("2026-10-13 10:00", "2026-10-13 11:00", Recurrence(Frequency.MONTHLY, nthWeekday = NthWeekday(2, KDay.TUESDAY)))
        Ical.expand(nth, "Home", WINDOW).occurrences.map { it.payload.repeats }.toSet() shouldBe setOf("monthly on the 2nd Tuesday")
        val yearly = create("2026-11-05 18:00", "2026-11-05 19:00", Recurrence(Frequency.YEARLY))
        Ical.expand(yearly, "Home", WINDOW).occurrences.map { it.payload.repeats }.toSet() shouldBe setOf("yearly")
    }
})
