package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import net.robog.clarity.backend.governance.ActionGate
import net.robog.clarity.backend.governance.MailAccounts
import net.robog.clarity.backend.persistence.NounReader
import net.robog.clarity.harness.Description
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.tool
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.calendar.EventScope
import net.robog.clarity.shared.calendar.Frequency
import net.robog.clarity.shared.calendar.NthWeekday
import net.robog.clarity.shared.calendar.Recurrence
import net.robog.clarity.shared.calendar.dayOfWeekFromCode
import net.robog.clarity.shared.calendar.describe
import net.robog.clarity.shared.calendar.startProblem
import net.robog.clarity.shared.connector.ConnectorAccount
import net.robog.clarity.shared.feed.ContextItem
import net.robog.clarity.shared.governance.EmailDraft
import net.robog.clarity.shared.governance.InviteResponse
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.Urgency
import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.text.oneLine
import net.robog.clarity.shared.time.LocalTimes

/** The inputs every external tool shares: why, and what prompted it. */
private const val WHY = "One or two sentences to the person, first person: what you think and why you want to do this. Shown on the held item."
private const val CONTEXT = "What prompted this (e.g. the message being replied to), shown to the person."

/** A time input on the person's clock ([zone]); a plain date is not enough here. */
/** How much of a title or a place a card summary shows. */
private const val TITLE_CHARS = 80
private const val PLACE_CHARS = 60

private fun instant(label: String, raw: String, zone: TimeZone): Instant =
    readToolTime(label, raw, zone).fold({ throw IllegalArgumentException(it) }, { it })

/**
 * Pure builders: tool input to [ProposedAction]. Throw [IllegalArgumentException] with a message for the model.
 * Times are read and summarized on the clock of the person's home zone [TimeZone] ([LocalTimes]).
 */
object ProposedActions {
    /**
     * An email from one of [accounts] (the person's mail accounts), either new or answering
     * [replied], the message `inReplyTo` names (null when there is none with that id). A reply goes
     * from the account that message arrived at.
     */
    fun draft(key: String, send: Boolean, input: ExternalTools.EmailInput, accounts: List<ConnectorAccount>, replied: MessagePayload?): ProposedAction {
        require(input.to.isNotEmpty()) { "`to` needs at least one recipient" }
        val sender = sender(input.from, accounts)
        val inReplyTo = input.inReplyTo?.trim()?.takeIf(String::isNotEmpty)
        require(!(input.newThread && inReplyTo != null)) { "Give `inReplyTo` for a reply or `newThread: true` for a new email, not both." }
        require(input.newThread || inReplyTo != null) {
            "A reply needs `inReplyTo`, the id of the message it answers (from list_messages or search_messages). For a new email set `newThread: true`."
        }
        if (inReplyTo != null) {
            requireNotNull(replied) { "No message has the id $inReplyTo. Take `inReplyTo` from list_messages or search_messages." }
            val arrivedAt = replied.account.value
            if (!arrivedAt.equals(sender.address, ignoreCase = true)) {
                val holder = accounts.firstOrNull { it.address.equals(arrivedAt, ignoreCase = true) }
                throw IllegalArgumentException(
                    if (holder != null) "That message arrived at ${holder.line()}, so the reply goes from there: set `from` to \"${holder.address}\"."
                    else "That message arrived at $arrivedAt, which is not one of the person's mail accounts (${accounts.joinToString { it.line() }}), " +
                        "so it can't be answered in its thread. Send a new email (`newThread: true`) instead.",
                )
            }
        }
        val email = EmailDraft(
            from = Handle.email(sender.address),
            to = input.to.map(::parseHandle),
            cc = input.cc.map(::parseHandle),
            subject = input.subject,
            body = input.body,
            inReplyTo = inReplyTo?.let(::EntityId),
            fromLabel = sender.label,
            replySubject = replied?.takeIf { inReplyTo != null }?.subject,
        )
        val who = oneLine(email.to.joinToString { it.displayName ?: it.value }, 120)
        return if (send) ProposedAction.SendEmail(key, "Send \"${oneLine(input.subject, TITLE_CHARS)}\" to $who from ${oneLine(sender.label, PLACE_CHARS)}", email)
        else ProposedAction.SaveDraft(key, "Draft \"${oneLine(input.subject, TITLE_CHARS)}\" to $who from ${oneLine(sender.label, PLACE_CHARS)}", email)
    }

