package net.robog.clarity.shared.calendar

import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.robog.clarity.shared.time.LocalTimes

@Serializable
enum class Frequency {
    @SerialName("daily") DAILY,
    @SerialName("weekly") WEEKLY,
    @SerialName("monthly") MONTHLY,
    @SerialName("yearly") YEARLY,
}

/** Which part of a repeating event an edit or a cancellation touches. */
@Serializable
enum class EventScope {
    /** Only the occurrence that was named. */
    @SerialName("this") THIS,
    /** The named occurrence and every later one. */
    @SerialName("following") FOLLOWING,
    /** Every occurrence, past and future. */
    @SerialName("series") SERIES,
}

/** The [n]th [day] of a month; [n] is 1 to 5, or [LAST]. */
@Serializable
data class NthWeekday(val n: Int, val day: DayOfWeek) {
    init {
        require(n in 1..5 || n == LAST) { "`nth` must be 1 to 5, or -1 for the last" }
    }

    companion object {
        const val LAST = -1
    }
}

/**
 * How an event repeats, as the agent states it. A weekly rule without [days] repeats on the weekday
 * of the start; a monthly one without [nthWeekday] on the day of the month of the start; a yearly one
 * on the date of the start. With neither [until] (a local date, inclusive) nor [count] it never ends.
 */
@Serializable
data class Recurrence(
    val frequency: Frequency,
    val interval: Int = 1,
    val days: List<DayOfWeek> = emptyList(),
    val nthWeekday: NthWeekday? = null,
    val until: LocalDate? = null,
    val count: Int? = null,
) {
    init {
        require(interval >= 1) { "`interval` must be 1 or more" }
        require(days.isEmpty() || frequency == Frequency.WEEKLY) { "`days` only goes with a weekly repeat" }
        require(days.distinct().size == days.size) { "`days` lists a weekday twice" }
        require(nthWeekday == null || frequency == Frequency.MONTHLY) { "`nthWeekday` only goes with a monthly repeat" }
        require(until == null || count == null) { "Give `until` or `count`, not both" }
        require(count == null || count >= 1) { "`count` must be 1 or more" }
    }
}

private val DAY_CODES = mapOf(
    DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE", DayOfWeek.THURSDAY to "TH",
    DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
)

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private val WEEKDAYS = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

private fun List<DayOfWeek>.inWeekOrder() = sortedBy { it.isoDayNumber }

/** `MO`, `TU`, … as RRULE BYDAY spells them. */
fun DayOfWeek.code(): String = DAY_CODES.getValue(this)

/** The weekday a two-letter [code] (any case) stands for, or null. */
fun dayOfWeekFromCode(code: String): DayOfWeek? = DAY_CODES.entries.firstOrNull { it.value.equals(code.trim(), ignoreCase = true) }?.key

private fun two(n: Int) = n.toString().padStart(2, '0')

/** `20261220`: the DATE form RFC 5545 wants for an all-day series. */
fun basicDate(date: LocalDate): String = date.year.toString().padStart(4, '0') + two(date.month.number) + two(date.day)

/** `20261220T235959Z`: [at] in the UTC form RFC 5545 wants in UNTIL for a timed series. */
fun basicUtc(at: Instant): String {
    val t = at.toLocalDateTime(TimeZone.UTC)
    return basicDate(t.date) + "T" + two(t.hour) + two(t.minute) + two(t.second) + "Z"
}

/**
 * The RRULE value (without `RRULE:`) for a series that starts on [start]. [zone] is the clock the
 * series runs on: a local [Recurrence.until] day ends at its last second there, written in UTC as
 * RFC 5545 asks of a timed series; an [allDay] series gets a date and a [floating] one (no zone) a local time.
 */
