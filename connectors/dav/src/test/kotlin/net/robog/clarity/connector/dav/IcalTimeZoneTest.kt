package net.robog.clarity.connector.dav

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.ZoneId
import net.robog.clarity.shared.calendar.EventScope

/** The compact VTIMEZONE of new events, and editing objects other apps wrote with their own. */

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

private fun icloud(vararg body: String) =
    (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Apple Inc.//iPhone OS 18.0//EN") + NEW_YORK.lines() + body + "END:VCALENDAR").joinToString("\r\n") + "\r\n"

private val PIANO = icloud(
    "BEGIN:VEVENT", "DTSTAMP:20260801T120000Z", "UID:piano", "SEQUENCE:0",
    "DTSTART;TZID=America/New_York:20260907T093000", "DTEND;TZID=America/New_York:20260907T103000",
    "RRULE:FREQ=WEEKLY;UNTIL=20261214T143000Z", "SUMMARY:Piano lesson", "END:VEVENT",
)

class IcalTimeZoneTest : FunSpec({
    test("a zone with daylight saving states its two yearly rules") {
        val text = timeZoneText(ZoneId.of("America/New_York"))
        text shouldContain "TZID:America/New_York"
        text shouldContain "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=SU;BYMONTHDAY=8,9,10,11,12,13,14"
        text shouldContain "RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=SU;BYMONTHDAY=1,2,3,4,5,6,7"
        text shouldContain "TZOFFSETFROM:-0500\r\nTZOFFSETTO:-0400"
    }

    test("European rules say the last Sunday") {
        val text = timeZoneText(ZoneId.of("Europe/Berlin"))
        text shouldContain "BYMONTH=3;BYDAY=-1SU"
        text shouldContain "BYMONTH=10;BYDAY=-1SU"
        text shouldContain "TZOFFSETTO:+0200"
    }

    test("a zone without daylight saving is one standard offset") {
        val text = timeZoneText(ZoneId.of("Asia/Kolkata"))
        text shouldContain "TZOFFSETTO:+0530"
        text shouldNotContain "RRULE"
        text shouldNotContain "DAYLIGHT"
    }

    test("a series another app wrote keeps working after one occurrence is changed") {
        val edited = IcalEdit.updated(
            PIANO, at("2026-11-09 09:30"), EventScope.THIS, EventChange(start = at("2026-11-09 11:00"), end = at("2026-11-09 12:00")), "next@clarity",
        ).shouldBeInstanceOf<Edited.Replace>().ics
        edited shouldContain "RECURRENCE-ID;TZID=America/New_York:20261109T093000"
        val starts = localStarts(edited)
        starts.first() shouldBe "2026-09-07 09:30"
        starts.contains("2026-11-09 11:00") shouldBe true
        starts.contains("2026-11-09 09:30") shouldBe false
        starts.last() shouldBe "2026-12-14 09:30"
    }

    test("the rest of a series another app wrote carries its end date") {
        val split = IcalEdit.updated(PIANO, at("2026-11-09 09:30"), EventScope.FOLLOWING, EventChange(title = "Piano with Sam"), "next@clarity")
            .shouldBeInstanceOf<Edited.Split>()
        localStarts(split.original).last() shouldBe "2026-11-02 09:30"
        localStarts(split.following) shouldContainExactly listOf("2026-11-09", "2026-11-16", "2026-11-23", "2026-11-30", "2026-12-07", "2026-12-14").map { "$it 09:30" }
    }

    test("a zone only the file defines is not edited") {
        val ics = PIANO.replace("America/New_York", "Eastern Standard Time")
        shouldThrow<IllegalArgumentException> {
            IcalEdit.updated(ics, at("2026-11-09 09:30"), EventScope.THIS, EventChange(title = "x"), "next@clarity")
        }.message shouldContain "time zone"
    }

    test("a single event in a zone only the file defines can still move, to UTC") {
        val single = icloud(
            "BEGIN:VEVENT", "DTSTAMP:20260801T120000Z", "UID:one", "DTSTART;TZID=Eastern Standard Time:20261109T093000",
            "DTEND;TZID=Eastern Standard Time:20261109T103000", "SUMMARY:Call", "END:VEVENT",
        ).replace("TZID:America/New_York", "TZID:Eastern Standard Time")
        val moved = IcalEdit.updated(single, null, null, EventChange(start = at("2026-11-10 09:30"), end = at("2026-11-10 10:30")), "next@clarity")
            .shouldBeInstanceOf<Edited.Replace>().ics
        moved shouldContain "DTSTART:20261110T143000Z"
    }

    test("known zones lose their copy of the definition before editing, others keep it") {
        withoutKnownTimeZones(PIANO) shouldNotContain "VTIMEZONE"
        withoutKnownTimeZones(PIANO.replace("America/New_York", "Eastern Standard Time")) shouldContain "BEGIN:VTIMEZONE"
    }
})
