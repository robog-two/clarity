package net.robog.clarity.connector.dav

import java.net.URI
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant as JInstant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.Temporal
import kotlinx.datetime.number
import net.fortuna.ical4j.data.ParserException
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.DateList
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.ParameterList
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.Recur
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VTimeZone
import net.fortuna.ical4j.model.parameter.TzId
import net.fortuna.ical4j.model.parameter.Value
import net.fortuna.ical4j.model.property.Description
import net.fortuna.ical4j.model.property.DtEnd
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.ExDate
import net.fortuna.ical4j.model.property.Location
import net.fortuna.ical4j.model.property.RDate
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.model.property.RecurrenceId
import net.fortuna.ical4j.model.property.Summary
import net.fortuna.ical4j.model.property.Uid
import net.fortuna.ical4j.model.property.Url
import net.robog.clarity.connector.dav.Ical.has
import net.robog.clarity.connector.dav.Ical.length
import net.robog.clarity.connector.dav.Ical.occurrenceStarts
import net.robog.clarity.connector.dav.Ical.recurrenceInstant
import net.robog.clarity.connector.dav.Ical.startTemporal
import net.robog.clarity.connector.dav.Ical.text
import net.robog.clarity.connector.dav.Ical.toInstantOrNull
import net.robog.clarity.connector.dav.Ical.zoneHint
import net.robog.clarity.shared.calendar.EventScope
import net.robog.clarity.shared.calendar.Recurrence
import net.robog.clarity.shared.calendar.basicDate
import net.robog.clarity.shared.calendar.basicUtc
import net.robog.clarity.shared.calendar.movedTo
import net.robog.clarity.shared.calendar.parseRRule
import net.robog.clarity.shared.calendar.toRRule
import net.robog.clarity.shared.noun.Handle

private val UTC_ZONE: ZoneId = ZoneOffset.UTC

/**
 * A new event. All-day [start] and [end] are UTC midnights, [end] exclusive. [zone] is the clock a
 * repeating timed event runs on.
 */
data class NewEvent(
    val uid: String,
    val title: String,
    val start: JInstant,
    val end: JInstant,
    val hold: Boolean = false,
    val attendees: List<Handle> = emptyList(),
    val organizer: Handle? = null,
    val description: String? = null,
    val location: String? = null,
    val url: String? = null,
    val allDay: Boolean = false,
    val repeat: Recurrence? = null,
    val zone: ZoneId = UTC_ZONE,
)

/**
 * What an update changes. A null field stays as it is; empty text clears [description], [location]
 * and [url]. [start] and [end] are instants (UTC midnights for an all-day event, [end] exclusive);
 * giving only one keeps the length or the other end. [zone] is the clock a repeating timed event runs on.
 */
data class EventChange(
    val start: JInstant? = null,
    val end: JInstant? = null,
    val title: String? = null,
    val description: String? = null,
    val location: String? = null,
    val url: String? = null,
    val allDay: Boolean? = null,
    val repeat: Recurrence? = null,
    val zone: ZoneId = UTC_ZONE,
)

/** What editing a calendar object produces. */
sealed interface Edited {
    /** The new text of the same object. */
    data class Replace(val ics: String) : Edited

    /** The original series ended before the occurrence, and [following] is a new object that carries on from it. */
    data class Split(val original: String, val following: String) : Edited

    /** The object already has this change (an earlier delivery of the same edit did it); nothing to write. */
    data object Applied : Edited
}

/** What cancelling part of a calendar object leaves. */
sealed interface Removal {
    /** Nothing is left: the object itself goes. */
    data object Delete : Removal

    /** The object stays with this new text. */
    data class Replace(val ics: String) : Removal
}

// ---- writing blocks shared by creating and editing --------------------------------------------

/**
 * [instant] as a start: a date when [allDay], a floating time when [floating], on the wall clock of
 * [zone] when there is one, else in UTC. Only zones from [realZone] go in: ical4j's private ones
 * would be written out as TZIDs nobody knows.
 */
