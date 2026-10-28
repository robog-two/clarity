package net.robog.clarity.backend.sessions

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import net.robog.clarity.backend.agent.ExposedNudgeStore
import net.robog.clarity.backend.agent.ExposedTaskStore
import net.robog.clarity.backend.agent.SettingsProvider
import net.robog.clarity.backend.agent.StepClock
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.feed.ExposedFeedStore
import net.robog.clarity.backend.feed.FeedHub
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.governance.HeldActionStore
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.session.Settings
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

/** Today keeps the tasks that finished since the person's local midnight, so they stay openable. */
@EnabledIf(ContainerRuntimeAvailable::class)
class TodayFinishedTasksTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }

    test("start of day follows the person's zone") {
        startOfDay(LocalDate(2026, 10, 25), "America/New_York") shouldBe Instant.parse("2026-10-25T04:00:00Z")
        startOfDay(LocalDate(2026, 10, 25), "nowhere/odd") shouldBe Instant.parse("2026-10-25T00:00:00Z")
    }

    test("Today lists open tasks and those finished today in the home zone, not yesterday's") {
        // 23:00 on Oct 24 in New York; local midnight is 04:00Z.
        val clock = StepClock(Instant.parse("2026-10-25T03:00:00Z"))
        val tasks = ExposedTaskStore(database, clock, FeedService(ExposedFeedStore(database, clock), FeedHub()))
        val yesterday = tasks.upsert(null, "Book the dentist", "booked", AgentTask.Status.DONE)!!
        val open = tasks.upsert(null, "Follow up with Nick", "waiting", AgentTask.Status.WAITING_FOR_YOU)!!
        clock.advance(2.hours) // 01:00 local, Oct 25
        val failed = tasks.upsert(null, "Find haircut openings", "site was down", AgentTask.Status.FAILED)!!
        clock.advance(5.minutes)
        val done = tasks.upsert(null, "Draft Dana's reply", "drafted", AgentTask.Status.DONE)!!
        clock.advance(5.minutes)
        val cancelled = tasks.upsert(null, "Nudge for pickup", "not needed", AgentTask.Status.CANCELLED)!!

        val settings = Settings(homeZone = "America/New_York")
        val service = TodayService(DayPlanStore(database, clock), tasks, HeldActionStore(database, clock), ExposedNudgeStore(database, clock), SessionStore(database), SettingsProvider { settings }, clock)
        val today = service.today()

        today.date shouldBe LocalDate(2026, 10, 25)
        today.doing.map { it.id } shouldBe listOf(cancelled.id, done.id, failed.id, open.id)
        (yesterday.id in today.doing.map { it.id }) shouldBe false
        // The store's other modes are unchanged.
        tasks.list().map { it.id } shouldBe listOf(open.id)
        tasks.list(includeFinished = true).map { it.id }.toSet() shouldBe setOf(cancelled.id, done.id, failed.id, open.id, yesterday.id)
    }
})
