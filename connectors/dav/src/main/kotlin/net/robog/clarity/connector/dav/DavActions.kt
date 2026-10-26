package net.robog.clarity.connector.dav

import at.bitfire.dav4jvm.ktor.exception.PreconditionFailedException
import at.bitfire.dav4jvm.ktor.exception.UnauthorizedException
import io.ktor.http.Url
import java.time.Instant as JInstant
import net.robog.clarity.connector.sdk.ActionHandlers
import net.robog.clarity.connector.sdk.ConnectorRuntime
import net.robog.clarity.connector.sdk.actionHandlers
import net.robog.clarity.connector.sdk.runIsolated
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
        val ics = Ical.newEvent(uid, a.title, a.start.toJava(), a.end.toJava(), a.hold, a.attendees, me?.let { Handle.email(it) })
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
        try {
            server.putCalendar(url, ics, ifNoneMatch = "*")
        } catch (_: PreconditionFailedException) {
            // already there: this is a re-delivery
        }
        return ActionOutcome.Done(SourceId(sourceIdOf(config.id, url.encodedPath)))
    }

    suspend fun moveEvent(a: ProposedAction.MoveEvent, target: SourceId?): ActionOutcome = onTarget("event", target) { server, config, path ->
        if ('#' in path) return@onTarget failed("That is one occurrence of a recurring event; moving a single occurrence or the series is not supported. Tell the person to change it in their calendar.")
        val discovery = server.discover()
        val url = server.resolve(Url(config.caldavUrl!!), path)
        val (etag, bytes) = server.get(url, 1_000_000) ?: return@onTarget failed("The event could not be read.")
        val ics = bytes.toString(Charsets.UTF_8)
        val me = myAddresses(discovery, config)
        val people = Ical.participants(ics)
        val others = people.attendees.filter { it !in me }
        if (people.organizer != null && people.organizer !in me) {
            return@onTarget failed("Only the organizer (${people.organizer}) can move this event; you are an attendee. Propose a different time to them instead.")
        }
        if (others.isNotEmpty()) {
            if (!a.notifyAttendees) {
                return@onTarget failed("This event has other attendees (${others.joinToString()}); moving it notifies them. Ask again with notifyAttendees set if that's intended.")
            }
            if (AUTO_SCHEDULE !in server.capabilities(url)) {
                return@onTarget failed("This calendar server does not send updates to attendees itself (no CalDAV scheduling), so I can't move an event that has attendees.")
            }
        }
        val moved = try { Ical.moved(ics, a.start.toJava(), a.end.toJava()) } catch (e: IllegalArgumentException) { return@onTarget failed(e.message ?: "This event can't be edited.") }
        try {
            server.putCalendar(url, moved, ifMatch = etag)
        } catch (_: PreconditionFailedException) {
            return@onTarget failed("The event changed on the server while I was moving it; trying again with the new version.", retryable = true)
        }
        ActionOutcome.Done(SourceId(sourceIdOf(config.id, path)))
    }

    suspend fun respond(a: ProposedAction.RespondToInvite, target: SourceId?): ActionOutcome = onTarget("invitation", target) { server, config, path ->
        if ('#' in path) return@onTarget failed("That is one occurrence of a recurring event; answering a single occurrence is not supported.")
        val discovery = server.discover()
        val url = server.resolve(Url(config.caldavUrl!!), path)
        if (AUTO_SCHEDULE !in server.capabilities(url)) {
            return@onTarget failed("This calendar server does not send replies to organizers itself (no CalDAV scheduling), so I can't answer the invitation.")
        }
        val (etag, bytes) = server.get(url, 1_000_000) ?: return@onTarget failed("The invitation could not be read.")
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