internal fun startAt(instant: JInstant, allDay: Boolean, floating: Boolean, zone: ZoneId?): Temporal = when {
    allDay -> LocalDate.ofInstant(instant, UTC_ZONE)
    floating -> LocalDateTime.ofInstant(instant, UTC_ZONE)
    zone != null -> instant.atZone(zone)
    else -> instant
}

/** The named zone of [this] event's DTSTART, or null (floating, UTC, a date, or a zone only the file defines). */
internal fun VEvent.tzid(): ZoneId? = getProperty<Property>("DTSTART").orElse(null)?.getParameter<Parameter>("TZID")?.orElse(null)?.value?.let(::realZone)

private fun endOf(start: Temporal, length: Duration): Temporal = when (start) {
    is LocalDate -> start.plusDays(maxOf(1, length.toDays()))
    is ZonedDateTime -> start.plus(length)
    is LocalDateTime -> start.plus(length)
    is JInstant -> start.plus(length)
    is OffsetDateTime -> start.plus(length)
    else -> throw IllegalArgumentException("unsupported start $start")
}

/** DTSTART at [start] and DTEND [length] later; an all-day event lasts at least a day. */
internal fun VEvent.setTimes(start: Temporal, length: Duration) {
    require(!length.isNegative && !length.isZero || start is LocalDate) { "The end must be after the start." }
    removeAll<VEvent>("DTSTART", "DTEND", "DURATION")
    add<VEvent>(DtStart(start))
    add<VEvent>(DtEnd(endOf(start, length)))
}

internal fun Temporal.localDate(): kotlinx.datetime.LocalDate = when (this) {
    is LocalDate -> this
    is ZonedDateTime -> toLocalDate()
    is LocalDateTime -> toLocalDate()
    else -> LocalDate.ofInstant(toInstantOrNull()!!, UTC_ZONE)
}.let { kotlinx.datetime.LocalDate(it.year, it.monthValue, it.dayOfMonth) }

internal fun ZoneId?.clock(): kotlinx.datetime.TimeZone =
    this?.let { runCatching { kotlinx.datetime.TimeZone.of(it.id) }.getOrNull() } ?: kotlinx.datetime.TimeZone.UTC

/** The RRULE for [rule] on a series starting at [start] on the clock of [zone]. */
internal fun rruleOf(rule: Recurrence, start: Temporal, zone: ZoneId?): RRule<Temporal> =
    rruleFromText(rule.toRRule(start.localDate(), zone.clock(), allDay = start is LocalDate, floating = start is LocalDateTime))

internal fun rruleFromText(text: String): RRule<Temporal> = RRule(Recur<Temporal>(text))

/** [id] as a zone of the Java database, or null for the private ids ical4j gives a calendar's own VTIMEZONE. */
internal fun realZone(id: String): ZoneId? = runCatching { ZoneId.of(id) }.getOrNull()

private val BASIC_DATE_TIME = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

private fun offsetText(offset: ZoneOffset): String {
    val total = offset.totalSeconds
    val abs = kotlin.math.abs(total)
    return (if (total < 0) "-" else "+") + "%02d%02d".format(abs / 3600, abs / 60 % 60)
}

/**
 * A small VTIMEZONE for [zone] that states only its current yearly rules, so a new event carries a
 * few lines instead of the whole history. Clients that know the TZID by name ignore it anyway.
 */
