package net.robog.clarity.connector.dav

import at.bitfire.dav4jvm.ktor.exception.NotFoundException
import at.bitfire.dav4jvm.ktor.exception.PreconditionFailedException
import at.bitfire.dav4jvm.ktor.exception.UnauthorizedException
import io.ktor.http.Url
import java.time.Instant as JInstant
import java.time.ZoneId
import java.time.ZoneOffset
import net.robog.clarity.connector.sdk.ActionHandlers
import net.robog.clarity.connector.sdk.ConnectorRuntime
import net.robog.clarity.connector.sdk.actionHandlers
import net.robog.clarity.connector.sdk.runIsolated
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.calendar.EventScope
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.noun.Handle
import kotlin.time.Instant

/** Largest calendar object read for an edit, a cancellation or an answer. */
private const val EDIT_LIMIT = 5_000_000L

/** Longest document text handed back to the agent. */
const val MAX_READ_CHARS = 200_000

private const val AUTO_SCHEDULE = "calendar-auto-schedule"

private fun Instant.toJava(): JInstant = JInstant.ofEpochMilli(toEpochMilliseconds())

private fun failed(message: String, retryable: Boolean = false) = ActionOutcome.Failed(message, retryable)

/** The person's clock as the action carried it; UTC when it did not say or is not a zone Java knows. */
private fun zoneOf(id: String?): ZoneId = id?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC

/**
 * `CREATE_EVENT`, `UPDATE_EVENT`, `CANCEL_EVENT`, `RESPOND_TO_INVITE`, `CREATE_REMINDER` and `READ_DOCUMENT`,
 * and `MOVE_EVENT` for actions stored before `UPDATE_EVENT`.
 * [accounts] yields the accounts of the newest config for each action and [connect] a fresh
 * server connection for one of them. Events, invitations and documents are found in the account
 * their source id names; a new event goes to the account that has the requested calendar.
 */