fun Recurrence.toRRule(start: LocalDate, zone: TimeZone, allDay: Boolean, floating: Boolean = false): String = buildList {
    add("FREQ=${frequency.name}")
    if (interval > 1) add("INTERVAL=$interval")
    if (frequency == Frequency.WEEKLY && interval > 1) add("WKST=MO")
    when (frequency) {
        Frequency.WEEKLY -> add("BYDAY=" + days.ifEmpty { listOf(start.dayOfWeek) }.inWeekOrder().joinToString(",") { it.code() })
        Frequency.MONTHLY -> add(nthWeekday?.let { "BYDAY=${it.n}${it.day.code()}" } ?: "BYMONTHDAY=${start.day}")
        Frequency.DAILY, Frequency.YEARLY -> Unit
    }
    until?.let {
        add(
            "UNTIL=" + when {
                allDay -> basicDate(it)
                floating -> basicDate(it) + "T235959"
                else -> basicUtc(it.atTime(23, 59, 59).toInstant(zone))
            },
        )
    }
    count?.let { add("COUNT=$it") }
}.joinToString(";")

/**
 * The [Recurrence] an RRULE value means, or null when it says more than a [Recurrence] can (BYSETPOS,
 * several days of the month, an hourly rule…). [start] checks rules that name the day of the month or
 * the month against where the series begins; a UTC UNTIL is read as a day on the clock of [zone].
 */