internal fun timeZoneText(zone: ZoneId): String {
    val rules = zone.rules
    val lines = mutableListOf("BEGIN:VTIMEZONE", "TZID:${zone.id}")
    if (rules.transitionRules.isEmpty()) {
        val offset = rules.transitions.lastOrNull()?.offsetAfter ?: rules.getOffset(JInstant.EPOCH)
        lines += listOf("BEGIN:STANDARD", "DTSTART:19700101T000000", "TZOFFSETFROM:${offsetText(offset)}", "TZOFFSETTO:${offsetText(offset)}", "END:STANDARD")
    }
    for (rule in rules.transitionRules) {
        val transition = rule.createTransition(1970)
        val indicator = rule.dayOfMonthIndicator
        val weekday = rule.dayOfWeek?.name?.take(2)
        val day = when {
            weekday == null -> "BYMONTHDAY=$indicator"
            indicator == -1 || indicator + 6 == rule.month.length(false) -> "BYDAY=-1$weekday"
            indicator < 0 -> "BYDAY=$weekday;BYMONTHDAY=" + (indicator - 6..indicator).joinToString(",")
            else -> "BYDAY=$weekday;BYMONTHDAY=" + (indicator..indicator + 6).joinToString(",")
        }
        val kind = if (rule.offsetAfter.totalSeconds > rule.offsetBefore.totalSeconds) "DAYLIGHT" else "STANDARD"
        lines += listOf(
            "BEGIN:$kind", "DTSTART:${transition.dateTimeBefore.format(BASIC_DATE_TIME)}",
            "TZOFFSETFROM:${offsetText(rule.offsetBefore)}", "TZOFFSETTO:${offsetText(rule.offsetAfter)}",
            "RRULE:FREQ=YEARLY;BYMONTH=${rule.month.value};$day", "END:$kind",
        )
    }
    lines += "END:VTIMEZONE"
    return lines.joinToString("\r\n")
}

private fun timeZoneComponent(zone: ZoneId): VTimeZone? =
    runCatching {
        Ical.parse("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Clarity//DAV connector//EN\r\n${timeZoneText(zone)}\r\nEND:VCALENDAR\r\n")
            .getComponents<VTimeZone>("VTIMEZONE").firstOrNull()
    }.getOrNull()

private val TIME_ZONE_BLOCK = Regex("(?s)BEGIN:VTIMEZONE\\r?\\n.*?END:VTIMEZONE(\\r?\\n)?")
private val TZID_LINE = Regex("(?m)^TZID:(.+?)\\r?$")

/**
 * [ics] without the VTIMEZONE blocks of zones Java knows by name: ical4j then reads their events in
 * the real zone (and writes the plain TZID back) instead of a private copy of the file's definition.
 */
internal fun withoutKnownTimeZones(ics: String): String = TIME_ZONE_BLOCK.replace(ics) { block ->
    val id = TZID_LINE.find(block.value)?.groupValues?.get(1)?.trim()
    if (id != null && realZone(id) != null) "" else block.value
}

/** Adds the VTIMEZONE of every named zone this calendar's events start in, unless it has it. */
internal fun Calendar.withTimeZones(): Calendar {
    val known = getComponents<VTimeZone>("VTIMEZONE").mapNotNull { it.getProperty<Property>("TZID").orElse(null)?.value }.toSet()
    getComponents<VEvent>("VEVENT").flatMap { event -> event.getProperties<Property>().mapNotNull { it.getParameter<Parameter>("TZID").orElse(null)?.value } }
        .mapNotNull(::realZone).toSet()
        .filter { it.id !in known }
        .mapNotNull(::timeZoneComponent)
        .forEach { add<Calendar>(it) }
    return this
}

// ---- editing ------------------------------------------------------------------------------------

/**
 * Pure edits of a calendar object's text: change or cancel a whole event, one occurrence of a
 * repeating one, or an occurrence and everything after it. An occurrence is named by its original
 * start (the epoch second in a source id after `#`).
 */
object IcalEdit {

    /**
     * [ics] with the event changed. [occurrence] and [scope] say what part of a repeating event; they
     * are ignored for a single event. [followingUid] names the new series a [EventScope.FOLLOWING]
     * edit starts.
     */
    fun updated(ics: String, occurrence: JInstant?, scope: EventScope?, change: EventChange, followingUid: String): Edited =
        cleanly { edit(ics, occurrence, scope, change, followingUid) }

