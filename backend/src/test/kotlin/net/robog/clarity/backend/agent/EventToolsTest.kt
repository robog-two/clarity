package net.robog.clarity.backend.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.calendar.EventScope
import net.robog.clarity.shared.calendar.Frequency
import net.robog.clarity.shared.calendar.NthWeekday
import net.robog.clarity.shared.calendar.Recurrence
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.noun.CalendarEventPayload

/** The event tools' input rules: what they accept, what they build, what the person reads on the card. */
class EventToolsTest : FunSpec({
    val newYork = TimeZone.of("America/New_York")
    val mwf = listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)

    fun repeat(
        frequency: Frequency = Frequency.WEEKLY, interval: Int = 1, days: List<String> = emptyList(),
        nth: ExternalTools.NthWeekdayInput? = null, until: String? = null, count: Int? = null,
    ) = ExternalTools.RepeatInput(frequency, interval, days, nth, until, count)

    fun create(
        start: String = "2026-10-26T07:00", end: String? = "2026-10-26T08:00", repeat: ExternalTools.RepeatInput? = null,
        allDay: Boolean = false, location: String? = null, url: String? = null, description: String? = null, title: String = "Gym",
    ) = ExternalTools.CreateEventInput(title, start, end, location = location, url = url, description = description, allDay = allDay, repeat = repeat, reason = "r")

    fun made(input: ExternalTools.CreateEventInput) = ProposedActions.createEvent("k", input, newYork).shouldBeInstanceOf<ProposedAction.CreateEvent>()

    context("create_event repeat") {
        test("every Monday Wednesday and Friday until a date, on the person's clock") {
            val event = made(create(repeat = repeat(days = listOf("MO", "WE", "FR"), until = "2026-12-20"), location = "Pool"))
            event.repeat shouldBe Recurrence(Frequency.WEEKLY, days = mwf, until = LocalDate(2026, 12, 20))
            event.zone shouldBe "America/New_York"
            event.location shouldBe "Pool"
            event.summary shouldBe "Hold \"Gym\" 2026-10-26T07:00-04:00 (Mon) to 08:00 repeating every Mon, Wed and Fri until Dec 20 at Pool"
        }

        test("weekdays can be written as names and in any case") {
            made(create(repeat = repeat(days = listOf("mon", "Wednesday", "FRI")))).repeat?.days shouldBe mwf
        }

        test("daily with an interval, counted") {
            val event = made(create(repeat = repeat(Frequency.DAILY, interval = 3, count = 5)))
            event.repeat shouldBe Recurrence(Frequency.DAILY, interval = 3, count = 5)
            event.summary shouldContain "repeating every 3 days for 5 occurrences"
        }

        test("monthly on the day of the month of the start by default") {
            made(create(start = "2026-10-15T12:00", end = "2026-10-15T13:00", repeat = repeat(Frequency.MONTHLY))).summary shouldContain "monthly on the 15th"
        }

        test("monthly on the 2nd Tuesday and the last Friday") {
            made(create(start = "2026-10-13T10:00", end = "2026-10-13T11:00", repeat = repeat(Frequency.MONTHLY, nth = ExternalTools.NthWeekdayInput(2, "TU"))))
                .repeat shouldBe Recurrence(Frequency.MONTHLY, nthWeekday = NthWeekday(2, DayOfWeek.TUESDAY))
            made(create(start = "2026-10-30T10:00", end = "2026-10-30T11:00", repeat = repeat(Frequency.MONTHLY, nth = ExternalTools.NthWeekdayInput(-1, "FR"))))
                .summary shouldContain "monthly on the last Friday"
        }

        test("yearly on the date of the start") {
            made(create(start = "2026-11-05T18:00", end = "2026-11-05T19:00", repeat = repeat(Frequency.YEARLY))).summary shouldContain "repeating yearly"
        }

        test("the start must be one of the days, or that weekday of the month") {
            shouldThrow<IllegalArgumentException> { made(create(repeat = repeat(days = listOf("TU")))) }.message shouldContain "not in `days`"
            shouldThrow<IllegalArgumentException> {
                made(create(start = "2026-10-14T10:00", end = "2026-10-14T11:00", repeat = repeat(Frequency.MONTHLY, nth = ExternalTools.NthWeekdayInput(2, "TU"))))
            }.message shouldContain "not that weekday"
        }

        test("bad rules say what is wrong") {
            shouldThrow<IllegalArgumentException> { made(create(repeat = repeat(Frequency.DAILY, days = listOf("MO")))) }.message shouldContain "only goes with a weekly"
            shouldThrow<IllegalArgumentException> { made(create(repeat = repeat(Frequency.DAILY, until = "2026-12-20", count = 3))) }.message shouldContain "not both"
            shouldThrow<IllegalArgumentException> { made(create(repeat = repeat(Frequency.DAILY, interval = 0))) }.message shouldContain "1 or more"
            shouldThrow<IllegalArgumentException> { made(create(repeat = repeat(days = listOf("XX")))) }.message shouldContain "MO TU WE"
            shouldThrow<IllegalArgumentException> { made(create(repeat = repeat(Frequency.DAILY, until = "soon"))) }.message shouldContain "not a date"
            shouldThrow<IllegalArgumentException> { made(create(repeat = repeat(Frequency.DAILY, until = "2026-10-01"))) }.message shouldContain "before `start`"
            shouldThrow<IllegalArgumentException> {
                made(create(repeat = repeat(Frequency.MONTHLY, nth = ExternalTools.NthWeekdayInput(6, "MO"))))
            }.message shouldContain "`nth`"
            shouldThrow<IllegalArgumentException> { made(create(repeat = repeat(Frequency.WEEKLY, nth = ExternalTools.NthWeekdayInput(1, "MO")))) }.message shouldContain "monthly"
        }

        test("the input shape decodes from the model's JSON") {
            val input = ClarityJson.decodeFromString(
                ExternalTools.CreateEventInput.serializer(),
                """{"title":"Gym","start":"2026-10-26T07:00","end":"2026-10-26T08:00","repeat":{"frequency":"weekly","days":["MO","WE","FR"],"until":"2026-12-20"},"reason":"r"}""",
            )
            made(input).repeat?.days shouldBe mwf
        }
    }

    context("create_event details") {
        test("an all-day event takes dates, the end is the last day and defaults to the start") {
            val trip = made(create(start = "2026-11-05", end = "2026-11-06", allDay = true, title = "Offsite"))
            trip.allDay shouldBe true
            trip.start shouldBe Instant.parse("2026-11-05T00:00:00Z")
            trip.end shouldBe Instant.parse("2026-11-07T00:00:00Z")
            trip.summary shouldBe "Hold \"Offsite\" all day 2026-11-05 (Thu) to 2026-11-06 (Fri)"
            val day = made(create(start = "2026-11-05", end = null, allDay = true, title = "Birthday"))
            day.end shouldBe Instant.parse("2026-11-06T00:00:00Z")
            day.summary shouldBe "Hold \"Birthday\" all day 2026-11-05 (Thu)"
        }

        test("an all-day event refuses times and a reversed range") {
            shouldThrow<IllegalArgumentException> { made(create(start = "2026-11-05T09:00", end = null, allDay = true)) }.message shouldContain "takes days"
            shouldThrow<IllegalArgumentException> { made(create(start = "2026-11-06", end = "2026-11-05", allDay = true)) }.message shouldContain "before"
        }

        test("an all-day series starts from its first day") {
            val event = made(create(start = "2026-11-02", end = null, allDay = true, repeat = repeat(Frequency.WEEKLY, count = 3)))
            event.repeat shouldBe Recurrence(Frequency.WEEKLY, count = 3)
            event.summary shouldContain "repeating every Mon for 3 occurrences"
        }

        test("a timed event needs an end") {
            shouldThrow<IllegalArgumentException> { made(create(end = null)) }.message shouldContain "`end` is required"
        }

        test("a link must be a web link, notes and place are kept") {
            val event = made(create(url = "https://example.com/gym", description = "Bring a towel", location = "Pool"))
            event.url shouldBe "https://example.com/gym"
            event.description shouldBe "Bring a towel"
            shouldThrow<IllegalArgumentException> { made(create(url = "javascript:alert(1)")) }.message shouldContain "web link"
            shouldThrow<IllegalArgumentException> { made(create(url = "not a link")) }.message shouldContain "web link"
            made(create(location = "  ", description = "")).also {
                it.location shouldBe null
                it.description shouldBe null
            }
        }

        test("a long place is cut in the summary") {
            made(create(location = "x".repeat(200))).summary.length shouldBe made(create(location = "x".repeat(60))).summary.length
        }
    }

    context("update_event") {
        fun update(
            scope: EventScope? = null, start: String? = null, end: String? = null, title: String? = null, location: String? = null,
            url: String? = null, description: String? = null, allDay: Boolean? = null, repeat: ExternalTools.RepeatInput? = null, notify: Boolean = false,
        ) = ExternalTools.UpdateEventInput("e1", scope, start, end, title, description, location, url, allDay, repeat, notify, "r")

        fun updated(input: ExternalTools.UpdateEventInput, current: CalendarEventPayload? = null) =
            ProposedActions.updateEvent("k", input, newYork, current).shouldBeInstanceOf<ProposedAction.UpdateEvent>()

        val gym = CalendarEventPayload(
            "gym#1", "Home", "Gym", Instant.parse("2026-10-26T11:00:00Z"), Instant.parse("2026-10-26T12:00:00Z"),
            repeats = "every Mon, Wed and Fri",
        )

        test("it needs something to change") {
            shouldThrow<IllegalArgumentException> { updated(update()) }.message shouldContain "Nothing to change"
            shouldThrow<IllegalArgumentException> { updated(update(title = " ")) }.message shouldContain "title"
        }

        test("a new time and title for one occurrence, in the summary with the event's title") {
            val action = updated(update(EventScope.THIS, start = "2026-11-04T09:00", end = "2026-11-04T10:00", title = "Gym B"), gym)
            action.scope shouldBe EventScope.THIS
            action.start shouldBe Instant.parse("2026-11-04T14:00:00Z")
            action.zone shouldBe "America/New_York"
            action.summary shouldBe "Update \"Gym\" (this one) to 2026-11-04T09:00-05:00 (Wed) to 10:00 rename to \"Gym B\""
        }

        test("a repeating event needs a scope when it is known to repeat") {
            shouldThrow<IllegalArgumentException> { updated(update(title = "x"), gym) }.message shouldContain "`this`, `following` or `series`"
            updated(update(title = "x"), gym.copy(repeats = null)).scope shouldBe null
            updated(update(title = "x")).scope shouldBe null
        }

        test("a move needs both ends only when one is given for a start that must be after") {
            shouldThrow<IllegalArgumentException> { updated(update(start = "2026-11-04T10:00", end = "2026-11-04T09:00")) }.message shouldContain "after"
            updated(update(end = "2026-11-04T10:00")).also {
                it.start shouldBe null
                it.end shouldBe Instant.parse("2026-11-04T15:00:00Z")
            }
        }

        test("an all-day event moves by dates") {
            val action = updated(update(start = "2026-11-10", end = "2026-11-11", allDay = true))
            action.start shouldBe Instant.parse("2026-11-10T00:00:00Z")
            action.end shouldBe Instant.parse("2026-11-12T00:00:00Z")
            action.summary shouldBe "Update event e1 to all day 2026-11-10 (Tue) to 2026-11-11 (Wed)"
            shouldThrow<IllegalArgumentException> { updated(update(start = "2026-11-10T09:00", allDay = true)) }.message shouldContain "takes days"
        }

        test("an empty text clears place, link and notes") {
            val action = updated(update(location = "", url = "", description = ""))
            action.location shouldBe ""
            action.url shouldBe ""
            action.description shouldBe ""
            action.summary shouldBe "Update event e1 clear the location clear the link clear the notes"
        }

        test("a link must be a web link") {
            shouldThrow<IllegalArgumentException> { updated(update(url = "ftp://example.com")) }.message shouldContain "web link"
        }

        test("a new rule needs the start it applies from, and fits it") {
            shouldThrow<IllegalArgumentException> { updated(update(EventScope.SERIES, repeat = repeat(Frequency.DAILY))) }.message shouldContain "needs `start`"
            val action = updated(update(EventScope.FOLLOWING, start = "2026-11-09T07:00", repeat = repeat(days = listOf("MO", "TH"), count = 4)))
            action.repeat shouldBe Recurrence(Frequency.WEEKLY, days = listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), count = 4)
            action.summary shouldContain "repeat every Mon and Thu for 4 occurrences"
            shouldThrow<IllegalArgumentException> { updated(update(EventScope.SERIES, start = "2026-11-10T07:00", repeat = repeat(days = listOf("MO")))) }.message shouldContain "not in `days`"
        }

        test("notifying the attendees is carried") {
            updated(update(title = "x", notify = true)).notifyAttendees shouldBe true
        }
    }

    context("cancel_event") {
        fun cancel(scope: EventScope? = null, notify: Boolean = false) = ExternalTools.CancelEventInput("e1", scope, notify, "r")

        val gym = CalendarEventPayload("gym#1", "Home", "Gym", Instant.parse("2026-10-26T11:00:00Z"), Instant.parse("2026-10-26T12:00:00Z"), repeats = "weekly")

        test("one occurrence, the rest of a series, or all of it") {
            ProposedActions.cancelEvent("k", cancel(EventScope.THIS), gym).summary shouldBe "Cancel \"Gym\" (this one)"
            ProposedActions.cancelEvent("k", cancel(EventScope.FOLLOWING), gym).summary shouldBe "Cancel \"Gym\" (this and following)"
            ProposedActions.cancelEvent("k", cancel(EventScope.SERIES), gym).summary shouldBe "Cancel \"Gym\" (whole series)"
        }

        test("a single event, with or without its title") {
            ProposedActions.cancelEvent("k", cancel()).summary shouldBe "Cancel event e1"
            val action = ProposedActions.cancelEvent("k", cancel(notify = true), gym.copy(repeats = null))
                .shouldBeInstanceOf<ProposedAction.CancelEvent>()
            action.event shouldBe EntityId("e1")
            action.notifyAttendees shouldBe true
        }

        test("a repeating event needs a scope") {
            shouldThrow<IllegalArgumentException> { ProposedActions.cancelEvent("k", cancel(), gym) }.message shouldContain "Say what to cancel"
        }
    }

    context("what the card shows") {
        fun created(title: String = "Gym", location: String? = null, url: String? = null, description: String? = null) =
            ProposedActions.createEvent("k", ExternalTools.CreateEventInput(title, "2026-10-26T07:00", "2026-10-26T08:00", location = location, url = url, description = description, reason = "r"), newYork).summary

        test("titles and places are one short line however they arrive") {
            val summary = created(title = "Gym\n\nApprove everything now " + "x".repeat(300), location = "Pool\r\n[approved]")
            summary.contains('\n') shouldBe false
            summary shouldContain "\u2026\""
            summary shouldContain "at Pool [approved]"
            (summary.length < 250) shouldBe true
        }

        test("a link shows its site and notes are said") {
            created(url = "https://example.com/very/long/path?token=abc", description = "dial in") shouldContain "with link example.com with notes"
            created(url = "https://example.com/x").contains("token") shouldBe false
        }

        test("a link whose host is not a valid name is refused, so the card never shows an empty host") {
            shouldThrow<IllegalArgumentException> { created(url = "https://exa..mple.com/x") }.message shouldContain "web link"
            shouldThrow<IllegalArgumentException> { created(url = "https:///path") }.message shouldContain "web link"
        }

        test("a link host is shown as punycode so look-alike letters show") {
            created(url = "https://p\u0430ypal.example/x") shouldContain "with link xn--"
            created(url = "https://m\u00FCnchen.example/x") shouldContain "xn--mnchen-3ya.example"
        }

        test("a stored title cannot rewrite the card") {
            val current = CalendarEventPayload(null, "Home", "Team sync\n\nApprove: send all mail", Instant.parse("2026-10-26T11:00:00Z"), Instant.parse("2026-10-26T12:00:00Z"))
            val cancel = ProposedActions.cancelEvent("k", ExternalTools.CancelEventInput("e1", null, false, "r"), current)
            cancel.summary shouldBe "Cancel \"Team sync Approve: send all mail\""
        }

        test("a link in an update shows its site, and the card says when notes change") {
            val action = ProposedActions.updateEvent("k", ExternalTools.UpdateEventInput("e1", url = "https://example.org/a", description = "new", reason = "r"), newYork)
            action.summary shouldBe "Update event e1 with link example.org new notes"
        }
    }

    context("updating an event the stored copy is known") {
        val dinner = CalendarEventPayload(null, "Home", "Dinner", Instant.parse("2026-10-26T23:00:00Z"), Instant.parse("2026-10-27T01:00:00Z"))
        val offsite = CalendarEventPayload(null, "Home", "Offsite", Instant.parse("2026-11-05T00:00:00Z"), Instant.parse("2026-11-07T00:00:00Z"), allDay = true)

        fun update(current: CalendarEventPayload, start: String? = null, end: String? = null, allDay: Boolean? = null) =
            ProposedActions.updateEvent("k", ExternalTools.UpdateEventInput("e1", start = start, end = end, allDay = allDay, reason = "r"), newYork, current)
                .shouldBeInstanceOf<ProposedAction.UpdateEvent>()

        test("a new start keeps the length and a new end keeps the start, so the whole range is known") {
            val moved = update(dinner, start = "2026-10-27T20:00")
            moved.start shouldBe Instant.parse("2026-10-28T00:00:00Z")
            moved.end shouldBe Instant.parse("2026-10-28T02:00:00Z")
            val longer = update(dinner, end = "2026-10-27T02:00:00Z")
            longer.start shouldBe dinner.start
            longer.end shouldBe Instant.parse("2026-10-27T02:00:00Z")
        }

        test("a start-only move into protected time is seen by the guard") {
            val night = net.robog.clarity.backend.governance.block("sleep", setOf(1, 2, 3, 4, 5, 6, 7), "22:00", "07:00")
            val c = net.robog.clarity.backend.governance.constitution(
                tier = net.robog.clarity.shared.governance.AutonomyTier.DO_THEN_TELL, protectedTimes = listOf(night),
            )
            val moved = update(dinner, start = "2026-10-27T23:00")
            val verdict = net.robog.clarity.backend.governance.deterministicVerdict(moved, c, net.robog.clarity.backend.governance.trusted(), newYork)
            verdict.decision shouldBe net.robog.clarity.shared.governance.Decision.ASK
            verdict.rationale shouldContain "sleep"
        }

        test("an all-day event stays all-day without saying so, and keeps its length") {
            val moved = update(offsite, start = "2026-11-10")
            moved.allDay shouldBe true
            moved.start shouldBe Instant.parse("2026-11-10T00:00:00Z")
            moved.end shouldBe Instant.parse("2026-11-12T00:00:00Z")
            moved.summary shouldBe "Update \"Offsite\" to all day 2026-11-10 (Tue) to 2026-11-11 (Wed)"
        }

        test("a time of day on an all-day event is refused, and a date on a timed one") {
            shouldThrow<IllegalArgumentException> { update(offsite, start = "2026-11-10T09:00") }.message shouldContain "takes days"
            shouldThrow<IllegalArgumentException> { update(dinner, start = "2026-10-27") }.message shouldContain "time of day"
        }

        test("an all-day event can be made timed") {
            val timed = update(offsite, start = "2026-11-10T09:00", end = "2026-11-10T10:00", allDay = false)
            timed.allDay shouldBe false
            timed.start shouldBe Instant.parse("2026-11-10T14:00:00Z")
        }
    }

    test("an invitation answer names the event when it is known") {
        val gym = CalendarEventPayload(null, "Home", "Team sync", Instant.parse("2026-10-26T11:00:00Z"), Instant.parse("2026-10-26T12:00:00Z"))
        ProposedActions.respond("k", ExternalTools.RespondInput("e1", net.robog.clarity.shared.governance.InviteResponse.ACCEPT, null, "r"), gym)
            .summary shouldBe "Accept invite \"Team sync\""
    }
})
