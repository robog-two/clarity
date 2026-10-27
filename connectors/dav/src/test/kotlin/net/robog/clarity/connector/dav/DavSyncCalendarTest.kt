package net.robog.clarity.connector.dav

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.robog.clarity.connector.sdk.InMemoryStateStore
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.noun.CalendarEventPayload

/** How calendar sync keeps history, retries series and upgrades its state. */

private class MovableClock(var at: Instant) : Clock { override fun now() = at }

private fun cal(vararg lines: String) =
    (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//t//EN", "BEGIN:VEVENT", "DTSTAMP:20260301T000000Z") + lines + listOf("END:VEVENT", "END:VCALENDAR")).joinToString("\r\n") + "\r\n"

private fun calendarOnly(): FakeDav = FakeDav().apply {
    collection("/dav/"); collection("/dav/principals/me/"); collection("/dav/cal/me/")
    collection("/dav/cal/me/work/"); calendars += "/dav/cal/me/work/"; names["/dav/cal/me/work/"] = "Work"; syncCollections += "/dav/cal/me/work/"
}

private fun calConfig() = DavConfig(
    caldavUrl = "https://dav.test/dav/", carddavUrl = null, webdavUrl = null,
    username = "me", password = "pw", email = null, calendars = emptyList(), addressBooks = emptyList(), webdavFolders = emptyList(),
)

private val List<IngestRecord>.events get() = filterIsInstance<IngestRecord.Upsert>().filter { it.payload is CalendarEventPayload }
private val List<IngestRecord>.tombstones get() = filterIsInstance<IngestRecord.Tombstone>()

class DavSyncCalendarTest : FunSpec({
    val standup = cal("UID:standup", "DTSTART:20260302T100000Z", "DTEND:20260302T101500Z", "RRULE:FREQ=WEEKLY;COUNT=20", "SUMMARY:Standup")

    test("a series with no occurrence in the window yet is retried when the window moves and then appears") {
        val dav = calendarOnly()
        // starts about 8 months out: beyond the 180-day horizon at the first sync
        dav.put("/dav/cal/me/work/later.ics", "text/calendar", cal("UID:later", "DTSTART:20261102T090000Z", "DTEND:20261102T100000Z", "RRULE:FREQ=WEEKLY;COUNT=4", "SUMMARY:Later"))
        val clock = MovableClock(Instant.parse("2026-03-10T08:00:00Z"))
        val state = InMemoryStateStore()
        DavServer(calConfig(), dav.engine).use { server ->
            val sync = DavSync(server, calConfig(), state, clock)
            val first = mutableListOf<IngestRecord>()
            sync.syncAll { first += it }
            first.events.shouldBeEmpty()

            clock.at = Instant.parse("2026-06-01T08:00:00Z") // the horizon now reaches late November
            val second = mutableListOf<IngestRecord>()
            sync.syncAll { second += it }
            second.events.map { (it.payload as CalendarEventPayload).title } shouldBe List(4) { "Later" }
        }
    }

    test("occurrences that fall out of the back of the window are kept, not tombstoned") {
        val dav = calendarOnly()
        dav.put("/dav/cal/me/work/standup.ics", "text/calendar", standup)
        val clock = MovableClock(Instant.parse("2026-03-10T08:00:00Z"))
        val state = InMemoryStateStore()
        DavServer(calConfig(), dav.engine).use { server ->
            val sync = DavSync(server, calConfig(), state, clock)
            val first = mutableListOf<IngestRecord>()
            sync.syncAll { first += it }
            first.events shouldHaveSize 20

            clock.at = clock.at + 500.days // every occurrence is now far behind the window
            val later = mutableListOf<IngestRecord>()
            sync.syncAll { later += it }
            later.tombstones.shouldBeEmpty()

            // deleting the series at the source still removes every occurrence, old ones included
            dav.delete("/dav/cal/me/work/standup.ics")
            val gone = mutableListOf<IngestRecord>()
            sync.syncAll { gone += it }
            gone.tombstones.map { it.sourceId.value }.toSet() shouldBe first.events.map { it.sourceId.value }.toSet()
        }
    }

    test("an occurrence removed at the source while inside the window is tombstoned; the rest are kept") {
        val dav = calendarOnly()
        dav.put("/dav/cal/me/work/standup.ics", "text/calendar", standup)
        val clock = MovableClock(Instant.parse("2026-03-10T08:00:00Z"))
        val state = InMemoryStateStore()
        DavServer(calConfig(), dav.engine).use { server ->
            val sync = DavSync(server, calConfig(), state, clock)
            sync.syncAll { }
            dav.put("/dav/cal/me/work/standup.ics", "text/calendar", standup.replace("SUMMARY:Standup", "EXDATE:20260316T100000Z\r\nSUMMARY:Standup"))
            val pushed = mutableListOf<IngestRecord>()
            sync.syncAll { pushed += it }
            pushed.tombstones.map { it.sourceId.value } shouldBe listOf("default:/dav/cal/me/work/standup.ics#${java.time.Instant.parse("2026-03-16T10:00:00Z").epochSecond}")
            pushed.events shouldHaveSize 19
        }
    }

    test("plain events are ingested regardless of age") {
        val dav = calendarOnly()
        dav.put("/dav/cal/me/work/old.ics", "text/calendar", cal("UID:old", "DTSTART:20240115T090000Z", "DTEND:20240115T100000Z", "SUMMARY:Old meeting"))
        dav.put("/dav/cal/me/work/far.ics", "text/calendar", cal("UID:far", "DTSTART:20280115T090000Z", "DTEND:20280115T100000Z", "SUMMARY:Far meeting"))
        val pushed = mutableListOf<IngestRecord>()
        DavServer(calConfig(), dav.engine).use { server ->
            DavSync(server, calConfig(), InMemoryStateStore(), MovableClock(Instant.parse("2026-10-27T08:00:00Z"))).syncAll { pushed += it }
        }
        pushed.events.map { (it.payload as CalendarEventPayload).title } shouldContainExactlyInAnyOrder listOf("Old meeting", "Far meeting")
        pushed.events.map { it.sourceId.value } shouldContainExactlyInAnyOrder listOf("default:/dav/cal/me/work/old.ics", "default:/dav/cal/me/work/far.ics")
    }

    test("recurring series are expanded from a year back") {
        val dav = calendarOnly()
        dav.put("/dav/cal/me/work/standup.ics", "text/calendar", standup)
        val pushed = mutableListOf<IngestRecord>()
        DavServer(calConfig(), dav.engine).use { server ->
            // 20 weekly occurrences from 2026-03-02 to 2026-07-13; eleven months on, all of them are still reported
            DavSync(server, calConfig(), InMemoryStateStore(), MovableClock(Instant.parse("2027-02-01T08:00:00Z"))).syncAll { pushed += it }
        }
        pushed.events shouldHaveSize 20
    }

    test("state written by the previous version is upgraded: every resource is refetched once, with the same source ids") {
        val dav = calendarOnly()
        dav.put("/dav/cal/me/work/standup.ics", "text/calendar", standup)
        dav.put("/dav/cal/me/work/plan.ics", "text/calendar", cal("UID:plan", "DTSTART:20260312T090000Z", "DTEND:20260312T100000Z", "SUMMARY:Planning"))
        val clock = MovableClock(Instant.parse("2026-03-10T08:00:00Z"))
        val state = InMemoryStateStore()
        DavServer(calConfig(), dav.engine).use { server ->
            val sync = DavSync(server, calConfig(), state, clock)
            val first = mutableListOf<IngestRecord>()
            sync.syncAll { first += it }

            // simulate what the old connector left behind: no version, no recurring set, a plain event that produced nothing
            val key = state.keys().single { it.startsWith("dav:cal:") }
            val old = state.get(key) as JsonObject
            val occ = (old["occurrences"] as JsonObject).toMutableMap()
            occ["/dav/cal/me/work/plan.ics"] = kotlinx.serialization.json.JsonArray(emptyList())
            state.put(key, JsonObject(old.toMutableMap().apply { remove("version"); remove("recurring"); put("occurrences", JsonObject(occ)) }))

            val upgraded = mutableListOf<IngestRecord>()
            sync.syncAll { upgraded += it }
            upgraded.tombstones.shouldBeEmpty()
            upgraded.events.map { it.sourceId.value }.toSet() shouldBe first.events.map { it.sourceId.value }.toSet()

            val third = mutableListOf<IngestRecord>()
            sync.syncAll { third += it }
            third shouldBe emptyList()
            ((state.get(key) as JsonObject)["version"] as JsonPrimitive).content.toInt() shouldBe CAL_STATE_VERSION
        }
    }
})
