package net.robog.clarity.connector.dav

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.time.Instant as JInstant

private fun cal(vararg body: String) =
    (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//test//EN", "BEGIN:VEVENT") + body + listOf("END:VEVENT", "END:VCALENDAR"))
        .joinToString("\r\n") + "\r\n"

private val window = TimeWindow(JInstant.parse("2026-10-01T00:00:00Z"), JInstant.parse("2026-12-31T00:00:00Z"))

/** Shapes seen in the person's iCloud calendars that ical4j refuses outright. */
class IcalBadLineTest : FunSpec({

    test("a Mail-made URL with a broken escape, folded mid-escape, drops only that line") {
        val ics = cal(
            "UID:mail-made",
            "DTSTART:20261030T220000Z",
            "DTEND:20261030T230000Z",
            "SUMMARY:Halloween Seance",
            "URL;VALUE=URI:message:%3CD07F3F83-270E-4E96-AACA-B9B385B1FF7F@ithaca.edu%",
            " 3E",
            "LOCATION:Muller Chapel",
        )
        val events = Ical.events(ics, "Calendar", window)
        events shouldHaveSize 1
        events.single().payload.title shouldBe "Halloween Seance"
        events.single().payload.location shouldBe "Muller Chapel"
    }

    test("an ATTENDEE whose parameters break off early drops that attendee, keeps the event and the others") {
        val ics = cal(
            "UID:club",
            "DTSTART:20261028T230000Z",
            "DTEND:20261029T000000Z",
            "SUMMARY:Club meeting",
            "ATTENDEE;CN=Club;PARTSTAT=:NEEDS-ACTION;ROLE=REQ-PARTICIPANT;RSVP=TRUE;SCHEDULE-AGENT=NONE:mailto:s@example.com",
            "ATTENDEE;CN=Nick:mailto:nick@example.com",
        )
        val events = Ical.events(ics, "Calendar", window)
        events shouldHaveSize 1
        events.single().payload.attendees.map { it.value } shouldBe listOf("nick@example.com")
    }

    test("a broken line in a repeating series keeps the series") {
        val ics = cal(
            "UID:weekly",
            "DTSTART:20261006T223000Z",
            "DTEND:20261007T003000Z",
            "RRULE:FREQ=WEEKLY;COUNT=4",
            "SUMMARY:Figure Drawing",
            "URL:message:%3Cbroken%",
        )
        Ical.events(ics, "Calendar", window) shouldHaveSize 4
    }

    test("a broken line that makes the event is never dropped") {
        val ics = cal("UID:bad-start", "DTSTART:not-a-date", "SUMMARY:x")
        shouldThrow<Exception> { Ical.events(ics, "Calendar", window) }
    }

    test("withoutLine removes a folded line whole and names it") {
        val ics = "A:1\r\nURL:message:%3Cx%\r\n 3E\r\nB:2"
        val (name, rest) = Ical.withoutLine(ics, 3)!!
        name shouldBe "URL"
        rest shouldBe "A:1\r\nB:2"
        rest shouldNotContain "message"
        Ical.withoutLine("UID:x\r\nB:2", 1) shouldBe null
    }
})