    /** [block]'s result; a calendar library or date failure on a strange object is a message for the model, not a crash. */
    private inline fun <T> cleanly(block: () -> T): T =
        try {
            block()
        } catch (e: DateTimeException) {
            throw IllegalArgumentException("This event could not be edited: ${e.message}")
        } catch (e: ParserException) {
            throw IllegalArgumentException("This event could not be read: ${e.message}")
        } catch (e: NoSuchElementException) {
            throw IllegalArgumentException("This event is missing a part that editing needs.")
        }

    private fun edit(ics: String, occurrence: JInstant?, scope: EventScope?, change: EventChange, followingUid: String): Edited {
        val parts = Parts.of(ics)
        val master = parts.master
        if (!master.repeats()) {
            editInPlace(master, change, fallbackZone = change.zone.takeIf { change.repeat != null })
            return Edited.Replace(parts.finish())
        }
        requireNotNull(scope) { "This event repeats. Say what to change: `this`, `following` or `series`." }
        val at = occurrence ?: master.seedInstant()
        // a re-delivered series move: the old occurrence is gone and the series is already at the new start
        if (scope == EventScope.SERIES && change.start != null && !parts.isOccurrence(at) && parts.isOccurrence(change.start)) return Edited.Applied
        parts.requireOccurrence(at)
        require(!master.has("RDATE") || scope == EventScope.SERIES) {
            "This repeating event lists single dates, so only the whole series can be changed."
        }
        require(!master.has("RDATE") || change.start == null) {
            "This repeating event lists single dates, so its time cannot be moved as a series. Cancel it and create a new one."
        }
        require(change.allDay == null || change.allDay == (master.startTemporal() is LocalDate)) {
            "A repeating event cannot switch between all-day and timed. Cancel it and create a new one."
        }
        return when {
            scope == EventScope.FOLLOWING && at == master.seedInstant() -> edit(ics, occurrence, EventScope.SERIES, change, followingUid)
            scope == EventScope.SERIES -> Edited.Replace(parts.updateSeries(at, change))
            scope == EventScope.THIS -> {
                require(change.repeat == null) { "`repeat` cannot change for a single occurrence. Use scope `following` or `series`." }
                Edited.Replace(parts.updateOccurrence(at, change))
            }
            else -> parts.splitFollowing(at, change, followingUid)
        }
    }

    /** What is left of [ics] once [scope] of the event is cancelled; see [updated] for [occurrence]. */
    fun cancelled(ics: String, occurrence: JInstant?, scope: EventScope?): Removal = cleanly { cancel(ics, occurrence, scope) }

    private fun cancel(ics: String, occurrence: JInstant?, scope: EventScope?): Removal {
        val parts = Parts.of(ics)
        val master = parts.master
        if (!master.repeats()) return Removal.Delete
        requireNotNull(scope) { "This event repeats. Say what to cancel: `this`, `following` or `series`." }
        val at = occurrence ?: master.seedInstant()
        parts.requireOccurrence(at)
        return when {
            scope == EventScope.SERIES || scope == EventScope.FOLLOWING && at == master.seedInstant() -> Removal.Delete
            scope == EventScope.THIS -> Removal.Replace(parts.excludeOccurrence(at))
            else -> Removal.Replace(parts.truncateBefore(at))
        }
    }

    /** True when cancelling [scope] at [at] has nothing left to do: a repeating event whose occurrence is already excluded or cut off. */
    fun alreadyCancelled(ics: String, at: JInstant, scope: EventScope?): Boolean = cleanly {
        val parts = Parts.of(ics)
        parts.master.repeats() && (scope == EventScope.THIS || scope == EventScope.FOLLOWING) && !parts.isOccurrence(at)
    }

    /** True when the occurrence starting at [at] is one of the series in [ics] (a changed occurrence counts at its original time). */
    fun hasOccurrence(ics: String, at: JInstant): Boolean = cleanly { Parts.of(ics).isOccurrence(at) }