    /** The account [raw] names: by address (also as "Name <address>"), else by its label ("Gmail"). */
    fun sender(raw: String, accounts: List<ConnectorAccount>): ConnectorAccount {
        require(accounts.isNotEmpty()) { "No mail account is connected, so there is no address to send from. Tell the person." }
        val wanted = raw.trim()
        val address = parseHandle(wanted).value
        return accounts.firstOrNull { it.address.equals(address, ignoreCase = true) }
            ?: accounts.filter { it.label.equals(wanted, ignoreCase = true) }.singleOrNull()
            ?: throw IllegalArgumentException("`from` must be one of the person's mail accounts: ${accounts.joinToString { it.line() }}. Give its address.")
    }

    private fun ConnectorAccount.line() = "$label <$address>"

    fun createEvent(key: String, input: ExternalTools.CreateEventInput, zone: TimeZone): ProposedAction {
        val (start, end) = eventTimes(input.allDay, input.start, input.end, zone)
        val first = firstDay(start, input.allDay, zone)
        val repeat = input.repeat?.let { recurrence(it, first) }
        val link = link(input.url)
        val lead = if (input.attendees.isEmpty()) "Hold " else "Invite to "
        val notes = input.description?.takeIf(String::isNotBlank)
        return ProposedAction.CreateEvent(
            idempotencyKey = key,
            summary = lead + "\"${oneLine(input.title, TITLE_CHARS)}\" ${whenText(start, end, input.allDay, zone)}" +
                repeat?.let { " repeating ${it.describe(first)}" }.orEmpty() + place(input.location) +
                link?.let { " with link ${host(it)}" }.orEmpty() + if (notes != null) " with notes" else "",
            calendar = input.calendar,
            title = input.title,
            start = start,
            end = end,
            attendees = input.attendees.map(::parseHandle),
            hold = input.attendees.isEmpty() && input.hold,
            description = notes,
            location = input.location?.takeIf(String::isNotBlank),
            url = link,
            allDay = input.allDay,
            repeat = repeat,
            zone = zone.id,
        )
    }

    /**
     * An edit of an event. [current] is the stored event the id names, when it is known: it gives the
     * summary a title, lets a repeating event be refused for a missing `scope` right away, says whether
     * the event is all-day, and completes a half-given time (a new start keeps the length, a new end
     * keeps the start) so the guard and the person see the whole range.
     */
    fun updateEvent(key: String, input: ExternalTools.UpdateEventInput, zone: TimeZone, current: CalendarEventPayload? = null): ProposedAction {
        require(listOf(input.start, input.end, input.title, input.description, input.location, input.url, input.allDay, input.repeat).any { it != null }) {
            "Nothing to change. Give at least one of start, end, title, description, location, url, allDay or repeat."
        }
        input.title?.let { require(it.isNotBlank()) { "`title` can't be empty" } }
        requireScope(input.scope, current?.repeats, "change")
        val allDay = input.allDay ?: current?.allDay
        val (start, finish) = editedRange(input.start, input.end, allDay, zone, current)
        val repeat = input.repeat?.let {
            requireNotNull(start) { "`repeat` needs `start`: the first occurrence it applies from." }
            recurrence(it, firstDay(start, allDay == true, zone))
        }
        val link = input.url?.let { if (it.isBlank()) "" else link(it) }
        val changes = listOfNotNull(
            start?.let { s -> if (finish != null) "to ${whenText(s, finish, allDay == true, zone)}" else "to start ${LocalTimes.format(s, zone)}" }
                ?: finish?.let { "to end ${LocalTimes.format(it, zone)}" },
            input.title?.let { "rename to \"${oneLine(it, TITLE_CHARS)}\"" },
            input.location?.let { if (it.isBlank()) "clear the location" else place(it).trim() },
            link?.let { if (it.isEmpty()) "clear the link" else "with link ${host(it)}" },
            input.description?.let { if (it.isBlank()) "clear the notes" else "new notes" },
            repeat?.let { "repeat ${it.describe(firstDay(start!!, allDay == true, zone))}" },
        )
        return ProposedAction.UpdateEvent(
            idempotencyKey = key,
            summary = "Update ${eventLabel(input.eventId, current)}${scopeText(input.scope)} ${changes.joinToString(" ")}".trim(),
            event = EntityId(input.eventId),
            scope = input.scope,
            start = start,
            end = finish,
            title = input.title,
            description = input.description,
            location = input.location,
            url = link,
            allDay = allDay,
            repeat = repeat,
            notifyAttendees = input.notifyAttendees,
            zone = zone.id,
        )
    }

