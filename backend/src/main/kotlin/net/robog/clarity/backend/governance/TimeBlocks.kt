package net.robog.clarity.backend.governance

import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.shared.governance.TimeBlock
import net.robog.clarity.shared.time.ScheduleClock

/** One concrete occurrence of a recurring [TimeBlock]: `[start, end)`. */
data class Occurrence(val block: TimeBlock, val start: Instant, val end: Instant) {
    operator fun contains(at: Instant): Boolean = at >= start && at < end
    fun overlaps(from: Instant, until: Instant): Boolean = from < end && until > start
}

/** A zone id that falls back to UTC instead of throwing, for settings the person typed. */
fun zoneOrUtc(id: String): TimeZone = net.robog.clarity.shared.time.zoneOrUtc(id)

/**
 * Occurrences of stored [blocks] that start on any date from [from] to [until] (inclusive), on the
 * clock of the home zone [zone] (see [ScheduleClock]). A block whose end is not after its start
 * crosses midnight and ends on the next date. Days are ISO (1 = Monday) home-zone days and refer to
 * the date the block *starts* on.
 */
fun occurrences(blocks: List<TimeBlock>, from: LocalDate, until: LocalDate, zone: TimeZone): List<Occurrence> {
    val result = ArrayList<Occurrence>()
    var date = from
    while (date <= until) {
        for (block in blocks) {
            if (date.dayOfWeek.isoDayNumber !in block.days) continue
            val endDate = if (block.end <= block.start) date.plus(1, DateTimeUnit.DAY) else date
            result += Occurrence(block, ScheduleClock.occurrence(date, block.start, zone), ScheduleClock.occurrence(endDate, block.end, zone))
        }
        date = date.plus(1, DateTimeUnit.DAY)
    }
    return result
}

/** Occurrences that could touch `[from, until)`: includes the previous date for midnight-crossing blocks. */
private fun candidates(blocks: List<TimeBlock>, from: Instant, until: Instant, zone: TimeZone): List<Occurrence> =
    occurrences(
        blocks,
        from.toLocalDateTime(zone).date.plus(-1, DateTimeUnit.DAY),
        until.toLocalDateTime(zone).date.plus(1, DateTimeUnit.DAY),
        zone,
    )

/** The first protected block overlapping `[from, until)`, if any. */
fun protectedOverlap(blocks: List<TimeBlock>, from: Instant, until: Instant, zone: TimeZone): TimeBlock? =
    candidates(blocks, from, until, zone).filter { it.overlaps(from, until) }.minByOrNull { it.start }?.block

/**
 * If [at] is inside a protected block, the instant the protection ends; back-to-back or
 * overlapping blocks are merged so "until" is when the person is genuinely reachable again.
 */
fun protectedUntil(blocks: List<TimeBlock>, at: Instant, zone: TimeZone): Instant? {
    val all = candidates(blocks, at, at + MERGE_HORIZON, zone)
    var end = all.filter { at in it }.maxOfOrNull { it.end } ?: return null
    while (true) {
        val next = all.filter { end in it }.maxOfOrNull { it.end } ?: return end
        if (next <= end) return end
        end = next
    }
}

private val MERGE_HORIZON = 72.hours
