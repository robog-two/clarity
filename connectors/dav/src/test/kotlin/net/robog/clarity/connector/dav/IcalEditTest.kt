package net.robog.clarity.connector.dav

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant as JInstant
import kotlinx.datetime.DayOfWeek as KDay
import kotlinx.datetime.LocalDate as KDate
import net.robog.clarity.shared.calendar.EventScope
import net.robog.clarity.shared.calendar.Frequency
import net.robog.clarity.shared.calendar.Recurrence
import net.robog.clarity.shared.governance.InviteResponse

/** Editing and cancelling plain events, one occurrence, the rest of a series, or all of it; checked by expanding the result. */

private val MWF = listOf(KDay.MONDAY, KDay.WEDNESDAY, KDay.FRIDAY)

/** Mon Wed Fri 07:00 from 2026-10-26 to Fri 2026-12-18; the clocks go back on 2026-11-01. */
private fun gym(count: Int? = null) = create(
    "2026-10-26 07:00", "2026-10-26 08:00",
    if (count != null) Recurrence(Frequency.WEEKLY, days = MWF, count = count) else Recurrence(Frequency.WEEKLY, days = MWF, until = KDate(2026, 12, 18)),
)

private fun update(ics: String, occurrence: String?, scope: EventScope?, change: EventChange) =
    IcalEdit.updated(ics, occurrence?.let { at(it) }, scope, change, "next@clarity")

private fun replaced(edited: Edited) = edited.shouldBeInstanceOf<Edited.Replace>().ics

private fun changed(ics: String, occurrence: String?, scope: EventScope?, change: EventChange) =
    replaced(update(ics, occurrence, scope, change))

private val allGym = listOf(
    "2026-10-26", "2026-10-28", "2026-10-30", "2026-11-02", "2026-11-04", "2026-11-06", "2026-11-09", "2026-11-11", "2026-11-13",
    "2026-11-16", "2026-11-18", "2026-11-20", "2026-11-23", "2026-11-25", "2026-11-27", "2026-11-30", "2026-12-02", "2026-12-04",
    "2026-12-07", "2026-12-09", "2026-12-11", "2026-12-14", "2026-12-16", "2026-12-18",
)

private fun at7(vararg dates: String) = dates.map { "$it 07:00" }