    private class Parts(val calendar: Calendar, val master: VEvent, val overrides: List<VEvent>) {
        val hint: ZoneId = calendar.zoneHint()
        val zone: ZoneId? = master.tzid()

        /** [at] in the kind of time the series starts in. */
        fun startLike(at: JInstant): Temporal = startAt(at, master.startTemporal() is LocalDate, master.startTemporal() is LocalDateTime, zone)

        companion object {
            fun of(ics: String): Parts {
                val calendar = Ical.parse(withoutKnownTimeZones(ics))
                val events = calendar.getComponents<VEvent>("VEVENT")
                val master = events.firstOrNull { !it.has("RECURRENCE-ID") }
                    ?: throw IllegalArgumentException("This calendar entry has no main event to edit.")
                require(!master.repeats() || events.none { it.startTemporal() is ZonedDateTime && it.tzid() == null }) {
                    "This repeating event uses a time zone that cannot be edited here. Tell the person to change it in their calendar."
                }
                return Parts(calendar, master, events.filter { it.has("RECURRENCE-ID") })
            }
        }

        fun finish(): String {
            Ical.bumpSequence(master)
            return calendar.withTimeZones().toString()
        }

        fun isOccurrence(at: JInstant): Boolean =
            master.occurrenceStarts(TimeWindow(at, at.plusSeconds(1)), hint, "event") {}.any { it.toInstantOrNull() == at } ||
                overrides.any { it.recurrenceInstant() == at }

        fun requireOccurrence(at: JInstant) =
            require(isOccurrence(at)) { "That occurrence is not in the series (it may have been changed or removed already)." }

        fun overrideAt(at: JInstant): VEvent? = overrides.firstOrNull { it.recurrenceInstant() == at }

        /** The RRULE of a series; a series that only lists dates has none. */
        fun rule(): String =
            master.getProperties<RRule<Temporal>>("RRULE").firstOrNull()?.value
                ?: throw IllegalArgumentException("This repeating event lists single dates, so only the whole series can be changed.")

        /**
         * The whole series; [at] names the occurrence the new times are measured from. With a new rule
         * the series restarts at `change.start`, so its first occurrence fits the rule.
         */
        fun updateSeries(at: JInstant, change: EventChange): String {
            val before = master.copy()
            val seed = master.startTemporal()!!
            val length = master.length(seed)
            val newStart = change.start ?: at
            val newLength = change.end?.let { Duration.between(newStart, it) } ?: length
            val shift = wallShift(at, newStart)
            val movedSeed = when {
                change.start == null -> seed
                change.repeat != null -> startAt(newStart, seed is LocalDate, seed is LocalDateTime, zone)
                else -> shift(seed)
            }
            if (change.start != null || change.end != null) master.setTimes(movedSeed, newLength)
            if (change.repeat != null) {
                master.removeAll<VEvent>("RRULE", "RDATE", "EXDATE")
                master.add<VEvent>(rruleOf(change.repeat, master.startTemporal()!!, zone))
                overrides.forEach { calendar.remove<Calendar>(it) }
            } else if (change.start != null) {
                if (master.has("RRULE")) {
                    val rule = rule()
                    master.removeAll<VEvent>("RRULE")
                    master.add<VEvent>(rruleFromText(ruleAfterMove(rule, seed, movedSeed, zone)))
                }
                setExdates(master, exdates(master).map(shift), zone)
                overrides.forEach { it.moveBy(shift) }
            }
            master.applyText(change)
            if (change.repeat == null) overrides.forEach { it.applyText(change, base = before) }
            return finish()
        }

        /** A changed occurrence of a series that moved: it keeps its own length and follows by the same shift, so it still matches its slot. */
        private fun VEvent.moveBy(shift: (Temporal) -> Temporal) {
            replaceRecurrenceId(shift(recurrenceTemporal()))
            val start = startTemporal()!!
            setTimes(shift(start), length(start))
        }

