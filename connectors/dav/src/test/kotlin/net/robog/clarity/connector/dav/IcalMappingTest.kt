package net.robog.clarity.connector.dav

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.Instant as JInstant
import kotlin.time.Instant
import net.robog.clarity.shared.governance.InviteResponse
import net.robog.clarity.shared.noun.Handle

private val window = TimeWindow(JInstant.parse("2026-03-01T00:00:00Z"), JInstant.parse("2026-09-01T00:00:00Z"))

private fun ics(vararg body: String) = (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//test//EN") + body + "END:VCALENDAR").joinToString("\r\n") + "\r\n"

private const val BERLIN = """BEGIN:VTIMEZONE
TZID:Europe/Berlin
BEGIN:STANDARD
DTSTART:19701025T030000
RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
TZOFFSETFROM:+0200
TZOFFSETTO:+0100
END:STANDARD
BEGIN:DAYLIGHT
DTSTART:19700329T020000
RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU
TZOFFSETFROM:+0100
TZOFFSETTO:+0200
END:DAYLIGHT
END:VTIMEZONE"""

class IcalMappingTest : FunSpec({
    test("a UTC event maps with title, place, notes and attendees") {
        val text = ics(
            "BEGIN:VEVENT", "UID:abc-1", "DTSTAMP:20260301T000000Z", "DTSTART:20260310T090000Z", "DTEND:20260310T100000Z",
            "SUMMARY:Planning", "LOCATION:Room 4", "DESCRIPTION:Quarterly planning", "ORGANIZER:mailto:boss@example.com",
            "ATTENDEE;CN=Alice;PARTSTAT=NEEDS-ACTION:mailto:Alice@Example.com", "ATTENDEE:mailto:me@example.com", "END:VEVENT",
        )
        val occ = Ical.events(text, "Work", window).single()
        occ.key shouldBe null
        occ.payload.icalUid shouldBe "abc-1"
        occ.payload.calendar shouldBe "Work"
        occ.payload.title shouldBe "Planning"
        occ.payload.start shouldBe Instant.parse("2026-03-10T09:00:00Z")
        occ.payload.end shouldBe Instant.parse("2026-03-10T10:00:00Z")
        occ.payload.allDay shouldBe false
        occ.payload.location shouldBe "Room 4"
        occ.payload.attendees shouldBe listOf(Handle.email("alice@example.com", "Alice"), Handle.email("me@example.com"))
    }

    test("a time zone event is converted to the right instant, across daylight saving") {
        val text = ics(
            BERLIN.replace("\n", "\r\n"),
            "BEGIN:VEVENT", "UID:tz-1", "DTSTAMP:20260301T000000Z",
            "DTSTART;TZID=Europe/Berlin:20260310T090000", "DTEND;TZID=Europe/Berlin:20260310T100000", "SUMMARY:Winter", "END:VEVENT",
            "BEGIN:VEVENT", "UID:tz-2", "DTSTAMP:20260301T000000Z",
            "DTSTART;TZID=Europe/Berlin:20260710T090000", "DTEND;TZID=Europe/Berlin:20260710T100000", "SUMMARY:Summer", "END:VEVENT",
        )
        val byTitle = Ical.events(text, "c", window).associate { it.payload.title to it.payload }
        byTitle.getValue("Winter").start shouldBe Instant.parse("2026-03-10T08:00:00Z") // CET, +1
        byTitle.getValue("Summer").start shouldBe Instant.parse("2026-07-10T07:00:00Z") // CEST, +2
    }

    test("an all-day event spans whole days (end exclusive)") {
        val text = ics("BEGIN:VEVENT", "UID:day-1", "DTSTAMP:20260301T000000Z", "DTSTART;VALUE=DATE:20260320", "DTEND;VALUE=DATE:20260322", "SUMMARY:Offsite", "END:VEVENT")
        val p = Ical.events(text, "c", window).single().payload
        p.allDay shouldBe true
        p.start shouldBe Instant.parse("2026-03-20T00:00:00Z")
        p.end shouldBe Instant.parse("2026-03-22T00:00:00Z")
        // an all-day event with no DTEND lasts one day
        val noEnd = ics("BEGIN:VEVENT", "UID:day-2", "DTSTAMP:20260301T000000Z", "DTSTART;VALUE=DATE:20260320", "SUMMARY:Holiday", "END:VEVENT")
        Ical.events(noEnd, "c", window).single().payload.end shouldBe Instant.parse("2026-03-21T00:00:00Z")
    }

    test("a weekly series expands inside the window, honours EXDATE and overridden occurrences, and keeps occurrences distinct") {
        val text = ics(
            "BEGIN:VEVENT", "UID:weekly", "DTSTAMP:20260101T000000Z", "DTSTART:20260302T100000Z", "DTEND:20260302T110000Z",
            "RRULE:FREQ=WEEKLY;COUNT=6", "EXDATE:20260316T100000Z", "SUMMARY:Standup", "END:VEVENT",
            "BEGIN:VEVENT", "UID:weekly", "DTSTAMP:20260101T000000Z", "RECURRENCE-ID:20260323T100000Z",
            "DTSTART:20260323T140000Z", "DTEND:20260323T150000Z", "SUMMARY:Standup (moved)", "END:VEVENT",
        )
        val occ = Ical.events(text, "c", window)
        // 6 weekly occurrences (2, 9, 16, 23, 30 Mar, 6 Apr) minus the excluded 16th; the 23rd comes from the override
        occ.map { it.payload.start.toString() } shouldContainExactly listOf(
            "2026-03-02T10:00:00Z", "2026-03-09T10:00:00Z", "2026-03-30T10:00:00Z", "2026-04-06T10:00:00Z", "2026-03-23T14:00:00Z",
        )
        occ.map { it.payload.title }.count { it == "Standup (moved)" } shouldBe 1
        occ.map { it.payload.icalUid }.toSet() shouldHaveSize 5 // distinct entity identity per occurrence
        occ.first().payload.icalUid shouldBe "weekly#${JInstant.parse("2026-03-02T10:00:00Z").epochSecond}"
        occ.first().key shouldBe "${JInstant.parse("2026-03-02T10:00:00Z").epochSecond}"
    }

    test("an unbounded series only produces what the window needs") {
        val text = ics("BEGIN:VEVENT", "UID:daily", "DTSTAMP:20250101T000000Z", "DTSTART:20250101T080000Z", "DTEND:20250101T083000Z", "RRULE:FREQ=DAILY", "SUMMARY:Run", "END:VEVENT")
        val small = TimeWindow(JInstant.parse("2026-03-01T00:00:00Z"), JInstant.parse("2026-03-04T00:00:00Z"))
        Ical.events(text, "c", small).map { it.payload.start.toString() } shouldContainExactly
            listOf("2026-03-01T08:00:00Z", "2026-03-02T08:00:00Z", "2026-03-03T08:00:00Z")
    }

    test("events outside the window and cancelled events are dropped") {
        val text = ics(
            "BEGIN:VEVENT", "UID:old", "DTSTAMP:20200101T000000Z", "DTSTART:20200101T090000Z", "DTEND:20200101T100000Z", "SUMMARY:Old", "END:VEVENT",
            "BEGIN:VEVENT", "UID:gone", "DTSTAMP:20260101T000000Z", "DTSTART:20260310T090000Z", "DTEND:20260310T100000Z", "STATUS:CANCELLED", "SUMMARY:Cancelled", "END:VEVENT",
        )
        Ical.events(text, "c", window) shouldBe emptyList()
    }

    test("a new hold is a private, opaque event with a derived UID that parses back") {
        val uid = uidFor("hold-1")
        uid shouldBe uidFor("hold-1")
        (uid == uidFor("hold-2")) shouldBe false
        val text = Ical.newEvent(uid, "Deep work", JInstant.parse("2026-03-10T09:30:00Z"), JInstant.parse("2026-03-10T12:00:00Z"), hold = true, attendees = emptyList(), organizer = null)
        text shouldContain "TRANSP:OPAQUE"
        text shouldContain "CLASS:PRIVATE"
        text shouldContain "UID:$uid"
        val back = Ical.events(text, "Mine", window).single().payload
        back.title shouldBe "Deep work"
        back.start shouldBe Instant.parse("2026-03-10T09:30:00Z")
        back.end shouldBe Instant.parse("2026-03-10T12:00:00Z")
        back.icalUid shouldBe uid
    }

    test("a new event with attendees names the organizer and asks each attendee to respond") {
        val text = Ical.newEvent("u@clarity", "Sync", JInstant.parse("2026-03-10T09:00:00Z"), JInstant.parse("2026-03-10T09:30:00Z"), hold = false, attendees = listOf(Handle.email("bob@example.com", "Bob")), organizer = Handle.email("me@example.com"))
        text shouldNotContain "CLASS:PRIVATE"
        text shouldContain "ORGANIZER"
        text shouldContain "mailto:me@example.com"
        Ical.participants(text).attendees shouldBe listOf("bob@example.com")
        Ical.participants(text).organizer shouldBe "me@example.com"
        text shouldContain "PARTSTAT=NEEDS-ACTION"
    }

    test("a reminder is a short event with a DISPLAY alarm at its start") {
        val text = Ical.newReminder(uidFor("rem-1"), "Call mum", JInstant.parse("2026-03-10T17:00:00Z"))
        text shouldContain "BEGIN:VALARM"
        text shouldContain "ACTION:DISPLAY"
        text shouldContain "TRIGGER:PT0S"
        text shouldContain "DESCRIPTION:Call mum"
        val p = Ical.events(text, "c", window).single().payload
        p.title shouldBe "Call mum"
        p.start shouldBe Instant.parse("2026-03-10T17:00:00Z")
        p.end shouldBe Instant.parse("2026-03-10T17:15:00Z")
    }

    test("moving keeps everything but the times and bumps SEQUENCE") {
        val original = ics("BEGIN:VEVENT", "UID:m-1", "DTSTAMP:20260301T000000Z", "DTSTART:20260310T090000Z", "DTEND:20260310T100000Z", "SEQUENCE:2", "SUMMARY:Review", "LOCATION:Room 1", "END:VEVENT")
        val moved = Ical.moved(original, JInstant.parse("2026-03-11T13:00:00Z"), JInstant.parse("2026-03-11T14:00:00Z"))
        moved shouldContain "SEQUENCE:3"
        val p = Ical.events(moved, "c", window).single().payload
        p.start shouldBe Instant.parse("2026-03-11T13:00:00Z")
        p.end shouldBe Instant.parse("2026-03-11T14:00:00Z")
        p.title shouldBe "Review"
        p.location shouldBe "Room 1"
        p.icalUid shouldBe "m-1"
    }

    test("recurring events cannot be edited") {
        val series = ics("BEGIN:VEVENT", "UID:r", "DTSTAMP:20260301T000000Z", "DTSTART:20260310T090000Z", "RRULE:FREQ=DAILY", "SUMMARY:x", "END:VEVENT")
        shouldThrow<IllegalArgumentException> { Ical.moved(series, JInstant.now(), JInstant.now()) }.message shouldContain "recurring"
    }

    test("responding sets the PARTSTAT of our address only") {
        val invite = ics(
            "BEGIN:VEVENT", "UID:i-1", "DTSTAMP:20260301T000000Z", "DTSTART:20260310T090000Z", "DTEND:20260310T100000Z", "SUMMARY:Invite",
            "ORGANIZER:mailto:boss@example.com", "ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:me@example.com", "ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:other@example.com", "END:VEVENT",
        )
        val accepted = Ical.responded(invite, "ME@example.com", InviteResponse.ACCEPT)!!
        accepted shouldContain "PARTSTAT=ACCEPTED"
        accepted.lines().count { it.contains("PARTSTAT=NEEDS-ACTION") } shouldBe 1
        Ical.responded(invite, "stranger@example.com", InviteResponse.ACCEPT) shouldBe null
        Ical.responded(invite, "me@example.com", InviteResponse.DECLINE)!! shouldContain "PARTSTAT=DECLINED"
        Ical.responded(invite, "me@example.com", InviteResponse.TENTATIVE)!! shouldContain "PARTSTAT=TENTATIVE"
    }
})
