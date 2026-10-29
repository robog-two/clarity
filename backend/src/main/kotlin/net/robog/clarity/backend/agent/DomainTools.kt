package net.robog.clarity.backend.agent

import net.robog.clarity.backend.persistence.emailFolderPattern
import net.robog.clarity.shared.session.Settings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.persistence.MessageSearch
import net.robog.clarity.backend.persistence.NounReader
import net.robog.clarity.backend.persistence.OpenRange
import net.robog.clarity.backend.persistence.SearchTerm
import net.robog.clarity.backend.persistence.searchTerms
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
import net.robog.clarity.shared.time.LocalTimes
import net.robog.clarity.shared.time.ScheduleClock

/**
 * The agent's window onto Clarity: tools defined once over [NounReader] and [TriggerStore], served
 * to the in-process harness and (via `mcp/`) to external MCP clients. Reads go only through
 * [NounReader], so what an agent can see is exactly what that interface exposes.
 *
 * Output is compact JSON (no pretty printing, empty fields omitted, long bodies truncated) because
 * every byte comes back as input tokens on the next turn. Times in and out are on the person's own
 * clock (their home zone, [LocalTimes]); storage stays in instants.
 */
@Inject
@SingleIn(AppScope::class)
class DomainTools(
    private val reader: NounReader,
    private val triggers: TriggerStore,
    private val documents: DocumentTools,
    private val settings: SettingsProvider = SettingsProvider { Settings() },
) {

    val all: List<Tool> by lazy {
        (listOf(listMessages, searchMessages, listCalendarEvents, searchContacts, listTriggers, createTrigger, deleteTrigger) + documents.all)
            .sortedBy { it.name }
    }

    @Serializable
    class ListMessagesInput(
        @Description("Start of the window. $LOCAL_TIME_HINT A plain date (2026-10-26) means the start of that day.") val from: String,
        @Description("End of the window (exclusive), local time like `from`. A plain date means the end of that day, so from=until=2026-10-26 is that whole day.") val until: String,
        @Description("Only messages sent or received by this address/number (exact, case-insensitive)") val participant: String? = null,
        @Description("Maximum messages to return (1-$MAX_LIMIT, default 50)") val limit: Int = 50,
        @Description("Email folder or label: INBOX (default), Sent, Junk, Spam, Archive, a label, or \"*\" for every folder. SMS and chat are always included.")
        val folder: String = "INBOX",
    )

    private val listMessages = tool<ListMessagesInput>(
        name = "list_messages",
        description = "List the user's messages (email, SMS, chat) in a time window, newest first. " +
            "Use to see what happened or arrived in a period, or what a known address has written; " +
            "to find mail by topic, word or a person's name, use search_messages. " +
            "Email comes from the inbox unless you name another folder (e.g. Junk when something seems missing). " +
            "`account` is the person's own address the message arrived at.",
        json = ClarityJson,
    ) { input ->
        val zone = settings.homeZone()
        withRange(input.from, input.until, zone) { range ->
            val folder = input.folder.trim().takeUnless { it.isEmpty() || it == "*" }
            val found = reader.messages(range, input.participant?.takeIf { it.isNotBlank() }, input.limit.clampLimit(), folder)
            if (found.isEmpty() && folder != null) return@withRange emptyFolderAnswer(folder, reader.emailFolders())
            val folders = reader.messageFolders(found.map { it.id })
            ToolResult.Ok(found.map { it.messageJson(folders[it.id].orEmpty(), zone) }.asJsonArray().toString())
        }
    }

    @Serializable
    class SearchMessagesInput(
        @Description("Words to find in names, addresses, subjects and bodies, e.g. `astrology club` or `turecek`. Every word must match (a name or address may match part of it); put a phrase in double quotes.")
        val query: String,
        @Description("Only messages sent at or after this. $LOCAL_TIME_HINT A plain date means the start of that day.") val from: String? = null,
        @Description("Only messages sent before this, local time like `from`. A plain date means the end of that day.") val until: String? = null,
        @Description("email, sms or chat; default all") val channel: String? = null,
        @Description("Email folder or label (INBOX, Sent, Junk, Spam, a label); default every folder. SMS and chat are always included.")
        val folder: String = "*",
        @Description("Maximum messages to return (1-$MAX_LIMIT, default 20)") val limit: Int = 20,
    )

    private val searchMessages = tool<SearchMessagesInput>(
        name = "search_messages",
        description = "Search all of the person's messages (email, SMS, chat) by words: a person's name or part of an address, " +
            "a topic, a subject. Best matches first, newest among equals. Each result has a short `snippet` around the match, " +
            "not the whole body; read a message in full with list_messages around its sentAt.",
        json = ClarityJson,
    ) { input ->
        val zone = settings.homeZone()
        val terms = searchTerms(input.query)
        if (terms.isEmpty()) return@tool ToolResult.Error("`query` needs at least one word.")
        val from = input.from?.takeIf { it.isNotBlank() }?.let { raw ->
            when (val b = parseBound("from", raw, zone, DayAs.START)) { is Parsed.Bad -> return@tool ToolResult.Error(b.message); is Parsed.Ok -> b.value }
        }
        val until = input.until?.takeIf { it.isNotBlank() }?.let { raw ->
            when (val b = parseBound("until", raw, zone, DayAs.END)) { is Parsed.Bad -> return@tool ToolResult.Error(b.message); is Parsed.Ok -> b.value }
        }
        if (from != null && until != null && until <= from) return@tool ToolResult.Error("`until` must be after `from`")
        val channel = input.channel?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            MessagePayload.Channel.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: return@tool ToolResult.Error("`channel` is email, sms or chat, not \"$raw\".")
        }
        val folder = input.folder.trim().takeUnless { it.isEmpty() || it == "*" }
        val hits = reader.searchMessages(MessageSearch(terms, OpenRange(from, until), channel, folder, input.limit.clampLimit()))
        if (hits.messages.isEmpty() && folder != null) return@tool emptyFolderAnswer(folder, reader.emailFolders())
        val folders = reader.messageFolders(hits.messages.map { it.id })
        val json = hits.messages.map { it.messageJson(folders[it.id].orEmpty(), zone, snippetOf = terms) }.asJsonArray().toString()
        ToolResult.Ok(if (hits.partial) "$json\nNo message matched every word; these match some of them." else json)
    }

    @Serializable
    class ListCalendarEventsInput(
        @Description("A single day, YYYY-MM-DD (the person's local day). Use this OR from/until.") val date: String? = null,
        @Description("Range start. Requires `until`. $LOCAL_TIME_HINT A plain date means the start of that day.") val from: String? = null,
        @Description("Range end (exclusive), local time like `from`. A plain date means the end of that day.") val until: String? = null,
        @Description("IANA time zone to read `date`/`from`/`until` in, e.g. Europe/Berlin; default (and almost always right) the person's home zone. Results are always shown in the person's home zone.") val timezone: String? = null,
        @Description("Maximum events to return (1-$MAX_LIMIT, default 100)") val limit: Int = 100,
    )

    private val listCalendarEvents = tool<ListCalendarEventsInput>(
        name = "list_calendar_events",
        description = "List calendar events overlapping a day or a time range, soonest first. Times are the person's local time " +
            "with their offset; all-day events give `start` and `end` as dates (the first and last day, inclusive).",
        json = ClarityJson,
    ) { input ->
        val home = settings.homeZone()
        val readIn = input.timezone?.let { id -> runCatching { TimeZone.of(id) }.getOrNull() ?: return@tool ToolResult.Error("Unknown `timezone`: $id") } ?: home
        when (val range = dayOrRange(input, readIn)) {
            is Parsed.Bad -> ToolResult.Error(range.message)
            is Parsed.Ok -> ToolResult.Ok(
                reader.calendarEvents(range.value, input.limit.clampLimit())
                    .filter { !it.payload.allDay || allDayTouches(it.payload, range.value, readIn) }
                    .map { it.eventJson(home) }.asJsonArray().toString(),
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

/** A range bound on the clock of [zone]: a plain date is the start of the day for `from`, its end for `until`. */
private fun parseBound(label: String, raw: String?, zone: TimeZone, dayAs: DayAs): Parsed<Instant> =
    readToolTime(label, raw, zone, dayAs).fold({ Parsed.Bad(it) }, { Parsed.Ok(it) })

private inline fun withRange(from: String, until: String, zone: TimeZone, block: (TimeRange) -> ToolResult): ToolResult {
    val start = parseBound("from", from, zone, DayAs.START)
    val end = parseBound("until", until, zone, DayAs.END)
    return when {
        start is Parsed.Bad -> ToolResult.Error(start.message)
        end is Parsed.Bad -> ToolResult.Error(end.message)
        start is Parsed.Ok && end is Parsed.Ok ->
            if (end.value <= start.value) ToolResult.Error("`until` must be after `from`") else block(TimeRange(start.value, end.value))
        else -> ToolResult.Error("invalid range")
    }
}

/**
 * Pure: what an empty `list_messages` answer for [folder] means, given the email folders seen so far.
 * A misspelt or missing folder gets the real names; a real folder that is empty says so.
 */
internal fun emptyFolderAnswer(folder: String, known: List<String>): ToolResult {
    if (known.isEmpty()) return ToolResult.Ok("[]")
    val pattern = Regex(emailFolderPattern(folder).removePrefix("^").removeSuffix("/[0-9]+/[0-9]+$") + "$", RegexOption.IGNORE_CASE)
    return if (known.any { pattern.matches(it) }) {
        ToolResult.Ok("[]\nThe folder \"$folder\" exists but has no messages in this window.")
    } else {
        ToolResult.Error("No email folder is called \"$folder\". Folders: ${known.joinToString(", ")}. Use one of these, or \"*\" for all.")
    }
}

private fun dayOrRange(input: DomainTools.ListCalendarEventsInput, zone: TimeZone): Parsed<TimeRange> {
    if (input.date != null) {
        val day = LocalTimes.parse(input.date, zone) as? LocalTimes.Parsed.Day ?: return Parsed.Bad("Invalid `date` (YYYY-MM-DD): ${input.date}")
        return Parsed.Ok(TimeRange(day.start, day.end))
    }
    val start = parseBound("from", input.from, zone, DayAs.START)
    val end = parseBound("until", input.until, zone, DayAs.END)
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

/** A message as the agent reads it; with [snippetOf], a short [snippet] around those terms instead of the body. */
private fun Stored<MessagePayload>.messageJson(folders: List<String>, zone: TimeZone, snippetOf: List<SearchTerm>? = null): JsonElement = buildJsonObject {
    put("id", id.value)
    if (folders.isNotEmpty()) put("folders", JsonArray(folders.map(::JsonPrimitive)))
    put("channel", payload.channel.name.lowercase())
    put("account", payload.account.value) // the person's own address it arrived at (a reply goes from it)
    put("from", payload.sender.label())
    put("to", JsonArray(payload.recipients.map { JsonPrimitive(it.label()) }))
    payload.subject?.let { put("subject", it) }
    put("sentAt", agentTime(payload.sentAt, zone))
    payload.threadRef?.let { put("thread", it) }
    if (snippetOf == null) put("body", payload.body.truncated()) else snippet(payload.body, snippetOf).takeIf { it.isNotEmpty() }?.let { put("snippet", it) }
}

internal const val SNIPPET_CHARS = 240

private val SPACES = Regex("\\s+")

/**
 * Pure: about [SNIPPET_CHARS] of [body] around the first place one of [terms] starts a word (whitespace
 * collapsed, cut on word boundaries, "…" where text was cut). A word also matches by its stem-like
 * prefix ("astrology" finds "astrological"); with no match, the start of the body.
 */
internal fun snippet(body: String, terms: List<SearchTerm>): String {
    val text = body.replace(SPACES, " ").trim()
    if (text.length <= SNIPPET_CHARS) return text
    val needles = terms.flatMap { it.words }.flatMap { w -> listOfNotNull(w, w.take(maxOf(4, w.length - 3)).takeIf { w.length > 4 }) }
    val at = needles.asSequence()
        .mapNotNull { n -> Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(n), RegexOption.IGNORE_CASE).find(text)?.range?.first }
        .minOrNull() ?: 0
    var start = (at - SNIPPET_CHARS / 3).coerceAtLeast(0)
    var end = (start + SNIPPET_CHARS).coerceAtMost(text.length)
    start = (end - SNIPPET_CHARS).coerceAtLeast(0)
    if (start > 0) text.indexOf(' ', start).takeIf { it in start until at }?.let { start = it + 1 }
    if (end < text.length) text.lastIndexOf(' ', end).takeIf { it > maxOf(start, at) }?.let { end = it }
    return (if (start > 0) "…" else "") + text.substring(start, end) + (if (end < text.length) "…" else "")
}

private const val MAX_BODY_CHARS = 1_500

private fun String.truncated(): String =
    if (length <= MAX_BODY_CHARS) this else take(MAX_BODY_CHARS) + "…[truncated ${length - MAX_BODY_CHARS} chars]"

/**
 * Pure: whether an all-day event (stored as UTC midnights, see [LocalTimes.allDayDates]) covers a
 * day of [range] as the person reads the range on the clock of [zone]. Its instants alone would
 * also overlap the evening before in zones west of UTC.
 */
internal fun allDayTouches(event: CalendarEventPayload, range: TimeRange, zone: TimeZone): Boolean {
    val (first, last) = LocalTimes.allDayDates(event.start, event.end)
    val rangeFirst = ScheduleClock.homeDate(range.from, zone)
    val rangeLast = ScheduleClock.homeDate(range.until - 1.nanoseconds, zone)
    return first <= rangeLast && last >= rangeFirst
}

private fun Stored<CalendarEventPayload>.eventJson(zone: TimeZone): JsonElement = buildJsonObject {
    put("id", id.value)
    put("title", payload.title)
    put("calendar", payload.calendar)
    if (payload.allDay) {
        val (first, last) = LocalTimes.allDayDates(payload.start, payload.end)
        put("start", LocalTimes.formatDate(first))
        put("end", LocalTimes.formatDate(last))
        put("allDay", true)
    } else {
        put("start", agentTime(payload.start, zone))
        put("end", agentTime(payload.end, zone))
    }
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

