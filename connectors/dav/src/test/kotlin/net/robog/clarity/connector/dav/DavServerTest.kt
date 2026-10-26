package net.robog.clarity.connector.dav

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Clock
import kotlin.time.Instant
import net.robog.clarity.connector.sdk.InMemoryStateStore
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.governance.InviteResponse
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.ContactPayload
import net.robog.clarity.shared.noun.DocumentPayload
import net.robog.clarity.shared.noun.Handle
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts

private val NOW = Instant.parse("2026-03-10T08:00:00Z")
private val fixedClock = object : Clock { override fun now() = NOW }

private fun vevent(uid: String, start: String, end: String, summary: String, vararg extra: String): String =
    (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//t//EN", "BEGIN:VEVENT", "UID:$uid", "DTSTAMP:20260301T000000Z", "DTSTART:$start", "DTEND:$end", "SUMMARY:$summary") +
        extra + listOf("END:VEVENT", "END:VCALENDAR")).joinToString("\r\n") + "\r\n"

private const val VCARD = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Alice Example\r\nN:Example;Alice;;;\r\nORG:Acme\r\nEMAIL:alice@example.com\r\nTEL:+1 555 0100\r\nNOTE:Met at the conference\r\nEND:VCARD\r\n"

private fun pdfWith(text: String): ByteArray {
    PDDocument().use { doc ->
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText(); cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f); cs.newLineAtOffset(50f, 700f); cs.showText(text); cs.endText()
        }
        val out = java.io.ByteArrayOutputStream()
        doc.save(out)
        return out.toByteArray()
    }
}

