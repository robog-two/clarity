package net.robog.clarity.backend.scheduler

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
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

    test("a job that throws is not advanced, so it is retried") {
        val bad = CountingJob("bad", Schedule.Every(1.hours), fail = true)
        val scheduler = Scheduler(database, setOf(bad), settings, Clock.System)
        scheduler.ensureRows()
        makeDue("bad", 1.hours)
        val before = nextRun("bad")
        shouldThrow<IllegalStateException> { scheduler.tickOnce() }
        nextRun("bad") shouldBe before
    }

    test("rows of jobs that no longer exist are dropped") {
        Scheduler(database, setOf(CountingJob("old", Schedule.Every(1.hours))), settings, Clock.System).ensureRows()
        Scheduler(database, setOf(CountingJob("new", Schedule.Every(1.hours))), settings, Clock.System).ensureRows()
        database.tx { ScheduledJobs.selectAll().map { it[ScheduledJobs.name] } } shouldBe listOf("new")
    }

    test("the periodic review enqueues one low-priority job item per slot, even if it fires twice") {
        val inbox = ExposedInboxStore(database, Clock.System, InboxSignals())
        val job = PeriodicReviewJob(AgentConfig(anthropicApiKey = null, reviewInterval = 2.hours), inbox)
        val slot = Instant.parse("2026-10-25T10:00:00Z")
        val ctx = FireContext(slot, slot + 1.hours, lastRunAt = null, settings = Settings())
        job.fire(ctx)
        job.fire(ctx)
        inbox.pending().map { it.item }.single().let {
            (it as InboxItem.JobDue).job shouldBe "periodic-review"
        }
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
        feedStore.pendingBatch().shouldBeEmpty()
    }
})
