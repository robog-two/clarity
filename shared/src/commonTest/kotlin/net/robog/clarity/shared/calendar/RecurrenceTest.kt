package net.robog.clarity.shared.calendar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import net.robog.clarity.shared.ClarityJson

class RecurrenceTest {
    private val monday = LocalDate(2026, 10, 26)
    private val newYork = TimeZone.of("America/New_York")
    private fun rrule(r: Recurrence, start: LocalDate = monday, allDay: Boolean = false) = r.toRRule(start, newYork, allDay)

    @Test fun eachFrequencyMapsToItsRule() {
        assertEquals("FREQ=DAILY", rrule(Recurrence(Frequency.DAILY)))
        assertEquals("FREQ=DAILY;INTERVAL=3", rrule(Recurrence(Frequency.DAILY, interval = 3)))
        assertEquals("FREQ=WEEKLY;BYDAY=MO", rrule(Recurrence(Frequency.WEEKLY)))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=26", rrule(Recurrence(Frequency.MONTHLY)))
        assertEquals("FREQ=YEARLY", rrule(Recurrence(Frequency.YEARLY)))
    }

    @Test fun weeklyDaysComeOutInWeekOrder() {
        val r = Recurrence(Frequency.WEEKLY, days = listOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", rrule(r))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=MO,WE,FR", rrule(r.copy(interval = 2)))
    }

    @Test fun monthlyByNthAndLastWeekday() {
        assertEquals("FREQ=MONTHLY;BYDAY=2TU", rrule(Recurrence(Frequency.MONTHLY, nthWeekday = NthWeekday(2, DayOfWeek.TUESDAY))))
        assertEquals("FREQ=MONTHLY;BYDAY=-1FR", rrule(Recurrence(Frequency.MONTHLY, nthWeekday = NthWeekday(NthWeekday.LAST, DayOfWeek.FRIDAY))))
    }

    @Test fun untilIsTheEndOfTheLocalDayInUtcOrADateWhenAllDay() {
        val until = LocalDate(2026, 12, 20)
        assertEquals("FREQ=DAILY;UNTIL=20261221T045959Z", rrule(Recurrence(Frequency.DAILY, until = until)))
        assertEquals("FREQ=DAILY;UNTIL=20261220", rrule(Recurrence(Frequency.DAILY, until = until), allDay = true))
        assertEquals("FREQ=DAILY;COUNT=5", rrule(Recurrence(Frequency.DAILY, count = 5)))
    }

    @Test fun invalidRulesAreRefusedWithAMessage() {
        assertFailsWith<IllegalArgumentException> { Recurrence(Frequency.DAILY, interval = 0) }
        assertFailsWith<IllegalArgumentException> { Recurrence(Frequency.DAILY, days = listOf(DayOfWeek.MONDAY)) }
        assertFailsWith<IllegalArgumentException> { Recurrence(Frequency.WEEKLY, days = listOf(DayOfWeek.MONDAY, DayOfWeek.MONDAY)) }
        assertFailsWith<IllegalArgumentException> { Recurrence(Frequency.WEEKLY, nthWeekday = NthWeekday(1, DayOfWeek.MONDAY)) }
        assertFailsWith<IllegalArgumentException> { Recurrence(Frequency.DAILY, until = monday, count = 3) }
        assertFailsWith<IllegalArgumentException> { Recurrence(Frequency.DAILY, count = 0) }
        assertFailsWith<IllegalArgumentException> { NthWeekday(6, DayOfWeek.MONDAY) }
        assertFailsWith<IllegalArgumentException> { NthWeekday(0, DayOfWeek.MONDAY) }
        assertEquals("Give `until` or `count`, not both", assertFailsWith<IllegalArgumentException> { Recurrence(Frequency.DAILY, until = monday, count = 3) }.message)
    }