        /** Only the occurrence at [at]: a changed copy of it (or the copy already there). */
        fun updateOccurrence(at: JInstant, change: EventChange): String {
            val existing = overrideAt(at)
            if (existing != null) {
                editInPlace(existing, change, fallbackZone = null)
                Ical.bumpSequence(existing)
                return calendar.withTimeZones().toString()
            }
            val seed = master.startTemporal()!!
            val copy = master.copy()
            copy.removeAll<VEvent>("RRULE", "RDATE", "EXRULE", "EXDATE", "RECURRENCE-ID")
            copy.add<VEvent>(RecurrenceId(startLike(at)))
            copy.setTimes(startLike(at), master.length(seed))
            editInPlace(copy, change, fallbackZone = null)
            Ical.bumpSequence(copy)
            calendar.add<Calendar>(copy)
            return calendar.withTimeZones().toString()
        }

        /** This occurrence and the later ones: the original ends before [at] and [followingUid] starts a new series there. */
        fun splitFollowing(at: JInstant, change: EventChange, followingUid: String): Edited {
            val oldMaster = master.copy()
            val seed = master.startTemporal()!!
            val rule = rule()
            val before = Ical.expandRule(rule, seed, seed.toInstantOrNull()!!, at, hint).count { it.toInstantOrNull()!! < at }
            val later = overrides.filter { it.recurrenceInstant()!! >= at }
            val laterExcluded = exdates(master).filter { it.toInstantOrNull()!! >= at }

            val newStart = change.start ?: at
            val length = master.length(seed)
            val shift = wallShift(at, newStart)
            val following = Calendar()
            calendar.getProperties<Property>().forEach { following.add<Calendar>(it) }
            calendar.getComponents<VTimeZone>("VTIMEZONE").forEach { following.add<Calendar>(it) }
            val fresh = master.copy()
            fresh.removeAll<VEvent>("UID", "RRULE", "EXDATE", "RECURRENCE-ID", "SEQUENCE")
            fresh.add<VEvent>(Uid(followingUid))
            val startsAt = startAt(newStart, seed is LocalDate, seed is LocalDateTime, zone)
            fresh.setTimes(startsAt, change.end?.let { Duration.between(newStart, it) } ?: length)
            fresh.add<VEvent>(
                change.repeat?.let { rruleOf(it, startsAt, zone) }
                    ?: rruleFromText(ruleAfterMove(rule.carriedOver(count = before), startLike(at), startsAt, zone)),
            )
            if (laterExcluded.isNotEmpty()) fresh.add<VEvent>(exDate(laterExcluded.map(shift), zone))
            fresh.applyText(change)
            following.add<Calendar>(fresh)
            later.forEach { o ->
                val moved = o.copy()
                moved.moveBy(shift)
                moved.removeAll<VEvent>("UID")
                moved.add<VEvent>(Uid(followingUid))
                moved.applyText(change, base = oldMaster)
                following.add<Calendar>(moved)
            }
            return Edited.Split(truncateBefore(at), following.withTimeZones().toString())
        }

        /** The series ending just before [at]; later changed occurrences and exclusions go with it. */
        fun truncateBefore(at: JInstant): String {
            val seed = master.startTemporal()!!
            val rule = rule()
            master.removeAll<VEvent>("RRULE")
            master.add<VEvent>(rruleFromText(rule.endedBefore(at, seed)))
            setExdates(master, exdates(master).filter { it.toInstantOrNull()!! < at }, zone)
            val listed = master.getProperties<RDate<Temporal>>("RDATE").flatMap { it.dates.orEmpty() }.map { it.inZone(zone) }
            if (listed.isNotEmpty()) {
                master.removeAll<VEvent>("RDATE")
                listed.filter { it.toInstantOrNull()!! < at }.takeIf { it.isNotEmpty() }?.let { master.add<VEvent>(RDate(dateParameters(it, zone), DateList(it))) }
            }
            overrides.filter { it.recurrenceInstant()!! >= at }.forEach { calendar.remove<Calendar>(it) }
            return finish()
        }

