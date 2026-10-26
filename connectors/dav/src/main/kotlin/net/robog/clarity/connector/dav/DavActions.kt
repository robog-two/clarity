package net.robog.clarity.connector.dav

import at.bitfire.dav4jvm.ktor.exception.PreconditionFailedException
import at.bitfire.dav4jvm.ktor.exception.UnauthorizedException
import io.ktor.http.Url
import java.time.Instant as JInstant
import net.robog.clarity.connector.sdk.ActionHandlers
import net.robog.clarity.connector.sdk.ConnectorRuntime
import net.robog.clarity.connector.sdk.actionHandlers
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.noun.Handle
import kotlin.time.Instant

/** Longest document text handed back to the agent. */
const val MAX_READ_CHARS = 200_000

private const val AUTO_SCHEDULE = "calendar-auto-schedule"

private fun Instant.toJava(): JInstant = JInstant.ofEpochMilli(toEpochMilliseconds())

private fun failed(message: String, retryable: Boolean = false) = ActionOutcome.Failed(message, retryable)

/**
 * `CREATE_EVENT`, `MOVE_EVENT`, `RESPOND_TO_INVITE`, `CREATE_REMINDER` and `READ_DOCUMENT`.
 * [open] yields a fresh server connection from the newest config for each action.
 */
class DavActions(private val open: suspend () -> Pair<DavConfig, DavServer>) {
    constructor(runtime: ConnectorRuntime) : this({
        val config = parseDavConfig(runtime.awaitConfig(), runtime)
        config to DavServer(config)
    })

    fun handlers(): ActionHandlers = actionHandlers {
        onTargeted<ProposedAction.CreateEvent> { a, _ -> createEvent(a) }
        onTargeted<ProposedAction.MoveEvent> { a, target -> moveEvent(a, target) }
        onTargeted<ProposedAction.RespondToInvite> { a, target -> respond(a, target) }
        onTargeted<ProposedAction.CreateReminder> { a, _ -> createReminder(a) }
        onTargeted<ProposedAction.ReadDocument> { a, target -> readDocument(a, target) }
    }

    suspend fun createEvent(a: ProposedAction.CreateEvent): ActionOutcome = run { server, config ->
        val discovery = server.discover()
        val calendar = pickCalendar(discovery, config, a.calendar) ?: return@run failed("No calendar to put the event in was found on the server.")
        val me = myAddress(discovery, config)
        if (a.attendees.isNotEmpty()) {
            if (me == null) return@run failed("I don't know your own address, so I can't invite people. Set your email in the calendar settings.")
            if (AUTO_SCHEDULE !in server.capabilities(calendar.url)) {
                return@run failed("This calendar server does not send invitations itself (no CalDAV scheduling), so the event can't be created with attendees.")
            }
        }
        val uid = uidFor(a.idempotencyKey)
        val ics = Ical.newEvent(uid, a.title, a.start.toJava(), a.end.toJava(), a.hold, a.attendees, me?.let { Handle.email(it) })
        create(server, calendar.url, uid, ics)
    }

    suspend fun createReminder(a: ProposedAction.CreateReminder): ActionOutcome = run { server, config ->
        val discovery = server.discover()
        val calendar = pickCalendar(discovery, config, null) ?: return@run failed("No calendar for the reminder was found on the server.")
        val uid = uidFor(a.idempotencyKey)
        create(server, calendar.url, uid, Ical.newReminder(uid, a.text, a.at.toJava()))
    }

    /** PUT that must not overwrite: an existing resource with our derived UID means an earlier delivery already did it. */
    private suspend fun create(server: DavServer, calendar: Url, uid: String, ics: String): ActionOutcome {
        val url = server.resolve(calendar, calendar.encodedPath.trimEnd('/') + "/" + uid + ".ics")
        try {
            server.putCalendar(url, ics, ifNoneMatch = "*")
        } catch (_: PreconditionFailedException) {
            // already there: this is a re-delivery
        }
        return ActionOutcome.Done(SourceId(url.encodedPath))
    }

    suspend fun moveEvent(a: ProposedAction.MoveEvent, target: SourceId?): ActionOutcome = run { server, config ->
        if (target?.value?.contains('#') == true) return@run failed("That is one occurrence of a recurring event; moving a single occurrence or the series is not supported. Tell the person to change it in their calendar.")
        val path = eventPath(target) ?: return@run unknownTarget("event", target)
        val discovery = server.discover()
        val url = server.resolve(Url(config.caldavUrl!!), path)
        val (etag, bytes) = server.get(url, 1_000_000) ?: return@run failed("The event could not be read.")
        val ics = bytes.toString(Charsets.UTF_8)
        val me = myAddresses(discovery, config)
        val people = Ical.participants(ics)
        val others = people.attendees.filter { it !in me }
        if (people.organizer != null && people.organizer !in me) {
            return@run failed("Only the organizer (${people.organizer}) can move this event; you are an attendee. Propose a different time to them instead.")
        }
        if (others.isNotEmpty()) {
            if (!a.notifyAttendees) {
                return@run failed("This event has other attendees (${others.joinToString()}); moving it notifies them. Ask again with notifyAttendees set if that's intended.")
            }
            if (AUTO_SCHEDULE !in server.capabilities(url)) {
                return@run failed("This calendar server does not send updates to attendees itself (no CalDAV scheduling), so I can't move an event that has attendees.")
            }
        }
        val moved = try { Ical.moved(ics, a.start.toJava(), a.end.toJava()) } catch (e: IllegalArgumentException) { return@run failed(e.message ?: "This event can't be edited.") }
        try {
            server.putCalendar(url, moved, ifMatch = etag)
        } catch (_: PreconditionFailedException) {
            return@run failed("The event changed on the server while I was moving it; trying again with the new version.", retryable = true)
        }
        ActionOutcome.Done(SourceId(path))
    }

