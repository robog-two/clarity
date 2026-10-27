package net.robog.clarity.connector.dav

import java.io.StringReader
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant as JInstant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.Temporal
import java.util.Optional
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Instant
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.Recur
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VTimeZone
import net.fortuna.ical4j.model.parameter.Cn
import net.fortuna.ical4j.model.parameter.PartStat
import net.fortuna.ical4j.model.property.Attendee
import net.fortuna.ical4j.model.property.CalScale
import net.fortuna.ical4j.model.property.DtEnd
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.ExDate
import net.fortuna.ical4j.model.property.Organizer
import net.fortuna.ical4j.model.property.ProdId
import net.fortuna.ical4j.model.property.RDate
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.model.property.RecurrenceId
import net.fortuna.ical4j.model.property.Trigger
import net.fortuna.ical4j.model.property.Uid
import net.fortuna.ical4j.model.property.Version
import net.fortuna.ical4j.model.property.immutable.ImmutableAction
import net.fortuna.ical4j.model.property.immutable.ImmutableCalScale
import net.fortuna.ical4j.model.property.immutable.ImmutableClazz
import net.fortuna.ical4j.model.property.immutable.ImmutableTransp
import net.fortuna.ical4j.model.property.immutable.ImmutableVersion
import net.robog.clarity.shared.governance.InviteResponse
import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.Handle

/** The interval of time events are expanded into (recurrences) and reported for. */
data class TimeWindow(val from: JInstant, val to: JInstant)

/** One reportable event: [key] distinguishes occurrences of a recurring event (null for a plain one). */
data class EventOccurrence(val key: String?, val payload: CalendarEventPayload)

/**
 * What one calendar object produced: its occurrences, whether it is a recurring series (RRULE,
 * RDATE or overridden instances; such objects are re-expanded as the window moves, even when they
 * produced nothing), and how many recurrence properties could not be read normally (each logged).
 */
data class Expansion(val occurrences: List<EventOccurrence>, val recurring: Boolean, val failures: Int)

private val log = KotlinLogging.logger {}

private const val PRODID = "-//Clarity//DAV connector//EN"

private val UTC = ZoneOffset.UTC

object Ical {

    /**
     * [ics] parsed. One unreadable property (say a Mail-made `URL:message:%3C…` with a broken
     * escape, or a mangled ATTENDEE) should not cost the whole event: the failing line is dropped
     * and the object parsed again, a few times at most. Lines that make the event (BEGIN, END,
     * UID, DTSTART, DTEND, RRULE, RECURRENCE-ID) are never dropped; then the error stands.
     */
    fun parse(ics: String): Calendar {
        var text = ics
        repeat(MAX_DROPPED_LINES) {
            try {
                return CalendarBuilder().build(StringReader(text))
            } catch (e: net.fortuna.ical4j.data.ParserException) {
                val (name, rest) = withoutLine(text, e.lineNo) ?: throw e
                log.warn { "dropped an unreadable $name line (line ${e.lineNo}) to read the rest: ${e.cause?.message?.substringBefore(':')}" }
                text = rest
            }
        }
        return CalendarBuilder().build(StringReader(text))
    }

    private const val MAX_DROPPED_LINES = 8

    private val KEEP = setOf("BEGIN", "END", "UID", "DTSTART", "DTEND", "RRULE", "RECURRENCE-ID", "DURATION")

    /**
     * Pure: [ics] without the content line (and its folded continuations) that covers physical line
     * [lineNo] (1-based), with that line's property name; null when it cannot or must not be dropped.
     */
    internal fun withoutLine(ics: String, lineNo: Int): Pair<String, String>? {
        val lines = ics.split("\r\n", "\n")
        val at = lineNo - 1
        if (at !in lines.indices) return null
        var start = at
        while (start > 0 && (lines[start].startsWith(" ") || lines[start].startsWith("\t"))) start--
        var end = at + 1
        while (end < lines.size && (lines[end].startsWith(" ") || lines[end].startsWith("\t"))) end++
        val name = lines[start].substringBefore(':').substringBefore(';').trim().uppercase()
        if (name.isEmpty() || name in KEEP) return null
        return name to (lines.subList(0, start) + lines.subList(end, lines.size)).joinToString("\r\n")
    }

    /**
     * Events in [ics] as payloads for [calendarName]. Recurring events are expanded inside [window]
     * (each occurrence gets `icalUid = "<uid>#<start epoch seconds>"` so occurrences stay separate
     * entities); overridden and excluded occurrences are honoured; cancelled events are dropped.
     */
    fun events(ics: String, calendarName: String, window: TimeWindow): List<EventOccurrence> = expand(ics, calendarName, window).occurrences

