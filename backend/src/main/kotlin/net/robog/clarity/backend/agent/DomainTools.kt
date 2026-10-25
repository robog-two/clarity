package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.persistence.NounReader
import net.robog.clarity.backend.persistence.Stored
import net.robog.clarity.backend.persistence.TimeRange
import net.robog.clarity.backend.triggers.StoredTrigger
import net.robog.clarity.backend.triggers.TriggerStore
import net.robog.clarity.harness.Description
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.tool
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.ContactPayload
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Noun
import net.robog.clarity.shared.query.RecordQuery
import net.robog.clarity.shared.query.TriggerSpec

/**
 * The agent's window onto Clarity: tools defined once over [NounReader] and [TriggerStore], served
 * to the in-process harness and (via `mcp/`) to external MCP clients. Reads go only through
 * [NounReader], so what an agent can see is exactly what that interface exposes.
 *
 * Output is compact JSON (no pretty printing, empty fields omitted, long bodies truncated) because
 * every byte comes back as input tokens on the next turn.
 */
@Inject
@SingleIn(AppScope::class)
class DomainTools(private val reader: NounReader, private val triggers: TriggerStore) {

    val all: List<Tool> by lazy {
        listOf(listMessages, listCalendarEvents, searchContacts, listTriggers, createTrigger, deleteTrigger)
            .sortedBy { it.name }
    }

    @Serializable
    class ListMessagesInput(
        @Description("Start of the window, ISO-8601 instant, e.g. 2026-10-24T00:00:00Z") val from: String,
        @Description("End of the window (exclusive), ISO-8601 instant") val until: String,
        @Description("Only messages sent or received by this address/number (exact, case-insensitive)") val participant: String? = null,
        @Description("Maximum messages to return (1-$MAX_LIMIT, default 50)") val limit: Int = 50,
    )

    private val listMessages = tool<ListMessagesInput>(
        name = "list_messages",
        description = "List the user's messages (email, SMS, chat) in a time window, newest first. " +
            "Use to see what happened or arrived in a period, or what a person has written.",
        json = ClarityJson,
    ) { input ->
        withRange(input.from, input.until) { range ->
            val found = reader.messages(range, input.participant?.takeIf { it.isNotBlank() }, input.limit.clampLimit())
            ToolResult.Ok(found.map { it.messageJson() }.asJsonArray().toString())
        }
    }

    @Serializable
    class ListCalendarEventsInput(
        @Description("A single day, YYYY-MM-DD (interpreted in `timezone`). Use this OR from/until.") val date: String? = null,
        @Description("Range start, ISO-8601 instant. Requires `until`.") val from: String? = null,
        @Description("Range end (exclusive), ISO-8601 instant") val until: String? = null,
        @Description("IANA time zone used for `date`, default UTC, e.g. Europe/Berlin") val timezone: String? = null,
        @Description("Maximum events to return (1-$MAX_LIMIT, default 100)") val limit: Int = 100,
    )

    private val listCalendarEvents = tool<ListCalendarEventsInput>(
        name = "list_calendar_events",
        description = "List calendar events overlapping a day or a time range, soonest first.",
        json = ClarityJson,
    ) { input ->
        val range = dayOrRange(input)
        when (range) {
            is Parsed.Bad -> ToolResult.Error(range.message)
            is Parsed.Ok -> ToolResult.Ok(
                reader.calendarEvents(range.value, input.limit.clampLimit()).map { it.eventJson() }.asJsonArray().toString(),
            )
        }
    }

    @Serializable
    class SearchContactsInput(
        @Description("Name, organization or handle fragment to look for") val query: String,
        @Description("Maximum contacts to return (1-$MAX_LIMIT, default 20)") val limit: Int = 20,
    )

    private val searchContacts = tool<SearchContactsInput>(
        name = "search_contacts",
        description = "Search the user's contacts by name, organization or email/phone.",
        json = ClarityJson,
    ) { input ->
        ToolResult.Ok(reader.searchContacts(input.query, input.limit.clampLimit()).map { it.contactJson() }.asJsonArray().toString())
    }

    @Serializable
    class NoInput

    private val listTriggers = tool<NoInput>(
        name = "list_triggers",
        description = "List the triggers that currently wake the agent when matching data arrives.",
        json = ClarityJson,
    ) {
        ToolResult.Ok(triggers.list().map { it.toJson() }.asJsonArray().toString())
    }

    private val createTrigger = tool<TriggerSpec>(
        name = "create_trigger",
        description = "Create a trigger: when a newly ingested record of `noun` matches `query`, an agent run starts " +
            "with `instruction`. Query field paths are payload JSON paths, e.g. `sender.value`, `subject`, `body`, " +
            "`title`, `attendees.value`. Check list_triggers first to avoid duplicates.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { spec ->
        ToolResult.Ok(triggers.create(spec).toJson().toString())
    }

    @Serializable
    class DeleteTriggerInput(@Description("Trigger id as returned by list_triggers") val id: String)