    suspend fun respond(a: ProposedAction.RespondToInvite, target: SourceId?): ActionOutcome = run { server, config ->
        if (target?.value?.contains('#') == true) return@run failed("That is one occurrence of a recurring event; answering a single occurrence is not supported.")
        val path = eventPath(target) ?: return@run unknownTarget("invitation", target)
        val discovery = server.discover()
        val url = server.resolve(Url(config.caldavUrl!!), path)
        if (AUTO_SCHEDULE !in server.capabilities(url)) {
            return@run failed("This calendar server does not send replies to organizers itself (no CalDAV scheduling), so I can't answer the invitation.")
        }
        val (etag, bytes) = server.get(url, 1_000_000) ?: return@run failed("The invitation could not be read.")
        val ics = bytes.toString(Charsets.UTF_8)
        val updated = try {
            myAddresses(discovery, config).firstNotNullOfOrNull { Ical.responded(ics, it, a.response) }
        } catch (e: IllegalArgumentException) {
            return@run failed(e.message ?: "This invitation can't be edited.")
        } ?: return@run failed("You are not listed as an attendee of this event, so there is nothing to answer.")
        try {
            server.putCalendar(url, updated, ifMatch = etag)
        } catch (_: PreconditionFailedException) {
            return@run failed("The invitation changed on the server while I was answering; trying again.", retryable = true)
        }
        ActionOutcome.Done(SourceId(path))
    }

    suspend fun readDocument(a: ProposedAction.ReadDocument, target: SourceId?): ActionOutcome = run { server, config ->
        val path = target?.value ?: return@run unknownTarget("document", target)
        val base = Url(config.webdavUrl ?: return@run failed("No file server is configured."))
        val url = server.resolve(base, path)
        val (_, bytes) = server.get(url) ?: return@run failed("The file is larger than ${MAX_EXTRACT_BYTES / (1024 * 1024)} MB; I can't read it.")
        val name = java.net.URLDecoder.decode(path.trimEnd('/').substringAfterLast('/'), Charsets.UTF_8)
        val text = extractText(name, mimeFor(name), bytes) ?: return@run failed("I can't extract text from this kind of file ($name).")
        ActionOutcome.Done(SourceId(path), text.take(MAX_READ_CHARS))
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun unknownTarget(what: String, target: SourceId?) =
        failed("I don't know which $what that is on the server${target?.let { " ($it)" }.orEmpty()}: it was not synced by this connector.")

    /** The resource path of an event source id (`<path>` or `<path>#<occurrence>`); null for a recurring occurrence is handled by the caller via message. */
    private fun eventPath(target: SourceId?): String? = target?.value?.takeIf { '#' !in it }

    private suspend fun myAddress(d: Discovery, config: DavConfig): String? = myAddresses(d, config).firstOrNull()

    private fun myAddresses(d: Discovery, config: DavConfig): List<String> =
        (listOfNotNull(config.email?.lowercase()) + d.userAddresses + listOfNotNull(config.username.lowercase().takeIf { '@' in it })).distinct()

    private fun pickCalendar(d: Discovery, config: DavConfig, wanted: String?): DavCollectionInfo? {
        fun matches(c: DavCollectionInfo, w: String) = c.name.equals(w, ignoreCase = true) || c.url.encodedPath.trimEnd('/').endsWith(w.trim('/'), ignoreCase = true)
        wanted?.let { w -> d.calendars.firstOrNull { matches(it, w) }?.let { return it } }
        config.calendars.firstNotNullOfOrNull { w -> d.calendars.firstOrNull { matches(it, w) } }?.let { return it }
        return d.calendars.firstOrNull()
    }

    /** Opens a connection, runs [block], maps expected failures to outcomes, always closes. */
    private suspend fun run(block: suspend (DavServer, DavConfig) -> ActionOutcome): ActionOutcome {
        val (config, server) = try {
            open()
        } catch (e: ConfigIncomplete) {
            return failed("The calendar and files connector is not configured: ${e.message}")
        }
        return try {
            server.use { block(it, config) }
        } catch (e: UnauthorizedException) {
            failed("The server refused the login. Ask the person to check the password in the calendar and files settings.")
        }
    }
}