    /**
     * Like [events], with more control: plain (non-recurring) events are kept when they overlap
     * [plainWindow], or always when it is null; recurring series are expanded inside [window].
     * Expansion problems are logged naming [source] and counted in [Expansion.failures].
     */
    fun expand(ics: String, calendarName: String, window: TimeWindow, plainWindow: TimeWindow? = window, source: String = "calendar object"): Expansion {
        val calendar = parse(ics)
        val all = calendar.getComponents<VEvent>("VEVENT")
        val byUid = all.groupBy { it.text("UID") ?: "" }
        val zoneHint = calendar.zoneHint()
        val out = mutableListOf<EventOccurrence>()
        var anyRecurring = false
        var failures = 0
        for ((uid, group) in byUid) {
            val master = group.firstOrNull { !it.has("RECURRENCE-ID") }
            val overrides = group.filter { it.has("RECURRENCE-ID") }
            if (overrides.isNotEmpty()) anyRecurring = true
            val overriddenAt = overrides.mapNotNull { it.recurrenceInstant() }.toSet()
            if (master != null) {
                val recurring = master.has("RRULE") || master.has("RDATE")
                if (!recurring) {
                    master.single(calendarName, uid.ifEmpty { null })?.takeIf { plainWindow == null || it.overlaps(plainWindow) }?.let { out += EventOccurrence(null, it) }
                } else {
                    anyRecurring = true
                    for (start in master.occurrenceStarts(window, zoneHint, "$source (UID $uid)") { failures++ }) {
                        val key = start.toInstantOrNull()!!.epochSecond
                        if (start.toInstantOrNull() in overriddenAt) continue
                        master.occurrence(calendarName, uid, start)?.takeIf { it.overlaps(window) }?.let { out += EventOccurrence("$key", it) }
                    }
                }
            }
            for (o in overrides) {
                val recurrenceAt = o.recurrenceInstant() ?: continue
                val payload = o.single(calendarName, "$uid#${recurrenceAt.epochSecond}") ?: continue
                if (payload.overlaps(window)) out += EventOccurrence("${recurrenceAt.epochSecond}", payload)
            }
        }
        return Expansion(out, anyRecurring, failures)
    }

    // ---- writing -------------------------------------------------------------------------------

    /** A new single event; `TRANSP:OPAQUE`, and `CLASS:PRIVATE` for a personal hold. */
    fun newEvent(uid: String, title: String, start: JInstant, end: JInstant, hold: Boolean, attendees: List<Handle>, organizer: Handle?): String {
        val event = VEvent(start, end, title)
        event.add<VEvent>(Uid(uid))
        event.add<VEvent>(ImmutableTransp.OPAQUE)
        if (hold) event.add<VEvent>(ImmutableClazz.PRIVATE)
        if (attendees.isNotEmpty() && organizer != null) {
            event.add<VEvent>(Organizer(URI.create("mailto:${organizer.value}")).also { o -> organizer.displayName?.let { o.add<Organizer>(Cn(it)) } })
            attendees.forEach { a ->
                val attendee = Attendee(URI.create("mailto:${a.value}"))
                a.displayName?.let { attendee.add<Attendee>(Cn(it)) }
                attendee.add<Attendee>(PartStat.NEEDS_ACTION)
                event.add<VEvent>(attendee)
            }
        }
        return wrap(event)
    }

    /** A short event at [at] with a DISPLAY alarm that fires at its start: a reminder with no store of its own. */
    fun newReminder(uid: String, text: String, at: JInstant, length: Duration = Duration.ofMinutes(15)): String {
        val event = VEvent(at, at.plus(length), text)
        event.add<VEvent>(Uid(uid))
        event.add<VEvent>(ImmutableTransp.TRANSPARENT)
        val alarm = VAlarm()
        alarm.add<VAlarm>(ImmutableAction.DISPLAY)
        alarm.add<VAlarm>(net.fortuna.ical4j.model.property.Description(text))
        alarm.add<VAlarm>(Trigger(Duration.ZERO))
        event.add<VEvent>(alarm)
        return wrap(event)
    }

    /** [ics] with the (single, non-recurring) event moved to [start]..[end]. */
    fun moved(ics: String, start: JInstant, end: JInstant): String {
        val calendar = parse(ics)
        val event = singleEditable(calendar)
        event.removeAll<VEvent>("DTSTART", "DTEND", "DURATION")
        event.add<VEvent>(DtStart(start))
        event.add<VEvent>(DtEnd(end))
        bumpSequence(event)
        return calendar.toString()
    }

