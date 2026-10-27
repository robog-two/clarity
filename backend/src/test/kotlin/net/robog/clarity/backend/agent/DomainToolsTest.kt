package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.robog.clarity.backend.persistence.NounReader
import net.robog.clarity.backend.persistence.Stored
import net.robog.clarity.backend.persistence.TimeRange
import net.robog.clarity.backend.triggers.StoredTrigger
import net.robog.clarity.backend.triggers.TriggerStore
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.ContactPayload
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Noun
import net.robog.clarity.shared.noun.Payload
import net.robog.clarity.shared.query.TriggerSpec

private val t0 = Instant.parse("2026-10-24T09:00:00Z")

private class FakeReader : NounReader {
    override suspend fun messageFolders(ids: List<EntityId>) = ids.associateWith { listOf("INBOX", "[Gmail]/All Mail") }
    var lastRange: TimeRange? = null
    var lastParticipant: String? = null
    var lastLimit: Int? = null
    var lastFolder: String? = "unset"
    var noMessages = false
    override suspend fun emailFolders() = listOf("INBOX", "[Gmail]/Sent Mail", "[Gmail]/Spam")

    override suspend fun messages(range: TimeRange, participant: String?, limit: Int, folder: String?): List<Stored<MessagePayload>> {
        lastRange = range; lastParticipant = participant; lastLimit = limit; lastFolder = folder
        if (noMessages) return emptyList()
        return listOf(
            Stored(
                EntityId("m1"),
                MessagePayload(
                    MessagePayload.Channel.EMAIL, Handle.email("me@x.org"), Handle.email("bob@x.org", "Bob"),
                    listOf(Handle.email("me@x.org")), "Hello", "x".repeat(5_000), t0,
                ),
                t0, t0,
            ),
        )
    }

    override suspend fun calendarEvents(range: TimeRange, limit: Int): List<Stored<CalendarEventPayload>> {
        lastRange = range
        return events ?: listOf(Stored(EntityId("e1"), CalendarEventPayload(null, "work", "Standup", t0, t0, false), t0, t0))
    }

    var events: List<Stored<CalendarEventPayload>>? = null

    override suspend fun searchContacts(query: String, limit: Int) =
        listOf(Stored(EntityId("c1"), ContactPayload("Bob", listOf(Handle.email("bob@x.org"))), t0, t0))

    override suspend fun changedSince(since: Instant, limit: Int): List<Stored<Payload>> = emptyList()
}

private class FakeTriggers : TriggerStore {
    val created = mutableListOf<TriggerSpec>()
    override suspend fun list() = created.mapIndexed { i, s -> StoredTrigger("id$i", s) }
    override suspend fun enabledFor(noun: Noun) = list().filter { it.spec.noun == noun }
    override suspend fun create(spec: TriggerSpec): StoredTrigger { created += spec; return StoredTrigger("id${created.size - 1}", spec) }
    override suspend fun delete(id: String) = id == "id0" && created.isNotEmpty().also { if (it) created.removeAt(0) }
}

private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

