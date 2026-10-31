package net.robog.clarity.backend.scheduler

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalTime
import net.robog.clarity.backend.agent.ExposedInboxStore
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.InboxSignals
import net.robog.clarity.backend.agent.ScheduledJobs
import net.robog.clarity.backend.agent.SettingsProvider
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.feed.ExposedFeedStore
import net.robog.clarity.backend.feed.FeedDraft
import net.robog.clarity.backend.feed.FeedHub
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.session.Settings
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import org.testcontainers.postgresql.PostgreSQLContainer

private class CountingJob(
    override val name: String,
    private val schedule: Schedule,
    private val fail: Boolean = false,
) : ScheduledJob {
    val fired = AtomicInteger()
    val contexts = java.util.concurrent.CopyOnWriteArrayList<FireContext>()
    override fun schedule(settings: Settings) = schedule
    override suspend fun fire(context: FireContext) {
        if (fail) error("boom")
        fired.incrementAndGet()
        contexts += context
    }
}

/** Throws on its first [failTimes] fires, then succeeds; records the slot of every try. */
private class FlakyJob(override val name: String, private val schedule: Schedule, private val failTimes: Int) : ScheduledJob {
    val slots = java.util.concurrent.CopyOnWriteArrayList<Instant>()
    val succeeded = AtomicInteger()
    override fun schedule(settings: Settings) = schedule
    override suspend fun fire(context: FireContext) {
        slots += context.scheduledFor
        if (slots.size <= failTimes) error("boom ${slots.size}")
        succeeded.incrementAndGet()
    }
}

