package net.robog.clarity.backend.governance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.LocalTime
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.Person
import net.robog.clarity.shared.governance.Urgency
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.session.Settings

private fun at(s: String) = Instant.parse(s)

private val dana = Handle.email("dana@example.com")
private val stranger = Handle.email("spam@example.com")

private fun base(
    protectedTimes: List<net.robog.clarity.shared.governance.TimeBlock> = emptyList(),
    people: List<Person> = emptyList(),
) = Constitution(version = 1, lines = emptyList(), getsThrough = people, protectedTimes = protectedTimes, updatedAt = T0)

private val utc = Settings()

class NotifyPolicyTest : FunSpec({

    val deepWork = block("deep work", setOf(1, 2, 3, 4, 5), "09:00", "12:00")
    val weekdayMorning = base(listOf(deepWork), listOf(Person("Dana", listOf(dana))))

    test("urgent outside protected time pushes now") {
        notifyDecision(Urgency.URGENT, listOf(stranger), weekdayMorning, utc, at("2026-03-04T13:00:00Z")) shouldBe NotifyDecision.PushNow
    }

    test("urgent inside protected time is held until the block ends, unless the sender gets through") {
        val now = at("2026-03-04T10:30:00Z") // Wednesday
        notifyDecision(Urgency.URGENT, listOf(stranger), weekdayMorning, utc, now) shouldBe NotifyDecision.HoldUntil(at("2026-03-04T12:00:00Z"))
        notifyDecision(Urgency.URGENT, listOf(dana), weekdayMorning, utc, now) shouldBe NotifyDecision.PushNow
        notifyDecision(Urgency.URGENT, listOf(Handle.email(" DANA@example.com ")), weekdayMorning, utc, now) shouldBe NotifyDecision.PushNow
    }

    test("a who-gets-through entry that has expired no longer gets through") {
        val expired = base(listOf(deepWork), listOf(Person("Dana", listOf(dana), until = at("2026-03-04T10:00:00Z"))))
        notifyDecision(Urgency.URGENT, listOf(dana), expired, utc, at("2026-03-04T10:30:00Z")) shouldBe NotifyDecision.HoldUntil(at("2026-03-04T12:00:00Z"))
    }

    test("stored protected blocks stay on the home clock through daylight saving") {
        // 09:00-12:00 on the New York clock; daylight saving starts 2026-03-08.
        val morning = base(listOf(block("deep work", setOf(1, 2, 3, 4, 5), "09:00", "12:00")))
        val newYork = utc.copy(homeZone = "America/New_York")
        // Wednesday 2026-03-04, EST: 14:30 UTC is 09:30
        notifyDecision(Urgency.URGENT, listOf(stranger), morning, newYork, at("2026-03-04T14:30:00Z")) shouldBe
            NotifyDecision.HoldUntil(at("2026-03-04T17:00:00Z"))
        // Wednesday 2026-03-11, EDT: 13:30 UTC is 09:30, inside, an hour earlier in UTC than in winter
        notifyDecision(Urgency.URGENT, listOf(stranger), morning, newYork, at("2026-03-11T13:30:00Z")) shouldBe
            NotifyDecision.HoldUntil(at("2026-03-11T16:00:00Z"))
        // 16:30 UTC is 12:30 EDT: outside
        notifyDecision(Urgency.URGENT, listOf(stranger), morning, newYork, at("2026-03-11T16:30:00Z")) shouldBe NotifyDecision.PushNow
    }

    test("Berlin protected hours stay on the Berlin clock through the autumn change") {
        // 09:00-12:00 on weekend days; Berlin goes back to CET on Sunday 2026-10-25.
        val weekend = base(listOf(block("drawing", setOf(6, 7), "09:00", "12:00")))
        val berlin = utc.copy(homeZone = "Europe/Berlin")
        // Saturday 09:30 CEST
        notifyDecision(Urgency.URGENT, listOf(stranger), weekend, berlin, at("2026-10-24T07:30:00Z")) shouldBe
            NotifyDecision.HoldUntil(at("2026-10-24T10:00:00Z"))
        // Sunday 09:30 CET
        notifyDecision(Urgency.URGENT, listOf(stranger), weekend, berlin, at("2026-10-25T08:30:00Z")) shouldBe
            NotifyDecision.HoldUntil(at("2026-10-25T11:00:00Z"))
        // Sunday 08:30 CET is before the block, though 07:30Z was inside it on Saturday
        notifyDecision(Urgency.URGENT, listOf(stranger), weekend, berlin, at("2026-10-25T07:30:00Z")) shouldBe NotifyDecision.PushNow
    }

    test("an unknown zone id falls back to UTC") {
        notifyDecision(Urgency.URGENT, listOf(stranger), weekdayMorning, utc.copy(homeZone = "Mars/Olympus"), at("2026-03-04T10:30:00Z")) shouldBe
            NotifyDecision.HoldUntil(at("2026-03-04T12:00:00Z"))
    }

    test("days refer to the day the block starts on") {
        // Saturday 2026-03-07 10:30 is not a weekday block
        notifyDecision(Urgency.URGENT, listOf(stranger), weekdayMorning, utc, at("2026-03-07T10:30:00Z")) shouldBe NotifyDecision.PushNow
    }

    test("a block crossing midnight protects both sides, and the Friday-night block runs into Saturday") {
        val sleep = base(listOf(block("sleep", setOf(1, 2, 3, 4, 5), "22:00", "07:00")))
        val night = at("2026-03-04T23:00:00Z") // Wednesday night
        notifyDecision(Urgency.URGENT, listOf(stranger), sleep, utc, night) shouldBe NotifyDecision.HoldUntil(at("2026-03-05T07:00:00Z"))
        val small = at("2026-03-05T03:00:00Z") // Thursday 03:00, still Wednesday's block
        notifyDecision(Urgency.URGENT, listOf(stranger), sleep, utc, small) shouldBe NotifyDecision.HoldUntil(at("2026-03-05T07:00:00Z"))
        val fridayNight = at("2026-03-06T23:30:00Z")
        notifyDecision(Urgency.URGENT, listOf(stranger), sleep, utc, fridayNight) shouldBe NotifyDecision.HoldUntil(at("2026-03-07T07:00:00Z"))
        // Saturday night does not start a block (Saturday = 6 not in days), but Saturday 03:00 is Friday's
        notifyDecision(Urgency.URGENT, listOf(stranger), sleep, utc, at("2026-03-07T23:30:00Z")) shouldBe NotifyDecision.PushNow
        notifyDecision(Urgency.URGENT, listOf(stranger), sleep, utc, at("2026-03-07T03:00:00Z")) shouldBe NotifyDecision.HoldUntil(at("2026-03-07T07:00:00Z"))
        notifyDecision(Urgency.URGENT, listOf(stranger), sleep, utc, at("2026-03-04T12:00:00Z")) shouldBe NotifyDecision.PushNow
    }

    test("back-to-back blocks merge") {
        val c = base(listOf(deepWork, block("review", setOf(3), "12:00", "13:00")))
        notifyDecision(Urgency.URGENT, listOf(stranger), c, utc, at("2026-03-04T10:00:00Z")) shouldBe NotifyDecision.HoldUntil(at("2026-03-04T13:00:00Z"))
    }

    test("urgent with push disabled stays in the app") {
        notifyDecision(Urgency.URGENT, listOf(dana), weekdayMorning, utc.copy(pushUrgent = false), T0) shouldBe NotifyDecision.ShowInApp
    }

    test("normal urgency is shown in the app, even in protected time") {
        notifyDecision(Urgency.NORMAL, listOf(stranger), weekdayMorning, utc, at("2026-03-04T10:30:00Z")) shouldBe NotifyDecision.ShowInApp
    }

    test("batch waits for the evening summary, rolling to tomorrow once it has passed") {
        // 18:00 in New York
        val settings = utc.copy(eveningSummaryAt = LocalTime(18, 0), homeZone = "America/New_York")
        // 2026-03-04 15:00 UTC = 10:00 EST: summary at 18:00 EST = 23:00 UTC
        notifyDecision(Urgency.BATCH, emptyList(), base(), settings, at("2026-03-04T15:00:00Z")) shouldBe NotifyDecision.EveningBatch(at("2026-03-04T23:00:00Z"))
        // 2026-03-05 00:30 UTC = 19:30 EST on the 4th: next summary is the 5th at 18:00 EST
        notifyDecision(Urgency.BATCH, emptyList(), base(), settings, at("2026-03-05T00:30:00Z")) shouldBe NotifyDecision.EveningBatch(at("2026-03-05T23:00:00Z"))
    }

    test("the evening summary follows daylight saving") {
        val settings = utc.copy(eveningSummaryAt = LocalTime(18, 0), homeZone = "America/New_York")
        // DST starts 2026-03-08: on the 9th, 18:00 is EDT (UTC-4) = 22:00 UTC
        nextEveningSummary(at("2026-03-09T12:00:00Z"), settings) shouldBe at("2026-03-09T22:00:00Z")
        nextEveningSummary(at("2026-03-09T12:00:00Z") + 0.days, settings.copy(homeZone = "UTC", eveningSummaryAt = LocalTime(18, 0))) shouldBe at("2026-03-09T18:00:00Z")
    }

    test("a 19:00 summary is 7 pm at home across the autumn change, in New York and in Berlin") {
        val newYork = utc.copy(eveningSummaryAt = LocalTime(19, 0), homeZone = "America/New_York")
        // Monday 2026-10-26, EDT: 23:00Z (it used to fire at 18:00Z, 2 pm)
        notifyDecision(Urgency.BATCH, emptyList(), base(), newYork, at("2026-10-26T12:00:00Z")) shouldBe NotifyDecision.EveningBatch(at("2026-10-26T23:00:00Z"))
        // clocks go back on 2026-11-01: 19:00 EST is midnight UTC
        nextEveningSummary(at("2026-11-01T12:00:00Z"), newYork) shouldBe at("2026-11-02T00:00:00Z")
        val berlin = newYork.copy(homeZone = "Europe/Berlin")
        // Berlin goes back on 2026-10-25: 17:00Z in CEST, 18:00Z in CET
        nextEveningSummary(at("2026-10-24T12:00:00Z"), berlin) shouldBe at("2026-10-24T17:00:00Z")
        nextEveningSummary(at("2026-10-25T12:00:00Z"), berlin) shouldBe at("2026-10-25T18:00:00Z")
    }

    test("batch with batching off is shown in the app") {
        notifyDecision(Urgency.BATCH, emptyList(), base(), utc.copy(batchLowUrgency = false), T0) shouldBe NotifyDecision.ShowInApp
    }
})
