package net.robog.clarity.backend.outings

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import net.robog.clarity.shared.governance.TimeBlock
import net.robog.clarity.shared.noun.Handle

private val ny = TimeZone.of("America/New_York")

/** A local time in New York as an instant: "2026-11-17T19:00" is Tuesday evening. */
private fun at(local: String, zone: TimeZone = ny): Instant = net.robog.clarity.shared.time.LocalTimes.parseMoment(local, zone)!!

private fun busy(from: String, to: String) = BusySpan(at(from), at(to))

private fun List<Slot>.shown(zone: TimeZone = ny) =
    map { net.robog.clarity.shared.time.LocalTimes.format(it.start, zone).take(16) + ".." + net.robog.clarity.shared.time.LocalTimes.format(it.end, zone).substring(11, 16) }

class OutingPlanTest : FunSpec({
    // Monday 2026-11-16, 14:00 in New York. The next seven days are Mon 16 to Sun 22.
    val now = at("2026-11-16T14:00")

    test("an empty week: every weekday evening and both weekend days") {
        freeSlots(emptyList(), emptyList(), now, ny).shown() shouldBe listOf(
            "2026-11-16T18:00..22:00", "2026-11-17T18:00..22:00", "2026-11-18T18:00..22:00", "2026-11-19T18:00..22:00",
            "2026-11-20T18:00..22:00", "2026-11-21T09:00..22:00", "2026-11-22T09:00..22:00",
        )
    }

    test("an event inside an evening leaves what is long enough; short leftovers are not slots") {
        val slots = freeSlots(
            listOf(busy("2026-11-17T19:00", "2026-11-17T20:00"), busy("2026-11-18T18:30", "2026-11-18T20:30")),
            emptyList(), now, ny,
        ).shown()
        slots shouldContain "2026-11-17T20:00..22:00" // 2 h left after the Tuesday event
        slots.none { it.startsWith("2026-11-17T18") } shouldBe true // the hour before is too short
        slots.none { it.startsWith("2026-11-18") } shouldBe true // 1.5 h after the Wednesday event
    }

    test("overlapping and touching events count as one taken stretch") {
        val slots = freeSlots(
            listOf(busy("2026-11-19T18:00", "2026-11-19T20:00"), busy("2026-11-19T19:00", "2026-11-19T21:00"), busy("2026-11-19T21:00", "2026-11-19T21:30")),
            emptyList(), now, ny,
        ).shown()
        slots.none { it.startsWith("2026-11-19") } shouldBe true
        // an event nested in a longer one changes nothing
        freeSlots(listOf(busy("2026-11-17T09:00", "2026-11-17T19:00"), busy("2026-11-17T10:00", "2026-11-17T11:00")), emptyList(), now, ny).shown() shouldContain
            "2026-11-17T19:00..22:00"
    }

    test("an event that spans midnight takes both evenings it touches") {
        val slots = freeSlots(listOf(busy("2026-11-20T20:00", "2026-11-21T10:00")), emptyList(), now, ny).shown()
        slots shouldContain "2026-11-20T18:00..20:00"
        slots shouldContain "2026-11-21T10:00..22:00"
        slots.none { it.startsWith("2026-11-21T09") } shouldBe true
        // one that began last night and runs into the first evening
        freeSlots(listOf(busy("2026-11-15T22:00", "2026-11-16T21:00")), emptyList(), now, ny).shown().first() shouldBe "2026-11-17T18:00..22:00"
    }

    test("an all-day event takes its whole days, however many, and is read on the date it names") {
        val friday = BusySpan(Instant.parse("2026-11-20T00:00:00Z"), Instant.parse("2026-11-21T00:00:00Z"), allDay = true)
        val weekend = BusySpan(Instant.parse("2026-11-21T00:00:00Z"), Instant.parse("2026-11-23T00:00:00Z"), allDay = true)
        freeSlots(listOf(friday), emptyList(), now, ny).shown().none { it.startsWith("2026-11-20") } shouldBe true
        val rest = freeSlots(listOf(friday, weekend), emptyList(), now, ny).shown()
        rest shouldBe listOf("2026-11-16T18:00..22:00", "2026-11-17T18:00..22:00", "2026-11-18T18:00..22:00", "2026-11-19T18:00..22:00")
    }

    test("protected times are taken: a blocked evening, and a block that crosses midnight") {
        val blocks = listOf(
            TimeBlock("Family dinner", setOf(1), LocalTime(18, 0), LocalTime(20, 0)), // Monday
            TimeBlock("Quiet", setOf(2), LocalTime(17, 0), LocalTime(21, 30)), // Tuesday, leaves 30 min
            TimeBlock("Game night", setOf(5), LocalTime(20, 0), LocalTime(10, 0)), // Friday 20:00 into Saturday 10:00
        )
        val slots = freeSlots(emptyList(), blocks, now, ny).shown()
        slots shouldContain "2026-11-16T20:00..22:00"
        slots.none { it.startsWith("2026-11-17") } shouldBe true
        slots shouldContain "2026-11-20T18:00..20:00"
        slots shouldContain "2026-11-21T10:00..22:00"
    }

    test("a block that began yesterday still covers the start of today") {
        val sleepIn = listOf(TimeBlock("Rest", setOf(7), LocalTime(23, 0), LocalTime(11, 0))) // Sunday night into Monday
        val sundayNow = at("2026-11-22T08:00")
        // Sunday 09:00..22:00 is free (the block begins at 23:00), Monday morning does not matter for evenings.
        freeSlots(emptyList(), sleepIn, sundayNow, ny).shown().first() shouldBe "2026-11-22T10:00..22:00" // now + 2 h
    }

    test("weekend days are long blocks, split around an event") {
        val slots = freeSlots(listOf(busy("2026-11-21T12:00", "2026-11-21T14:00")), emptyList(), now, ny).shown()
        slots shouldContain "2026-11-21T09:00..12:00"
        slots shouldContain "2026-11-21T14:00..22:00"
        // a one-hour hole between two events is not a slot
        freeSlots(listOf(busy("2026-11-22T09:00", "2026-11-22T12:00"), busy("2026-11-22T13:00", "2026-11-22T20:30")), emptyList(), now, ny).shown()
            .none { it.startsWith("2026-11-22") } shouldBe true
    }

    test("a slot starts at least two hours from now, and today's evening can be gone") {
        freeSlots(emptyList(), emptyList(), at("2026-11-16T17:00"), ny).shown().first() shouldBe "2026-11-16T19:00..22:00"
        freeSlots(emptyList(), emptyList(), at("2026-11-16T20:30"), ny).shown().first() shouldBe "2026-11-17T18:00..22:00"
    }

    test("the horizon is seven days, today included") {
        val slots = freeSlots(emptyList(), emptyList(), now, ny)
        slots.last().start shouldBe at("2026-11-22T09:00")
        slots.none { it.start >= at("2026-11-23T00:00") } shouldBe true
    }

    test("daylight saving: slots follow the local clock") {
        // Friday 2027-03-12, clocks go forward on Sunday 14 at 02:00 (EST to EDT).
        val spring = freeSlots(emptyList(), emptyList(), at("2027-03-12T10:00"), ny)
        spring.first { it.start == at("2027-03-13T09:00") }.start shouldBe Instant.parse("2027-03-13T14:00:00Z") // 09:00 EST
        spring.first { it.start == at("2027-03-14T09:00") }.start shouldBe Instant.parse("2027-03-14T13:00:00Z") // 09:00 EDT
        // Fall back on Sunday 2026-11-01: Saturday 09:00 is EDT, Sunday 09:00 is EST, and both days run until 22:00 local.
        val fall = freeSlots(emptyList(), emptyList(), at("2026-10-30T10:00"), ny)
        fall.first { it.start == at("2026-10-31T09:00") }.start shouldBe Instant.parse("2026-10-31T13:00:00Z")
        fall.first { it.start == at("2026-11-01T09:00") }.start shouldBe Instant.parse("2026-11-01T14:00:00Z")
        fall.first { it.start == at("2026-11-01T09:00") }.end shouldBe Instant.parse("2026-11-02T03:00:00Z")
    }

    test("a gap of an hour after a skipped hour is not made two by the clock") {
        // Saturday 2027-03-13 20:00 to Sunday 09:30 taken: Sunday's slot starts at 09:30 local, and it is still 12.5 h.
        val slots = freeSlots(listOf(BusySpan(at("2027-03-13T20:00"), at("2027-03-14T09:30"))), emptyList(), at("2027-03-12T10:00"), ny)
        val sunday = slots.first { it.start == at("2027-03-14T09:30") }
        sunday.length shouldBe 12.hours + 30.minutes
    }

    test("the scout plan: nothing free or the week's two suggestions used means no model call") {
        val slot = Slot(at("2026-11-17T18:00"), at("2026-11-17T22:00"))
        scoutPlan(emptyList(), 0) shouldBe ScoutPlan.Skip(SkipReason.NO_FREE_SLOT)
        scoutPlan(listOf(slot), 2) shouldBe ScoutPlan.Skip(SkipReason.WEEK_BUDGET_USED)
        scoutPlan(listOf(slot), 3) shouldBe ScoutPlan.Skip(SkipReason.WEEK_BUDGET_USED)
        scoutPlan(listOf(slot), 1) shouldBe ScoutPlan.Offer(listOf(slot))
        scoutPlan(listOf(slot), 0).shouldBeInstanceOf<ScoutPlan.Offer>()
    }

    test("the scout plan offers the soonest few slots, and a no leaves nothing behind") {
        val slots = (0..5).map { Slot(at("2026-11-17T18:00") + it.days, at("2026-11-17T22:00") + it.days) }
        (scoutPlan(slots.reversed(), 0) as ScoutPlan.Offer).slots shouldBe slots.take(MAX_SLOTS_OFFERED)
        // The plan takes no input about answers: after a "not this time" the count of suggestions is what it was,
        // so the next run offers again right away (the declined idea itself is refused by ideaRefusal).
        scoutPlan(slots, 1).shouldBeInstanceOf<ScoutPlan.Offer>()
    }

    context("recentContacts") {
        val sam = ContactRef("c-sam", "Sam Example", listOf(Handle.email("sam@example.com"), Handle.phone("+1 (555) 555-0123")))
        val pat = ContactRef("c-pat", "Pat Example", listOf(Handle.email("pat@example.com")))
        val quiet = ContactRef("c-quiet", "Quiet Example", listOf(Handle.email("quiet@example.com")))
        val nobody = ContactRef("c-none", "No Handle", emptyList())
        val today = at("2026-11-16T14:00")
        fun msg(daysAgo: Int, vararg who: Handle) = MessageParties(today - daysAgo.days, who.toList())

        test("sent or received, email or SMS, formatting aside") {
            val found = recentContacts(
                listOf(sam, pat, quiet, nobody),
                listOf(
                    msg(3, Handle.email("SAM@example.com")), // received from Sam, case aside
                    msg(10, Handle.email("pat@example.com")), // sent to Pat
                    msg(1, Handle.phone("5555550123")), // an SMS with Sam's number written another way
                ),
                today,
            )
            found.map { it.id } shouldBe listOf("c-sam", "c-pat")
            found.first().lastAt shouldBe today - 1.days
        }

        test("a contact with no message, or no handle, is never company") {
            recentContacts(listOf(quiet, nobody), listOf(msg(1, Handle.email("stranger@example.com"))), today).shouldBeEmpty()
            recentContacts(listOf(sam), emptyList(), today).shouldBeEmpty()
            recentContacts(emptyList(), listOf(msg(1, Handle.email("sam@example.com"))), today).shouldBeEmpty()
        }

        test("the edge is ninety days: the day itself counts, a second earlier does not") {
            val edge = MessageParties(today - 90.days, listOf(Handle.email("pat@example.com")))
            recentContacts(listOf(pat), listOf(edge), today) shouldHaveSize 1
            recentContacts(listOf(pat), listOf(edge.copy(sentAt = edge.sentAt - 1.seconds)), today).shouldBeEmpty()
            recentContacts(listOf(pat), listOf(edge.copy(sentAt = today - 89.days)), today) shouldHaveSize 1
        }

        test("only the newest message counts, and a message from the future is no exchange") {
            val msgs = listOf(msg(80, Handle.email("sam@example.com")), msg(5, Handle.email("sam@example.com")), msg(120, Handle.email("sam@example.com")))
            recentContacts(listOf(sam), msgs, today).single().lastAt shouldBe today - 5.days
            recentContacts(listOf(sam), listOf(MessageParties(today + 1.days, listOf(Handle.email("sam@example.com")))), today).shouldBeEmpty()
        }

        test("most recent first, ties by name") {
            val a = ContactRef("a", "Zed", listOf(Handle.email("z@example.com")))
            val b = ContactRef("b", "Amy", listOf(Handle.email("a@example.com")))
            val c = ContactRef("c", "Bo", listOf(Handle.email("b@example.com")))
            val at1 = today - 2.days
            recentContacts(
                listOf(a, b, c),
                listOf(MessageParties(at1, listOf(Handle.email("z@example.com"))), MessageParties(at1, listOf(Handle.email("a@example.com"))), MessageParties(today - 1.days, listOf(Handle.email("b@example.com")))),
                today,
            ).map { it.name } shouldBe listOf("Bo", "Amy", "Zed")
        }
    }

    context("ideas") {
        fun idea(what: String, state: IdeaState, url: String? = null) =
            SavedIdea("i-$what", what, null, null, null, null, null, null, url, state, Instant.parse("2026-11-01T00:00:00Z"))

        test("a declined idea cannot be saved again, in other spelling or at the same address") {
            val declined = listOf(idea("Jazz night at the Blue Room", IdeaState.DECLINED, "https://example.com/jazz"))
            ideaRefusal("jazz night at the blue room!", null, declined)!! shouldContain "never suggested again"
            ideaRefusal("Something else", "https://example.com/jazz/", declined)!! shouldContain "never suggested again"
            ideaRefusal("Pottery class", "https://example.com/pots", declined) shouldBe null
        }

        test("open and used duplicates are told apart from declined ones, and blank is refused") {
            ideaRefusal("Hike", null, listOf(idea("Hike", IdeaState.OPEN)))!! shouldContain "already saved"
            ideaRefusal("Hike", null, listOf(idea("Hike", IdeaState.USED)))!! shouldContain "calendar"
            ideaRefusal("  ", null, emptyList()) shouldNotBe null
        }

        test("an idea fits a slot when open and, if bound to a window, inside it") {
            val slot = Slot(at("2026-11-19T18:00"), at("2026-11-19T22:00"))
            val open = idea("Gig", IdeaState.OPEN)
            open.fits(slot) shouldBe true
            open.copy(from = at("2026-11-19T20:00"), to = at("2026-11-19T23:00")).fits(slot) shouldBe true
            open.copy(from = at("2026-11-20T20:00"), to = at("2026-11-20T23:00")).fits(slot) shouldBe false
            open.copy(state = IdeaState.DECLINED).fits(slot) shouldBe false
        }
    }

    test("the brief names the slots, only the allowed people and the web search limits") {
        val slot = Slot(at("2026-11-19T18:00"), at("2026-11-19T22:00"))
        val text = outingInstruction(listOf(slot), listOf(RecentContact("c-sam", "Sam Example", at("2026-11-10T10:00"))), 2, ny)
        text shouldContain "2026-11-19T18:00-05:00 (Thu) until 2026-11-19T22:00-05:00 (Thu)"
        text shouldContain "Sam Example (contact c-sam)"
        text shouldContain "nobody else"
        text shouldContain "Saved ideas waiting: 2"
        text shouldContain "only the city, the time window"
        text shouldContain "web_search"
        text shouldNotContain "@"
        outingInstruction(listOf(slot), emptyList(), 0, ny) shouldContain "the person alone"
    }
})