@EnabledIf(ContainerRuntimeAvailable::class)
class SchedulerClaimTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }
    beforeTest { database.tx { exec("TRUNCATE scheduled_job, inbox_item, feed_item RESTART IDENTITY CASCADE") } }

    val settings = SettingsProvider { Settings() }

    suspend fun makeDue(name: String, ago: kotlin.time.Duration) {
        database.tx { ScheduledJobs.update({ ScheduledJobs.name eq name }) { it[nextRunAt] = Clock.System.now() - ago } }
    }

    suspend fun nextRun(name: String): Instant =
        database.tx { ScheduledJobs.selectAll().where { ScheduledJobs.name eq name }.single()[ScheduledJobs.nextRunAt] }

    suspend fun failuresOf(name: String): Int =
        database.tx { ScheduledJobs.selectAll().where { ScheduledJobs.name eq name }.single()[ScheduledJobs.failures] }

    test("new jobs get a row scheduled in the future and are not fired immediately") {
        val job = CountingJob("a", Schedule.Every(2.hours))
        val scheduler = Scheduler(database, setOf(job), settings, Clock.System)
        scheduler.ensureRows()
        scheduler.tickOnce() shouldBe false
        (nextRun("a") > Clock.System.now()) shouldBe true
    }

    test("two schedulers racing for a due job fire it exactly once") {
        val job = CountingJob("race", Schedule.Every(1.hours))
        val one = Scheduler(database, setOf(job), settings, Clock.System)
        val two = Scheduler(database, setOf(job), settings, Clock.System)
        one.ensureRows()
        makeDue("race", 5.hours)

        coroutineScope {
            (1..8).map { i -> async { (if (i % 2 == 0) one else two).tickOnce() } }.awaitAll()
        }
        job.fired.get() shouldBe 1
        (nextRun("race") > Clock.System.now()) shouldBe true
    }

    test("a restart does not fire a job that already ran in its slot") {
        val job = CountingJob("restart", Schedule.Every(1.hours))
        Scheduler(database, setOf(job), settings, Clock.System).also { it.ensureRows() }
        makeDue("restart", 1.hours)
        Scheduler(database, setOf(job), settings, Clock.System).tickOnce() shouldBe true
        // a new process starts: ensureRows keeps the existing row, nothing is due
        val rebooted = Scheduler(database, setOf(job), settings, Clock.System)
        rebooted.ensureRows()
        rebooted.tickOnce() shouldBe false
        job.fired.get() shouldBe 1
    }

    test("after downtime a daily job fires once and moves to the next slot after now") {
        val job = CountingJob("daily", Schedule.DailyAt(LocalTime(3, 30)))
        val scheduler = Scheduler(database, setOf(job), settings, Clock.System)
        scheduler.ensureRows()
        makeDue("daily", 3.days)
        scheduler.tickOnce() shouldBe true
        scheduler.tickOnce() shouldBe false
        job.fired.get() shouldBe 1
        (nextRun("daily") > Clock.System.now()) shouldBe true
        (nextRun("daily") <= Clock.System.now() + 1.days + 1.hours) shouldBe true
    }

    // Guards against: a job that throws being re-claimed first on every tick, so the jobs due after it never fire.
    test("a job that throws does not stop another due job from firing") {
        val bad = CountingJob("bad", Schedule.Every(1.hours), fail = true)
        val good = CountingJob("good", Schedule.Every(1.hours))
        val scheduler = Scheduler(database, setOf(bad, good), settings, Clock.System)
        scheduler.ensureRows()
        makeDue("bad", 2.hours) // the oldest due row: claimed first
        makeDue("good", 1.hours)
        var ticks = 0
        while (scheduler.tickOnce()) ticks++.also { check(it < 10) { "the scheduler kept finding due work" } }
        good.fired.get() shouldBe 1
        bad.fired.get() shouldBe 0
        (nextRun("bad") > Clock.System.now()) shouldBe true
        failuresOf("bad") shouldBe 1
    }

    // Guards against: a failing job being retried every tick forever (or never again).
    test("a failing job is retried later and later, and the delay stops growing at the cap") {
        val bad = CountingJob("flaky", Schedule.Every(1.days), fail = true)
        val scheduler = Scheduler(database, setOf(bad), settings, Clock.System)
        scheduler.ensureRows()
        val delays = (1..9).map {
            makeDue("flaky", 1.minutes)
            val before = Clock.System.now()
            scheduler.tickOnce() shouldBe true
            nextRun("flaky") - before
        }
        val expected = listOf(1, 2, 4, 8, 16, 32, 60, 60, 60).map { it.minutes }
        delays.zip(expected).forEach { (got, want) -> got.inWholeSeconds shouldBeInRange (want.inWholeSeconds - 5)..(want.inWholeSeconds + 5) }
        failuresOf("flaky") shouldBe 9
    }

    // Guards against: a retry pushing a frequent job past its own next regular run.
    test("a failing job's retry is never later than its regular next run") {
        val bad = CountingJob("often", Schedule.Every(3.minutes), fail = true)
        val scheduler = Scheduler(database, setOf(bad), settings, Clock.System)
        scheduler.ensureRows()
        repeat(6) {
            makeDue("often", 1.minutes)
            scheduler.tickOnce() shouldBe true
        }
        (nextRun("often") <= Clock.System.now() + 3.minutes) shouldBe true
    }

    // Guards against: retries of one slot getting a new idempotency key each time, so their side effects repeat.
    test("retries of a failed slot see the same scheduledFor; success clears the failure count") {
        val job = FlakyJob("slot", Schedule.Every(1.hours), failTimes = 2)
        val scheduler = Scheduler(database, setOf(job), settings, Clock.System)
        scheduler.ensureRows()
        makeDue("slot", 2.hours)
        val slot = nextRun("slot")
        scheduler.tickOnce() shouldBe true
        repeat(2) {
            makeDue("slot", 1.minutes) // the retry is due now (the backoff had moved it on)
            scheduler.tickOnce() shouldBe true
        }
        job.slots shouldBe listOf(slot, slot, slot)
        job.succeeded.get() shouldBe 1
        failuresOf("slot") shouldBe 0
        database.tx { ScheduledJobs.selectAll().where { ScheduledJobs.name eq "slot" }.single()[ScheduledJobs.retrySlot] } shouldBe null
        (nextRun("slot") > Clock.System.now() + 50.minutes) shouldBe true
    }

    // Guards against: the failure path rolling back the backoff along with the job's writes.
    test("a failing job's own writes roll back, but its backoff is kept") {
        val writer = object : ScheduledJob {
            override val name = "writer"
            override fun schedule(settings: Settings) = Schedule.Every(1.hours)
            override suspend fun fire(context: FireContext) {
                with(ExposedInboxStore(database, Clock.System, InboxSignals())) {
                    TransactionManager.current().enqueueTx(InboxItem.JobDue("writer", "x"), "writer:${context.scheduledFor}")
                }
                error("boom after writing")
            }
        }
        val scheduler = Scheduler(database, setOf(writer), settings, Clock.System)
        scheduler.ensureRows()
        makeDue("writer", 1.hours)
        scheduler.tickOnce() shouldBe true
        failuresOf("writer") shouldBe 1
        (nextRun("writer") > Clock.System.now()) shouldBe true
        ExposedInboxStore(database, Clock.System, InboxSignals()).pending().shouldBeEmpty()
    }

    test("the failure backoff doubles from one minute and is capped at an hour") {
        Scheduler.failureBackoff(1) shouldBe 1.minutes
        Scheduler.failureBackoff(2) shouldBe 2.minutes
        Scheduler.failureBackoff(6) shouldBe 32.minutes
        Scheduler.failureBackoff(7) shouldBe 1.hours
        Scheduler.failureBackoff(1_000) shouldBe 1.hours
        Scheduler.failureBackoff(0) shouldBe 1.minutes
    }

    test("rows of jobs that no longer exist are dropped") {
        Scheduler(database, setOf(CountingJob("old", Schedule.Every(1.hours))), settings, Clock.System).ensureRows()
        Scheduler(database, setOf(CountingJob("new", Schedule.Every(1.hours))), settings, Clock.System).ensureRows()
        database.tx { ScheduledJobs.selectAll().map { it[ScheduledJobs.name] } } shouldBe listOf("new")
    }

    test("the periodic review enqueues one low-priority job item per slot, even if it fires twice") {
        val inbox = ExposedInboxStore(database, Clock.System, InboxSignals())
        val config = AgentConfig(reviewInterval = 2.hours)
        val gate = ReviewGate(config, ExposedReviewChanges(database), net.robog.clarity.backend.agent.Decisions(config, { null }, database, Clock.System))
        val job = PeriodicReviewJob(config, inbox, gate)
        val slot = Instant.parse("2026-10-25T10:00:00Z")
        val ctx = FireContext(slot, slot + 1.hours, lastRunAt = null, settings = Settings())
        job.fire(ctx)
        job.fire(ctx)
        inbox.pending().map { it.item }.single().let {
            (it as InboxItem.JobDue).job shouldBe "periodic-review"
        }
    }

    test("the stored notify decision comes back on the feed item, and defaults to in-app") {
        val feedStore = ExposedFeedStore(database, Clock.System)
        val pushed = feedStore.append(FeedDraft(FeedItem.Author.AGENT, listOf(Card.Text("Urgent")), notify = "push_now")).item
        val plain = feedStore.append(FeedDraft(FeedItem.Author.AGENT, listOf(Card.Text("Plain")))).item
        pushed.notify shouldBe "push_now"
        plain.notify shouldBe "in_app"
        feedStore.page(pushed.seq - 1, 10).items.first { it.id == pushed.id }.notify shouldBe "push_now"
    }

    test("the evening batch turns held items into one summary and does not post twice") {
        val feedStore = ExposedFeedStore(database, Clock.System)
        val feed = FeedService(feedStore, FeedHub())
        feed.append(FeedDraft(FeedItem.Author.AGENT, listOf(Card.Text("Newsletter ready")), batchPending = true))
        feed.append(FeedDraft(FeedItem.Author.AGENT, listOf(Card.Note("Receipt filed")), batchPending = true))
        val job = EveningBatchJob(feedStore, feed)
        val ctx = FireContext(Instant.parse("2026-10-25T18:00:00Z"), Instant.parse("2026-10-25T18:00:05Z"), null, Settings())
        job.fire(ctx)
        job.fire(ctx) // a replay after a crash finds nothing pending and posts nothing
        val visible = feed.page(0, 10).items
        visible shouldHaveSize 1
        (visible.single().cards[0] as Card.Text).text shouldBe "2 things waited for tonight."
        visible.single().notify shouldBe "evening_batch:2026-10-25T18:00:00Z" // the phone posts it on the low-importance channel
        feedStore.pendingBatch().shouldBeEmpty()
    }
})