        /** The series without the occurrence at [at]. */
        fun excludeOccurrence(at: JInstant): String {
            overrideAt(at)?.let { calendar.remove<Calendar>(it) }
            setExdates(master, exdates(master) + startLike(at), zone)
            return finish()
        }

        /** Maps a date of this series to the date it has once the occurrence at [from] is moved to [to], on the wall clock. */
        fun wallShift(from: JInstant, to: JInstant): (Temporal) -> Temporal = { t ->
            when (t) {
                is ZonedDateTime -> (zone ?: realZone(t.zone.id))?.let { z ->
                    t.toLocalDateTime().plus(Duration.between(from.atZone(z).toLocalDateTime(), to.atZone(z).toLocalDateTime())).atZone(z)
                } ?: t
                is LocalDate -> t.plusDays(ChronoUnit.DAYS.between(LocalDate.ofInstant(from, UTC_ZONE), LocalDate.ofInstant(to, UTC_ZONE)))
                is LocalDateTime -> t.plus(Duration.between(from, to))
                is JInstant -> t.plus(Duration.between(from, to))
                is OffsetDateTime -> t.plus(Duration.between(from, to))
                else -> t
            }
        }

        fun VEvent.recurrenceTemporal(): Temporal = getProperty<RecurrenceId<Temporal>>("RECURRENCE-ID").get().date.inZone(zone)

        fun exdates(event: VEvent): List<Temporal> =
            event.getProperties<ExDate<Temporal>>("EXDATE").flatMap { it.dates.orEmpty() }.map { it.inZone(zone) }
    }

    /** [this] on the clock of the named [zone]: ical4j reads a zoned time in a private zone that cannot be written back. */
    private fun Temporal.inZone(zone: ZoneId?): Temporal = if (this is ZonedDateTime && zone != null) toInstant().atZone(zone) else this

    private fun VEvent.repeats() = has("RRULE") || has("RDATE")

    private fun VEvent.seedInstant(): JInstant = startTemporal()!!.toInstantOrNull()!!

    private fun VEvent.replaceRecurrenceId(at: Temporal) {
        removeAll<VEvent>("RECURRENCE-ID")
        add<VEvent>(RecurrenceId(at))
    }

    /** EXDATE for [dates], which ical4j writes without their TZID or VALUE=DATE unless told. */
    private fun exDate(dates: List<Temporal>, zone: ZoneId?): ExDate<Temporal> = ExDate(dateParameters(dates, zone), DateList(dates))

    private fun dateParameters(dates: List<Temporal>, zone: ZoneId?): ParameterList =
        ParameterList(
            when {
                dates.firstOrNull() is ZonedDateTime && zone != null -> listOf<Parameter>(TzId(zone.id))
                dates.firstOrNull() is LocalDate -> listOf<Parameter>(Value.DATE)
                else -> emptyList()
            },
        )

    private fun setExdates(event: VEvent, dates: List<Temporal>, zone: ZoneId?) {
        event.removeAll<VEvent>("EXDATE")
        if (dates.isNotEmpty()) event.add<VEvent>(exDate(dates, zone))
    }

    /** Times and wording of a single VEVENT (a plain event, or one occurrence's copy). */
    private fun editInPlace(event: VEvent, change: EventChange, fallbackZone: ZoneId?) {
        val current = event.startTemporal()!!
        val allDay = change.allDay ?: (current is LocalDate)
        // a series runs on a named clock: a UTC, floating or file-defined time gets the person's zone first
        val toZone = change.repeat != null && !allDay && event.tzid() == null && fallbackZone != null
        if (change.start != null || change.end != null || allDay != (current is LocalDate) || toZone) {
            val start = change.start
                ?: if (toZone && current is LocalDateTime) current.atZone(fallbackZone).toInstant() else current.toInstantOrNull()!!
            val length = change.end?.let { Duration.between(start, it) } ?: event.length(current)
            event.setTimes(startAt(start, allDay, current is LocalDateTime && !toZone, event.tzid() ?: fallbackZone), length)
        }
        if (change.repeat != null) {
            event.removeAll<VEvent>("RRULE", "RDATE", "EXDATE")
            event.add<VEvent>(rruleOf(change.repeat, event.startTemporal()!!, event.tzid() ?: fallbackZone))
        }
        event.applyText(change)
    }