    private val deleteTrigger = tool<DeleteTriggerInput>(
        name = "delete_trigger",
        description = "Delete a trigger by id.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        if (triggers.delete(input.id)) ToolResult.Ok("""{"deleted":true}""") else ToolResult.Error("No trigger with id ${input.id}")
    }

    private companion object {
        const val MAX_LIMIT = 200

        fun Int.clampLimit() = coerceIn(1, MAX_LIMIT)
    }
}

/** Parse outcome as a value, so tool bodies stay free of exceptions. */
private sealed interface Parsed<out T> {
    data class Ok<T>(val value: T) : Parsed<T>
    data class Bad(val message: String) : Parsed<Nothing>
}

private fun parseInstant(label: String, raw: String?): Parsed<Instant> =
    if (raw == null) Parsed.Bad("`$label` is required")
    else runCatching { Instant.parse(raw) }.fold({ Parsed.Ok(it) }, { Parsed.Bad("`$label` is not an ISO-8601 instant: $raw") })

private inline fun withRange(from: String, until: String, block: (TimeRange) -> ToolResult): ToolResult {
    val start = parseInstant("from", from)
    val end = parseInstant("until", until)
    return when {
        start is Parsed.Bad -> ToolResult.Error(start.message)
        end is Parsed.Bad -> ToolResult.Error(end.message)
        start is Parsed.Ok && end is Parsed.Ok ->
            if (end.value <= start.value) ToolResult.Error("`until` must be after `from`") else block(TimeRange(start.value, end.value))
        else -> ToolResult.Error("invalid range")
    }
}

private fun dayOrRange(input: DomainTools.ListCalendarEventsInput): Parsed<TimeRange> {
    if (input.date != null) {
        return try {
            val zone = input.timezone?.let(ZoneId::of) ?: ZoneId.of("UTC")
            val day = LocalDate.parse(input.date)
            val start = day.atStartOfDay(zone).toInstant()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant()
            Parsed.Ok(TimeRange(Instant.fromEpochSeconds(start.epochSecond), Instant.fromEpochSeconds(end.epochSecond)))
        } catch (e: DateTimeException) {
            Parsed.Bad("Invalid `date`/`timezone`: ${e.message}")
        }
    }
    val start = parseInstant("from", input.from)
    val end = parseInstant("until", input.until)
    return when {
        start is Parsed.Bad -> Parsed.Bad("Provide `date` or both `from` and `until`. ${start.message}")
        end is Parsed.Bad -> Parsed.Bad("Provide `date` or both `from` and `until`. ${end.message}")
        start is Parsed.Ok && end is Parsed.Ok ->
            if (end.value <= start.value) Parsed.Bad("`until` must be after `from`") else Parsed.Ok(TimeRange(start.value, end.value))
        else -> Parsed.Bad("invalid range")
    }
}

private fun Iterable<JsonElement>.asJsonArray() = JsonArray(toList())

private fun Handle.label(): String = if (displayName.isNullOrBlank()) value else "$displayName <$value>"

private fun Stored<MessagePayload>.messageJson(): JsonElement = buildJsonObject {
    put("id", id.value)
    put("channel", payload.channel.name.lowercase())
    put("from", payload.sender.label())
    put("to", JsonArray(payload.recipients.map { JsonPrimitive(it.label()) }))
    payload.subject?.let { put("subject", it) }
    put("sentAt", payload.sentAt.toString())
    payload.threadRef?.let { put("thread", it) }
    put("body", payload.body.truncated())
}

private const val MAX_BODY_CHARS = 1_500

private fun String.truncated(): String =
    if (length <= MAX_BODY_CHARS) this else take(MAX_BODY_CHARS) + "…[truncated ${length - MAX_BODY_CHARS} chars]"

private fun Stored<CalendarEventPayload>.eventJson(): JsonElement = buildJsonObject {
    put("id", id.value)
    put("title", payload.title)
    put("calendar", payload.calendar)
    put("start", payload.start.toString())
    put("end", payload.end.toString())
    if (payload.allDay) put("allDay", true)
    payload.location?.let { put("location", it) }
    if (payload.attendees.isNotEmpty()) put("attendees", JsonArray(payload.attendees.map { JsonPrimitive(it.label()) }))
    payload.description?.let { put("description", it.truncated()) }
}

private fun Stored<ContactPayload>.contactJson(): JsonElement = buildJsonObject {
    put("id", id.value)
    put("name", payload.displayName)
    payload.organization?.let { put("organization", it) }
    put("handles", JsonArray(payload.handles.map { JsonPrimitive(it.value) }))
    payload.notes?.let { put("notes", it.truncated()) }
}

private fun StoredTrigger.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", spec.name)
    put("noun", ClarityJson.encodeToJsonElement(Noun.serializer(), spec.noun))
    put("enabled", spec.enabled)
    put("instruction", spec.instruction)
    put("query", ClarityJson.encodeToJsonElement(RecordQuery.serializer(), spec.query))
}

