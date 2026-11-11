package net.robog.clarity.connector.dav

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.calendar.EventScope
import net.robog.clarity.shared.calendar.Frequency
import net.robog.clarity.shared.calendar.Recurrence
import net.robog.clarity.shared.governance.InviteResponse
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.noun.Handle

/** Creating, editing, cancelling and answering events through the HTTP flow, against the in-memory server. */

private const val CAL = "/dav/cal/me/work/"

private fun fake(autoSchedule: Boolean = true): FakeDav = FakeDav(autoSchedule).apply {
    collection("/dav/"); collection("/dav/principals/me/"); collection("/dav/cal/me/")
    collection(CAL); calendars += CAL; names[CAL] = "Work"
}

private fun config() = DavConfig(
    caldavUrl = "https://dav.test/dav/", carddavUrl = null, webdavUrl = null,
    username = "me", password = "pw", email = null, calendars = emptyList(), addressBooks = emptyList(), webdavFolders = emptyList(),
)

private fun actions(dav: FakeDav) = DavActions({ listOf(config()) }) { DavServer(it, dav.engine) }

private fun instant(local: String) = Instant.fromEpochSeconds(at(local).epochSecond)

private fun occurrence(path: String, local: String) = SourceId("$path#${at(local).epochSecond}")

private val MWF = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)

private fun seriesAction(key: String = "gym", extra: Recurrence = Recurrence(Frequency.WEEKLY, days = MWF, count = 6)) = ProposedAction.CreateEvent(
    key, "Hold Gym", null, "Gym", instant("2026-10-26 07:00"), instant("2026-10-26 08:00"),
    location = "Pool", url = "https://example.com/gym", repeat = extra, zone = "America/New_York",
)

/** Creates the Gym series and returns its resource path. */
private suspend fun DavActions.createGym(key: String = "gym"): String {
    val done = createEvent(seriesAction(key)).shouldBeInstanceOf<ActionOutcome.Done>()
    return DavRef.parse(done.ref!!.value).path
}

private fun update(key: String, scope: EventScope?, change: (ProposedAction.UpdateEvent) -> ProposedAction.UpdateEvent = { it }) =
    change(ProposedAction.UpdateEvent(key, "update", EntityId("e"), scope = scope))

private fun cancel(key: String, scope: EventScope?, notify: Boolean = false) = ProposedAction.CancelEvent(key, "cancel", EntityId("e"), scope, notify)

private fun DavActions.invite(dav: FakeDav, path: String = "${CAL}team.ics", organizer: String = "me@example.com") {
    dav.put(
        path, "text/calendar",
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nBEGIN:VEVENT\r\nUID:team\r\nDTSTAMP:20260301T000000Z\r\nDTSTART:20261104T150000Z\r\nDTEND:20261104T160000Z\r\n" +
            "SUMMARY:Team\r\nORGANIZER:mailto:$organizer\r\nATTENDEE:mailto:bob@example.com\r\nATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:me@example.com\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
    )
}