    @Test fun describesInPlainWords() {
        assertEquals("daily", Recurrence(Frequency.DAILY).describe())
        assertEquals("every 3 days", Recurrence(Frequency.DAILY, interval = 3).describe())
        val mwf = Recurrence(Frequency.WEEKLY, days = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), until = LocalDate(2026, 12, 20))
        assertEquals("every Mon, Wed and Fri until Dec 20", mwf.describe(monday))
        assertEquals("every Mon and Wed", Recurrence(Frequency.WEEKLY, days = listOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY)).describe())
        assertEquals("every 2 weeks on Mon", Recurrence(Frequency.WEEKLY, interval = 2).describe(monday))
        assertEquals("weekly", Recurrence(Frequency.WEEKLY).describe())
        assertEquals("every weekday", Recurrence(Frequency.WEEKLY, days = listOf(1, 2, 3, 4, 5).map { DayOfWeek(it) }).describe())
        assertEquals("monthly on the 2nd Tuesday", Recurrence(Frequency.MONTHLY, nthWeekday = NthWeekday(2, DayOfWeek.TUESDAY)).describe())
        assertEquals("monthly on the last Friday", Recurrence(Frequency.MONTHLY, nthWeekday = NthWeekday(NthWeekday.LAST, DayOfWeek.FRIDAY)).describe())
        assertEquals("monthly on the 22nd", Recurrence(Frequency.MONTHLY).describe(LocalDate(2026, 10, 22)))
        assertEquals("monthly on the 11th", Recurrence(Frequency.MONTHLY).describe(LocalDate(2026, 10, 11)))
        assertEquals("every 3 months on the 3rd", Recurrence(Frequency.MONTHLY, interval = 3).describe(LocalDate(2026, 10, 3)))
        assertEquals("yearly", Recurrence(Frequency.YEARLY).describe())
        assertEquals("every 2 years for 4 occurrences", Recurrence(Frequency.YEARLY, interval = 2, count = 4).describe())
    }

    @Test fun anEndInAnotherYearNamesTheYear() {
        val r = Recurrence(Frequency.DAILY, until = LocalDate(2027, 1, 5))
        assertEquals("daily until Jan 5 2027", r.describe(monday))
        assertEquals("daily until Jan 5", r.describe())
    }

    @Test fun readsBackWhatItWrites() {
        val rules = listOf(
            Recurrence(Frequency.DAILY, interval = 2, count = 4),
            Recurrence(Frequency.WEEKLY, interval = 2, days = listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), until = LocalDate(2026, 12, 20)),
            Recurrence(Frequency.MONTHLY),
            Recurrence(Frequency.MONTHLY, nthWeekday = NthWeekday(NthWeekday.LAST, DayOfWeek.FRIDAY)),
            Recurrence(Frequency.YEARLY),
        )
        for (r in rules) {
            val expected = if (r.frequency == Frequency.WEEKLY && r.days.isEmpty()) r.copy(days = listOf(monday.dayOfWeek)) else r
            assertEquals(expected, parseRRule(rrule(r), monday, newYork), r.toString())
        }
    }

    @Test fun readsForeignRulesOrGivesUp() {
        assertEquals(Recurrence(Frequency.WEEKLY, days = listOf(DayOfWeek.TUESDAY)), parseRRule("FREQ=WEEKLY;BYDAY=TU;WKST=MO"))
        assertEquals(Recurrence(Frequency.DAILY, until = LocalDate(2026, 12, 20)), parseRRule("FREQ=DAILY;UNTIL=20261220"))
        assertEquals(Recurrence(Frequency.MONTHLY, nthWeekday = NthWeekday(1, DayOfWeek.MONDAY)), parseRRule("RRULE:FREQ=MONTHLY;BYDAY=1MO"))
        assertNull(parseRRule("FREQ=HOURLY"))
        assertNull(parseRRule("FREQ=MONTHLY;BYDAY=MO,TU;BYSETPOS=1"))
        assertNull(parseRRule("FREQ=MONTHLY;BYDAY=MO"))
        assertNull(parseRRule("FREQ=MONTHLY;BYMONTHDAY=15", start = monday))
        assertNull(parseRRule("FREQ=DAILY;BYDAY=MO"))
        assertNull(parseRRule("nonsense"))
    }

    @Test fun aUtcUntilIsReadAsADayOnTheSeriesClock() {
        // 04:59:59Z on the 21st is still the 20th in New York
        assertEquals(LocalDate(2026, 12, 20), parseRRule("FREQ=DAILY;UNTIL=20261221T045959Z", zone = newYork)?.until)
        assertEquals(LocalDate(2026, 12, 21), parseRRule("FREQ=DAILY;UNTIL=20261221T045959Z")?.until)
    }

    @Test fun roundTripsThroughJson() {
        val r = Recurrence(Frequency.MONTHLY, nthWeekday = NthWeekday(2, DayOfWeek.TUESDAY), until = LocalDate(2027, 3, 1))
        assertEquals(r, ClarityJson.decodeFromString(Recurrence.serializer(), ClarityJson.encodeToString(Recurrence.serializer(), r)))
    }
}

class RecurrenceMoveTest {
    @Test fun weekdaysMoveWithTheOccurrences() {
        val mwf = Recurrence(Frequency.WEEKLY, days = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
        assertEquals(listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY), mwf.movedByDays(1).days)
        assertEquals(listOf(DayOfWeek.SUNDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), mwf.movedByDays(-1).days)
        assertEquals(mwf, mwf.movedByDays(7))
        assertEquals(DayOfWeek.WEDNESDAY, Recurrence(Frequency.MONTHLY, nthWeekday = NthWeekday(2, DayOfWeek.TUESDAY)).movedByDays(1).nthWeekday?.day)
        assertEquals(Recurrence(Frequency.DAILY), Recurrence(Frequency.DAILY).movedByDays(3))
    }
}

class RecurrenceStartTest {
    private val monday = LocalDate(2026, 10, 26)

    @Test fun aWeeklyStartMustBeOneOfTheDays() {
        val tuesdays = Recurrence(Frequency.WEEKLY, days = listOf(DayOfWeek.TUESDAY))
        assertEquals("`start` falls on a Mon, which is not in `days`. Start on one of the days.", tuesdays.startProblem(monday))
        assertNull(tuesdays.startProblem(LocalDate(2026, 10, 27)))
        assertNull(Recurrence(Frequency.WEEKLY).startProblem(monday))
    }

    @Test fun aMonthlyStartMustBeThatWeekday() {
        val secondTuesday = Recurrence(Frequency.MONTHLY, nthWeekday = NthWeekday(2, DayOfWeek.TUESDAY))
        assertNull(secondTuesday.startProblem(LocalDate(2026, 10, 13)))
        assertNotNull(secondTuesday.startProblem(LocalDate(2026, 10, 6)))
        assertNotNull(secondTuesday.startProblem(LocalDate(2026, 10, 14)))
        val lastFriday = Recurrence(Frequency.MONTHLY, nthWeekday = NthWeekday(NthWeekday.LAST, DayOfWeek.FRIDAY))
        assertNull(lastFriday.startProblem(LocalDate(2026, 10, 30)))
        assertNotNull(lastFriday.startProblem(LocalDate(2026, 10, 23)))
        assertNull(lastFriday.startProblem(LocalDate(2026, 11, 27)))
    }

    @Test fun anEndBeforeTheStartIsAProblem() {
        assertNotNull(Recurrence(Frequency.DAILY, until = LocalDate(2026, 10, 1)).startProblem(monday))
        assertNull(Recurrence(Frequency.DAILY, until = monday).startProblem(monday))
    }
}