private fun fakeWithData(autoSchedule: Boolean = true): FakeDav = FakeDav(autoSchedule).apply {
    collection("/dav/"); collection("/dav/principals/me/")
    collection("/dav/cal/me/")
    collection("/dav/cal/me/work/"); calendars += "/dav/cal/me/work/"; names["/dav/cal/me/work/"] = "Work"; syncCollections += "/dav/cal/me/work/"
    collection("/dav/cal/me/personal/"); calendars += "/dav/cal/me/personal/"; names["/dav/cal/me/personal/"] = "Personal"
    collection("/dav/card/me/")
    collection("/dav/card/me/main/"); addressBooks += "/dav/card/me/main/"; names["/dav/card/me/main/"] = "Contacts"
    collection("/dav/files/me/"); collection("/dav/files/me/Notes/"); collection("/dav/files/me/Notes/sub/")
    put("/dav/cal/me/work/plan.ics", "text/calendar", vevent("plan-1", "20260312T090000Z", "20260312T100000Z", "Planning"))
    put("/dav/cal/me/work/standup.ics", "text/calendar", vevent("standup", "20260302T100000Z", "20260302T101500Z", "Standup", "RRULE:FREQ=WEEKLY;COUNT=20"))
    put("/dav/cal/me/personal/dentist.ics", "text/calendar", vevent("dentist", "20260320T140000Z", "20260320T150000Z", "Dentist"))
    put("/dav/card/me/main/alice.vcf", "text/vcard", VCARD)
    put("/dav/files/me/Notes/todo.md", "text/markdown", "# Todo\nBuy milk and write the quarterly report")
    put("/dav/files/me/Notes/page.html", "text/html", "<html><body><h1>Trip</h1><p>Flight at 9</p><script>x()</script></body></html>")
    put("/dav/files/me/Notes/sub/report.pdf", "application/pdf", pdfWith("Annual revenue grew"))
    put("/dav/files/me/Notes/photo.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
}

private fun config() = DavConfig(
    caldavUrl = "https://dav.test/dav/", carddavUrl = "https://dav.test/dav/", webdavUrl = "https://dav.test/dav/files/me/",
    username = "me", password = "pw", email = null, calendars = emptyList(), addressBooks = emptyList(), webdavFolders = listOf("Notes"),
)

private fun move(key: String, start: Instant, end: Instant, notify: Boolean) = ProposedAction.MoveEvent(key, "move", EntityId("e"), start, end, notify)

class DavServerTest : FunSpec({
    test("discovery finds calendars, address books and the user's own address through the principal") {
        val dav = fakeWithData()
        DavServer(config(), dav.engine).use { server ->
            val d = server.discover()
            d.calendars.map { it.name } shouldContainExactlyInAnyOrder listOf("Work", "Personal")
            d.calendars.first { it.name == "Work" }.supportsSync shouldBe true
            d.calendars.first { it.name == "Personal" }.supportsSync shouldBe false
            d.addressBooks.map { it.name } shouldBe listOf("Contacts")
            d.userAddresses shouldBe listOf("me@example.com")
        }
    }

    test("a full sync maps events (recurrences expanded), contacts and documents; a second sync pushes nothing") {
        val dav = fakeWithData()
        val state = InMemoryStateStore()
        val pushed = mutableListOf<IngestRecord>()
        DavServer(config(), dav.engine).use { server ->
            val sync = DavSync(server, config(), state, fixedClock)
            val counts = sync.syncAll { pushed += it }
            counts.contacts shouldBe 1
            counts.documents shouldBe 4
            val upserts = pushed.filterIsInstance<IngestRecord.Upsert>()

            val events = upserts.filter { it.payload is CalendarEventPayload }
            events.count { (it.payload as CalendarEventPayload).title == "Standup" } shouldBe 20
            events.map { it.sourceId.value }.toSet() shouldHaveSize 22 // 20 occurrences + Planning + Dentist
            events.first { (it.payload as CalendarEventPayload).title == "Planning" }.sourceId shouldBe SourceId("/dav/cal/me/work/plan.ics")
            (events.first { (it.payload as CalendarEventPayload).title == "Dentist" }.payload as CalendarEventPayload).calendar shouldBe "Personal"
            events.filter { (it.payload as CalendarEventPayload).title == "Standup" }.map { (it.payload as CalendarEventPayload).icalUid }.toSet() shouldHaveSize 20

            val contact = upserts.map { it.payload }.filterIsInstance<ContactPayload>().single()
            contact.displayName shouldBe "Alice Example"
            contact.organization shouldBe "Acme"
            contact.handles shouldContain Handle.email("alice@example.com")
            contact.notes shouldBe "Met at the conference"

            val docs = upserts.map { it.payload }.filterIsInstance<DocumentPayload>().associateBy { it.title }
            docs.getValue("todo.md").excerpt shouldContain "quarterly report"
            docs.getValue("todo.md").path shouldBe "/Notes/todo.md"
            docs.getValue("todo.md").store shouldBe "webdav:https://dav.test/dav/files/me"
            docs.getValue("page.html").excerpt shouldBe "Trip Flight at 9"
            docs.getValue("report.pdf").excerpt shouldContain "Annual revenue grew"
            docs.getValue("report.pdf").path shouldBe "/Notes/sub/report.pdf"
            docs.getValue("photo.jpg").excerpt shouldBe ""

            pushed.clear()
            sync.syncAll { pushed += it }
            pushed shouldBe emptyList()
        }
    }

    test("later syncs report only changes: edits, deletions (with every occurrence), new files; sync tokens are used") {
        val dav = fakeWithData()
        val state = InMemoryStateStore()
        val pushed = mutableListOf<IngestRecord>()
        DavServer(config(), dav.engine).use { server ->
            val sync = DavSync(server, config(), state, fixedClock)
            sync.syncAll { }
            dav.put("/dav/cal/me/work/plan.ics", "text/calendar", vevent("plan-1", "20260312T110000Z", "20260312T120000Z", "Planning (moved)"))
            dav.delete("/dav/cal/me/work/standup.ics")
            dav.delete("/dav/cal/me/personal/dentist.ics")
            dav.put("/dav/files/me/Notes/new.txt", "text/plain", "fresh note")
            dav.delete("/dav/files/me/Notes/page.html")

            sync.syncAll { pushed += it }
            val upserts = pushed.filterIsInstance<IngestRecord.Upsert>()
            val tombstones = pushed.filterIsInstance<IngestRecord.Tombstone>()

            upserts.map { it.payload }.filterIsInstance<CalendarEventPayload>().map { it.title } shouldBe listOf("Planning (moved)")
            upserts.map { it.payload }.filterIsInstance<DocumentPayload>().map { it.title } shouldBe listOf("new.txt")
            tombstones.count { it.sourceId.value.startsWith("/dav/cal/me/work/standup.ics#") } shouldBe 20
            tombstones.map { it.sourceId.value } shouldContain "/dav/cal/me/personal/dentist.ics"
            tombstones.map { it.sourceId.value } shouldContain "/dav/files/me/Notes/page.html"
            (dav.syncReportTokens.filterNotNull().isNotEmpty()) shouldBe true // the second run presented the stored token
        }
    }

    test("CREATE_EVENT and CREATE_REMINDER are idempotent by key and create the derived resource") {
        val dav = fakeWithData()
        val actions = DavActions { config() to DavServer(config(), dav.engine) }
        val create = ProposedAction.CreateEvent("hold-1", "hold", "Work", "Deep work", Instant.parse("2026-03-11T09:00:00Z"), Instant.parse("2026-03-11T11:00:00Z"))
        val first = actions.createEvent(create).shouldBeInstanceOf<ActionOutcome.Done>()
        val path = first.ref!!.value
        path shouldBe "/dav/cal/me/work/${uidFor("hold-1")}.ics"
        dav.text(path) shouldContain "TRANSP:OPAQUE"
        dav.text(path) shouldContain "CLASS:PRIVATE"
        // a re-delivered action finds its event instead of creating another
        actions.createEvent(create).shouldBeInstanceOf<ActionOutcome.Done>().ref shouldBe first.ref
        dav.files.keys.count { it.startsWith("/dav/cal/me/work/clarity-") } shouldBe 1

        val reminder = ProposedAction.CreateReminder("rem-1", "remind", "Call mum", Instant.parse("2026-03-11T17:00:00Z"))
        val r = actions.createReminder(reminder).shouldBeInstanceOf<ActionOutcome.Done>()
        dav.text(r.ref!!.value) shouldContain "BEGIN:VALARM"
        dav.text(r.ref!!.value) shouldContain "ACTION:DISPLAY"
    }

    test("CREATE_EVENT with attendees needs scheduling support") {
        val dav = fakeWithData(autoSchedule = false)
        val actions = DavActions { config() to DavServer(config(), dav.engine) }
        val invite = ProposedAction.CreateEvent(
            "inv-1", "invite", null, "Sync", Instant.parse("2026-03-11T09:00:00Z"), Instant.parse("2026-03-11T10:00:00Z"),
            attendees = listOf(Handle.email("bob@example.com")), hold = false,
        )
        val out = actions.createEvent(invite).shouldBeInstanceOf<ActionOutcome.Failed>()
        out.retryable shouldBe false
        out.message shouldContain "scheduling"
    }

    test("MOVE_EVENT uses If-Match, refuses to notify without the flag, and refuses when you are not the organizer") {
        val dav = fakeWithData()
        val actions = DavActions { config() to DavServer(config(), dav.engine) }
        val start = Instant.parse("2026-03-13T09:00:00Z")
        val end = Instant.parse("2026-03-13T10:00:00Z")

        actions.moveEvent(move("mv-1", start, end, false), SourceId("/dav/cal/me/work/plan.ics")).shouldBeInstanceOf<ActionOutcome.Done>()
        dav.text("/dav/cal/me/work/plan.ics") shouldContain "DTSTART:20260313T090000Z"

        dav.put(
            "/dav/cal/me/work/team.ics", "text/calendar",
            vevent("team", "20260314T090000Z", "20260314T100000Z", "Team", "ORGANIZER:mailto:me@example.com", "ATTENDEE:mailto:bob@example.com", "ATTENDEE:mailto:me@example.com"),
        )
        val team = SourceId("/dav/cal/me/work/team.ics")
        actions.moveEvent(move("mv-2", start, end, false), team).shouldBeInstanceOf<ActionOutcome.Failed>().message shouldContain "notifyAttendees"
        actions.moveEvent(move("mv-3", start, end, true), team).shouldBeInstanceOf<ActionOutcome.Done>()

        dav.put(
            "/dav/cal/me/work/theirs.ics", "text/calendar",
            vevent("theirs", "20260315T090000Z", "20260315T100000Z", "Theirs", "ORGANIZER:mailto:boss@example.com", "ATTENDEE:mailto:me@example.com"),
        )
        // moving someone else's event is refused with a message the agent can relay
        actions.moveEvent(move("mv-4", start, end, true), SourceId("/dav/cal/me/work/theirs.ics"))
            .shouldBeInstanceOf<ActionOutcome.Failed>().message shouldContain "organizer"
        // a recurring occurrence and an unknown target fail without retrying
        actions.moveEvent(move("mv-5", start, end, false), SourceId("/dav/cal/me/work/standup.ics#1772445600"))
            .shouldBeInstanceOf<ActionOutcome.Failed>().retryable shouldBe false
        actions.moveEvent(move("mv-6", start, end, false), null).shouldBeInstanceOf<ActionOutcome.Failed>().retryable shouldBe false
    }

    test("RESPOND_TO_INVITE updates our PARTSTAT through the server's scheduling") {
        val dav = fakeWithData()
        dav.put(
            "/dav/cal/me/work/invite.ics", "text/calendar",
            vevent("inv", "20260316T090000Z", "20260316T100000Z", "Invite", "ORGANIZER:mailto:boss@example.com", "ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:me@example.com"),
        )
        val actions = DavActions { config() to DavServer(config(), dav.engine) }
        val answer = ProposedAction.RespondToInvite("rsvp-1", "accept", EntityId("e"), InviteResponse.ACCEPT)
        actions.respond(answer, SourceId("/dav/cal/me/work/invite.ics")).shouldBeInstanceOf<ActionOutcome.Done>()
        dav.text("/dav/cal/me/work/invite.ics") shouldContain "PARTSTAT=ACCEPTED"
        // not an attendee of this one
        actions.respond(answer, SourceId("/dav/cal/me/work/plan.ics")).shouldBeInstanceOf<ActionOutcome.Failed>().message shouldContain "not listed"
        // a server without scheduling cannot deliver the reply
        val plain = fakeWithData(autoSchedule = false)
        val noSchedule = DavActions { config() to DavServer(config(), plain.engine) }
        noSchedule.respond(answer, SourceId("/dav/cal/me/work/plan.ics")).shouldBeInstanceOf<ActionOutcome.Failed>().message shouldContain "scheduling"
    }

    test("READ_DOCUMENT returns extracted text, capped; unknown or unreadable files fail clearly") {
        val dav = fakeWithData()
        dav.put("/dav/files/me/Notes/big.txt", "text/plain", "x".repeat(300_000))
        val actions = DavActions { config() to DavServer(config(), dav.engine) }
        suspend fun read(path: String?) = actions.readDocument(ProposedAction.ReadDocument("rd", "read", EntityId("d")), path?.let(::SourceId))

        read("/dav/files/me/Notes/sub/report.pdf").shouldBeInstanceOf<ActionOutcome.Done>().content!! shouldContain "Annual revenue grew"
        read("/dav/files/me/Notes/big.txt").shouldBeInstanceOf<ActionOutcome.Done>().content!!.length shouldBe MAX_READ_CHARS
        read("/dav/files/me/Notes/photo.jpg").shouldBeInstanceOf<ActionOutcome.Failed>().message shouldContain "extract text"
        read(null).shouldBeInstanceOf<ActionOutcome.Failed>().retryable shouldBe false
    }
})
