package net.robog.clarity.connector.dav

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.ZoneId
import kotlinx.datetime.DayOfWeek as KDay
import kotlinx.datetime.LocalDate as KDate
import net.robog.clarity.shared.calendar.EventScope
import net.robog.clarity.shared.calendar.Frequency
import net.robog.clarity.shared.calendar.NthWeekday
import net.robog.clarity.shared.calendar.Recurrence

/** Edge cases of moving, restarting and ending series: exceptions, end dates, month rules, other kinds of start. */

private val UTC = ZoneId.of("UTC")
private val MWF = listOf(KDay.MONDAY, KDay.WEDNESDAY, KDay.FRIDAY)

private fun gym() = create("2026-10-26 07:00", "2026-10-26 08:00", Recurrence(Frequency.WEEKLY, days = MWF, until = KDate(2026, 12, 18)))

private fun edit(ics: String, at: String, scope: EventScope?, change: EventChange, zone: ZoneId = NY) =
    IcalEdit.updated(ics, at(at, zone), scope, change, "next@clarity")

private fun replaced(edited: Edited) = edited.shouldBeInstanceOf<Edited.Replace>().ics

private fun titles(ics: String) = Ical.expand(ics, "Home", WINDOW).occurrences.associate { it.payload.start.toString() to it.payload.title }