class DomainToolsTest : FunSpec({
    val reader = FakeReader()
    val triggers = FakeTriggers()
    val home = net.robog.clarity.shared.session.Settings(homeZone = "America/New_York")
    val tools = DomainTools(reader, triggers, DocumentTools(reader) { DocumentFetch.Unavailable("none") }, SettingsProvider { home })
        .all.associateBy { it.name }

    test("exposes the eight tools, sorted, with the right effects") {
        tools.keys.toList() shouldContainExactly
            listOf(
                "create_trigger", "delete_trigger", "list_calendar_events", "list_messages", "list_triggers",
                "read_document", "search_contacts", "search_documents",
            )
        tools.getValue("create_trigger").effect shouldBe ToolEffect.WRITE
        tools.getValue("delete_trigger").effect shouldBe ToolEffect.WRITE
        tools.getValue("list_messages").effect shouldBe ToolEffect.READ
    }

    test("create_trigger schema embeds the recursive RecordQuery as \$defs") {
        val schema = tools.getValue("create_trigger").inputSchema
        schema["\$defs"].toString() shouldContain "RecordQuery"
        schema["\$defs"].toString() shouldContain """"const":"contains""""
    }

    test("list_messages returns compact JSON, truncates bodies, forwards the filters") {
        val result = tools.getValue("list_messages").invoke(
            obj("""{"from":"2026-10-24T00:00:00Z","until":"2026-10-25T00:00:00Z","participant":"bob@x.org","limit":9999}"""),
        ).shouldBeInstanceOf<ToolResult.Ok>()
        reader.lastParticipant shouldBe "bob@x.org"
        reader.lastLimit shouldBe 200
        reader.lastRange shouldBe TimeRange(Instant.parse("2026-10-24T00:00:00Z"), Instant.parse("2026-10-25T00:00:00Z"))
        val first = Json.parseToJsonElement(result.content).let { it as kotlinx.serialization.json.JsonArray }.first().jsonObject
        first["from"].toString() shouldBe "\"Bob <bob@x.org>\""
        first["body"].toString().length shouldBe (1_500 + 2 + "…[truncated 3500 chars]".length)
        result.content.contains("\n") shouldBe false
    }

    test("list_messages reads the inbox unless asked for another folder or every folder, and shows each message's folders") {
        val window = """"from":"2026-10-24T00:00:00Z","until":"2026-10-25T00:00:00Z""""
        val result = tools.getValue("list_messages").invoke(obj("{$window}")).shouldBeInstanceOf<ToolResult.Ok>()
        reader.lastFolder shouldBe "INBOX"
        Json.parseToJsonElement(result.content).let { it as kotlinx.serialization.json.JsonArray }.first().jsonObject["folders"]
            .toString() shouldBe """["INBOX","[Gmail]/All Mail"]"""
        tools.getValue("list_messages").invoke(obj("""{$window,"folder":"Junk"}"""))
        reader.lastFolder shouldBe "Junk"
        tools.getValue("list_messages").invoke(obj("""{$window,"folder":"*"}"""))
        reader.lastFolder shouldBe null
    }

    test("bad timestamps and missing fields are tool errors, not exceptions") {
        tools.getValue("list_messages").invoke(obj("""{"from":"yesterday","until":"2026-10-25T00:00:00Z"}"""))
            .shouldBeInstanceOf<ToolResult.Error>()
        tools.getValue("list_messages").invoke(obj("""{}""")).shouldBeInstanceOf<ToolResult.Error>()
        tools.getValue("list_messages").invoke(obj("""{"from":"2026-10-25T00:00:00Z","until":"2026-10-24T00:00:00Z"}"""))
            .shouldBeInstanceOf<ToolResult.Error>()
    }

    test("list_calendar_events accepts a single date in a time zone, or a range") {
        tools.getValue("list_calendar_events").invoke(obj("""{"date":"2026-10-24","timezone":"Europe/Berlin"}"""))
            .shouldBeInstanceOf<ToolResult.Ok>()
        reader.lastRange shouldBe TimeRange(Instant.parse("2026-10-23T22:00:00Z"), Instant.parse("2026-10-24T22:00:00Z"))
        tools.getValue("list_calendar_events").invoke(obj("""{}""")).shouldBeInstanceOf<ToolResult.Error>()
        tools.getValue("list_calendar_events").invoke(obj("""{"date":"nope"}""")).shouldBeInstanceOf<ToolResult.Error>()
    }

    test("an empty folder and a folder that does not exist get different answers") {
        val window = """"from":"2026-10-24T00:00:00Z","until":"2026-10-25T00:00:00Z""""
        reader.noMessages = true
        try {
            tools.getValue("list_messages").invoke(obj("""{$window,"folder":"spam"}""")).shouldBeInstanceOf<ToolResult.Ok>()
                .content shouldContain "exists but has no messages"
            tools.getValue("list_messages").invoke(obj("""{$window,"folder":"Junk"}""")).shouldBeInstanceOf<ToolResult.Error>()
                .message shouldContain "INBOX, [Gmail]/Sent Mail, [Gmail]/Spam"
            tools.getValue("list_messages").invoke(obj("""{$window,"folder":"*"}""")).shouldBeInstanceOf<ToolResult.Ok>()
                .content shouldBe "[]"
        } finally {
            reader.noMessages = false
        }
    }

    test("emptyFolderAnswer says nothing extra before any email has synced") {
        emptyFolderAnswer("Junk", emptyList()) shouldBe ToolResult.Ok("[]")
        emptyFolderAnswer("sent mail", listOf("[Gmail]/Sent Mail")).shouldBeInstanceOf<ToolResult.Ok>()
    }

    test("list_calendar_events reads a date in the person's home zone unless told otherwise") {
        tools.getValue("list_calendar_events").invoke(obj("""{"date":"2026-10-24"}""")).shouldBeInstanceOf<ToolResult.Ok>()
        reader.lastRange shouldBe TimeRange(Instant.parse("2026-10-24T04:00:00Z"), Instant.parse("2026-10-25T04:00:00Z"))
    }

    test("list_messages shows when a message was sent on the person's clock") {
        val result = tools.getValue("list_messages").invoke(obj("""{"from":"2026-10-24","until":"2026-10-24"}""")).shouldBeInstanceOf<ToolResult.Ok>()
        // 09:00Z is 05:00 in New York (EDT).
        Json.parseToJsonElement(result.content).let { it as kotlinx.serialization.json.JsonArray }.first().jsonObject["sentAt"]
            .toString() shouldBe "\"2026-10-24T05:00-04:00 (Sat)\""
        // A plain date as from and until is that whole local day.
        reader.lastRange shouldBe TimeRange(Instant.parse("2026-10-24T04:00:00Z"), Instant.parse("2026-10-25T04:00:00Z"))
    }

    test("list_messages reads times without an offset as the person's local time, and still takes Z and offsets") {
        tools.getValue("list_messages").invoke(obj("""{"from":"2026-10-26T19:00","until":"2026-10-26T21:30:00"}""")).shouldBeInstanceOf<ToolResult.Ok>()
        reader.lastRange shouldBe TimeRange(Instant.parse("2026-10-26T23:00:00Z"), Instant.parse("2026-10-27T01:30:00Z"))
        tools.getValue("list_messages").invoke(obj("""{"from":"2026-10-26T23:00:00Z","until":"2026-10-26T21:30-04:00 (Mon)"}"""))
            .shouldBeInstanceOf<ToolResult.Ok>()
        reader.lastRange shouldBe TimeRange(Instant.parse("2026-10-26T23:00:00Z"), Instant.parse("2026-10-27T01:30:00Z"))
        tools.getValue("list_messages").invoke(obj("""{"from":"tonight","until":"2026-10-26"}""")).shouldBeInstanceOf<ToolResult.Error>()
            .message shouldContain "America/New_York"
    }

    test("list_calendar_events shows a 7 pm event at 7 pm on its own day, and all-day events as dates") {
        reader.events = listOf(
            // 19:00-20:00 EDT on Monday: 23:00Z-00:00Z, which is already Tuesday in UTC.
            Stored(EntityId("e1"), CalendarEventPayload(null, "work", "Dinner", Instant.parse("2026-10-26T23:00:00Z"), Instant.parse("2026-10-27T00:00:00Z")), t0, t0),
            // All-day Monday, stored as UTC midnights.
            Stored(EntityId("e2"), CalendarEventPayload(null, "home", "Holiday", Instant.parse("2026-10-26T00:00:00Z"), Instant.parse("2026-10-27T00:00:00Z"), allDay = true), t0, t0),
        )
        try {
            val result = tools.getValue("list_calendar_events").invoke(obj("""{"date":"2026-10-26"}""")).shouldBeInstanceOf<ToolResult.Ok>()
            val events = Json.parseToJsonElement(result.content) as kotlinx.serialization.json.JsonArray
            events[0].jsonObject["start"].toString() shouldBe "\"2026-10-26T19:00-04:00 (Mon)\""
            events[0].jsonObject["end"].toString() shouldBe "\"2026-10-26T20:00-04:00 (Mon)\""
            events[1].jsonObject["start"].toString() shouldBe "\"2026-10-26 (Mon)\""
            events[1].jsonObject["end"].toString() shouldBe "\"2026-10-26 (Mon)\""
            // Sunday in New York ends at 04:00Z Monday, overlapping Monday's all-day instants: it is not Sunday's.
            val sunday = tools.getValue("list_calendar_events").invoke(obj("""{"date":"2026-10-25"}""")).shouldBeInstanceOf<ToolResult.Ok>()
            sunday.content shouldContain "Dinner" // the fake returns everything; the real reader filters by instants
            sunday.content.contains("Holiday") shouldBe false
            tools.getValue("list_calendar_events").invoke(obj("""{"date":"2026-10-26","timezone":"Mars/Base"}""")).shouldBeInstanceOf<ToolResult.Error>()
        } finally {
            reader.events = null
        }
    }

    test("search_contacts returns handles") {
        tools.getValue("search_contacts").invoke(obj("""{"query":"bob"}""")).shouldBeInstanceOf<ToolResult.Ok>()
            .content shouldContain "bob@x.org"
    }

    test("trigger tools: create decodes the nested query, list shows it, delete removes it") {
        val create = tools.getValue("create_trigger").invoke(
            obj(
                """{"name":"boss","noun":"message","instruction":"Tell me",
                    "query":{"type":"all","of":[{"type":"equals","field":"sender.value","value":"boss@x.org"},
                                                {"type":"not","query":{"type":"contains","field":"subject","value":"fyi"}}]}}""",
            ),
        )
        create.shouldBeInstanceOf<ToolResult.Ok>()
        triggers.created.single().noun shouldBe Noun.MESSAGE

        tools.getValue("list_triggers").invoke(obj("{}")).shouldBeInstanceOf<ToolResult.Ok>().content shouldContain "boss"
        tools.getValue("delete_trigger").invoke(obj("""{"id":"id0"}""")).shouldBeInstanceOf<ToolResult.Ok>()
        tools.getValue("delete_trigger").invoke(obj("""{"id":"id0"}""")).shouldBeInstanceOf<ToolResult.Error>()
        tools.getValue("create_trigger").invoke(obj("""{"name":"x"}""")).shouldBeInstanceOf<ToolResult.Error>()
    }
})
