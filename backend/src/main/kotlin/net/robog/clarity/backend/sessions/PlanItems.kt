package net.robog.clarity.backend.sessions

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.robog.clarity.backend.persistence.parseWire
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.persistence.wire
import net.robog.clarity.shared.time.ScheduleClock
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** Where a plan item stands. [REPLACED] is an item the person's plan of the day dropped again the same day. */
@Serializable
enum class PlanItemState {
    @SerialName("open") OPEN,
    @SerialName("done") DONE,
    @SerialName("dropped") DROPPED,
    @SerialName("replaced") REPLACED,
}

/** What a plan was for: a redone plan replaces the items of its own kind only. */
@Serializable
enum class PlanSource {
    @SerialName("priority") PRIORITY,
    @SerialName("parked") PARKED,
}

/**
 * Something the person put on a day's plan (a priority or a parked item), followed across days.
 * [daysCarried] is the number of days in a row it has been on a plan, so a new one is 1. [startedOn]
 * is the day the person said they were starting it (no more check-ins that day). [firstStep] is the
 * small first move agreed for it, on the day [firstStepOn]. [prevLastDate] and [prevDaysCarried] are
 * where it stood before the latest plan moved it to a new day (to put it back when that plan is redone).
 */
data class PlanItem(
    val id: String,
    val text: String,
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    val daysCarried: Int = 1,
    val state: PlanItemState = PlanItemState.OPEN,
    val startedOn: LocalDate? = null,
    val firstStep: String? = null,
    val firstStepOn: LocalDate? = null,
    val source: PlanSource = PlanSource.PRIORITY,
    val prevLastDate: LocalDate? = null,
    val prevDaysCarried: Int? = null,
) {
    /** The day number it is on when planned on [date] (2 days carried, planned again: day 3). */
    fun dayOn(date: LocalDate): Int = if (lastDate == date) daysCarried else daysCarried + 1
}

/** What the model says it put on today's plan; [continues] is the id of the plan item it carries on, when it knows. */
data class Planned(val text: String, val continues: String? = null)

/** A plan item that has come back this many days in a row counts as stuck. */
const val STUCK_FROM_DAY = 3

/** An open item not planned for longer than this is forgotten, not carried over (the person moved on). */
const val CARRY_WINDOW_DAYS = 3

private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}]+")
private val FILLER = setOf("the", "a", "an", "to", "my", "for", "of", "and", "on", "with", "up", "in", "at", "do")

/** Pure: [text] as comparable words (lower case, no punctuation, no filler words). */
fun wordsOf(text: String): List<String> =
    text.lowercase().replace("’", "").replace("'", "").split(WORD_SPLIT).filter { it.isNotEmpty() && it !in FILLER }

private fun String.hasDigit() = any { it.isDigit() }

/**
 * Pure: how close two wordings are, 0 when they are not the same thing. Words with digits (Q3, 2026)
 * must be the same on both sides. The shorter wording of up to three words must be entirely in the
 * other (and a single word only matches itself); a longer one needs four words in five. The score is
 * the share of all words they have in common.
 */
fun wordingScore(a: String, b: String): Double {
    val x = wordsOf(a).toSet()
    val y = wordsOf(b).toSet()
    if (x.isEmpty() || y.isEmpty()) return 0.0
    if (x == y) return 1.0
    if (x.filter { it.hasDigit() }.toSet() != y.filter { it.hasDigit() }.toSet()) return 0.0
    val shared = x.intersect(y).size
    val shorter = minOf(x.size, y.size)
    val longer = maxOf(x.size, y.size)
    val enough = if (shorter <= 3) shorter >= 2 && shared == shorter else shared * 5 >= shorter * 4
    return if (enough && shared * 2 >= longer) shared.toDouble() / x.union(y).size else 0.0
}

/** Pure: whether two wordings name the same item (see [wordingScore]). */
fun sameWording(a: String, b: String): Boolean = wordingScore(a, b) > 0.0