    /**
     * The text changes of [change]. With a [base] (the series' main event before the change) only fields
     * that still equal the base's are set: a changed occurrence that was renamed on its own keeps its name.
     */
    private fun VEvent.applyText(change: EventChange, base: VEvent? = null) {
        fun follows(name: String) = base == null || text(name) == base.text(name)
        change.title?.takeIf { follows("SUMMARY") }?.let { replaceText("SUMMARY", it) { v -> Summary(v) } }
        change.description?.takeIf { follows("DESCRIPTION") }?.let { replaceText("DESCRIPTION", it) { v -> Description(v) } }
        change.location?.takeIf { follows("LOCATION") }?.let { replaceText("LOCATION", it) { v -> Location(v) } }
        change.url?.takeIf { follows("URL") }?.let { replaceText("URL", it) { v -> Url(URI.create(v)) } }
    }

    private inline fun VEvent.replaceText(name: String, value: String, make: (String) -> Property) {
        removeAll<VEvent>(name)
        if (value.isNotEmpty()) add<VEvent>(make(value))
    }

    private val RULE_PARTS = Regex("(?i)^(UNTIL|COUNT)=")

    /**
     * [rule] of a series whose start moves from [from] to [to]: the weekdays, the end date and an
     * "nth weekday" follow. An unchanged start keeps the rule as it is; a new time of day re-renders it
     * so the end date still covers the last day.
     */
    private fun ruleAfterMove(rule: String, from: Temporal, to: Temporal, zone: ZoneId?): String {
        if (from.toInstantOrNull() == to.toInstantOrNull()) return rule
        val parsed = parseRRule(rule, from.localDate(), zone.clock())
        if (parsed == null && from.localDate() == to.localDate()) return rule
        val moved = parsed?.movedTo(from.localDate(), to.localDate())
            ?: throw IllegalArgumentException("This repeating event cannot move to that day because its rule would no longer fit it. Move it to another day, change its time only, or cancel it and create a new one.")
        return moved.toRRule(to.localDate(), zone.clock(), allDay = to is LocalDate, floating = to is LocalDateTime)
    }

    private fun kotlinx.datetime.LocalDate.toJava(): LocalDate = LocalDate.of(year, month.number, day)

    /**
     * [this] rule ended just before [at]: an UNTIL replaces any COUNT. It is a date for an all-day [seed],
     * a local time for a floating one and UTC otherwise, as RFC 5545 asks.
     */
    internal fun String.endedBefore(at: JInstant, seed: Temporal): String {
        val kept = split(';').filter { it.isNotBlank() && !RULE_PARTS.containsMatchIn(it) }
        val last = basicUtc(kotlin.time.Instant.fromEpochSeconds(at.epochSecond - 1))
        val until = when (seed) {
            is LocalDate -> basicJavaDate(LocalDate.ofInstant(at, UTC_ZONE).minusDays(1))
            is LocalDateTime -> last.removeSuffix("Z")
            else -> last
        }
        return (kept + "UNTIL=$until").joinToString(";")
    }

    /** [this] rule for the new series that carries on after the first [count] occurrences are gone: a COUNT shrinks, an UNTIL stays. */
    internal fun String.carriedOver(count: Int): String =
        split(';').filter { it.isNotBlank() }.map { part ->
            if (part.startsWith("COUNT=", ignoreCase = true)) "COUNT=${part.substringAfter('=').toInt() - count}" else part
        }.joinToString(";")

    private fun basicJavaDate(date: LocalDate): String = basicDate(kotlinx.datetime.LocalDate(date.year, date.monthValue, date.dayOfMonth))
}