fun parseRRule(text: String, start: LocalDate? = null, zone: TimeZone = TimeZone.UTC): Recurrence? {
    val parts = text.removePrefix("RRULE:").split(';').filter { it.isNotBlank() }.associate {
        it.substringBefore('=').trim().uppercase() to it.substringAfter('=', "").trim()
    }
    val frequency = Frequency.entries.firstOrNull { it.name == parts["FREQ"]?.uppercase() } ?: return null
    if (!setOf("FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY", "BYMONTH", "UNTIL", "COUNT", "WKST").containsAll(parts.keys)) return null
    val interval = parts["INTERVAL"]?.let { it.toIntOrNull() ?: return null } ?: 1
    val count = parts["COUNT"]?.let { it.toIntOrNull() ?: return null }
    val until = parts["UNTIL"]?.let { parseUntil(it, zone) ?: return null }
    val byDay = parts["BYDAY"]?.split(',')?.map { it.trim() }.orEmpty()
    val monthDay = parts["BYMONTHDAY"]?.let { it.toIntOrNull() ?: return null }
    val month = parts["BYMONTH"]?.let { it.toIntOrNull() ?: return null }
    if (start != null && (monthDay != null && monthDay != start.day || month != null && month != start.month.number)) return null
    if (month != null && frequency != Frequency.YEARLY) return null
    if (monthDay != null && frequency != Frequency.MONTHLY && frequency != Frequency.YEARLY) return null
    val days = mutableListOf<DayOfWeek>()
    var nth: NthWeekday? = null
    when (frequency) {
        Frequency.WEEKLY -> byDay.forEach { days += dayOfWeekFromCode(it) ?: return null }
        Frequency.MONTHLY -> if (byDay.isNotEmpty()) {
            val one = byDay.singleOrNull()?.takeIf { monthDay == null } ?: return null
            val day = dayOfWeekFromCode(one.takeLast(2)) ?: return null
            val n = one.dropLast(2).removePrefix("+").toIntOrNull() ?: return null
            nth = if (n in 1..5 || n == NthWeekday.LAST) NthWeekday(n, day) else return null
        }
        Frequency.DAILY, Frequency.YEARLY -> if (byDay.isNotEmpty()) return null
    }
    return try {
        Recurrence(frequency, interval, days, nth, until.takeIf { count == null }, count)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun parseUntil(value: String, zone: TimeZone): LocalDate? {
    val v = value.trim().uppercase()
    if (v.length < 8 || !v.take(8).all(Char::isDigit)) return null
    val date = LocalDate(v.take(4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt())
    if (v.length != 16 || !v.endsWith("Z")) return date
    val time = v.substring(9, 15)
    if (!time.all(Char::isDigit)) return null
    val utc = date.atTime(time.take(2).toInt(), time.substring(2, 4).toInt(), time.substring(4, 6).toInt()).toInstant(TimeZone.UTC)
    return utc.toLocalDateTime(zone).date
}

private fun ordinal(n: Int): String = when {
    n % 100 in 11..13 -> "${n}th"
    n % 10 == 1 -> "${n}st"
    n % 10 == 2 -> "${n}nd"
    n % 10 == 3 -> "${n}rd"
    else -> "${n}th"
}

private fun joinWords(items: List<String>): String =
    if (items.size < 2) items.joinToString() else items.dropLast(1).joinToString(", ") + " and " + items.last()

private fun DayOfWeek.fullName() = name.lowercase().replaceFirstChar { it.uppercase() }

/** `Dec 20`, with the year when it is not [start]'s. */
private fun LocalDate.short(start: LocalDate?) =
    "${MONTHS[month.number - 1]} $day" + if (start != null && start.year != year) " $year" else ""

/**
 * The rule in plain words: "every Mon, Wed and Fri until Dec 20", "monthly on the 2nd Tuesday",
 * "yearly". [start] fills in what the rule leaves to it (the weekday or the day of the month) and
 * says whether an end date needs its year.
 */
fun Recurrence.describe(start: LocalDate? = null): String {
    val every = interval.takeIf { it > 1 }
    val base = when (frequency) {
        Frequency.DAILY -> if (every == null) "daily" else "every $every days"
        Frequency.WEEKLY -> {
            val on = days.ifEmpty { listOfNotNull(start?.dayOfWeek) }.inWeekOrder()
            when {
                on.isEmpty() -> if (every == null) "weekly" else "every $every weeks"
                every == null && on == WEEKDAYS -> "every weekday"
                every == null -> "every " + joinWords(on.map(LocalTimes::weekday))
                else -> "every $every weeks on " + joinWords(on.map(LocalTimes::weekday))
            }
        }
        Frequency.MONTHLY -> {
            val lead = if (every == null) "monthly" else "every $every months"
            val on = nthWeekday?.let { (if (it.n == NthWeekday.LAST) "last" else ordinal(it.n)) + " " + it.day.fullName() }
                ?: start?.let { ordinal(it.day) }
            if (on == null) lead else "$lead on the $on"
        }
        Frequency.YEARLY -> if (every == null) "yearly" else "every $every years"
    }
    return base + when {
        until != null -> " until " + until.short(start)
        count != null -> " for $count occurrences"
        else -> ""
    }
}

/**
 * The rule once the series' start moves from [from] to [to] (every occurrence moves by the same
 * days): weekdays and the end date move with it, an "nth weekday" is recomputed for the new date.
 * Null when that is not expressible: a "last weekday" that the new date is not in the last week
 * of its month for, or a every-other-week rule whose days would land in different weeks.
 */
fun Recurrence.movedTo(from: LocalDate, to: LocalDate): Recurrence? {
    val delta = from.daysUntil(to)
    fun DayOfWeek.moved() = DayOfWeek((((isoDayNumber - 1 + delta) % 7 + 7) % 7) + 1)
    fun weekIndex(day: DayOfWeek) = (day.isoDayNumber - 1 + delta).let { (it - ((it % 7) + 7) % 7) / 7 }
    if (frequency == Frequency.WEEKLY && interval > 1 && days.map(::weekIndex).distinct().size > 1) return null
    val nth = nthWeekday?.let {
        val day = it.day.moved()
        if (day != to.dayOfWeek) return null
        when {
            it.n != NthWeekday.LAST -> NthWeekday((to.day - 1) / 7 + 1, day)
            to.inLastWeek() -> NthWeekday(NthWeekday.LAST, day)
            else -> return null
        }
    }
    return copy(days = days.map { it.moved() }, nthWeekday = nth, until = until?.plus(DatePeriod(days = delta)))
}

private fun LocalDate.inLastWeek(): Boolean = plus(DatePeriod(days = 7)).month != month

private fun NthWeekday.fits(date: LocalDate): Boolean =
    date.dayOfWeek == day && if (n == NthWeekday.LAST) date.inLastWeek() else (date.day - 1) / 7 + 1 == n

/**
 * Why a series starting on [start] would begin off its own pattern (calendars then list the start as
 * an extra occurrence), or null when it fits. Also catches an end before the start.
 */
fun Recurrence.startProblem(start: LocalDate): String? = when {
    days.isNotEmpty() && start.dayOfWeek !in days ->
        "`start` falls on a ${LocalTimes.weekday(start.dayOfWeek)}, which is not in `days`. Start on one of the days."
    nthWeekday != null && !nthWeekday.fits(start) ->
        "`start` is not that weekday of the month (${nthWeekday.n} ${LocalTimes.weekday(nthWeekday.day)}). Start on the first such day."
    until != null && until < start -> "`until` is before `start`."
    else -> null
}
