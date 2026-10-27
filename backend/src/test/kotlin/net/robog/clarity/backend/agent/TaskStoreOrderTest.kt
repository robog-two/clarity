package net.robog.clarity.backend.agent

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.feed.ExposedFeedStore
import net.robog.clarity.backend.feed.FeedHub
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.shared.feed.AgentTask
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

/** Today's "I'm doing" reads [TaskStore.list]: newest first, so stale tasks sink instead of leading. */
@EnabledIf(ContainerRuntimeAvailable::class)
class TaskStoreOrderTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }

    test("open tasks list newest update first; finished ones only when asked") {
        val clock = StepClock()
        val tasks = ExposedTaskStore(database, clock, FeedService(ExposedFeedStore(database, clock), FeedHub()))
        val stale = tasks.upsert(null, "Follow up with Nick", "waiting on the sent-mail sync", AgentTask.Status.WAITING_FOR_YOU)!!
        clock.advance(10.hours)
        val running = tasks.upsert(null, "Find haircut openings", "checking", AgentTask.Status.WORKING)!!
        clock.advance(5.minutes)
        val done = tasks.upsert(null, "Draft Dana's reply", "drafted", AgentTask.Status.DONE)!!
        clock.advance(5.minutes)
        val scheduled = tasks.upsert(null, "Nudge for pickup", "at 3:45", AgentTask.Status.SCHEDULED)!!

        tasks.list().map { it.id } shouldBe listOf(scheduled.id, running.id, stale.id)

        // Touching the stale task brings it back to the top.
        clock.advance(1.minutes)
        tasks.upsert(stale.id, null, "still waiting", null)
        tasks.list().map { it.id } shouldBe listOf(stale.id, scheduled.id, running.id)
        tasks.list(includeFinished = true).map { it.id } shouldBe listOf(stale.id, scheduled.id, done.id, running.id)
    }
})