    /** [ics] with the PARTSTAT of the attendee [email] set per [response]; null when that address is not an attendee. */
    fun responded(ics: String, email: String, response: InviteResponse): String? {
        val calendar = parse(ics)
        val event = singleEditable(calendar)
        val mine = event.getProperties<Attendee>("ATTENDEE").firstOrNull { it.address().equals(email, ignoreCase = true) } ?: return null
        val state = when (response) {
            InviteResponse.ACCEPT -> PartStat.ACCEPTED
            InviteResponse.DECLINE -> PartStat.DECLINED
            InviteResponse.TENTATIVE -> PartStat.TENTATIVE
        }
        mine.replace<Attendee>(state)
        mine.remove<Attendee>(mine.getParameter<Parameter>("RSVP").orElse(null) ?: return calendar.toString())
        return calendar.toString()
    }

    /** Organizer and attendee addresses of the (first) event, lower-cased. */
    fun participants(ics: String): Participants {
        val event = parse(ics).getComponents<VEvent>("VEVENT").firstOrNull() ?: return Participants(null, emptyList())
        return Participants(
            organizer = event.getProperty<Organizer>("ORGANIZER").orElse(null)?.address(),
            attendees = event.getProperties<Attendee>("ATTENDEE").mapNotNull { it.address() },
        )
    }

    data class Participants(val organizer: String?, val attendees: List<String>)

    // ---- internals -----------------------------------------------------------------------------

    private fun wrap(event: VEvent): String {
        val calendar = Calendar()
        calendar.add<Calendar>(ProdId(PRODID))
        calendar.add<Calendar>(ImmutableVersion.VERSION_2_0)
        calendar.add<Calendar>(ImmutableCalScale.GREGORIAN)
        calendar.add<Calendar>(event)
        return calendar.toString()
    }

    private fun singleEditable(calendar: Calendar): VEvent {
        val events = calendar.getComponents<VEvent>("VEVENT")
        require(events.size == 1) { "This calendar entry has ${events.size} parts (a recurring series with exceptions); editing it is not supported." }
        val event = events.single()
        require(!event.has("RRULE") && !event.has("RDATE")) { "This is a recurring event; editing a single occurrence or the series is not supported." }
        return event
    }

    private fun bumpSequence(event: VEvent) {
        val current = event.getProperty<Property>("SEQUENCE").orElse(null)?.value?.toIntOrNull() ?: 0
        event.removeAll<VEvent>("SEQUENCE")
        event.add<VEvent>(net.fortuna.ical4j.model.property.Sequence(current + 1))
    }

    private fun Attendee.address(): String? = calAddress?.toString()?.removePrefix("mailto:")?.removePrefix("MAILTO:")?.trim()?.lowercase()?.takeIf { it.contains('@') }
    private fun Organizer.address(): String? = calAddress?.toString()?.removePrefix("mailto:")?.removePrefix("MAILTO:")?.trim()?.lowercase()?.takeIf { it.contains('@') }

    private fun VEvent.has(name: String) = getProperty<Property>(name).isPresent

    private fun VEvent.text(name: String): String? = getProperty<Property>(name).orElse(null)?.value?.trim()?.takeIf(String::isNotEmpty)

    @Suppress("UNCHECKED_CAST")
    private fun VEvent.startTemporal(): Temporal? = (getProperty<DtStart<Temporal>>("DTSTART") as Optional<DtStart<Temporal>>).orElse(null)?.date

    @Suppress("UNCHECKED_CAST")
    private fun VEvent.endTemporal(): Temporal? = (getProperty<DtEnd<Temporal>>("DTEND") as Optional<DtEnd<Temporal>>).orElse(null)?.date

    @Suppress("UNCHECKED_CAST")
    private fun VEvent.recurrenceInstant(): JInstant? = (getProperty<RecurrenceId<Temporal>>("RECURRENCE-ID") as Optional<RecurrenceId<Temporal>>).orElse(null)?.date?.let { it.toInstantOrNull() }

    private fun Temporal.toInstantOrNull(): JInstant? = when (this) {
        is JInstant -> this
        is ZonedDateTime -> toInstant()
        is OffsetDateTime -> toInstant()
        is LocalDateTime -> toInstant(UTC) // floating time: read as UTC
        is LocalDate -> atStartOfDay(UTC).toInstant()
        else -> null
    }