    fun cancelEvent(key: String, input: ExternalTools.CancelEventInput, current: CalendarEventPayload? = null): ProposedAction {
        requireScope(input.scope, current?.repeats, "cancel")
        return ProposedAction.CancelEvent(
            key, "Cancel ${eventLabel(input.eventId, current)}${scopeText(input.scope)}".trim(),
            EntityId(input.eventId), input.scope, input.notifyAttendees,
        )
    }

    /**
     * The new start and end of an edit, null where nothing is given and nothing is known. A new start
     * keeps the stored event's length, a new end keeps its start. [allDay] says how to read the inputs:
     * as dates (an all-day event, the end being its last day) or as moments on [zone]'s clock.
     */
    private fun editedRange(start: String?, end: String?, allDay: Boolean?, zone: TimeZone, current: CalendarEventPayload?): Pair<Instant?, Instant?> {
        if (start == null && end == null) return null to null
        if (allDay == true) {
            val stored = current?.let { LocalTimes.allDayDates(it.start, it.end) }
            val first = day("start", start) ?: stored?.first ?: throw IllegalArgumentException("`start` is required: the first day, like 2026-10-26")
            val last = day("end", end) ?: stored?.let { first.plus(DatePeriod(days = it.first.daysUntil(it.second))) } ?: first
            require(last >= first) { "`end` must not be before `start`" }
            return first.atStartOfDayIn(TimeZone.UTC) to last.plus(DatePeriod(days = 1)).atStartOfDayIn(TimeZone.UTC)
        }
        val from = start?.let { instant("start", it, zone) }
        val to = end?.let { instant("end", it, zone) }
        if (from != null && to != null) require(to > from) { "`end` must be after `start`" }
        return when {
            current == null -> from to to
            from != null && to == null -> from to from + (current.end - current.start)
            from == null && to != null -> current.start to to
            else -> from to to
        }
    }

    /** A repeating event is only ever touched in part by saying which part. */
    private fun requireScope(scope: EventScope?, repeats: String?, verb: String) {
        require(scope != null || repeats == null) { "This event repeats ($repeats). Say what to $verb: scope `this`, `following` or `series`." }
    }

    private fun eventLabel(id: String, current: CalendarEventPayload?) = current?.let { "\"${oneLine(it.title, TITLE_CHARS)}\"" } ?: "event ${oneLine(id, 40)}"

    private fun scopeText(scope: EventScope?) = when (scope) {
        EventScope.THIS -> " (this one)"
        EventScope.FOLLOWING -> " (this and following)"
        EventScope.SERIES -> " (whole series)"
        null -> ""
    }

    private fun place(location: String?) = location?.takeIf(String::isNotBlank)?.let { " at ${oneLine(it, PLACE_CHARS)}" }.orEmpty()

    /** The site of a link, for the card: what the person can judge it by. */
    private fun host(link: String) = oneLine(runCatching { asciiHost(java.net.URI(link)) }.getOrNull().orEmpty(), 60)

    /** The host of [uri] as punycode; null when it has none or is not a valid name. */
    private fun asciiHost(uri: java.net.URI): String? = runCatching { java.net.IDN.toASCII(hostOf(uri)) }.getOrNull()?.takeIf(String::isNotEmpty)

    /** The host of [uri]; java.net.URI leaves it null for letters outside ASCII, so it is read from the authority. */
    private fun hostOf(uri: java.net.URI): String = uri.host ?: uri.authority?.substringAfterLast('@')?.substringBefore(':').orEmpty()