class DavActions(
    private val accounts: suspend () -> List<DavConfig>,
    private val connect: (DavConfig) -> DavServer = { DavServer(it) },
) {
    constructor(runtime: ConnectorRuntime) : this({ usableDavAccounts(runtime.awaitConfig(), runtime) })

    fun handlers(): ActionHandlers = actionHandlers {
        onTargeted<ProposedAction.CreateEvent> { a, _ -> createEvent(a) }
        onTargeted<ProposedAction.MoveEvent> { a, target -> moveEvent(a, target) }
        onTargeted<ProposedAction.UpdateEvent> { a, target -> updateEvent(a, target) }
        onTargeted<ProposedAction.CancelEvent> { a, target -> cancelEvent(a, target) }
        onTargeted<ProposedAction.RespondToInvite> { a, target -> respond(a, target) }
        onTargeted<ProposedAction.CreateReminder> { a, _ -> createReminder(a) }
        onTargeted<ProposedAction.ReadDocument> { a, target -> readDocument(a, target) }
    }

    suspend fun createEvent(a: ProposedAction.CreateEvent): ActionOutcome = withAccounts { all ->
        val (account, wanted) = resolveCalendar(all, a.calendar)
        onAccount(account) { server, config -> createEventOn(server, config, a, wanted) }
    }

    private suspend fun createEventOn(server: DavServer, config: DavConfig, a: ProposedAction.CreateEvent, wanted: String?): ActionOutcome {
        val discovery = server.discover()
        val calendar = pickCalendar(discovery, config, wanted) ?: return failed("No calendar to put the event in was found on the server.")
        val me = myAddress(discovery, config)
        if (a.attendees.isNotEmpty()) {
            if (me == null) return failed("I don't know your own address, so I can't invite people. Set your email in the calendar settings.")
            if (AUTO_SCHEDULE !in server.capabilities(calendar.url)) {
                return failed("This calendar server does not send invitations itself (no CalDAV scheduling), so the event can't be created with attendees.")
            }
        }
        val uid = uidFor(a.idempotencyKey)
        val ics = Ical.newEvent(
            NewEvent(
                uid, a.title, a.start.toJava(), a.end.toJava(), a.hold, a.attendees, me?.let { Handle.email(it) },
                a.description, a.location, a.url, a.allDay, a.repeat, zoneOf(a.zone),
            ),
        )
        return create(server, config, calendar.url, uid, ics)
    }

    suspend fun createReminder(a: ProposedAction.CreateReminder): ActionOutcome = withAccounts { all ->
        onAccount(all.first()) { server, config ->
            val discovery = server.discover()
            val calendar = pickCalendar(discovery, config, null) ?: return@onAccount failed("No calendar for the reminder was found on the server.")
            val uid = uidFor(a.idempotencyKey)
            create(server, config, calendar.url, uid, Ical.newReminder(uid, a.text, a.at.toJava()))
        }
    }

    /** PUT that must not overwrite: an existing resource with our derived UID means an earlier delivery already did it. */
    private suspend fun create(server: DavServer, config: DavConfig, calendar: Url, uid: String, ics: String): ActionOutcome {
        val url = server.resolve(calendar, calendar.encodedPath.trimEnd('/') + "/" + uid + ".ics")
        putNew(server, url, ics)
        return ActionOutcome.Done(SourceId(sourceIdOf(config.id, url.encodedPath)))
    }

    /**
     * The new series of a split. One that is already there came from an earlier attempt whose second write
     * failed; it is replaced (by its own version) with what the latest original gives, not kept stale.
     */
    private suspend fun putFollowing(server: DavServer, url: Url, ics: String): ActionOutcome.Failed? {
        try {
            server.putCalendar(url, ics, ifNoneMatch = "*")
        } catch (_: PreconditionFailedException) {
            val etag = server.get(url, EDIT_LIMIT)?.first ?: return noVersion()
            server.putCalendar(url, ics, ifMatch = etag)
        }
        return null
    }

    private suspend fun putNew(server: DavServer, url: Url, ics: String) {
        try {
            server.putCalendar(url, ics, ifNoneMatch = "*")
        } catch (_: PreconditionFailedException) {
            // already there: this is a re-delivery
        }
    }

    /** An occurrence's source path is `<resource>#<epoch seconds>`; the resource alone is the whole event. */
    private class EventPath(path: String) {
        val resource = path.substringBefore('#')
        val occurrence: JInstant? = path.substringAfter('#', "").toLongOrNull()?.let(JInstant::ofEpochSecond)
    }

    /**
     * Why the person may not change an event that has other people on it, or null when they may:
     * only the organizer changes it, and telling the others needs [notify] and a server that does it.
     */
    private suspend fun attendeeRefusal(server: DavServer, discovery: Discovery, config: DavConfig, url: Url, ics: String, notify: Boolean, verb: String): ActionOutcome.Failed? {
        val me = myAddresses(discovery, config)
        val people = Ical.participants(ics)
        val others = people.attendees.filter { it !in me }
        if (people.organizer != null && people.organizer !in me) {
            return failed("Only the organizer (${people.organizer}) can $verb this event; the person is an attendee. They can answer the invitation (respond_to_invite) or ask the organizer.")
        }
        if (others.isEmpty()) return null
        if (!notify) {
            return failed("This event has other attendees (${others.joinToString()}); this notifies them. Ask again with notifyAttendees set if that is intended.")
        }
        if (AUTO_SCHEDULE !in server.capabilities(url)) {
            return failed("This calendar server does not send updates to attendees itself (no CalDAV scheduling), so an event with attendees can't be changed.")
        }
        return null
    }

    /** What an event looks like before an edit or cancellation: its place, version and text. */
    private class Loaded(val url: Url, val etag: String?, val ics: String, val path: EventPath)

    private sealed interface Load {
        class Found(val event: Loaded) : Load
        data object Gone : Load
        data object TooLarge : Load
    }

    private suspend fun load(server: DavServer, config: DavConfig, path: String): Load {
        val event = EventPath(path)
        val url = server.resolve(Url(config.caldavUrl!!), event.resource)
        val (etag, bytes) = try {
            server.get(url, EDIT_LIMIT) ?: return Load.TooLarge
        } catch (_: NotFoundException) {
            return Load.Gone
        }
        return Load.Found(Loaded(url, etag, bytes.toString(Charsets.UTF_8), event))
    }

    private fun tooLarge() = failed("The event is larger than ${EDIT_LIMIT / 1_000_000} MB, so it can't be changed here.")

    /** Without a version to match, a write could overwrite someone else's change; a server that never sends one will not start to. */
    private fun noVersion() = failed("This calendar server gives no version tag for events, so edits can't be made safely. Tell the person to change it in their calendar.")

    /** A stored `MOVE_EVENT`: the single-event part of [updateEvent]. */
    suspend fun moveEvent(a: ProposedAction.MoveEvent, target: SourceId?): ActionOutcome {
        if (target?.value?.contains('#') == true) {
            return failed("That is one occurrence of a recurring event. Use update_event with a scope to change it.")
        }
        return updateEvent(ProposedAction.UpdateEvent(a.idempotencyKey, a.summary, a.event, start = a.start, end = a.end, notifyAttendees = a.notifyAttendees), target)
    }

    suspend fun updateEvent(a: ProposedAction.UpdateEvent, target: SourceId?): ActionOutcome = onTarget("event", target) { server, config, path ->
        val done = ActionOutcome.Done(SourceId(sourceIdOf(config.id, path)))
        val discovery = server.discover()
        val event = when (val loaded = load(server, config, path)) {
            is Load.Found -> loaded.event
            Load.Gone -> return@onTarget failed("The event no longer exists on the server.")
            Load.TooLarge -> return@onTarget tooLarge()
        }
        val etag = event.etag ?: return@onTarget noVersion()
        // a re-delivery is done whatever the attendees or the server can do now
        try {
            if (IcalEdit.alreadyMarked(event.ics, a.idempotencyKey)) return@onTarget done
        } catch (e: IllegalArgumentException) {
            return@onTarget failed(e.message ?: "This event can't be edited.")
        }
        attendeeRefusal(server, discovery, config, event.url, event.ics, a.notifyAttendees, "change")?.let { return@onTarget it }
        val following = uidFor(a.idempotencyKey)
        val followingUrl = server.resolve(event.url, event.path.resource.substringBeforeLast('/') + "/" + following + ".ics")
        val change = EventChange(
            a.start?.toJava(), a.end?.toJava(), a.title, a.description, a.location, a.url, a.allDay, a.repeat, zoneOf(a.zone),
        )
        val edited = try {
            IcalEdit.updated(event.ics, event.path.occurrence, a.scope, change, following, a.idempotencyKey)
        } catch (e: IllegalArgumentException) {
            return@onTarget failed(e.message ?: "This event can't be edited.")
        }
        try {
            when (edited) {
                Edited.Applied -> return@onTarget done
                is Edited.Replace -> server.putCalendar(event.url, edited.ics, ifMatch = etag)
                is Edited.Split -> {
                    putFollowing(server, followingUrl, edited.following)?.let { return@onTarget it }
                    server.putCalendar(event.url, edited.original, ifMatch = etag)
                }
            }
        } catch (_: PreconditionFailedException) {
            return@onTarget failed("The event changed on the server while it was being edited; trying again with the new version.", retryable = true)
        }
        done
    }

    suspend fun cancelEvent(a: ProposedAction.CancelEvent, target: SourceId?): ActionOutcome = onTarget("event", target) { server, config, path ->
        val done = ActionOutcome.Done(SourceId(sourceIdOf(config.id, path)))
        val discovery = server.discover()
        val event = when (val loaded = load(server, config, path)) {
            is Load.Found -> loaded.event
            Load.Gone -> return@onTarget done
            Load.TooLarge -> return@onTarget tooLarge()
        }
        val etag = event.etag ?: return@onTarget noVersion()
        try {
            if (IcalEdit.alreadyMarked(event.ics, a.idempotencyKey)) return@onTarget done
        } catch (e: IllegalArgumentException) {
            return@onTarget failed(e.message ?: "This event can't be cancelled.")
        }
        attendeeRefusal(server, discovery, config, event.url, event.ics, a.notifyAttendees, "cancel")?.let { return@onTarget it }
        val removal = try {
            IcalEdit.cancelled(event.ics, event.path.occurrence, a.scope, a.idempotencyKey)
        } catch (e: IllegalArgumentException) {
            return@onTarget failed(e.message ?: "This event can't be cancelled.")
        }
        try {
            when (removal) {
                Removal.Applied -> return@onTarget done
                Removal.Delete -> server.deleteCalendar(event.url, ifMatch = etag)
                is Removal.Replace -> server.putCalendar(event.url, removal.ics, ifMatch = etag)
            }
        } catch (_: PreconditionFailedException) {
            return@onTarget failed("The event changed on the server while it was being cancelled; trying again with the new version.", retryable = true)
        }
        done
    }

    /** Answers for the whole event: a repeating one is answered as a series, whichever occurrence is named. */
    suspend fun respond(a: ProposedAction.RespondToInvite, target: SourceId?): ActionOutcome = onTarget("invitation", target) { server, config, path ->
        val discovery = server.discover()
        val url = server.resolve(Url(config.caldavUrl!!), EventPath(path).resource)
        if (AUTO_SCHEDULE !in server.capabilities(url)) {
            return@onTarget failed("This calendar server does not send replies to organizers itself (no CalDAV scheduling), so I can't answer the invitation.")
        }
        val (etag, bytes) = server.get(url, EDIT_LIMIT) ?: return@onTarget failed("The invitation is too large to answer here.")
        if (etag == null) return@onTarget noVersion()
        val ics = bytes.toString(Charsets.UTF_8)
        val updated = try {
            myAddresses(discovery, config).firstNotNullOfOrNull { Ical.responded(ics, it, a.response) }
        } catch (e: IllegalArgumentException) {
            return@onTarget failed(e.message ?: "This invitation can't be edited.")
        } ?: return@onTarget failed("You are not listed as an attendee of this event, so there is nothing to answer.")
        try {
            server.putCalendar(url, updated, ifMatch = etag)
        } catch (_: PreconditionFailedException) {
            return@onTarget failed("The invitation changed on the server while I was answering; trying again.", retryable = true)
        }
        ActionOutcome.Done(SourceId(sourceIdOf(config.id, path)))
    }

    suspend fun readDocument(a: ProposedAction.ReadDocument, target: SourceId?): ActionOutcome = onTarget("document", target) { server, config, path ->
        val base = Url(config.webdavUrl ?: return@onTarget failed("No file server is configured."))
        val url = server.resolve(base, path)
        val (_, bytes) = server.get(url) ?: return@onTarget failed("The file is larger than ${MAX_EXTRACT_BYTES / (1024 * 1024)} MB; I can't read it.")
        val name = java.net.URLDecoder.decode(path.trimEnd('/').substringAfterLast('/'), Charsets.UTF_8)
        val text = extractText(name, mimeFor(name), bytes) ?: return@onTarget failed("I can't extract text from this kind of file ($name).")
        ActionOutcome.Done(SourceId(sourceIdOf(config.id, path)), text.take(MAX_READ_CHARS))
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun unknownTarget(what: String, target: SourceId?) =
        failed("I don't know which $what that is on the server${target?.let { " ($it)" }.orEmpty()}: it was not synced by this connector.")

    private suspend fun myAddress(d: Discovery, config: DavConfig): String? = myAddresses(d, config).firstOrNull()

    private fun myAddresses(d: Discovery, config: DavConfig): List<String> =
        (listOfNotNull(config.email?.lowercase()) + d.userAddresses + listOfNotNull(config.username.lowercase().takeIf { '@' in it })).distinct()

    private fun matches(c: DavCollectionInfo, wanted: String) =
        c.name.equals(wanted, ignoreCase = true) || c.url.encodedPath.trimEnd('/').endsWith(wanted.trim('/'), ignoreCase = true)

    private fun pickCalendar(d: Discovery, config: DavConfig, wanted: String?): DavCollectionInfo? {
        wanted?.let { w -> d.calendars.firstOrNull { matches(it, w) }?.let { return it } }
        config.calendars.firstNotNullOfOrNull { w -> d.calendars.firstOrNull { matches(it, w) } }?.let { return it }
        return d.calendars.firstOrNull()
    }

    /**
     * The account and calendar name for a new event. `"<label>/<calendar>"` names the account;
     * `"<calendar>"` is the first match across the accounts; null is the first account's default calendar.
     */
    private suspend fun resolveCalendar(all: List<DavConfig>, wanted: String?): Pair<DavConfig, String?> {
        if (wanted == null) return all.first() to null
        val rest = wanted.substringAfter('/', "")
        all.firstOrNull { rest.isNotEmpty() && it.label.equals(wanted.substringBefore('/'), ignoreCase = true) }?.let { return it to rest }
        return (all.firstOrNull { hasCalendar(it, wanted) } ?: all.first()) to wanted
    }

    private suspend fun hasCalendar(account: DavConfig, wanted: String): Boolean =
        runIsolated { connect(account).use { server -> server.discover().calendars.any { matches(it, wanted) } } }.getOrDefault(false)

    /** Loads the accounts; maps a missing or incomplete configuration to an outcome. */
    private suspend fun withAccounts(block: suspend (List<DavConfig>) -> ActionOutcome): ActionOutcome =
        try {
            block(accounts())
        } catch (e: ConfigIncomplete) {
            failed("The calendar and files connector is not configured: ${e.message}")
        }

    /** Runs [block] on the account that holds [target] (named by its source id) with the resource path. */
    private suspend fun onTarget(what: String, target: SourceId?, block: suspend (DavServer, DavConfig, String) -> ActionOutcome): ActionOutcome =
        withAccounts { all ->
            val ref = target?.value?.let(DavRef::parse) ?: return@withAccounts unknownTarget(what, target)
            val account = all.firstOrNull { it.id == ref.accountId }
                ?: return@withAccounts failed("The account that holds this $what (${ref.accountId}) is not set up any more.")
            onAccount(account) { server, config -> block(server, config, ref.path) }
        }

    /** Opens a connection to [account], runs [block], maps expected failures to outcomes, always closes. */
    private suspend fun onAccount(account: DavConfig, block: suspend (DavServer, DavConfig) -> ActionOutcome): ActionOutcome =
        try {
            connect(account).use { block(it, account) }
        } catch (e: UnauthorizedException) {
            failed("The server refused the login for ${account.label}. Ask the person to check the password in the calendar and files settings.")
        }
}
