package net.robog.clarity.backend.agent

import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours
import net.robog.clarity.shared.noun.CalendarEventPayload

/**
 * One calendar event as far as being busy goes. [free] is for an event marked free (the payload
 * has no such field yet, so [busyOf] never sets it).
 */
data class BusyEvent(val start: Instant, val end: Instant, val allDay: Boolean = false, val free: Boolean = false)

/** Pure: a stored event as a [BusyEvent]. */
fun busyOf(event: CalendarEventPayload) = BusyEvent(event.start, event.end, event.allDay)

/** A timed event longer than this is a backdrop (a conference day, a long trip), not something the person is busy in. */
val LONG_EVENT: Duration = 5.hours

/**
 * Pure: the busy spans of [events]. Only timed events of at most [LONG_EVENT] are busy. All-day
 * events (single- or multi-day), longer ones, events marked free, and empty or backwards spans are not.
 */
internal fun busySpans(events: List<BusyEvent>): List<Pair<Instant, Instant>> =
    events.filter { !it.free && !it.allDay && it.end > it.start && it.end - it.start <= LONG_EVENT }.map { it.start to it.end }

/**
 * Pure: the earliest instant at or after [after] from which the person has no busy event for at
 * least [minGap], and which is not after [until]; null when there is none. Overlapping and
 * back-to-back events are one busy stretch, so no gap lies between them. Past the last event the
 * calendar is free, so the slot may be that event's end. Events are not held back: this only
 * finds a time, it never blocks anything.
 */
fun nextFreeStart(events: List<BusyEvent>, after: Instant, minGap: Duration, until: Instant): Instant? {
    var cursor = after
    for ((start, end) in busySpans(events).sortedBy { it.first }) {
        if (end <= cursor) continue
        if (start - cursor >= minGap) break
        cursor = end
    }
    return cursor.takeIf { it <= until }
}