    /**
     * The start and end of an event as stored. All-day: `start` is a date and `end` the last day
     * (inclusive, default `start`), stored as UTC midnights with the end exclusive. Timed: moments on the
     * person's clock; `end` must come after `start`.
     */
    private fun eventTimes(allDay: Boolean, start: String?, end: String?, zone: TimeZone): Pair<Instant, Instant> {
        if (!allDay) {
            val s = instant("start", start.orEmpty(), zone)
            val e = instant("end", end.orEmpty(), zone)
            require(e > s) { "`end` must be after `start`" }
            return s to e
        }
        val first = day("start", start) ?: throw IllegalArgumentException("`start` is required: the first day, like 2026-10-26")
        val last = end?.takeIf(String::isNotBlank)?.let { day("end", it) } ?: first
        require(last >= first) { "`end` must not be before `start`" }
        return first.atStartOfDayIn(TimeZone.UTC) to last.plus(DatePeriod(days = 1)).atStartOfDayIn(TimeZone.UTC)
    }

    private fun day(label: String, raw: String?): LocalDate? =
        raw?.let { LocalTimes.parseDate(it) ?: throw IllegalArgumentException("`$label` is not a date: \"$it\". An all-day event takes days like 2026-10-26.") }

    /** The local date an event starts on: its first day for all-day, else the day of [start] on [zone]'s clock. */
    private fun firstDay(start: Instant, allDay: Boolean, zone: TimeZone): LocalDate =
        start.toLocalDateTime(if (allDay) TimeZone.UTC else zone).date

    private fun whenText(start: Instant, end: Instant, allDay: Boolean, zone: TimeZone): String {
        if (!allDay) return LocalTimes.formatRange(start, end, zone)
        val (first, last) = LocalTimes.allDayDates(start, end)
        return "all day " + LocalTimes.formatDate(first) + if (last != first) " to " + LocalTimes.formatDate(last) else ""
    }

    private fun link(raw: String?): String? {
        val text = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val uri = runCatching { java.net.URI(text) }.getOrNull()
        require(uri != null && uri.scheme?.lowercase() in setOf("http", "https") && asciiHost(uri) != null) { "`url` must be a web link like https://example.com/page" }
        return text
    }

    /** A `repeat` input as a [Recurrence]; [first] is the day the series starts on. Messages are for the model. */
    fun recurrence(input: ExternalTools.RepeatInput, first: LocalDate): Recurrence {
        fun weekday(raw: String, label: String) =
            dayOfWeekFromCode(raw.trim().take(2)) ?: throw IllegalArgumentException("`$label` takes weekdays like MO TU WE TH FR SA SU, not \"$raw\"")
        val rule = Recurrence(
            frequency = input.frequency,
            interval = input.interval,
            days = input.days.map { weekday(it, "days") },
            nthWeekday = input.nthWeekday?.let { NthWeekday(it.nth, weekday(it.day, "nthWeekday.day")) },
            until = input.until?.let { LocalTimes.parseDate(it) ?: throw IllegalArgumentException("`until` is not a date: \"$it\". Use a day like 2026-12-20.") },
            count = input.count,
        )
        rule.startProblem(first)?.let { throw IllegalArgumentException(it) }
        return rule
    }

    fun respond(key: String, input: ExternalTools.RespondInput, current: CalendarEventPayload? = null): ProposedAction =
        ProposedAction.RespondToInvite(
            key, "${input.response.name.lowercase().replaceFirstChar { it.uppercase() }} invite ${eventLabel(input.eventId, current)}",
            EntityId(input.eventId), input.response, input.comment,
        )

    fun reminder(key: String, input: ExternalTools.ReminderInput, zone: TimeZone): ProposedAction {
        val at = instant("at", input.at, zone)
        return ProposedAction.CreateReminder(key, "Remind at ${LocalTimes.format(at, zone)}: ${input.text.take(80)}", input.text, at)
    }

    fun doNotDisturb(key: String, input: ExternalTools.DndInput, zone: TimeZone): ProposedAction {
        val until = instant("until", input.until, zone)
        return ProposedAction.SetDoNotDisturb(key, "Do not disturb until ${LocalTimes.format(until, zone)}", until, input.allow.map(::parseHandle))
    }
}