private fun ics(vararg body: String) =
    (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//t//EN", "BEGIN:VEVENT", "UID:x", "DTSTAMP:20260301T000000Z") + body + listOf("END:VEVENT", "END:VCALENDAR")).joinToString("\r\n") + "\r\n"

class IcalEditFixesTest : FunSpec({
    context("changed occurrences when a series moves") {
        val changedOne = replaced(edit(gym(), "2026-11-04 07:00", EventScope.THIS, EventChange(title = "Special", start = at("2026-11-04 09:00"), end = at("2026-11-04 10:00"))))

        test("a changed occurrence moves with the series, keeps its own name and length") {
            val moved = replaced(edit(changedOne, "2026-11-06 07:00", EventScope.SERIES, EventChange(start = at("2026-11-06 08:00"))))
            val starts = localStarts(moved)
            starts.contains("2026-11-04 10:00") shouldBe true // 09:00 plus the same hour
            starts.contains("2026-11-04 08:00") shouldBe false
            starts.size shouldBe 24
            val special = Ical.expand(moved, "Home", WINDOW).occurrences.single { it.payload.title == "Special" }.payload
            special.end.epochSeconds - special.start.epochSeconds shouldBe 3600
        }

        test("a series rename leaves a changed occurrence's own name alone") {
            val renamed = replaced(edit(changedOne, "2026-11-06 07:00", EventScope.SERIES, EventChange(title = "Strength")))
            titles(renamed).values.count { it == "Special" } shouldBe 1
            titles(renamed).values.count { it == "Strength" } shouldBe 23
        }

        test("a changed occurrence after a split moves with the new series") {
            val later = replaced(edit(gym(), "2026-11-11 07:00", EventScope.THIS, EventChange(title = "Special", start = at("2026-11-11 18:00"), end = at("2026-11-11 19:00"))))
            val split = edit(later, "2026-11-09 07:00", EventScope.FOLLOWING, EventChange(start = at("2026-11-09 08:00"), title = "Gym B")).shouldBeInstanceOf<Edited.Split>()
            localStarts(split.following).contains("2026-11-11 19:00") shouldBe true
            titles(split.following).values.toSet() shouldBe setOf("Gym B", "Special")
        }
    }

    context("end dates") {
        val piano = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//t//EN
BEGIN:VEVENT
DTSTAMP:20260801T120000Z
UID:piano
DTSTART;TZID=America/New_York:20260907T093000
DTEND;TZID=America/New_York:20260907T103000
RRULE:FREQ=WEEKLY;UNTIL=20261214T143000Z
SUMMARY:Piano
END:VEVENT
END:VCALENDAR
""".replace("\n", "\r\n")

        test("a later time of day keeps the last occurrence inside the end date") {
            val moved = replaced(edit(piano, "2026-11-09 09:30", EventScope.SERIES, EventChange(start = at("2026-11-09 10:30"), end = at("2026-11-09 11:30"))))
            localStarts(moved).last() shouldBe "2026-12-14 10:30"
        }

        test("another day moves the end date with the series") {
            val moved = replaced(edit(piano, "2026-11-09 09:30", EventScope.SERIES, EventChange(start = at("2026-11-10 09:30"), end = at("2026-11-10 10:30"))))
            localStarts(moved).first() shouldBe "2026-09-08 09:30"
            localStarts(moved).last() shouldBe "2026-12-15 09:30"
        }
    }

    context("month and fortnight rules when the start moves") {
        val secondTuesday = create("2026-10-13 10:00", "2026-10-13 11:00", Recurrence(Frequency.MONTHLY, nthWeekday = NthWeekday(2, KDay.TUESDAY), count = 3))

        test("an nth weekday is recomputed for the new date") {
            val moved = replaced(edit(secondTuesday, "2026-10-13 10:00", EventScope.SERIES, EventChange(start = at("2026-10-21 10:00"), end = at("2026-10-21 11:00"))))
            localStarts(moved) shouldContainExactly listOf("2026-10-21 10:00", "2026-11-18 10:00", "2026-12-16 10:00")
        }

        test("a last weekday that would no longer be last is refused") {
            val lastFriday = create("2026-10-30 10:00", "2026-10-30 11:00", Recurrence(Frequency.MONTHLY, nthWeekday = NthWeekday(NthWeekday.LAST, KDay.FRIDAY), count = 3))
            shouldThrow<IllegalArgumentException> {
                edit(lastFriday, "2026-10-30 10:00", EventScope.SERIES, EventChange(start = at("2026-10-23 10:00"), end = at("2026-10-23 11:00")))
            }.message shouldContain "no longer fit"
        }

        test("an every-other-week rule whose days would land in different weeks is refused") {
            val fortnightly = create("2026-10-23 10:00", "2026-10-23 11:00", Recurrence(Frequency.WEEKLY, interval = 2, days = listOf(KDay.FRIDAY, KDay.SUNDAY), count = 4))
            shouldThrow<IllegalArgumentException> {
                edit(fortnightly, "2026-10-23 10:00", EventScope.SERIES, EventChange(start = at("2026-10-24 10:00"), end = at("2026-10-24 11:00")))
            }.message shouldContain "no longer fit"
        }
    }

    test("a new rule restarts the series at the given start, even from a later occurrence") {
        val restarted = replaced(
            edit(gym(), "2026-11-09 07:00", EventScope.SERIES, EventChange(start = at("2026-11-10 07:00"), end = at("2026-11-10 08:00"), repeat = Recurrence(Frequency.WEEKLY, days = listOf(KDay.TUESDAY), count = 3))),
        )
        localStarts(restarted) shouldContainExactly listOf("2026-11-10 07:00", "2026-11-17 07:00", "2026-11-24 07:00")
    }

    test("a series that was already moved is recognised when the same move arrives again") {
        val moved = replaced(edit(gym(), "2026-11-04 07:00", EventScope.SERIES, EventChange(start = at("2026-11-04 08:00"), end = at("2026-11-04 09:00"))))
        edit(moved, "2026-11-04 07:00", EventScope.SERIES, EventChange(start = at("2026-11-04 08:00"), end = at("2026-11-04 09:00"))) shouldBe Edited.Applied
    }

    context("cancelled already") {
        test("an excluded occurrence and a cut-off tail have nothing left to cancel") {
            val excluded = (IcalEdit.cancelled(gym(), at("2026-11-04 07:00"), EventScope.THIS) as Removal.Replace).ics
            IcalEdit.alreadyCancelled(excluded, at("2026-11-04 07:00"), EventScope.THIS) shouldBe true
            IcalEdit.alreadyCancelled(gym(), at("2026-11-04 07:00"), EventScope.THIS) shouldBe false
            val cut = (IcalEdit.cancelled(gym(), at("2026-11-09 07:00"), EventScope.FOLLOWING) as Removal.Replace).ics
            IcalEdit.alreadyCancelled(cut, at("2026-11-11 07:00"), EventScope.FOLLOWING) shouldBe true
            IcalEdit.alreadyCancelled(cut, at("2026-11-02 07:00"), EventScope.FOLLOWING) shouldBe false
            IcalEdit.alreadyCancelled(cut, at("2026-11-11 07:00"), EventScope.SERIES) shouldBe false
        }
    }

    context("other kinds of start") {
        test("cancelling the rest of a series also drops listed extra dates from then on") {
            val series = ics("DTSTART:20261026T120000Z", "DTEND:20261026T130000Z", "RRULE:FREQ=DAILY;COUNT=5", "RDATE:20261101T120000Z,20261120T120000Z", "SUMMARY:Mixed")
            val cut = (IcalEdit.cancelled(series, at("2026-10-28 12:00", UTC), EventScope.FOLLOWING) as Removal.Replace).ics
            utcStarts(cut).map { it.take(13) } shouldContainExactly listOf("2026-10-26T12", "2026-10-27T12")
            cut shouldNotContain "RDATE"
        }

        test("the end of a floating series is a floating time") {
            val floating = ics("DTSTART:20261026T090000", "DTEND:20261026T100000", "RRULE:FREQ=DAILY;COUNT=5", "SUMMARY:Local")
            val cut = (IcalEdit.cancelled(floating, at("2026-10-28 09:00", UTC), EventScope.FOLLOWING) as Removal.Replace).ics
            cut shouldContain "UNTIL=20261028T085959\r\n"
            cut shouldNotContain "UNTIL=20261028T085959Z"
            Ical.expand(cut, "Home", WINDOW).occurrences.size shouldBe 2
        }

        test("a single UTC event that starts repeating gets the person's clock first") {
            // 02:00Z is still Monday 22:00 in New York
            val single = create("2026-10-26 22:00", "2026-10-26 23:00", title = "Late call")
            val repeating = replaced(IcalEdit.updated(single, null, null, EventChange(repeat = Recurrence(Frequency.WEEKLY, count = 3), zone = NY), "n"))
            repeating shouldContain "DTSTART;TZID=America/New_York:20261026T220000"
            repeating shouldContain "BYDAY=MO"
            localStarts(repeating) shouldContainExactly listOf("2026-10-26 22:00", "2026-11-02 22:00", "2026-11-09 22:00")
            utcStarts(repeating).first() shouldBe "2026-10-27T02:00:00Z"
            utcStarts(repeating).last() shouldBe "2026-11-10T03:00:00Z" // after the clocks went back
        }

        test("a floating single event that starts repeating is read in the person's zone") {
            val floating = ics("DTSTART:20261026T090000", "DTEND:20261026T100000", "SUMMARY:Local")
            val repeating = replaced(IcalEdit.updated(floating, null, null, EventChange(repeat = Recurrence(Frequency.DAILY, count = 2), zone = NY), "n"))
            repeating shouldContain "DTSTART;TZID=America/New_York:20261026T090000"
        }
    }

    test("every zone an event uses keeps its VTIMEZONE") {
        val twoZones = ics(
            "DTSTART;TZID=America/New_York:20261026T090000", "DTEND;TZID=Europe/Berlin:20261026T170000", "SUMMARY:Flight",
        )
        val edited = replaced(IcalEdit.updated(twoZones, null, null, EventChange(title = "Flight home"), "n"))
        edited shouldContain "TZID:America/New_York"
        edited shouldContain "TZID:Europe/Berlin"
    }

    test("a date that no longer exists is a message, not a crash") {
        val odd = ics("DTSTART;TZID=America/New_York:20261026T090000", "DTEND;TZID=America/New_York:20261026T100000", "RRULE:FREQ=DAILY;COUNT=2", "SUMMARY:x")
        shouldThrow<IllegalArgumentException> { IcalEdit.updated(odd, at("2026-12-01 09:00"), EventScope.THIS, EventChange(title = "y"), "n") }
    }
})
