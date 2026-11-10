package net.robog.clarity.backend.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import net.robog.clarity.shared.backup.BackupKind

private fun nightly(day: Int) = BackupNames.nightly(LocalDate(2026, 11, day))

class BackupFilesTest : FunSpec({
    test("names round-trip, and only exact backup names parse") {
        BackupNames.nightly(LocalDate(2026, 11, 10)) shouldBe "clarity-2026-11-10.dump"
        BackupNames.beforeRollback(LocalDateTime(2026, 11, 10, 14, 5)) shouldBe "clarity-2026-11-10T1405-before-rollback.dump"
        BackupNames.parse("clarity-2026-11-10.dump")!!.kind shouldBe BackupKind.NIGHTLY
        BackupNames.parse("clarity-2026-11-10T1405-before-rollback.dump")!!.kind shouldBe BackupKind.BEFORE_ROLLBACK
        listOf("../clarity-2026-11-10.dump", "clarity-2026-11-10.dump.part", "clarity-2026-13-45.dump", ".tmp-1", "rollback-result.json", "x/clarity-2026-11-10.dump")
            .forEach { BackupNames.parse(it).shouldBeNull() }
    }

    test("retention keeps the seven newest nightly files and the two newest before-rollback files") {
        val nightlies = (1..10).map { nightly(it) }
        val safeties = listOf("clarity-2026-11-03T0900-before-rollback.dump", "clarity-2026-11-05T2300-before-rollback.dump", "clarity-2026-11-05T0100-before-rollback.dump")
        val other = listOf("notes.txt", "rollback-result.json")
        val gone = filesToDelete(nightlies + safeties + other, keepNightly = 7, keepBeforeRollback = 2)
        gone shouldContainExactlyInAnyOrder listOf(nightly(1), nightly(2), nightly(3), "clarity-2026-11-03T0900-before-rollback.dump")
    }

    test("nothing is deleted while there are fewer files than the limits") {
        filesToDelete((1..7).map { nightly(it) } + "clarity-2026-11-07T0200-before-rollback.dump", 7, 2).shouldBeEmpty()
    }

    test("the days sort across a month end") {
        val names = listOf("clarity-2026-10-31.dump", "clarity-2026-11-01.dump", "clarity-2026-11-02.dump")
        filesToDelete(names, keepNightly = 2, keepBeforeRollback = 2) shouldBe listOf("clarity-2026-10-31.dump")
    }
})

private val newYork = TimeZone.of("America/New_York")
private val berlin = TimeZone.of("Europe/Berlin")

class NightlyScheduleTest : FunSpec({
    test("the next run is 02:00 on the home clock") {
        // 2026-11-10 09:00 in New York (EST, UTC-5) -> next 02:00 is 2026-11-11 07:00Z
        NightlySchedule.nextRun(Instant.parse("2026-11-10T14:00:00Z"), newYork) shouldBe Instant.parse("2026-11-11T07:00:00Z")
        // 01:30 in New York -> the same night's 02:00
        NightlySchedule.nextRun(Instant.parse("2026-11-10T06:30:00Z"), newYork) shouldBe Instant.parse("2026-11-10T07:00:00Z")
    }

    test("it stays at 02:00 local across the clock changes") {
        // clocks go back in New York on 2026-11-01 at 02:00 EDT -> 01:00 EST, so 02:00 happens once, as EST (07:00Z)
        NightlySchedule.nextRun(Instant.parse("2026-10-31T12:00:00Z"), newYork) shouldBe Instant.parse("2026-11-01T07:00:00Z")
        NightlySchedule.nextRun(Instant.parse("2026-11-01T12:00:00Z"), newYork) shouldBe Instant.parse("2026-11-02T07:00:00Z")
        // and it does not run twice on the repeated hour
        NightlySchedule.nextRun(Instant.parse("2026-11-01T07:00:00Z"), newYork) shouldBe Instant.parse("2026-11-02T07:00:00Z")
        // Berlin goes back on 2026-10-25 (02:00 CEST = 00:00Z, then 02:00 CET = 01:00Z)
        NightlySchedule.nextRun(Instant.parse("2026-10-24T12:00:00Z"), berlin) shouldBe Instant.parse("2026-10-25T00:00:00Z")
        NightlySchedule.nextRun(Instant.parse("2026-10-25T12:00:00Z"), berlin) shouldBe Instant.parse("2026-10-26T01:00:00Z")
    }

    test("on the night 02:00 does not exist it still runs once, right after the gap") {
        // New York 2027-03-14: 02:00 EST jumps to 03:00 EDT (07:00Z). The next run after the evening before is the one on the 14th at the earliest 03:00 EDT.
        val next = NightlySchedule.nextRun(Instant.parse("2027-03-14T01:00:00Z"), newYork)
        next shouldBe Instant.parse("2027-03-14T07:00:00Z")
        // and the day after is back to 02:00 EDT = 06:00Z
        NightlySchedule.nextRun(next, newYork) shouldBe Instant.parse("2027-03-15T06:00:00Z")
        // Berlin 2027-03-28: 02:00 CET jumps to 03:00 CEST (01:00Z)
        NightlySchedule.nextRun(Instant.parse("2027-03-27T12:00:00Z"), berlin) shouldBe Instant.parse("2027-03-28T01:00:00Z")
    }

    test("after 02:00 the missing night is today, before it yesterday") {
        val have = setOf(LocalDate(2026, 11, 8))
        // 03:00 on the 10th: tonight's slot has passed; the 10th is missing
        NightlySchedule.missing(Instant.parse("2026-11-10T08:00:00Z"), newYork, have) shouldBe LocalDate(2026, 11, 10)
        // 01:00 on the 10th: the latest slot was the 9th's
        NightlySchedule.missing(Instant.parse("2026-11-10T06:00:00Z"), newYork, have) shouldBe LocalDate(2026, 11, 9)
    }

    test("a backend that was down at 02:00 makes the missed one at start-up, once") {
        val zone = newYork
        val have = mutableSetOf(LocalDate(2026, 11, 8), LocalDate(2026, 11, 9))
        val startedAt = Instant.parse("2026-11-10T15:00:00Z") // 10:00 on the 10th, after missing the 02:00 slot
        val due = NightlySchedule.missing(startedAt, zone, have)
        due shouldBe LocalDate(2026, 11, 10)
        have += due!!
        NightlySchedule.missing(startedAt, zone, have).shouldBeNull()
        // downtime of several days: only the latest night is made, not every missed one
        NightlySchedule.missing(Instant.parse("2026-11-14T15:00:00Z"), zone, have) shouldBe LocalDate(2026, 11, 14)
    }

    test("nothing is missing right after a nightly backup") {
        val have = setOf(LocalDate(2026, 11, 10))
        NightlySchedule.missing(Instant.parse("2026-11-10T07:05:00Z"), newYork, have).shouldBeNull()
    }

    test("on the night of the gap the slot passes at 03:00 local, not before") {
        // Berlin 2027-03-28: at 00:30Z it is 01:30 CET, still the 27th's slot
        NightlySchedule.missing(Instant.parse("2027-03-28T00:30:00Z"), berlin, emptySet()) shouldBe LocalDate(2027, 3, 27)
        // at 01:00Z it is 03:00 CEST: the 28th's slot has passed
        NightlySchedule.missing(Instant.parse("2027-03-28T01:00:00Z"), berlin, emptySet()) shouldBe LocalDate(2027, 3, 28)
    }
})