class IcalEditTest : FunSpec({
    context("update one occurrence") {
        test("moving it writes an override and leaves the rest alone") {
            val ics = changed(gym(), "2026-11-04 07:00", EventScope.THIS, EventChange(start = at("2026-11-04 09:00"), end = at("2026-11-04 10:00")))
            ics shouldContain "RECURRENCE-ID"
            localStarts(ics) shouldContainExactly allGym.map { if (it == "2026-11-04") "$it 09:00" else "$it 07:00" }
        }

        test("a second edit of the same occurrence changes the override instead of adding another") {
            val once = changed(gym(), "2026-11-04 07:00", EventScope.THIS, EventChange(start = at("2026-11-04 09:00"), end = at("2026-11-04 10:00")))
            val twice = changed(once, "2026-11-04 07:00", EventScope.THIS, EventChange(title = "Gym with Sam", location = "Pool"))
            Regex("RECURRENCE-ID").findAll(twice).count() shouldBe 1
            val edited = Ical.expand(twice, "Home", WINDOW).occurrences.single { it.payload.title == "Gym with Sam" }.payload
            edited.location shouldBe "Pool"
            localStarts(twice).contains("2026-11-04 09:00") shouldBe true
        }

        test("changing only the title keeps the time") {
            val ics = changed(gym(), "2026-11-06 07:00", EventScope.THIS, EventChange(title = "Gym day"))
            localStarts(ics) shouldContainExactly allGym.map { "$it 07:00" }
            Ical.expand(ics, "Home", WINDOW).occurrences.count { it.payload.title == "Gym day" } shouldBe 1
        }

        test("moving it keeps the length unless an end is given") {
            val ics = changed(gym(), "2026-11-04 07:00", EventScope.THIS, EventChange(start = at("2026-11-04 12:00")))
            Ical.expand(ics, "Home", WINDOW).occurrences.single { it.payload.start == at("2026-11-04 12:00").toKotlin() }
                .payload.end shouldBe at("2026-11-04 13:00").toKotlin()
        }

        test("a rule change is refused for one occurrence") {
            shouldThrow<IllegalArgumentException> {
                update(gym(), "2026-11-04 07:00", EventScope.THIS, EventChange(repeat = Recurrence(Frequency.DAILY)))
            }.message shouldContain "following"
        }
    }

    context("update this and following") {
        test("the series ends the day before and a new one carries on with the change") {
            val edited = update(gym(), "2026-11-09 07:00", EventScope.FOLLOWING, EventChange(start = at("2026-11-09 08:00"), end = at("2026-11-09 09:00"), title = "Gym B"))
                .shouldBeInstanceOf<Edited.Split>()
            localStarts(edited.original) shouldContainExactly allGym.takeWhile { it < "2026-11-09" }.map { "$it 07:00" }
            localStarts(edited.following) shouldContainExactly allGym.dropWhile { it < "2026-11-09" }.map { "$it 08:00" }
            edited.following shouldContain "UID:next@clarity"
            edited.original shouldContain "UID:u1@clarity"
            Ical.expand(edited.following, "Home", WINDOW).occurrences.map { it.payload.title }.toSet() shouldBe setOf("Gym B")
            Ical.expand(edited.original, "Home", WINDOW).occurrences.map { it.payload.title }.toSet() shouldBe setOf("Gym")
        }

        test("a counted series hands the rest of its count to the new one") {
            val edited = update(gym(count = 6), "2026-11-02 07:00", EventScope.FOLLOWING, EventChange(title = "Gym B")).shouldBeInstanceOf<Edited.Split>()
            localStarts(edited.original) shouldContainExactly at7("2026-10-26", "2026-10-28", "2026-10-30")
            localStarts(edited.following) shouldContainExactly at7("2026-11-02", "2026-11-04", "2026-11-06")
        }

        test("moving to another day moves the weekdays of the new series") {
            val edited = update(gym(count = 6), "2026-11-02 07:00", EventScope.FOLLOWING, EventChange(start = at("2026-11-03 07:00")))
                .shouldBeInstanceOf<Edited.Split>()
            localStarts(edited.following) shouldContainExactly at7("2026-11-03", "2026-11-05", "2026-11-07")
        }

        test("a new rule applies from that occurrence on") {
            val edited = update(gym(), "2026-11-09 07:00", EventScope.FOLLOWING, EventChange(repeat = Recurrence(Frequency.WEEKLY, days = listOf(KDay.MONDAY), count = 2)))
                .shouldBeInstanceOf<Edited.Split>()
            localStarts(edited.following) shouldContainExactly at7("2026-11-09", "2026-11-16")
        }

        test("exceptions after the split go with the new series") {
            val withException = changed(gym(), "2026-11-11 07:00", EventScope.THIS, EventChange(start = at("2026-11-11 18:00")))
            val withExclusion = (IcalEdit.cancelled(withException, at("2026-11-13 07:00"), EventScope.THIS) as Removal.Replace).ics
            val edited = update(withExclusion, "2026-11-09 07:00", EventScope.FOLLOWING, EventChange(title = "Gym B")).shouldBeInstanceOf<Edited.Split>()
            val later = allGym.filter { it >= "2026-11-09" && it != "2026-11-13" }
            localStarts(edited.following) shouldContainExactly later.map { if (it == "2026-11-11") "$it 18:00" else "$it 07:00" }
            localStarts(edited.original) shouldContainExactly allGym.filter { it < "2026-11-09" }.map { "$it 07:00" }
            edited.original.contains("RECURRENCE-ID") shouldBe false
        }

        test("from the first occurrence it is the whole series") {
            val ics = changed(gym(), "2026-10-26 07:00", EventScope.FOLLOWING, EventChange(title = "Gym B"))
            Ical.expand(ics, "Home", WINDOW).occurrences.map { it.payload.title }.toSet() shouldBe setOf("Gym B")
            localStarts(ics) shouldHaveSize allGym.size
        }
    }

    context("update the series") {
        test("a later time moves every occurrence, keeping the local time across the clock change") {
            val ics = changed(gym(), "2026-11-04 07:00", EventScope.SERIES, EventChange(start = at("2026-11-04 08:30"), end = at("2026-11-04 09:30")))
            localStarts(ics) shouldContainExactly allGym.map { "$it 08:30" }
            utcStarts(ics).first() shouldBe "2026-10-26T12:30:00Z"
            utcStarts(ics).last() shouldBe "2026-12-18T13:30:00Z"
        }

        test("another day moves the weekdays too") {
            val ics = changed(gym(count = 6), "2026-11-04 07:00", EventScope.SERIES, EventChange(start = at("2026-11-05 07:00")))
            localStarts(ics) shouldContainExactly at7("2026-10-27", "2026-10-29", "2026-10-31", "2026-11-03", "2026-11-05", "2026-11-07")
        }

        test("title location link and description change everywhere, an empty text clears") {
            val withPlace = changed(gym(), "2026-11-04 07:00", EventScope.SERIES, EventChange(title = "Strength", location = "Pool", url = "https://example.com/gym", description = "Bring a towel"))
            val payloads = Ical.expand(withPlace, "Home", WINDOW).occurrences.map { it.payload }
            payloads.map { it.title }.toSet() shouldBe setOf("Strength")
            payloads.map { it.location }.toSet() shouldBe setOf("Pool")
            payloads.map { it.url }.toSet() shouldBe setOf("https://example.com/gym")
            payloads.map { it.description }.toSet() shouldBe setOf("Bring a towel")
            val cleared = changed(withPlace, "2026-11-04 07:00", EventScope.SERIES, EventChange(location = "", url = ""))
            Ical.expand(cleared, "Home", WINDOW).occurrences.map { it.payload.location to it.payload.url }.toSet() shouldBe setOf(null to null)
        }

        test("a new rule replaces the old one and drops one-off changes") {
            val withException = changed(gym(), "2026-11-04 07:00", EventScope.THIS, EventChange(start = at("2026-11-04 09:00")))
            val ics = changed(withException, "2026-11-09 07:00", EventScope.SERIES, EventChange(repeat = Recurrence(Frequency.DAILY, count = 3)))
            localStarts(ics) shouldContainExactly at7("2026-10-26", "2026-10-27", "2026-10-28")
            ics.contains("RECURRENCE-ID") shouldBe false
        }

        test("a longer event keeps its start") {
            val ics = changed(gym(), "2026-11-04 07:00", EventScope.SERIES, EventChange(end = at("2026-11-04 09:00")))
            localStarts(ics) shouldHaveSize allGym.size
            Ical.expand(ics, "Home", WINDOW).occurrences.map { (it.payload.end.epochSeconds - it.payload.start.epochSeconds) }.toSet() shouldBe setOf(7200L)
        }

        test("excluded dates and changed occurrences move with the series") {
            val excluded = IcalEdit.cancelled(gym(), at("2026-11-06 07:00"), EventScope.THIS).let { (it as Removal.Replace).ics }
            val moved = changed(excluded, "2026-11-04 07:00", EventScope.SERIES, EventChange(start = at("2026-11-04 08:00")))
            localStarts(moved) shouldContainExactly allGym.filter { it != "2026-11-06" }.map { "$it 08:00" }
        }

        test("an all-day series moves by days") {
            val series = create("2026-11-02 00:00", "2026-11-03 00:00", Recurrence(Frequency.WEEKLY, count = 3), allDay = true)
            val utc = java.time.ZoneId.of("UTC")
            val ics = replaced(IcalEdit.updated(series, at("2026-11-09 00:00", utc), EventScope.SERIES, EventChange(start = at("2026-11-10 00:00", utc), end = at("2026-11-11 00:00", utc)), "next@clarity"))
            Ical.expand(ics, "Home", WINDOW).occurrences.map { it.payload.start.toString().take(10) } shouldContainExactly listOf("2026-11-03", "2026-11-10", "2026-11-17")
        }
    }

    context("refusals") {
        test("a repeating event needs a scope") {
            shouldThrow<IllegalArgumentException> { update(gym(), "2026-11-04 07:00", null, EventChange(title = "x")) }.message shouldContain "`this`, `following` or `series`"
        }

        test("an occurrence that is not in the series is refused") {
            shouldThrow<IllegalArgumentException> { update(gym(), "2026-11-03 07:00", EventScope.THIS, EventChange(title = "x")) }.message shouldContain "not in the series"
            shouldThrow<IllegalArgumentException> { IcalEdit.cancelled(gym(), at("2026-11-03 07:00"), EventScope.THIS) }.message shouldContain "not in the series"
        }

        test("a repeating event cannot become all-day") {
            shouldThrow<IllegalArgumentException> {
                update(gym(), "2026-11-04 07:00", EventScope.SERIES, EventChange(allDay = true, start = at("2026-11-04 00:00", java.time.ZoneId.of("UTC"))))
            }.message shouldContain "all-day"
        }

        test("an end before the start is refused") {
            shouldThrow<IllegalArgumentException> { update(gym(), "2026-11-04 07:00", EventScope.THIS, EventChange(start = at("2026-11-04 09:00"), end = at("2026-11-04 08:00"))) }
        }
    }

    context("a single event") {
        val single = create("2026-10-26 16:00", "2026-10-26 17:00", title = "Lunch")

        test("scope is ignored and the event changes") {
            val ics = changed(single, null, null, EventChange(start = at("2026-10-27 12:00"), end = at("2026-10-27 13:00"), title = "Late lunch", location = "Cafe"))
            val payload = Ical.expand(ics, "Home", WINDOW).occurrences.single().payload
            payload.title to payload.location shouldBe ("Late lunch" to "Cafe")
            payload.start shouldBe at("2026-10-27 12:00").toKotlin()
            ics shouldContain "SEQUENCE:1"
        }

        test("it can start repeating") {
            val ics = changed(single, null, null, EventChange(repeat = Recurrence(Frequency.DAILY, count = 3), zone = NY))
            localStarts(ics) shouldContainExactly at7("2026-10-26").map { it.replace("07:00", "16:00") } + listOf("2026-10-27 16:00", "2026-10-28 16:00")
        }

        test("it can become all-day and back") {
            val allDay = changed(single, null, null, EventChange(allDay = true, start = at("2026-10-27 00:00", java.time.ZoneId.of("UTC")), end = at("2026-10-28 00:00", java.time.ZoneId.of("UTC"))))
            allDay shouldContain "DTSTART;VALUE=DATE:20261027"
            Ical.expand(allDay, "Home", WINDOW).occurrences.single().payload.allDay shouldBe true
            val timed = changed(allDay, null, null, EventChange(allDay = false, start = at("2026-10-27 09:00"), end = at("2026-10-27 10:00")))
            Ical.expand(timed, "Home", WINDOW).occurrences.single().payload.allDay shouldBe false
        }

        test("cancelling it deletes it") {
            IcalEdit.cancelled(single, null, null) shouldBe Removal.Delete
        }
    }

    context("cancel") {
        test("the whole series deletes the resource") {
            IcalEdit.cancelled(gym(), at("2026-11-04 07:00"), EventScope.SERIES) shouldBe Removal.Delete
        }

        test("following from the first occurrence deletes the resource") {
            IcalEdit.cancelled(gym(), at("2026-10-26 07:00"), EventScope.FOLLOWING) shouldBe Removal.Delete
        }

        test("one occurrence is excluded") {
            val ics = (IcalEdit.cancelled(gym(), at("2026-11-04 07:00"), EventScope.THIS) as Removal.Replace).ics
            ics shouldContain "EXDATE"
            localStarts(ics) shouldContainExactly allGym.filter { it != "2026-11-04" }.map { "$it 07:00" }
        }

        test("a changed occurrence is removed with its override") {
            val moved = changed(gym(), "2026-11-04 07:00", EventScope.THIS, EventChange(start = at("2026-11-04 09:00")))
            val ics = (IcalEdit.cancelled(moved, at("2026-11-04 07:00"), EventScope.THIS) as Removal.Replace).ics
            ics.contains("RECURRENCE-ID") shouldBe false
            localStarts(ics) shouldContainExactly allGym.filter { it != "2026-11-04" }.map { "$it 07:00" }
        }

        test("following ends the series before the occurrence") {
            val ics = (IcalEdit.cancelled(gym(), at("2026-11-09 07:00"), EventScope.FOLLOWING) as Removal.Replace).ics
            localStarts(ics) shouldContainExactly allGym.filter { it < "2026-11-09" }.map { "$it 07:00" }
        }

        test("following on a counted series swaps the count for an end") {
            val ics = (IcalEdit.cancelled(gym(count = 6), at("2026-11-02 07:00"), EventScope.FOLLOWING) as Removal.Replace).ics
            ics.contains("COUNT") shouldBe false
            localStarts(ics) shouldContainExactly at7("2026-10-26", "2026-10-28", "2026-10-30")
        }

        test("following drops later exceptions") {
            val moved = changed(gym(), "2026-11-11 07:00", EventScope.THIS, EventChange(start = at("2026-11-11 09:00")))
            val ics = (IcalEdit.cancelled(moved, at("2026-11-09 07:00"), EventScope.FOLLOWING) as Removal.Replace).ics
            ics.contains("RECURRENCE-ID") shouldBe false
            localStarts(ics) shouldHaveSize 6
        }

        test("an all-day series loses an occurrence and ends on the day before") {
            val series = create("2026-11-02 00:00", "2026-11-03 00:00", Recurrence(Frequency.DAILY, count = 5), allDay = true)
            val utc = java.time.ZoneId.of("UTC")
            val one = (IcalEdit.cancelled(series, at("2026-11-04 00:00", utc), EventScope.THIS) as Removal.Replace).ics
            Ical.expand(one, "Home", WINDOW).occurrences.map { it.payload.start.toString().take(10) } shouldContainExactly listOf("2026-11-02", "2026-11-03", "2026-11-05", "2026-11-06")
            val rest = (IcalEdit.cancelled(series, at("2026-11-04 00:00", utc), EventScope.FOLLOWING) as Removal.Replace).ics
            rest shouldContain "UNTIL=20261103"
            Ical.expand(rest, "Home", WINDOW).occurrences.map { it.payload.start.toString().take(10) } shouldContainExactly listOf("2026-11-02", "2026-11-03")
        }
    }

    context("a series in UTC") {
        val daily = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nBEGIN:VEVENT\r\nUID:utc\r\nDTSTAMP:20260301T000000Z\r\nDTSTART:20261026T090000Z\r\n" +
            "DTEND:20261026T100000Z\r\nRRULE:FREQ=DAILY;COUNT=5\r\nSUMMARY:Daily\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val utc = java.time.ZoneId.of("UTC")
        fun starts(ics: String) = utcStarts(ics).map { it.take(16) }

        test("one occurrence moves, the series shifts, the rest splits off and one date drops out") {
            val one = replaced(IcalEdit.updated(daily, at("2026-10-27 09:00", utc), EventScope.THIS, EventChange(start = at("2026-10-27 12:00", utc)), "n"))
            starts(one) shouldContainExactly listOf("2026-10-26T09:00", "2026-10-27T12:00", "2026-10-28T09:00", "2026-10-29T09:00", "2026-10-30T09:00")
            val shifted = replaced(IcalEdit.updated(daily, at("2026-10-27 09:00", utc), EventScope.SERIES, EventChange(start = at("2026-10-28 09:00", utc)), "n"))
            starts(shifted) shouldContainExactly listOf("2026-10-27T09:00", "2026-10-28T09:00", "2026-10-29T09:00", "2026-10-30T09:00", "2026-10-31T09:00")
            val split = IcalEdit.updated(daily, at("2026-10-28 09:00", utc), EventScope.FOLLOWING, EventChange(start = at("2026-10-28 08:00", utc)), "n").shouldBeInstanceOf<Edited.Split>()
            starts(split.original) shouldContainExactly listOf("2026-10-26T09:00", "2026-10-27T09:00")
            starts(split.following) shouldContainExactly listOf("2026-10-28T08:00", "2026-10-29T08:00", "2026-10-30T08:00")
            val dropped = (IcalEdit.cancelled(daily, at("2026-10-29 09:00", utc), EventScope.THIS) as Removal.Replace).ics
            starts(dropped) shouldContainExactly listOf("2026-10-26T09:00", "2026-10-27T09:00", "2026-10-28T09:00", "2026-10-30T09:00")
        }
    }

    context("a series that lists its dates") {
        val dates = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nBEGIN:VEVENT\r\nUID:rd\r\nDTSTAMP:20260301T000000Z\r\nDTSTART:20261026T090000Z\r\n" +
            "DTEND:20261026T100000Z\r\nRDATE:20261028T090000Z,20261030T090000Z\r\nSUMMARY:Dates\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val utc = java.time.ZoneId.of("UTC")

        test("the whole series can change, one date can be cancelled, the rest is refused") {
            val renamed = replaced(IcalEdit.updated(dates, at("2026-10-28 09:00", utc), EventScope.SERIES, EventChange(title = "Renamed"), "n"))
            utcStarts(renamed) shouldContainExactly listOf("2026-10-26T09:00:00Z", "2026-10-28T09:00:00Z", "2026-10-30T09:00:00Z")
            shouldThrow<IllegalArgumentException> {
                IcalEdit.updated(dates, at("2026-10-28 09:00", utc), EventScope.SERIES, EventChange(start = at("2026-10-28 10:00", utc)), "n")
            }.message shouldContain "cannot be moved"
            val excluded = (IcalEdit.cancelled(dates, at("2026-10-28 09:00", utc), EventScope.THIS) as Removal.Replace).ics
            utcStarts(excluded) shouldContainExactly listOf("2026-10-26T09:00:00Z", "2026-10-30T09:00:00Z")
            shouldThrow<IllegalArgumentException> { IcalEdit.cancelled(dates, at("2026-10-28 09:00", utc), EventScope.FOLLOWING) }.message shouldContain "single dates"
            shouldThrow<IllegalArgumentException> { IcalEdit.updated(dates, at("2026-10-28 09:00", utc), EventScope.FOLLOWING, EventChange(title = "x"), "n") }.message shouldContain "single dates"
        }
    }

    context("answering an invitation") {
        val invite = create("2026-10-26 07:00", "2026-10-26 08:00", Recurrence(Frequency.WEEKLY, count = 3))
            .replace("END:VEVENT", "ORGANIZER:mailto:boss@example.com\r\nATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:me@example.com\r\nEND:VEVENT")

        test("a repeating event is answered as a whole, including changed occurrences") {
            val moved = changed(invite, "2026-11-02 07:00", EventScope.THIS, EventChange(start = at("2026-11-02 09:00")))
            val answered = Ical.responded(moved, "me@example.com", InviteResponse.ACCEPT)!!
            Regex("PARTSTAT=ACCEPTED").findAll(answered).count() shouldBe 2
            answered.contains("NEEDS-ACTION") shouldBe false
            Ical.participants(answered).organizer shouldBe "boss@example.com"
        }
    }
})

private fun JInstant.toKotlin() = kotlin.time.Instant.fromEpochSeconds(epochSecond)