class DavEventsTest : FunSpec({
    test("CREATE_EVENT writes a repeating event with its place and link, in the person's zone, once per key") {
        val dav = fake()
        val actions = actions(dav)
        val path = actions.createGym()
        val text = dav.text(path)
        text shouldContain "DTSTART;TZID=America/New_York:20261026T070000"
        text shouldContain "RRULE:FREQ=WEEKLY;COUNT=6;BYDAY=MO,WE,FR"
        text shouldContain "LOCATION:Pool"
        text shouldContain "URL:https://example.com/gym"
        localStarts(text).size shouldBe 6
        actions.createGym()
        dav.files.keys.count { it.startsWith("${CAL}clarity-") } shouldBe 1
    }

    test("CREATE_EVENT writes an all-day event as dates") {
        val dav = fake()
        val utc = { s: String -> Instant.parse(s) }
        val done = actions(dav).createEvent(
            ProposedAction.CreateEvent("trip", "Trip", null, "Offsite", utc("2026-11-05T00:00:00Z"), utc("2026-11-07T00:00:00Z"), allDay = true),
        ).shouldBeInstanceOf<ActionOutcome.Done>()
        val text = dav.text(DavRef.parse(done.ref!!.value).path)
        text shouldContain "DTSTART;VALUE=DATE:20261105"
        text shouldContain "DTEND;VALUE=DATE:20261107"
    }

    context("UPDATE_EVENT") {
        test("a single event changes with If-Match and keeps its other details") {
            val dav = fake()
            val actions = actions(dav)
            val done = actions.createEvent(ProposedAction.CreateEvent("one", "Lunch", null, "Lunch", instant("2026-10-26 12:00"), instant("2026-10-26 13:00"), location = "Cafe")).shouldBeInstanceOf<ActionOutcome.Done>()
            val path = DavRef.parse(done.ref!!.value).path
            actions.updateEvent(update("u1", null) { it.copy(title = "Late lunch", start = instant("2026-10-26 14:00"), end = instant("2026-10-26 15:00")) }, done.ref).shouldBeInstanceOf<ActionOutcome.Done>()
            val payload = Ical.expand(dav.text(path), "Work", WINDOW).occurrences.single().payload
            payload.title shouldBe "Late lunch"
            payload.location shouldBe "Cafe"
            payload.start shouldBe instant("2026-10-26 14:00")
            dav.requests.count { it == "PUT $path" } shouldBe 2
        }

        test("a version that changed under it fails to retry, not to overwrite") {
            val dav = fake()
            val actions = actions(dav)
            val path = actions.createGym()
            val original = dav.text(path)
            dav.beforeWrite = { p -> if (p == path) { dav.put(p, "text/calendar", original); dav.beforeWrite = null } }
            val out = actions.updateEvent(update("u1", EventScope.SERIES) { it.copy(title = "Strength") }, occurrence(path, "2026-11-04 07:00")).shouldBeInstanceOf<ActionOutcome.Failed>()
            out.retryable shouldBe true
            dav.text(path) shouldBe original
            actions.updateEvent(update("u1", EventScope.SERIES) { it.copy(title = "Strength") }, occurrence(path, "2026-11-04 07:00")).shouldBeInstanceOf<ActionOutcome.Done>()
        }

        test("this changes one occurrence in the same resource") {
            val dav = fake()
            val actions = actions(dav)
            val path = actions.createGym()
            actions.updateEvent(update("u1", EventScope.THIS) { it.copy(start = instant("2026-11-04 09:00"), end = instant("2026-11-04 10:00")) }, occurrence(path, "2026-11-04 07:00"))
                .shouldBeInstanceOf<ActionOutcome.Done>()
            dav.text(path) shouldContain "RECURRENCE-ID;TZID=America/New_York:20261104T070000"
            localStarts(dav.text(path)) shouldContain "2026-11-04 09:00"
            localStarts(dav.text(path)).size shouldBe 6
        }

        test("following ends the series and starts a new one under a key-derived id, once") {
            val dav = fake()
            val actions = actions(dav)
            val path = actions.createGym()
            val target = occurrence(path, "2026-11-02 07:00")
            val action = update("u1", EventScope.FOLLOWING) { it.copy(title = "Gym B", start = instant("2026-11-02 08:00"), end = instant("2026-11-02 09:00")) }
            actions.updateEvent(action, target).shouldBeInstanceOf<ActionOutcome.Done>()
            val newPath = "${CAL}${uidFor("u1")}.ics"
            localStarts(dav.text(path)) shouldContainExactly listOf("2026-10-26 07:00", "2026-10-28 07:00", "2026-10-30 07:00")
            localStarts(dav.text(newPath)) shouldContainExactly listOf("2026-11-02 08:00", "2026-11-04 08:00", "2026-11-06 08:00")
            dav.text(newPath) shouldContain "UID:${uidFor("u1")}"
            // a re-delivery after success finds both halves in place
            actions.updateEvent(action, target).shouldBeInstanceOf<ActionOutcome.Done>()
            dav.files.keys.count { it.startsWith("${CAL}clarity-") } shouldBe 2
            localStarts(dav.text(newPath)).size shouldBe 3
        }

        test("series shifts every occurrence") {
            val dav = fake()
            val actions = actions(dav)
            val path = actions.createGym()
            actions.updateEvent(update("u1", EventScope.SERIES) { it.copy(start = instant("2026-11-04 08:00"), end = instant("2026-11-04 09:00")) }, occurrence(path, "2026-11-04 07:00"))
                .shouldBeInstanceOf<ActionOutcome.Done>()
            localStarts(dav.text(path)).map { it.takeLast(5) }.toSet() shouldBe setOf("08:00")
        }

        test("a repeating event needs a scope and a real occurrence") {
            val dav = fake()
            val actions = actions(dav)
            val path = actions.createGym()
            actions.updateEvent(update("u1", null), occurrence(path, "2026-11-04 07:00")).shouldBeInstanceOf<ActionOutcome.Failed>().message shouldContain "`this`, `following` or `series`"
            actions.updateEvent(update("u2", EventScope.THIS), occurrence(path, "2026-11-03 07:00")).shouldBeInstanceOf<ActionOutcome.Failed>().message shouldContain "not in the series"
        }

        test("others on the event need notifyAttendees and a server that schedules; only the organizer may change it") {
            val dav = fake()
            val actions = actions(dav)
            actions.invite(dav)
            val team = SourceId("${CAL}team.ics")
            actions.updateEvent(update("u1", null) { it.copy(title = "x") }, team).shouldBeInstanceOf<ActionOutcome.Failed>().message shouldContain "notifyAttendees"
            actions.updateEvent(update("u2", null) { it.copy(title = "x", notifyAttendees = true) }, team).shouldBeInstanceOf<ActionOutcome.Done>()
            dav.text("${CAL}team.ics") shouldContain "SUMMARY:x"

            val plain = fake(autoSchedule = false)
            val noSchedule = actions(plain)
            noSchedule.invite(plain)
            noSchedule.updateEvent(update("u3", null) { it.copy(title = "x", notifyAttendees = true) }, team).shouldBeInstanceOf<ActionOutcome.Failed>().message shouldContain "scheduling"

            val theirs = fake()
            val theirActions = actions(theirs)
            theirActions.invite(theirs, organizer = "boss@example.com")
            theirActions.updateEvent(update("u4", null) { it.copy(title = "x", notifyAttendees = true) }, team).shouldBeInstanceOf<ActionOutcome.Failed>().message shouldContain "organizer"
        }
    }

    context("CANCEL_EVENT") {
        test("a single event is deleted with If-Match") {
            val dav = fake()
            val actions = actions(dav)
            val done = actions.createEvent(ProposedAction.CreateEvent("one", "Lunch", null, "Lunch", instant("2026-10-26 12:00"), instant("2026-10-26 13:00"))).shouldBeInstanceOf<ActionOutcome.Done>()
            val path = DavRef.parse(done.ref!!.value).path
            actions.cancelEvent(cancel("c1", null), done.ref).shouldBeInstanceOf<ActionOutcome.Done>()
            dav.files.containsKey(path) shouldBe false
            dav.requests shouldContain "DELETE $path"
            // already gone: nothing left to do
            actions.cancelEvent(cancel("c1", null), done.ref).shouldBeInstanceOf<ActionOutcome.Done>()
        }

        test("a version that changed under it fails to retry, not to delete") {
            val dav = fake()
            val actions = actions(dav)
            val path = actions.createGym()
            dav.beforeWrite = { p -> if (p == path) { dav.put(p, "text/calendar", dav.text(p)); dav.beforeWrite = null } }
            actions.cancelEvent(cancel("c1", EventScope.SERIES), occurrence(path, "2026-11-04 07:00")).shouldBeInstanceOf<ActionOutcome.Failed>().retryable shouldBe true
            dav.files.containsKey(path) shouldBe true
            actions.cancelEvent(cancel("c1", EventScope.SERIES), occurrence(path, "2026-11-04 07:00")).shouldBeInstanceOf<ActionOutcome.Done>()
            dav.files.containsKey(path) shouldBe false
        }

        test("this excludes one occurrence") {
            val dav = fake()
            val actions = actions(dav)
            val path = actions.createGym()
            actions.cancelEvent(cancel("c1", EventScope.THIS), occurrence(path, "2026-11-04 07:00")).shouldBeInstanceOf<ActionOutcome.Done>()
            localStarts(dav.text(path)) shouldContainExactly listOf("2026-10-26 07:00", "2026-10-28 07:00", "2026-10-30 07:00", "2026-11-02 07:00", "2026-11-06 07:00")
        }

        test("following ends the series before the occurrence") {
            val dav = fake()
            val actions = actions(dav)
            val path = actions.createGym()
            actions.cancelEvent(cancel("c1", EventScope.FOLLOWING), occurrence(path, "2026-11-02 07:00")).shouldBeInstanceOf<ActionOutcome.Done>()
            localStarts(dav.text(path)) shouldContainExactly listOf("2026-10-26 07:00", "2026-10-28 07:00", "2026-10-30 07:00")
        }

        test("series deletes the resource") {
            val dav = fake()
            val actions = actions(dav)
            val path = actions.createGym()
            actions.cancelEvent(cancel("c1", EventScope.SERIES), occurrence(path, "2026-11-02 07:00")).shouldBeInstanceOf<ActionOutcome.Done>()
            dav.files.containsKey(path) shouldBe false
        }

        test("a repeating event needs a scope") {
            val dav = fake()
            val actions = actions(dav)
            val path = actions.createGym()
            actions.cancelEvent(cancel("c1", null), occurrence(path, "2026-11-02 07:00")).shouldBeInstanceOf<ActionOutcome.Failed>().message shouldContain "`this`"
            dav.files.containsKey(path) shouldBe true
        }

        test("others on the event need notifyAttendees; an attendee cannot cancel someone else's event") {
            val dav = fake()
            val actions = actions(dav)
            actions.invite(dav)
            val team = SourceId("${CAL}team.ics")
            actions.cancelEvent(cancel("c1", null), team).shouldBeInstanceOf<ActionOutcome.Failed>().message shouldContain "notifyAttendees"
            dav.files.containsKey("${CAL}team.ics") shouldBe true
            actions.cancelEvent(cancel("c2", null, notify = true), team).shouldBeInstanceOf<ActionOutcome.Done>()
            dav.files.containsKey("${CAL}team.ics") shouldBe false

            val theirs = fake()
            val theirActions = actions(theirs)
            theirActions.invite(theirs, organizer = "boss@example.com")
            theirActions.cancelEvent(cancel("c3", null, notify = true), team).shouldBeInstanceOf<ActionOutcome.Failed>().message shouldContain "respond_to_invite"
            theirs.files.containsKey("${CAL}team.ics") shouldBe true
        }
    }

    context("delivered twice") {
        test("cancelling one occurrence or the tail again is done, not a failure") {
            val dav = fake()
            val actions = actions(dav)
            val path = actions.createGym()
            val one = cancel("c1", EventScope.THIS)
            actions.cancelEvent(one, occurrence(path, "2026-11-04 07:00")).shouldBeInstanceOf<ActionOutcome.Done>()
            val afterFirst = dav.text(path)
            actions.cancelEvent(one, occurrence(path, "2026-11-04 07:00")).shouldBeInstanceOf<ActionOutcome.Done>()
            dav.text(path) shouldBe afterFirst
            val tail = cancel("c2", EventScope.FOLLOWING)
            actions.cancelEvent(tail, occurrence(path, "2026-11-02 07:00")).shouldBeInstanceOf<ActionOutcome.Done>()
            val afterTail = dav.text(path)
            actions.cancelEvent(tail, occurrence(path, "2026-11-02 07:00")).shouldBeInstanceOf<ActionOutcome.Done>()
            dav.text(path) shouldBe afterTail
        }

        test("moving one occurrence or the series again is done, and nothing is written twice") {
            val dav = fake()
            val actions = actions(dav)
            val path = actions.createGym()
            val one = update("u1", EventScope.THIS) { it.copy(start = instant("2026-11-04 09:00"), end = instant("2026-11-04 10:00")) }
            actions.updateEvent(one, occurrence(path, "2026-11-04 07:00")).shouldBeInstanceOf<ActionOutcome.Done>()
            actions.updateEvent(one, occurrence(path, "2026-11-04 07:00")).shouldBeInstanceOf<ActionOutcome.Done>()
            localStarts(dav.text(path)).count { it == "2026-11-04 09:00" } shouldBe 1

            val whole = update("u2", EventScope.SERIES) { it.copy(start = instant("2026-11-02 08:00"), end = instant("2026-11-02 09:00")) }
            actions.updateEvent(whole, occurrence(path, "2026-11-02 07:00")).shouldBeInstanceOf<ActionOutcome.Done>()
            val afterFirst = dav.text(path)
            actions.updateEvent(whole, occurrence(path, "2026-11-02 07:00")).shouldBeInstanceOf<ActionOutcome.Done>()
            dav.text(path) shouldBe afterFirst
        }
    }

    context("a delivery that was already done stays done") {
        test("even when the event now has attendees the server could not notify") {
            val dav = fake()
            val actions = actions(dav)
            val path = actions.createGym()
            val action = update("u1", EventScope.THIS) { it.copy(title = "Once") }
            actions.updateEvent(action, occurrence(path, "2026-11-04 07:00")).shouldBeInstanceOf<ActionOutcome.Done>()
            // the same event gains an attendee and the server stops scheduling: a fresh action would be refused, the repeat is not
            dav.put(path, "text/calendar", dav.text(path).replace("END:VEVENT", "ORGANIZER:mailto:me@example.com\r\nATTENDEE:mailto:bob@example.com\r\nEND:VEVENT"))
            actions.updateEvent(action, occurrence(path, "2026-11-04 07:00")).shouldBeInstanceOf<ActionOutcome.Done>()
            actions.updateEvent(update("u2", EventScope.THIS) { it.copy(title = "Twice") }, occurrence(path, "2026-11-04 07:00"))
                .shouldBeInstanceOf<ActionOutcome.Failed>().message shouldContain "notifyAttendees"
        }
    }

    context("a split whose second write failed") {
        test("the new series is rebuilt from the latest original, not kept stale") {
            val dav = fake()
            val actions = actions(dav)
            val path = actions.createGym()
            val target = occurrence(path, "2026-11-02 07:00")
            val action = update("u1", EventScope.FOLLOWING) { it.copy(title = "Gym B") }
            val newPath = "${CAL}${uidFor("u1")}.ics"
            // someone moves the whole series to another place after the new series was written and before the original is
            dav.beforeWrite = { p ->
                if (p == path) {
                    dav.put(p, "text/calendar", dav.text(p).replace("LOCATION:Pool", "LOCATION:Deck"))
                    dav.beforeWrite = null
                }
            }
            actions.updateEvent(action, target).shouldBeInstanceOf<ActionOutcome.Failed>().retryable shouldBe true
            dav.text(newPath) shouldContain "LOCATION:Pool" // written from the version before the rename
            actions.updateEvent(action, target).shouldBeInstanceOf<ActionOutcome.Done>()
            dav.text(newPath) shouldContain "SUMMARY:Gym B"
            dav.text(newPath) shouldContain "LOCATION:Deck"
            dav.text(path) shouldContain "LOCATION:Deck"
            localStarts(dav.text(newPath)) shouldContainExactly listOf("2026-11-02 07:00", "2026-11-04 07:00", "2026-11-06 07:00")
        }
    }

    test("a re-run split whose existing new series is too large says so, not that there is no version") {
        val dav = fake()
        val actions = actions(dav)
        val path = actions.createGym()
        dav.put(
            "${CAL}${uidFor("u1")}.ics", "text/calendar",
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nBEGIN:VEVENT\r\nUID:${uidFor("u1")}\r\nDTSTAMP:20260301T000000Z\r\nDTSTART:20261104T150000Z\r\n" +
                "SUMMARY:Big\r\nX-FILL:" + "x".repeat(5_100_000) + "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
        )
        val out = actions.updateEvent(update("u1", EventScope.FOLLOWING) { it.copy(title = "B") }, occurrence(path, "2026-11-02 07:00")).shouldBeInstanceOf<ActionOutcome.Failed>()
        out.message shouldContain "larger"
        out.retryable shouldBe false
    }

    context("a wrong occurrence is a failure, not a done") {
        test("cancelling or editing an occurrence that is not in the series fails and writes nothing") {
            val dav = fake()
            val actions = actions(dav)
            val path = actions.createGym()
            val before = dav.text(path)
            actions.cancelEvent(cancel("c1", EventScope.THIS), occurrence(path, "2026-11-03 07:00")).shouldBeInstanceOf<ActionOutcome.Failed>().message shouldContain "not in the series"
            actions.cancelEvent(cancel("c2", EventScope.FOLLOWING), occurrence(path, "2026-11-03 07:00")).shouldBeInstanceOf<ActionOutcome.Failed>().retryable shouldBe false
            dav.text(path) shouldBe before
        }
    }

    test("reading stops at the limit instead of buffering a larger body") {
        val dav = fake()
        dav.put("${CAL}big.bin", "text/calendar", "x".repeat(2_000))
        DavServer(config(), dav.engine).use { server ->
            server.get(server.resolve(io.ktor.http.Url("https://dav.test/dav/"), "${CAL}big.bin"), 100) shouldBe null
            server.get(server.resolve(io.ktor.http.Url("https://dav.test/dav/"), "${CAL}big.bin"), 5_000)!!.second.size shouldBe 2_000
        }
    }

    context("versions and size") {
        test("an event too large to read is not reported as cancelled") {
            val dav = fake()
            val big = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nBEGIN:VEVENT\r\nUID:big\r\nDTSTAMP:20260301T000000Z\r\nDTSTART:20261104T150000Z\r\n" +
                "DTEND:20261104T160000Z\r\nSUMMARY:Big\r\nX-FILL:" + "x".repeat(5_100_000) + "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
            dav.put("${CAL}big.ics", "text/calendar", big)
            val actions = actions(dav)
            actions.cancelEvent(cancel("c1", null), SourceId("${CAL}big.ics")).shouldBeInstanceOf<ActionOutcome.Failed>().message shouldContain "larger"
            dav.files.containsKey("${CAL}big.ics") shouldBe true
            actions.updateEvent(update("u1", null) { it.copy(title = "x") }, SourceId("${CAL}big.ics")).shouldBeInstanceOf<ActionOutcome.Failed>().message shouldContain "larger"
        }

        test("a server that sends no version gets no blind write") {
            val dav = fake()
            val actions = actions(dav)
            val path = actions.createGym()
            val before = dav.text(path)
            dav.omitEtag = true
            actions.updateEvent(update("u1", EventScope.SERIES) { it.copy(title = "x") }, occurrence(path, "2026-11-04 07:00")).shouldBeInstanceOf<ActionOutcome.Failed>().also { it.retryable shouldBe false; it.message shouldContain "no version tag" }
            actions.cancelEvent(cancel("c1", EventScope.SERIES), occurrence(path, "2026-11-04 07:00")).shouldBeInstanceOf<ActionOutcome.Failed>().retryable shouldBe false
            actions.respond(ProposedAction.RespondToInvite("r1", "accept", EntityId("e"), InviteResponse.ACCEPT), SourceId(path)).shouldBeInstanceOf<ActionOutcome.Failed>().retryable shouldBe false
            dav.text(path) shouldBe before
            dav.requests.none { it == "DELETE $path" } shouldBe true
        }
    }

    test("a full re-sync of a series with a link carries both and makes the same entities") {
        val dav = fake()
        val actions = actions(dav)
        actions.createGym()
        suspend fun synced(): List<net.robog.clarity.shared.ingest.IngestRecord.Upsert> {
            val out = mutableListOf<net.robog.clarity.shared.ingest.IngestRecord>()
            DavServer(config(), dav.engine).use { server ->
                DavSync(server, config(), net.robog.clarity.connector.sdk.InMemoryStateStore(), object : kotlin.time.Clock { override fun now() = Instant.parse("2026-10-20T08:00:00Z") })
                    .syncAll { out += it }
            }
            return out.filterIsInstance<net.robog.clarity.shared.ingest.IngestRecord.Upsert>().filter { it.payload is net.robog.clarity.shared.noun.CalendarEventPayload }
        }
        val first = synced()
        val again = synced()
        first.map { it.sourceId } shouldBe again.map { it.sourceId }
        first.map { it.sourceId }.toSet().size shouldBe 6
        val payload = first.first().payload as net.robog.clarity.shared.noun.CalendarEventPayload
        payload.url shouldBe "https://example.com/gym"
        payload.repeats shouldBe "every Mon, Wed and Fri for 6 occurrences"
    }

    test("a stored MOVE_EVENT still moves a single event and still refuses an occurrence") {
        val dav = fake()
        val actions = actions(dav)
        val done = actions.createEvent(ProposedAction.CreateEvent("one", "Lunch", null, "Lunch", instant("2026-10-26 12:00"), instant("2026-10-26 13:00"))).shouldBeInstanceOf<ActionOutcome.Done>()
        val move = ProposedAction.MoveEvent("m1", "move", EntityId("e"), instant("2026-10-27 12:00"), instant("2026-10-27 13:00"), false)
        actions.moveEvent(move, done.ref).shouldBeInstanceOf<ActionOutcome.Done>()
        Ical.expand(dav.text(DavRef.parse(done.ref!!.value).path), "Work", WINDOW).occurrences.single().payload.start shouldBe instant("2026-10-27 12:00")
        val path = actions.createGym("gym2")
        actions.moveEvent(move, occurrence(path, "2026-11-04 07:00")).shouldBeInstanceOf<ActionOutcome.Failed>().message shouldContain "update_event"
    }

    test("RESPOND_TO_INVITE on one occurrence answers the whole series") {
        val dav = fake()
        val actions = actions(dav)
        dav.put(
            "${CAL}weekly.ics", "text/calendar",
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nBEGIN:VEVENT\r\nUID:weekly\r\nDTSTAMP:20260301T000000Z\r\nDTSTART:20261104T150000Z\r\nDTEND:20261104T160000Z\r\n" +
                "RRULE:FREQ=WEEKLY;COUNT=4\r\nSUMMARY:Sync\r\nORGANIZER:mailto:boss@example.com\r\nATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:me@example.com\r\nEND:VEVENT\r\n" +
                "BEGIN:VEVENT\r\nUID:weekly\r\nDTSTAMP:20260301T000000Z\r\nRECURRENCE-ID:20261111T150000Z\r\nDTSTART:20261111T160000Z\r\nDTEND:20261111T170000Z\r\nSUMMARY:Sync\r\n" +
                "ORGANIZER:mailto:boss@example.com\r\nATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:me@example.com\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
        )
        val answer = ProposedAction.RespondToInvite("r1", "accept", EntityId("e"), InviteResponse.ACCEPT)
        actions.respond(answer, SourceId("${CAL}weekly.ics#1793804400")).shouldBeInstanceOf<ActionOutcome.Done>()
        Regex("PARTSTAT=ACCEPTED").findAll(dav.text("${CAL}weekly.ics")).count() shouldBe 2
        dav.requests.count { it == "PUT ${CAL}weekly.ics" } shouldBe 1
    }

    test("an event with a repeat and attendees is invited through the scheduling server") {
        val dav = fake()
        val action = seriesAction("team-series").copy(attendees = listOf(Handle.email("bob@example.com")), hold = false)
        val done = actions(dav).createEvent(action).shouldBeInstanceOf<ActionOutcome.Done>()
        val text = dav.text(DavRef.parse(done.ref!!.value).path)
        text shouldContain "ATTENDEE"
        text shouldContain "RRULE:FREQ=WEEKLY"
        text shouldContain "ORGANIZER"
    }
})