/**
 * Pure: the plan items after [today]'s items are put on the plan of [date] as [source]. Each of [today]
 * continues one of the [open] items when it names its id in `continues`, else the open item whose wording
 * is closest (see [wordingScore]; ties go to the lower id, so the order of [open] never matters); an item
 * is continued at most once. A continued item moves to [date] and counts one more day (an item already
 * planned for [date] stays as it is, so planning twice in a day changes nothing), remembering where it was.
 * Open items last planned more than [CARRY_WINDOW_DAYS] days ago are not candidates. Anything else is new,
 * with an id from [newId]. The result lists [today]'s items in order.
 */
fun carryOver(open: List<PlanItem>, today: List<Planned>, date: LocalDate, newId: () -> String, source: PlanSource = PlanSource.PRIORITY): List<PlanItem> {
    val candidates = open.filter { it.state == PlanItemState.OPEN && it.lastDate <= date && it.lastDate.daysUntil(date) <= CARRY_WINDOW_DAYS }
    val taken = mutableSetOf<String>()
    // Ids first, so a wording match never takes an item the model named.
    val byId = today.map { p -> p.continues?.trim()?.let { id -> candidates.firstOrNull { it.id == id && taken.add(it.id) } } }
    return today.mapIndexed { i, planned ->
        val previous = byId[i] ?: candidates.asSequence().filter { it.id !in taken }
            .map { it to wordingScore(it.text, planned.text) }.filter { it.second > 0.0 }
            .sortedWith(compareByDescending<Pair<PlanItem, Double>> { it.second }.thenBy { it.first.id })
            .firstOrNull()?.first?.also { taken += it.id }
        when {
            previous == null -> PlanItem(newId(), planned.text.trim(), date, date, source = source)
            previous.lastDate == date -> previous
            else -> previous.copy(
                text = planned.text.trim(), lastDate = date, daysCarried = previous.daysCarried + 1, startedOn = null, source = source,
                prevLastDate = previous.lastDate, prevDaysCarried = previous.daysCarried,
            )
        }
    }
}

/**
 * Pure: the items of [open] that an earlier plan of [date] (as [source]) put on the day but the plan now in
 * [kept] no longer has, put back as they were: an item that was carried from an earlier day returns to
 * that day and its count; one that began today is [PlanItemState.REPLACED]. Other days' items, other kinds
 * and kept items are not touched.
 */
fun replaceUnplanned(open: List<PlanItem>, kept: List<PlanItem>, date: LocalDate, source: PlanSource): List<PlanItem> {
    val keptIds = kept.map { it.id }.toSet()
    return open.filter { it.state == PlanItemState.OPEN && it.lastDate == date && it.source == source && it.id !in keptIds }.map { item ->
        val before = item.prevLastDate
        if (before != null && item.prevDaysCarried != null) {
            item.copy(lastDate = before, daysCarried = item.prevDaysCarried, prevLastDate = null, prevDaysCarried = null)
        } else {
            item.copy(state = PlanItemState.REPLACED)
        }
    }
}

/**
 * Pure: the open items that will be on their third day or later if planned again on [date]: what a
 * morning call's preparation lists as stuck. Most days carried first.
 */
fun stuckItems(open: List<PlanItem>, date: LocalDate): List<PlanItem> =
    open.filter { it.state == PlanItemState.OPEN && it.lastDate < date && it.lastDate.daysUntil(date) <= CARRY_WINDOW_DAYS && it.dayOn(date) >= STUCK_FROM_DAY }
        .sortedWith(compareByDescending<PlanItem> { it.daysCarried }.thenBy { it.id })

/** Pure: "first step: x" when it was agreed on [date], "earlier first step: x" when it was agreed another day; null with no step. */
fun firstStepNote(item: PlanItem, date: LocalDate): String? =
    item.firstStep?.takeIf { it.isNotBlank() }?.let { (if (item.firstStepOn == date) "first step: " else "earlier first step: ") + it.trim() }

