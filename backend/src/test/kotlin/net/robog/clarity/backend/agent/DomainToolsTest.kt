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
    var lastRange: TimeRange? = null
    var lastParticipant: String? = null
    var lastLimit: Int? = null

    override suspend fun messages(range: TimeRange, participant: String?, limit: Int): List<Stored<MessagePayload>> {
        lastRange = range; lastParticipant = participant; lastLimit = limit
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
        return listOf(Stored(EntityId("e1"), CalendarEventPayload(null, "work", "Standup", t0, t0, false), t0, t0))
    }

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
    val tools = DomainTools(reader, triggers, DocumentTools(reader) { DocumentFetch.Unavailable("none") }).all.associateBy { it.name }

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