    /** Length of the event; all-day events default to one day, timed ones to zero. */
    private fun VEvent.length(start: Temporal): Duration {
        val end = endTemporal()
        if (end != null) {
            val s = start.toInstantOrNull()
            val e = end.toInstantOrNull()
            if (s != null && e != null && e >= s) return Duration.between(s, e)
        }
        val duration = getProperty<Property>("DURATION").orElse(null)?.value
        if (duration != null) runCatching { return java.time.Period.parse(duration).let { p -> Duration.ofDays(p.days.toLong()) } }
        runCatching { if (duration != null) return Duration.parse(duration) }
        return if (start is LocalDate) Duration.ofDays(1) else Duration.ZERO
    }

    private fun VEvent.single(calendarName: String, icalUid: String?): CalendarEventPayload? {
        val start = startTemporal() ?: return null
        return payload(calendarName, icalUid, start, length(start))
    }

    private fun VEvent.occurrence(calendarName: String, uid: String, start: Temporal): CalendarEventPayload? {
        val master = startTemporal() ?: return null
        val at = start.toInstantOrNull() ?: return null
        return payload(calendarName, "$uid#${at.epochSecond}", start, length(master))
    }

    private fun VEvent.payload(calendarName: String, icalUid: String?, start: Temporal, length: Duration): CalendarEventPayload? {
        if (text("STATUS").equals("CANCELLED", ignoreCase = true)) return null
        val s = start.toInstantOrNull() ?: return null
        val allDay = start is LocalDate
        val e = s.plus(length)
        return CalendarEventPayload(
            icalUid = icalUid,
            calendar = calendarName,
            title = text("SUMMARY") ?: "(no title)",
            start = Instant.fromEpochSeconds(s.epochSecond),
            end = Instant.fromEpochSeconds(e.epochSecond),
            allDay = allDay,
            location = text("LOCATION"),
            description = text("DESCRIPTION"),
            attendees = getProperties<Attendee>("ATTENDEE").mapNotNull { a ->
                a.address()?.let { addr -> Handle.email(addr, a.getParameter<Cn>("CN").orElse(null)?.value) }
            },
        )
    }

    private fun CalendarEventPayload.overlaps(window: TimeWindow): Boolean =
        start.toEpochMilliseconds() < window.to.toEpochMilli() && end.toEpochMilliseconds() >= window.from.toEpochMilli()

    /**
     * Start times of the occurrences of a recurring event whose span may touch [window]. A rule that
     * cannot be expanded is logged (naming [source]) and reported through [onFailure]; the series
     * then falls back to ical4j's own reading of the rule, and failing that to DTSTART and RDATEs.
     */
    @Suppress("UNCHECKED_CAST")
    private fun VEvent.occurrenceStarts(window: TimeWindow, zoneHint: ZoneId, source: String, onFailure: () -> Unit): List<Temporal> {
        val seed = startTemporal() ?: return emptyList()
        val length = length(seed)
        val from = window.from.minus(length).minus(Duration.ofDays(1))
        val dates = LinkedHashSet<Temporal>()
        dates += seed
        getProperties<RRule<Temporal>>("RRULE").forEach { rule ->
            val text = rule.value
            try {
                dates += expandRule(text, seed, from, window.to, zoneHint)
            } catch (e: Exception) {
                onFailure()
                log.warn(e) { "could not expand RRULE:$text of $source (DTSTART $seed); falling back to ical4j's reading of the rule" }
                try {
                    dates += rule.recur.getDates(seed, seed.like(from), seed.like(window.to))
                } catch (e2: Exception) {
                    log.warn(e2) { "fallback expansion of RRULE:$text of $source failed too; reporting only DTSTART and RDATEs" }
                }
            }
        }
        getProperties<RDate<Temporal>>("RDATE").forEach { r ->
            try { r.dates?.let { dates += it } } catch (e: Exception) { onFailure(); log.warn(e) { "unreadable RDATE:${r.value} of $source; ignored" } }
        }
        val excluded = getProperties<ExDate<Temporal>>("EXDATE").flatMap { x ->
            try { x.dates.orEmpty() } catch (e: Exception) { onFailure(); log.warn(e) { "unreadable EXDATE:${x.value} of $source; ignored" }; emptyList() }
        }.mapNotNull { it.toInstantOrNull() }.toSet()
        return dates.filter { it.toInstantOrNull() !in excluded }.sortedBy { it.toInstantOrNull() }
    }

