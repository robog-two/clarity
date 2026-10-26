package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.robog.clarity.backend.persistence.NounReader
import net.robog.clarity.backend.persistence.Stored
import net.robog.clarity.backend.persistence.TimeRange
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.ContactPayload
import net.robog.clarity.shared.noun.DocumentPayload
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Payload

private val t0 = Instant.parse("2026-10-24T08:00:00Z")
private val plan = Stored(
    EntityId("d1"),
    DocumentPayload("webdav:x", "/plan.md", "Plan", "text/markdown", 12, t0, "Quarterly plan " + "x".repeat(1000)),
    t0, t0,
)

private class DocReader : NounReader {
    var lastQuery: String? = null
    override suspend fun messages(range: TimeRange, participant: String?, limit: Int) = emptyList<Stored<MessagePayload>>()
    override suspend fun calendarEvents(range: TimeRange, limit: Int) = emptyList<Stored<CalendarEventPayload>>()
    override suspend fun searchContacts(query: String, limit: Int) = emptyList<Stored<ContactPayload>>()
    override suspend fun changedSince(since: Instant, limit: Int) = emptyList<Stored<Payload>>()
    override suspend fun searchDocuments(query: String, limit: Int): List<Stored<DocumentPayload>> {
        lastQuery = query
        return listOf(plan)
    }
    override suspend fun document(id: EntityId) = plan.takeIf { it.id == id }
}

private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

class DocumentToolsTest : FunSpec({
    val reader = DocReader()
    var next: DocumentFetch = DocumentFetch.Text("full text")
    val tools = DocumentTools(reader) { next }.all.associateBy { it.name }

    test("search_documents returns ids with a short excerpt") {
        val ok = tools.getValue("search_documents").invoke(args("""{"query":"plan"}""")).shouldBeInstanceOf<ToolResult.Ok>()
        reader.lastQuery shouldBe "plan"
        val first = (Json.parseToJsonElement(ok.content) as JsonArray).first().jsonObject
        first["id"]!!.jsonPrimitive.content shouldBe "d1"
        first["excerpt"]!!.jsonPrimitive.content.length shouldBe 400
    }

    test("read_document returns the text the connector produced") {
        val ok = tools.getValue("read_document").invoke(args("""{"id":"d1"}""")).shouldBeInstanceOf<ToolResult.Ok>()
        Json.parseToJsonElement(ok.content).jsonObject["text"]!!.jsonPrimitive.content shouldBe "full text"
    }

    test("read_document reports still-fetching and unknown ids as errors the model can act on") {
        next = DocumentFetch.Unavailable("Still fetching /plan.md, try again shortly")
        tools.getValue("read_document").invoke(args("""{"id":"d1"}""")).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "Still fetching"
        tools.getValue("read_document").invoke(args("""{"id":"nope"}""")).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "No document"
    }
})
