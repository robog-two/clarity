package net.robog.clarity.backend.scheduler

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

private class FakeClock(var now: Instant) : Clock {
    override fun now(): Instant = now
}

private class MemoryStore(val runs: MutableMap<String, Instant> = mutableMapOf()) : JobRunStore {
    override suspend fun lastRun(name: String) = runs[name]
    override suspend fun markRun(name: String, at: Instant) { runs[name] = at }
}

private class CountingJob(override val interval: Duration = 2.hours, val fail: Boolean = false) : ScheduledJob {
    override val name = "job"
    val calls = mutableListOf<Pair<Instant?, Instant>>()
    override suspend fun run(lastRun: Instant?, now: Instant) {
        calls += lastRun to now
        if (fail) error("boom")
    }
}

private val t0 = Instant.parse("2026-06-01T00:00:00Z")

class SchedulerTest : FunSpec({
    test("timeUntilDue") {
        timeUntilDue(null, t0, 2.hours) shouldBe Duration.ZERO
        timeUntilDue(t0, t0 + 30.minutes, 2.hours) shouldBe 90.minutes
        timeUntilDue(t0, t0 + 3.hours, 2.hours) shouldBe (-1).hours
    }

    test("first tick runs immediately with no last run, then waits a full interval") { runTest {
        val clock = FakeClock(t0)
        val store = MemoryStore()
        val job = CountingJob()
        val scheduler = Scheduler(setOf(job), store, clock)

        scheduler.tick(job) shouldBe 2.hours
        job.calls shouldBe listOf(null to t0)
        store.runs["job"] shouldBe t0
    } }

    test("a restart inside the interval does not fire again and waits only the remainder") { runTest {
        val clock = FakeClock(t0 + 30.minutes)
        val store = MemoryStore(mutableMapOf("job" to t0))
        val job = CountingJob()

        Scheduler(setOf(job), store, clock).tick(job) shouldBe 90.minutes
        job.calls shouldBe emptyList()
    } }

    test("when due it passes the persisted last run") { runTest {
        val clock = FakeClock(t0 + 2.hours)
        val store = MemoryStore(mutableMapOf("job" to t0))
        val job = CountingJob()

        Scheduler(setOf(job), store, clock).tick(job) shouldBe 2.hours
        job.calls shouldBe listOf(t0 to t0 + 2.hours)
        store.runs["job"] shouldBe t0 + 2.hours
    } }

    test("a failing job is not marked as run and is retried soon") { runTest {
        val clock = FakeClock(t0)
        val store = MemoryStore()
        val job = CountingJob(fail = true)

        Scheduler(setOf(job), store, clock).tick(job) shouldBe 1.minutes
        store.runs shouldBe emptyMap()
    } }
})