    /**
     * Occurrence starts of the rule [text] for [seed] from about [from] to [to] (callers filter by
     * overlap). UNTIL is read here rather than by ical4j, whose comparison of UNTIL with a DTSTART of
     * a different kind (UTC UNTIL with a floating or all-day DTSTART, DATE UNTIL with a timed one)
     * depends on the JVM's default zone or drops the last day: UNTIL becomes an instant in the
     * seed's own terms, is removed from the rule, and is applied as an inclusive bound.
     */
    @Suppress("UNCHECKED_CAST")
    private fun expandRule(text: String, seed: Temporal, from: JInstant, to: JInstant, zoneHint: ZoneId): List<Temporal> {
        val parts = text.split(';').map(String::trim).filter(String::isNotEmpty)
        val untilPart = parts.firstOrNull { it.startsWith("UNTIL=", ignoreCase = true) }
        val until = untilPart?.let { untilInstant(it.substringAfter('='), seed, zoneHint) }
        if (until != null && until < from) return emptyList()
        val recur = Recur<Temporal>(parts.filterNot { it.startsWith("UNTIL=", ignoreCase = true) }.joinToString(";"))
        val end = if (until != null && until < to) until else to
        // a day of slack at both ends: the bounds are applied exactly below and by the caller
        val dates: List<Temporal> = recur.getDates(seed, seed.like(from.minus(Duration.ofDays(1))), seed.like(end.plus(Duration.ofDays(1))))
        return if (until == null) dates else dates.filter { d -> d.toInstantOrNull()?.let { it <= until } ?: false }
    }

    private val BASIC_DATE = java.time.format.DateTimeFormatter.BASIC_ISO_DATE
    private val BASIC_DATE_TIME = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** The last instant an occurrence of a [seed]-started series may begin at, from an RRULE UNTIL value. */
    private fun untilInstant(value: String, seed: Temporal, zoneHint: ZoneId): JInstant {
        val v = value.trim().uppercase()
        val endOfDay = java.time.LocalTime.of(23, 59, 59)
        return when {
            v.length == 8 -> {
                val date = LocalDate.parse(v, BASIC_DATE)
                when (seed) {
                    is LocalDate -> date.atStartOfDay(UTC).toInstant()
                    is ZonedDateTime -> date.atTime(endOfDay).atZone(seed.zone).toInstant()
                    is OffsetDateTime -> date.atTime(endOfDay).atOffset(seed.offset).toInstant()
                    else -> date.atTime(endOfDay).toInstant(UTC)
                }
            }
            v.length == 16 && v.endsWith("Z") -> {
                val at = LocalDateTime.parse(v.dropLast(1), BASIC_DATE_TIME).toInstant(UTC)
                if (seed is LocalDate) LocalDate.ofInstant(at, zoneHint).atStartOfDay(UTC).toInstant() else at
            }
            v.length == 15 -> {
                val local = LocalDateTime.parse(v, BASIC_DATE_TIME)
                when (seed) {
                    is LocalDate -> local.toLocalDate().atStartOfDay(UTC).toInstant()
                    is ZonedDateTime -> local.atZone(seed.zone).toInstant()
                    is OffsetDateTime -> local.atOffset(seed.offset).toInstant()
                    else -> local.toInstant(UTC)
                }
            }
            else -> throw IllegalArgumentException("unrecognised UNTIL value '$value'")
        }
    }

    /** The zone a UTC UNTIL of an all-day series is read in: the object's first known VTIMEZONE, else UTC. */
    private fun Calendar.zoneHint(): ZoneId =
        getComponents<VTimeZone>("VTIMEZONE").firstNotNullOfOrNull { tz ->
            tz.getProperty<Property>("TZID").orElse(null)?.value?.trim()?.let { id -> try { ZoneId.of(id) } catch (e: Exception) { null } }
        } ?: UTC

    /** [instant] expressed in the same kind of temporal as [this] (zoned, local, date, instant). */
    private fun Temporal.like(instant: JInstant): Temporal = when (this) {
        is ZonedDateTime -> instant.atZone(zone)
        is OffsetDateTime -> instant.atOffset(offset)
        is LocalDateTime -> LocalDateTime.ofInstant(instant, UTC)
        is LocalDate -> LocalDate.ofInstant(instant, UTC)
        else -> instant
    }
}

/** `<hash of key>@clarity`: the same idempotency key always produces the same UID, so a re-delivered action finds its event. */
fun uidFor(idempotencyKey: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(idempotencyKey.toByteArray(Charsets.UTF_8))
    return "clarity-" + digest.joinToString("") { "%02x".format(it) }.take(32) + "@clarity"
}
