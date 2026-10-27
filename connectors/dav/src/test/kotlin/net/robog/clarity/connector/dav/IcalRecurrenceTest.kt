package net.robog.clarity.connector.dav

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.Instant as JInstant
import java.time.LocalDate
import java.time.ZoneId

/** Recurrence expansion against iCloud-shaped ICS: TZID starts, UTC UNTIL, all-day series, overrides. */

// Roughly what production uses on 2026-10-27: 30 days back, 180 ahead.
private val W = TimeWindow(JInstant.parse("2026-09-27T00:00:00Z"), JInstant.parse("2027-04-25T00:00:00Z"))

private const val NEW_YORK = """BEGIN:VTIMEZONE
TZID:America/New_York
BEGIN:DAYLIGHT
TZOFFSETFROM:-0500
RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU
DTSTART:20070311T020000
TZNAME:EDT
TZOFFSETTO:-0400
END:DAYLIGHT
BEGIN:STANDARD
TZOFFSETFROM:-0400
RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU
DTSTART:20071104T020000
TZNAME:EST
TZOFFSETTO:-0500
END:STANDARD
END:VTIMEZONE"""

/** An iCloud-style calendar object: CALSCALE, a VTIMEZONE, then the VEVENT(s). */
private fun icloud(vararg body: String) = (
    listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Apple Inc.//iPhone OS 18.0//EN", "CALSCALE:GREGORIAN") +
        NEW_YORK.lines() + body + "END:VCALENDAR"
    ).joinToString("\r\n") + "\r\n"

private fun vevent(uid: String, vararg props: String) =
    arrayOf("BEGIN:VEVENT", "CREATED:20250801T120000Z", "DTSTAMP:20260801T120000Z", "UID:$uid", "SEQUENCE:0", "TRANSP:OPAQUE", *props, "END:VEVENT")

private fun starts(ics: String, window: TimeWindow = W) = Ical.events(ics, "Home", window).map { it.payload.start.toString() }.sorted()

private fun ny(date: String, time: String) = java.time.LocalDateTime.parse("${date}T$time").atZone(ZoneId.of("America/New_York")).toInstant().toString()

