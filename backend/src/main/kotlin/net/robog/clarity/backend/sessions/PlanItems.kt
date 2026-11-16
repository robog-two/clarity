package net.robog.clarity.backend.sessions

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.daysUntil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.robog.clarity.backend.persistence.parseWire
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.persistence.wire
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** Where a plan item stands. */
@Serializable
enum class PlanItemState {
    @SerialName("open") OPEN,
    @SerialName("done") DONE,
    @SerialName("dropped") DROPPED,
}

/**
 * Something the person put on a day's plan (a priority or a parked item), followed across days.
 * [daysCarried] is the number of days in a row it has been on a plan, so a new one is 1. [startedOn]
 * is the day the person said they were starting it (no more check-ins that day). [firstStep] is the
 * small first move agreed for it.
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

/** Pure: whether two wordings name the same item: the same words, or two thirds of the shorter one found in the other. */
fun sameWording(a: String, b: String): Boolean {
    val x = wordsOf(a).toSet()
    val y = wordsOf(b).toSet()
    if (x.isEmpty() || y.isEmpty()) return false
    if (x == y) return true
    val shared = x.intersect(y).size
    val shorter = minOf(x.size, y.size)
    return shorter >= 2 && shared * 3 >= shorter * 2 && shared * 2 >= maxOf(x.size, y.size)
}

/**
 * Pure: the plan items after [today]'s items are put on the plan of [date]. Each of [today] continues
 * one of the [open] items when it names its id in `continues`, else when its wording is close to one
 * (see [sameWording]); an item is continued at most once. A continued item moves to [date] and
 * counts one more day (an item already planned for [date] stays as it is, so planning twice in a
 * day changes nothing). Open items last planned more than [CARRY_WINDOW_DAYS] days ago are not
 * candidates. Anything else is new, with an id from [newId]. The result lists [today]'s items in order.
 */
fun carryOver(open: List<PlanItem>, today: List<Planned>, date: LocalDate, newId: () -> String): List<PlanItem> {
    val candidates = open.filter { it.state == PlanItemState.OPEN && it.lastDate <= date && it.lastDate.daysUntil(date) <= CARRY_WINDOW_DAYS }
    val taken = mutableSetOf<String>()
    // Ids first, so a wording match never takes an item the model named.
    val byId = today.map { p -> p.continues?.trim()?.let { id -> candidates.firstOrNull { it.id == id && taken.add(it.id) } } }
    return today.mapIndexed { i, planned ->
        val previous = byId[i] ?: candidates.firstOrNull { it.id !in taken && sameWording(it.text, planned.text) }?.also { taken += it.id }
        when {
            previous == null -> PlanItem(newId(), planned.text.trim(), date, date)
            previous.lastDate == date -> previous
            else -> previous.copy(text = planned.text.trim(), lastDate = date, daysCarried = previous.daysCarried + 1, startedOn = null)
        }
    }
}

/**
 * Pure: the open items that will be on their third day or later if planned again on [date]: what a
 * morning call's preparation lists as stuck. Most days carried first.
 */
fun stuckItems(open: List<PlanItem>, date: LocalDate): List<PlanItem> =
    open.filter { it.state == PlanItemState.OPEN && it.lastDate < date && it.lastDate.daysUntil(date) <= CARRY_WINDOW_DAYS && it.dayOn(date) >= STUCK_FROM_DAY }
        .sortedByDescending { it.daysCarried }

/** Pure: "stuck: spec, day 3 (plan item ...)" for the lead's preparation. */
fun stuckLine(item: PlanItem, date: LocalDate): String =
    "stuck: ${item.text}, day ${item.dayOn(date)} (plan item ${item.id}" + (item.firstStep?.let { ", first step: $it" } ?: "") + ")"

private val CLOCK_24 = Regex("""(?<![\d:])([01]?\d|2[0-3]):([0-5]\d)(?![\d:])(?:\s*([ap])\.?m\b)?""", RegexOption.IGNORE_CASE)
private val CLOCK_AMPM = Regex("""(?<![\d:])(1[0-2]|0?[1-9])\s*([ap])\.?m\b""", RegexOption.IGNORE_CASE)

/**
 * Pure: the clock time an if-then cue names ("it's 9:30", "it's 3pm", "at 14:00"), or null when it
 * has none ("Dana emails"). The first time in the cue wins.
 */
fun clockTimeIn(cue: String): LocalTime? {
    val colon = CLOCK_24.find(cue)
    val ampm = CLOCK_AMPM.find(cue)
    val first = listOfNotNull(colon, ampm).minByOrNull { it.range.first } ?: return null
    return if (first === colon) {
        val hour = first.groupValues[1].toInt()
        val minute = first.groupValues[2].toInt()
        val suffix = first.groupValues[3].lowercase().firstOrNull()
        when {
            suffix == null -> LocalTime(hour, minute)
            hour !in 1..12 -> null
            else -> LocalTime(to24(hour, suffix == 'p'), minute)
        }
    } else {
        LocalTime(to24(first.groupValues[1].toInt(), first.groupValues[2].lowercase() == "p"), 0)
    }
}

private fun to24(hour12: Int, pm: Boolean): Int = (hour12 % 12) + if (pm) 12 else 0

private fun ResultRow.toItem() = PlanItem(
    id = this[PlanItems.id].toString(),
    text = this[PlanItems.text],
    firstDate = this[PlanItems.firstDate],
    lastDate = this[PlanItems.lastDate],
    daysCarried = this[PlanItems.daysCarried],
    state = parseWire<PlanItemState>(this[PlanItems.state]) ?: PlanItemState.OPEN,
    startedOn = this[PlanItems.startedOn],
    firstStep = this[PlanItems.firstStep],
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

    /** Puts [planned] on the plan of [date] (see [carryOver]) and stores the result; returns the items in order. */
    suspend fun plan(date: LocalDate, planned: List<Planned>): List<PlanItem> = database.tx {
        val open = PlanItems.selectAll().where { PlanItems.state eq PlanItemState.OPEN.wire() }.forUpdate().map { it.toItem() }
        val items = carryOver(open, planned, date) { Uuid.random().toString() }
        val known = open.associateBy { it.id }
        items.forEach { item ->
            val before = known[item.id]
            if (before == null) {
                PlanItems.insert {
                    it[id] = Uuid.parse(item.id)
                    it[text] = item.text
                    it[firstDate] = item.firstDate
                    it[lastDate] = item.lastDate
                    it[daysCarried] = item.daysCarried
                    it[state] = item.state.wire()
                }
            } else if (before != item) {
                PlanItems.update({ PlanItems.id eq Uuid.parse(item.id) }) {
                    it[text] = item.text
                    it[lastDate] = item.lastDate
                    it[daysCarried] = item.daysCarried
                    it[startedOn] = item.startedOn
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
            }
            after
        }
    }
}