/**
 * Domain tools with effects outside Clarity. They never act: each builds a [ProposedAction] and
 * hands it to the [ActionGate], whose guard decides to queue it, hold it for the person, or
 * block it. The tool returns the gate's text, which tells the model what happened (and not to retry
 * a held action). The idempotency key comes from the tool call, so a replayed call is not repeated.
 */
@Inject
@SingleIn(AppScope::class)
class ExternalTools(
    private val gate: ActionGate,
    private val mailAccounts: MailAccounts,
    private val reader: NounReader,
    private val settings: SettingsProvider = SettingsProvider { net.robog.clarity.shared.session.Settings() },
) {

    @Serializable
    class EmailInput(
        @Description("The person's own mail account it goes from: its address (or its name, like \"Gmail\"). A reply goes from the account the message arrived at (`account` in list_messages and search_messages).")
        val from: String,
        @Description("Recipients: addresses, or \"Name <address>\".") val to: List<String>,
        val subject: String,
        val body: String,
        val cc: List<String> = emptyList(),
        @Description("Id of the message this answers (from list_messages or search_messages); the email joins its thread. Leave out for a new email.") val inReplyTo: String? = null,
        @Description("true for a new email that answers nothing; false for a reply, which needs `inReplyTo`.") val newThread: Boolean,
        @Description(WHY) val reason: String,
        @Description(CONTEXT) val context: List<ContextItem> = emptyList(),
        val urgency: Urgency = Urgency.NORMAL,
    )

    @Serializable
    class NthWeekdayInput(
        @Description("1 to 5 for the 1st to 5th, or -1 for the last") val nth: Int,
        @Description("MO TU WE TH FR SA SU") val day: String,
    )

    @Serializable
    class RepeatInput(
        val frequency: Frequency,
        @Description("Every N days, weeks, months or years. Default 1.") val interval: Int = 1,
        @Description("Weekly only: the weekdays, like [\"MO\",\"WE\",\"FR\"]. Default: the weekday of `start`, which must be one of them.") val days: List<String> = emptyList(),
        @Description("Monthly only: the nth weekday of the month (2nd Tuesday, last Friday) instead of the day of the month of `start`. `start` must be that day.") val nthWeekday: NthWeekdayInput? = null,
        @Description("The last day it can happen on, a date like 2026-12-20 (inclusive). Not together with `count`. With neither it never ends.") val until: String? = null,
        @Description("Stop after this many occurrences. Not together with `until`.") val count: Int? = null,
    )

    @Serializable
    class CreateEventInput(
        val title: String,
        @Description("Start. $LOCAL_TIME_HINT For an all-day event: a date like 2026-10-26.") val start: String,
        @Description("End, local time like `start`. For an all-day event: the last day (inclusive), default `start`.") val end: String? = null,
        val calendar: String? = null,
        @Description("Invitees. Leave empty for a private block on the person's own calendar.") val attendees: List<String> = emptyList(),
        @Description("True for a private focus/hold block (only without attendees).") val hold: Boolean = true,
        val description: String? = null,
        val location: String? = null,
        @Description("A web link for the event, like https://example.com/page.") val url: String? = null,
        @Description("True for an all-day event: `start` and `end` are dates.") val allDay: Boolean = false,
        @Description("Makes the event repeat. Leave out for a single event.") val repeat: RepeatInput? = null,
        @Description(WHY) val reason: String,
        @Description(CONTEXT) val context: List<ContextItem> = emptyList(),
        val urgency: Urgency = Urgency.NORMAL,
    )

    @Serializable
    class UpdateEventInput(
        @Description("Event id from list_calendar_events. For a repeating event it is one occurrence.") val eventId: String,
        @Description("Required when the event repeats: `this` changes only this occurrence, `following` this and every later one, `series` all of them.") val scope: EventScope? = null,
        @Description("New start. $LOCAL_TIME_HINT For an all-day event: a date, with `allDay` true. Leave out to keep it. A repeating event with `series` or `following` moves by the same amount.") val start: String? = null,
        @Description("New end, like `start` (all-day: the last day). Leave out to keep the length.") val end: String? = null,
        val title: String? = null,
        @Description("New notes. An empty text clears them.") val description: String? = null,
        @Description("New place. An empty text clears it.") val location: String? = null,
        @Description("New web link. An empty text clears it.") val url: String? = null,
        @Description("Set to say whether it is all-day; needed to move an all-day event, whose `start` and `end` are dates. A repeating event can't switch.") val allDay: Boolean? = null,
        @Description("A new repeat rule (needs `start`). It replaces the old rule, restarts the series at `start` (which must fit the rule) and drops one-off changes. Not with scope `this`.") val repeat: RepeatInput? = null,
        @Description("True if other people are on the event and should be told.") val notifyAttendees: Boolean = false,
        @Description(WHY) val reason: String,
        @Description(CONTEXT) val context: List<ContextItem> = emptyList(),
        val urgency: Urgency = Urgency.NORMAL,
    )

    @Serializable
    class CancelEventInput(
        @Description("Event id from list_calendar_events. For a repeating event it is one occurrence.") val eventId: String,
        @Description("Required when the event repeats: `this` cancels only this occurrence, `following` this and every later one, `series` all of them.") val scope: EventScope? = null,
        @Description("True if other people are on the event and should be told.") val notifyAttendees: Boolean = false,
        @Description(WHY) val reason: String,
        @Description(CONTEXT) val context: List<ContextItem> = emptyList(),
        val urgency: Urgency = Urgency.NORMAL,
    )

    @Serializable
    class RespondInput(
        @Description("Event id from list_calendar_events") val eventId: String,
        val response: InviteResponse,
        val comment: String? = null,
        @Description(WHY) val reason: String,
        @Description(CONTEXT) val context: List<ContextItem> = emptyList(),
        val urgency: Urgency = Urgency.NORMAL,
    )

    @Serializable
    class ReminderInput(
        val text: String,
        @Description("When. $LOCAL_TIME_HINT") val at: String,
        @Description(WHY) val reason: String,
        @Description(CONTEXT) val context: List<ContextItem> = emptyList(),
        val urgency: Urgency = Urgency.NORMAL,
    )

    @Serializable
    class DndInput(
        @Description("When do-not-disturb ends. $LOCAL_TIME_HINT") val until: String,
        @Description("People still allowed through (addresses or numbers).") val allow: List<String> = emptyList(),
        @Description(WHY) val reason: String,
        @Description(CONTEXT) val context: List<ContextItem> = emptyList(),
        val urgency: Urgency = Urgency.NORMAL,
    )

    private suspend inline fun <reified I : Any> propose(
        scope: RunScope,
        name: String,
        input: I,
        reason: String,
        context: List<ContextItem>,
        urgency: Urgency,
        noinline build: (String, TimeZone) -> ProposedAction,
    ): ToolResult {
        val zone = settings.homeZone()
        val action = try {
            build(idempotencyKey(scope, name, ClarityJson.encodeToString(input)), zone)
        } catch (e: IllegalArgumentException) {
            return ToolResult.Error(e.message ?: "invalid input")
        }
        return ToolResult.Ok(gate.propose(action, reason, context, scope.provenance(), urgency).text)
    }

    /** Proposes an email after looking up what [ProposedActions.draft] checks it against: the accounts and the message answered. */
    private suspend fun proposeEmail(scope: RunScope, name: String, send: Boolean, i: EmailInput): ToolResult {
        val accounts = mailAccounts.list()
        val replied = i.inReplyTo?.trim()?.takeIf(String::isNotEmpty)?.let { reader.message(EntityId(it))?.payload }
        return propose(scope, name, i, i.reason, i.context, i.urgency) { k, _ -> ProposedActions.draft(k, send, i, accounts, replied) }
    }

    fun saveDraft(scope: RunScope): Tool = tool<EmailInput>(
        name = "save_draft",
        description = "Save an email draft in one of the person's mail accounts (not sent). Only when the person asks for a draft; to email someone, use send_email. " +
            "Choose the account it is from, and whether it answers a message (inReplyTo) or starts a new thread.",
        effect = ToolEffect.EXTERNAL,
        json = ClarityJson,
    ) { i -> proposeEmail(scope, "save_draft", send = false, i) }

    fun sendEmail(scope: RunScope): Tool = tool<EmailInput>(
        name = "send_email",
        description = "Send an email as the person. It goes to the person for approval unless their rules allow it, so there is no need to save a draft before it. Result tells you whether it was queued or held. " +
            "Choose the account it is from, and whether it answers a message (inReplyTo) or starts a new thread.",
        effect = ToolEffect.EXTERNAL,
        json = ClarityJson,
    ) { i -> proposeEmail(scope, "send_email", send = true, i) }

    fun createEvent(scope: RunScope): Tool = tool<CreateEventInput>(
        name = "create_event",
        description = "Create a calendar event or a private hold block. It can be all-day, have a place, notes and a link, and repeat (`repeat`).",
        effect = ToolEffect.EXTERNAL,
        json = ClarityJson,
    ) { i -> propose(scope, "create_event", i, i.reason, i.context, i.urgency) { k, zone -> ProposedActions.createEvent(k, i, zone) } }

    /** The stored event an event tool names, for its summary; null when it is not found. */
    private suspend fun storedEvent(id: String): CalendarEventPayload? = reader.calendarEvent(EntityId(id))?.payload

    fun updateEvent(scope: RunScope): Tool = tool<UpdateEventInput>(
        name = "update_event",
        description = "Change a calendar event: its time, title, notes, place, link, all-day or repeat rule. Leave a field out to keep it. " +
            "For a repeating event give `scope`: `this` (one occurrence), `following` (this and later ones) or `series`. " +
            "Events with other attendees need `notifyAttendees`, and only the organizer can change them.",
        effect = ToolEffect.EXTERNAL,
        json = ClarityJson,
    ) { i ->
        val current = storedEvent(i.eventId)
        propose(scope, "update_event", i, i.reason, i.context, i.urgency) { k, zone -> ProposedActions.updateEvent(k, i, zone, current) }
    }

    fun cancelEvent(scope: RunScope): Tool = tool<CancelEventInput>(
        name = "cancel_event",
        description = "Cancel (delete) a calendar event. For a repeating event give `scope`: `this` (one occurrence), `following` (this and later ones) or `series`. " +
            "Events with other attendees need `notifyAttendees`, and only the organizer can cancel them; to leave someone else's event, decline it with respond_to_invite.",
        effect = ToolEffect.EXTERNAL,
        json = ClarityJson,
    ) { i ->
        val current = storedEvent(i.eventId)
        propose(scope, "cancel_event", i, i.reason, i.context, i.urgency) { k, _ -> ProposedActions.cancelEvent(k, i, current) }
    }

    fun respondToInvite(scope: RunScope): Tool = tool<RespondInput>(
        name = "respond_to_invite",
        description = "Accept, decline or tentatively accept a calendar invitation. For a repeating event the answer covers the whole series, whichever occurrence the id names.",
        effect = ToolEffect.EXTERNAL,
        json = ClarityJson,
    ) { i ->
        val current = storedEvent(i.eventId)
        propose(scope, "respond_to_invite", i, i.reason, i.context, i.urgency) { k, _ -> ProposedActions.respond(k, i, current) }
    }

    fun createReminder(scope: RunScope): Tool = tool<ReminderInput>(
        name = "create_reminder",
        description = "Create a reminder for the person at a time (shows up as a short event with an alarm on their calendar). " +
            "It only alerts them; to check in with them yourself, use nudge.",
        effect = ToolEffect.EXTERNAL,
        json = ClarityJson,
    ) { i -> propose(scope, "create_reminder", i, i.reason, i.context, i.urgency) { k, zone -> ProposedActions.reminder(k, i, zone) } }

    fun setDoNotDisturb(scope: RunScope): Tool = tool<DndInput>(
        name = "set_do_not_disturb",
        description = "Turn on do-not-disturb on the person's phone until a time, optionally letting some people through.",
        effect = ToolEffect.EXTERNAL,
        json = ClarityJson,
    ) { i -> propose(scope, "set_do_not_disturb", i, i.reason, i.context, i.urgency) { k, zone -> ProposedActions.doNotDisturb(k, i, zone) } }

    fun all(scope: RunScope): List<Tool> = listOf(
        saveDraft(scope), sendEmail(scope), createEvent(scope), updateEvent(scope), cancelEvent(scope), respondToInvite(scope),
        createReminder(scope), setDoNotDisturb(scope),
    )
}