/** Pure: "stuck: spec, day 3 (plan item ...)" for the lead's preparation, as of the call on [date]. */
fun stuckLine(item: PlanItem, date: LocalDate): String =
    "stuck: ${item.text}, day ${item.dayOn(date)} (plan item ${item.id}" + (firstStepNote(item, date)?.let { ", $it" } ?: "") + ")"

/** A time of day read from a cue; [bare] when it had no am/pm and could be morning or evening. */
data class CueTime(val time: LocalTime, val bare: Boolean)

private val NOON = Regex("""\bnoon\b""", RegexOption.IGNORE_CASE)
private val MIDNIGHT = Regex("""\bmidnight\b""", RegexOption.IGNORE_CASE)
private val RANGE = Regex(
    """\bbetween\s+(\d{1,2})(?:[:.]([0-5]\d))?\s*(?:([ap])\.?m\b\.?)?\s+and\s+(\d{1,2})(?:[:.]([0-5]\d))?\s*([ap])\.?m\b""",
    RegexOption.IGNORE_CASE,
)
private val CLOCK = Regex("""(?<![\d:.$])([01]?\d|2[0-3])[:.]([0-5]\d)(?![\d:])(?!\.\d)(?:\s*([ap])\.?m\b)?""", RegexOption.IGNORE_CASE)
private val HOUR_AMPM = Regex("""(?<![\d:.$])(1[0-2]|0?[1-9])\s*([ap])\.?m\b""", RegexOption.IGNORE_CASE)

private fun to24(hour12: Int, pm: Boolean): Int = (hour12 % 12) + if (pm) 12 else 0

private fun suffixed(hour: Int, minute: Int, suffix: String): CueTime? =
    if (hour in 1..12) CueTime(LocalTime(to24(hour, suffix.lowercase() == "p"), minute), bare = false) else null

/**
 * Pure: the time of day an if-then cue names, or null when it names none ("Dana emails"). Understands
 * "9:30", "9.30", "14:00", "3pm", "noon", "midnight" and "between 9 and 10am" (its start). A bare
 * 1:00 to 6:59 is the afternoon; a bare 7:00 to 12:59 stays as written, flagged [CueTime.bare]. The
 * first time in the cue wins.
 */
fun parseCueTime(cue: String): CueTime? {
    val found = mutableListOf<Pair<Int, CueTime?>>()
    NOON.find(cue)?.let { found += it.range.first to CueTime(LocalTime(12, 0), false) }
    MIDNIGHT.find(cue)?.let { found += it.range.first to CueTime(LocalTime(0, 0), false) }
    RANGE.find(cue)?.let { m ->
        val g = m.groupValues
        val suffix = g[3].ifEmpty { g[6] }
        found += m.range.first to suffixed(g[1].toInt(), g[2].ifEmpty { "0" }.toInt(), suffix)
    }
    CLOCK.find(cue)?.let { m ->
        val g = m.groupValues
        val hour = g[1].toInt()
        val minute = g[2].toInt()
        found += m.range.first to when {
            g[3].isNotEmpty() -> suffixed(hour, minute, g[3])
            hour in 1..6 -> CueTime(LocalTime(hour + 12, minute), false)
            hour in 7..12 -> CueTime(LocalTime(hour, minute), true)
            else -> CueTime(LocalTime(hour, minute), false)
        }
    }
    HOUR_AMPM.find(cue)?.let { m -> found += m.range.first to suffixed(m.groupValues[1].toInt(), 0, m.groupValues[2]) }
    return found.minByOrNull { it.first }?.second
}

/** Pure: the time of day a cue names (see [parseCueTime]). */
fun clockTimeIn(cue: String): LocalTime? = parseCueTime(cue)?.time

/**
 * Pure: the moment a cue means on [date]: its time on the person's clock. A bare time that has already
 * passed at [now] means the same time in the evening when that is still [date] and still ahead.
 */