class IcalRecurrenceTest : FunSpec({
    test("TZID weekly series with a UTC UNTIL expands, crossing the November DST change") {
        val text = icloud(*vevent("tz-weekly",
            "DTSTART;TZID=America/New_York:20260907T093000", "DTEND;TZID=America/New_York:20260907T103000",
            "RRULE:FREQ=WEEKLY;UNTIL=20261214T143000Z", "SUMMARY:Piano lesson"))
        val expected = generateSequence(LocalDate.parse("2026-09-28")) { it.plusWeeks(1) }.takeWhile { it <= LocalDate.parse("2026-12-14") }
            .map { ny(it.toString(), "09:30") }.toList()
        starts(text) shouldContainExactly expected
        expected.first() shouldBe "2026-09-28T13:30:00Z"
        expected.last() shouldBe "2026-12-14T14:30:00Z" // UNTIL is inclusive
    }

    test("all-day weekly series with a date-time UNTIL expands; UNTIL is read in the object's own time zone") {
        // Apple writes the end of the last local day in UTC: 2026-11-26 23:59:59 in New York
        val text = icloud(*vevent("allday-weekly",
            "DTSTART;VALUE=DATE:20260904", "DTEND;VALUE=DATE:20260905",
            "RRULE:FREQ=WEEKLY;UNTIL=20261127T045959Z", "SUMMARY:Bin day"))
        val occ = Ical.events(text, "Home", W)
        occ.map { it.payload.start.toString() }.sorted() shouldContainExactly
            listOf("2026-10-02", "2026-10-09", "2026-10-16", "2026-10-23", "2026-10-30", "2026-11-06", "2026-11-13", "2026-11-20").map { "${it}T00:00:00Z" }
        occ.all { it.payload.allDay } shouldBe true
    }

    test("all-day series with a date-time UNTIL and no VTIMEZONE reads UNTIL as UTC, whatever the JVM's zone") {
        val text = (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Apple Inc.//iPhone OS 18.0//EN") +
            vevent("allday-utc", "DTSTART;VALUE=DATE:20261002", "DTEND;VALUE=DATE:20261003", "RRULE:FREQ=WEEKLY;UNTIL=20261016T045959Z", "SUMMARY:Bins") +
            "END:VCALENDAR").joinToString("\r\n") + "\r\n"
        starts(text) shouldContainExactly listOf("2026-10-02T00:00:00Z", "2026-10-09T00:00:00Z", "2026-10-16T00:00:00Z")
    }

    test("a TZID series with a DATE UNTIL includes that last day") {
        val text = icloud(*vevent("tz-date-until",
            "DTSTART;TZID=America/New_York:20261005T193000", "DTEND;TZID=America/New_York:20261005T203000",
            "RRULE:FREQ=WEEKLY;UNTIL=20261019", "SUMMARY:Evening"))
        starts(text) shouldContainExactly listOf("2026-10-05", "2026-10-12", "2026-10-19").map { ny(it, "19:30") }
    }

    test("a floating series with a UTC UNTIL") {
        val text = icloud(*vevent("floating-z",
            "DTSTART:20261005T090000", "DTEND:20261005T100000", "RRULE:FREQ=WEEKLY;UNTIL=20261019T090000Z", "SUMMARY:Floating Z"))
        starts(text) shouldContainExactly listOf("2026-10-05T09:00:00Z", "2026-10-12T09:00:00Z", "2026-10-19T09:00:00Z")
    }

    test("all-day weekly series with a DATE UNTIL expands") {
        val text = icloud(*vevent("allday-weekly-date",
            "DTSTART;VALUE=DATE:20260904", "DTEND;VALUE=DATE:20260905",
            "RRULE:FREQ=WEEKLY;UNTIL=20261009", "SUMMARY:Bin day"))
        starts(text) shouldContainExactly listOf("2026-10-02T00:00:00Z", "2026-10-09T00:00:00Z")
    }

    test("BYDAY with several days expands each of them") {
        val text = icloud(*vevent("byday",
            "DTSTART;TZID=America/New_York:20260928T080000", "DTEND;TZID=America/New_York:20260928T090000",
            "RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR;UNTIL=20261009T235959Z", "SUMMARY:Gym"))
        starts(text) shouldContainExactly listOf("2026-09-28", "2026-09-30", "2026-10-02", "2026-10-05", "2026-10-07", "2026-10-09").map { ny(it, "08:00") }
    }

    test("COUNT bounds a TZID series") {
        val text = icloud(*vevent("count",
            "DTSTART;TZID=America/New_York:20261005T070000", "DTEND;TZID=America/New_York:20261005T073000",
            "RRULE:FREQ=DAILY;COUNT=5", "SUMMARY:Meds"))
        starts(text) shouldContainExactly (5..9).map { ny("2026-10-0$it", "07:00") }
    }

    test("EXDATE with a TZID removes that occurrence") {
        val text = icloud(*vevent("exdate",
            "DTSTART;TZID=America/New_York:20261005T093000", "DTEND;TZID=America/New_York:20261005T100000",
            "RRULE:FREQ=WEEKLY;COUNT=4", "EXDATE;TZID=America/New_York:20261012T093000", "SUMMARY:Standup"))
        starts(text) shouldContainExactly listOf("2026-10-05", "2026-10-19", "2026-10-26").map { ny(it, "09:30") }
    }

    test("a RECURRENCE-ID override with a TZID replaces its occurrence and keeps the original key") {
        val text = icloud(
            *vevent("override",
                "DTSTART;TZID=America/New_York:20261005T093000", "DTEND;TZID=America/New_York:20261005T100000",
                "RRULE:FREQ=WEEKLY;COUNT=4", "SUMMARY:1:1"),
            *vevent("override",
                "RECURRENCE-ID;TZID=America/New_York:20261019T093000",
                "DTSTART;TZID=America/New_York:20261019T110000", "DTEND;TZID=America/New_York:20261019T113000", "SUMMARY:1:1 (moved)"),
        )
        val occ = Ical.events(text, "Home", W)
        occ.map { it.payload.start.toString() }.sorted() shouldContainExactly
            listOf(ny("2026-10-05", "09:30"), ny("2026-10-12", "09:30"), ny("2026-10-19", "11:00"), ny("2026-10-26", "09:30"))
        val moved = occ.single { it.payload.title == "1:1 (moved)" }
        moved.key shouldBe "${JInstant.parse(ny("2026-10-19", "09:30")).epochSecond}"
        occ.map { it.key }.toSet().size shouldBe 4
    }

    test("a series that began a year before the window and is still running expands across the whole window") {
        val text = icloud(*vevent("long-running",
            "DTSTART;TZID=America/New_York:20250901T183000", "DTEND;TZID=America/New_York:20250901T193000",
            "RRULE:FREQ=WEEKLY;BYDAY=MO", "SUMMARY:Choir"))
        val expected = generateSequence(LocalDate.parse("2026-09-28")) { it.plusWeeks(1) }.takeWhile { it < LocalDate.parse("2027-04-25") }
            .map { ny(it.toString(), "18:30") }.toList()
        starts(text) shouldContainExactly expected
    }

    test("a long-running series with a far UTC UNTIL expands across the window") {
        val text = icloud(*vevent("long-until",
            "DTSTART;TZID=America/New_York:20250902T120000", "DTEND;TZID=America/New_York:20250902T130000",
            "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=TU;UNTIL=20271231T045959Z", "SUMMARY:Lunch"))
        val expected = generateSequence(LocalDate.parse("2025-09-02")) { it.plusWeeks(2) }.takeWhile { it < LocalDate.parse("2027-04-25") }
            .filter { it >= LocalDate.parse("2026-09-27") }.map { ny(it.toString(), "12:00") }.toList()
        starts(text) shouldContainExactly expected
    }

    test("monthly by weekday with a UTC UNTIL") {
        val text = icloud(*vevent("monthly",
            "DTSTART;TZID=America/New_York:20260106T190000", "DTEND;TZID=America/New_York:20260106T200000",
            "RRULE:FREQ=MONTHLY;BYDAY=1TU;UNTIL=20270101T000000Z", "SUMMARY:Book club"))
        val expected = (10..12).map { m -> LocalDate.of(2026, m, 1).let { d -> generateSequence(d) { it.plusDays(1) }.first { it.dayOfWeek == DayOfWeek.TUESDAY } } }
            .map { ny(it.toString(), "19:00") }
        starts(text) shouldContainExactly expected
    }

    test("floating times expand as UTC wall-clock times") {
        val text = icloud(*vevent("floating",
            "DTSTART:20261005T090000", "DTEND:20261005T100000", "RRULE:FREQ=WEEKLY;UNTIL=20261019T090000", "SUMMARY:Floating"))
        starts(text) shouldContainExactly listOf("2026-10-05T09:00:00Z", "2026-10-12T09:00:00Z", "2026-10-19T09:00:00Z")
    }

    test("a daily series keeps its local time across the end of daylight saving (Nov 1 2026)") {
        val text = icloud(*vevent("dst",
            "DTSTART;TZID=America/New_York:20261030T090000", "DTEND;TZID=America/New_York:20261030T091500",
            "RRULE:FREQ=DAILY;COUNT=5", "SUMMARY:Walk"))
        starts(text) shouldContainExactly listOf(
            "2026-10-30T13:00:00Z", "2026-10-31T13:00:00Z", "2026-11-01T14:00:00Z", "2026-11-02T14:00:00Z", "2026-11-03T14:00:00Z",
        )
    }

    test("a UTC-dated series with a UTC UNTIL still works") {
        val text = icloud(*vevent("utc",
            "DTSTART:20261005T150000Z", "DTEND:20261005T160000Z", "RRULE:FREQ=WEEKLY;UNTIL=20261019T150000Z", "SUMMARY:UTC"))
        starts(text) shouldContainExactly listOf("2026-10-05T15:00:00Z", "2026-10-12T15:00:00Z", "2026-10-19T15:00:00Z")
    }
    test("iCloud's WKST=SU and INTERVAL=1 on a TZID series") {
        val text = icloud(*vevent("wkst",
            "DTSTART;TZID=America/New_York:20260901T140000", "DTEND;TZID=America/New_York:20260901T151500",
            "RRULE:FREQ=WEEKLY;INTERVAL=1;UNTIL=20261013T035959Z;BYDAY=TU,TH;WKST=SU", "SUMMARY:Lecture"))
        starts(text) shouldContainExactly listOf("2026-09-29", "2026-10-01", "2026-10-06", "2026-10-08").map { ny(it, "14:00") }
    }

    test("a yearly all-day series from decades ago (a birthday)") {
        val text = icloud(*vevent("birthday",
            "DTSTART;VALUE=DATE:19950214", "DTEND;VALUE=DATE:19950215", "RRULE:FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=14", "SUMMARY:Alex birthday"))
        starts(text) shouldContainExactly listOf("2027-02-14T00:00:00Z")
    }

    test("a TZID with no VTIMEZONE in the object falls back to the named zone") {
        val text = (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Apple Inc.//Mac OS X 10.15//EN") +
            vevent("no-vtz", "DTSTART;TZID=America/New_York:20261005T093000", "DTEND;TZID=America/New_York:20261005T100000",
                "RRULE:FREQ=WEEKLY;UNTIL=20261019T133000Z", "SUMMARY:No VTIMEZONE") + "END:VCALENDAR").joinToString("\r\n") + "\r\n"
        starts(text) shouldContainExactly listOf("2026-10-05", "2026-10-12", "2026-10-19").map { ny(it, "09:30") }
    }

    test("EXDATE in UTC on a TZID series, and EXDATE as a DATE on an all-day series") {
        val timed = icloud(*vevent("ex-utc",
            "DTSTART;TZID=America/New_York:20261005T093000", "DTEND;TZID=America/New_York:20261005T100000",
            "RRULE:FREQ=WEEKLY;COUNT=3", "EXDATE:20261012T133000Z", "SUMMARY:x"))
        starts(timed) shouldContainExactly listOf("2026-10-05", "2026-10-19").map { ny(it, "09:30") }
        val allDay = icloud(*vevent("ex-date",
            "DTSTART;VALUE=DATE:20261005", "DTEND;VALUE=DATE:20261006", "RRULE:FREQ=DAILY;COUNT=3", "EXDATE;VALUE=DATE:20261006", "SUMMARY:y"))
        starts(allDay) shouldContainExactly listOf("2026-10-05T00:00:00Z", "2026-10-07T00:00:00Z")
    }

    test("an RDATE with a TZID adds an occurrence") {
        val text = icloud(*vevent("rdate",
            "DTSTART;TZID=America/New_York:20261005T093000", "DTEND;TZID=America/New_York:20261005T100000",
            "RRULE:FREQ=WEEKLY;COUNT=2", "RDATE;TZID=America/New_York:20261014T120000", "SUMMARY:x"))
        starts(text) shouldContainExactly listOf(ny("2026-10-05", "09:30"), ny("2026-10-12", "09:30"), ny("2026-10-14", "12:00"))
    }
})