fun cueMoment(cue: String, date: LocalDate, zone: TimeZone, now: Instant): Instant? {
    val cueTime = parseCueTime(cue) ?: return null
    val at = ScheduleClock.occurrence(date, cueTime.time, zone)
    if (cueTime.bare && at <= now) {
        val evening = at + 12.hours
        if (evening > now && ScheduleClock.homeDate(evening, zone) == date) return evening
    }
    return at
}

private fun ResultRow.toItem() = PlanItem(
    id = this[PlanItems.id].toString(),
    text = this[PlanItems.text],
    firstDate = this[PlanItems.firstDate],
    lastDate = this[PlanItems.lastDate],
    daysCarried = this[PlanItems.daysCarried],
    state = parseWire<PlanItemState>(this[PlanItems.state]) ?: PlanItemState.OPEN,
    startedOn = this[PlanItems.startedOn],
    firstStep = this[PlanItems.firstStep],
    firstStepOn = this[PlanItems.firstStepOn],
    source = parseWire<PlanSource>(this[PlanItems.planSource]) ?: PlanSource.PRIORITY,
    prevLastDate = this[PlanItems.prevLastDate],
    prevDaysCarried = this[PlanItems.prevDaysCarried],
)

/** `plan_item`: the durable plan items. */
@Inject
@SingleIn(AppScope::class)
class PlanItemStore(private val database: Database) {
    suspend fun get(id: String): PlanItem? {
        val uuid = runCatching { Uuid.parse(id.trim()) }.getOrNull() ?: return null
        return database.tx { PlanItems.selectAll().where { PlanItems.id eq uuid }.singleOrNull()?.toItem() }
    }

    suspend fun open(): List<PlanItem> = database.tx {
        PlanItems.selectAll().where { PlanItems.state eq PlanItemState.OPEN.wire() }.map { it.toItem() }
    }

    /**
     * Puts [planned] on the plan of [date] as [source] (see [carryOver]), puts back what an earlier plan of
     * the day had that this one no longer has (see [replaceUnplanned]), and stores both; returns [planned]'s items in order.
     */
    suspend fun plan(date: LocalDate, planned: List<Planned>, source: PlanSource = PlanSource.PRIORITY): List<PlanItem> = database.tx {
        val open = PlanItems.selectAll().where { PlanItems.state eq PlanItemState.OPEN.wire() }.forUpdate().map { it.toItem() }
        val items = carryOver(open, planned, date, { Uuid.random().toString() }, source)
        val known = open.associateBy { it.id }
        (items + replaceUnplanned(open, items, date, source)).forEach { item ->
            val before = known[item.id]
            if (before == null) {
                PlanItems.insert {
                    it[id] = Uuid.parse(item.id)
                    it[text] = item.text
                    it[firstDate] = item.firstDate
                    it[lastDate] = item.lastDate
                    it[daysCarried] = item.daysCarried
                    it[state] = item.state.wire()
                    it[planSource] = item.source.wire()
                }
            } else if (before != item) {
                PlanItems.update({ PlanItems.id eq Uuid.parse(item.id) }) {
                    it[text] = item.text
                    it[lastDate] = item.lastDate
                    it[daysCarried] = item.daysCarried
                    it[state] = item.state.wire()
                    it[startedOn] = item.startedOn
                    it[planSource] = item.source.wire()
                    it[prevLastDate] = item.prevLastDate
                    it[prevDaysCarried] = item.prevDaysCarried
                }
            }
        }
        items
    }

    /** Applies [change] to the item under a row lock; null when there is no such item. */
    suspend fun update(id: String, change: (PlanItem) -> PlanItem): PlanItem? {
        val uuid = runCatching { Uuid.parse(id.trim()) }.getOrNull() ?: return null
        return database.tx {
            val before = PlanItems.selectAll().where { PlanItems.id eq uuid }.forUpdate().singleOrNull()?.toItem() ?: return@tx null
            val after = change(before)
            PlanItems.update({ PlanItems.id eq uuid }) {
                it[state] = after.state.wire()
                it[startedOn] = after.startedOn
                it[firstStep] = after.firstStep
                it[firstStepOn] = after.firstStepOn
            }
            after
        }
    }
}
