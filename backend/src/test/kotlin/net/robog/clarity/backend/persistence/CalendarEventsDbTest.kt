package net.robog.clarity.backend.persistence

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Instant
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.dedup.DedupKey
import net.robog.clarity.backend.ingest.upsertCanonical
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.noun.CalendarEventPayload
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.postgresql.PostgreSQLContainer

/** An event keeps its link and how it repeats, and a second write of the same occurrence updates it instead of adding another. */
@EnabledIf(ContainerRuntimeAvailable::class)
class CalendarEventsDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine").withStartupAttempts(3)
    lateinit var database: Database
    val start = Instant.parse("2026-10-26T11:00:00Z")

    fun save(payload: CalendarEventPayload, key: String) = transaction(database) {
        upsertCanonical(payload, DedupKey(MessageDigest.getInstance("SHA-256").digest(key.toByteArray())), Clock.System.now())
    }

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }

    test("link and repeat survive the round trip, and a re-sync updates the same row") {
        val first = CalendarEventPayload("gym#1", "Home", "Gym", start, start, url = "https://example.com/gym", repeats = "every Mon, Wed and Fri")
        val created = save(first, "gym-1")
        created.created shouldBe true
        val again = save(first.copy(repeats = "every Mon and Fri"), "gym-1")
        again.created shouldBe false
        again.id shouldBe created.id
        val stored = ExposedNounReader(database).calendarEvent(EntityId(created.id.toString()))!!.payload
        stored.url shouldBe "https://example.com/gym"
        stored.repeats shouldBe "every Mon and Fri"
    }

    test("an event with neither reads back with neither") {
        val saved = save(CalendarEventPayload(null, "Home", "Lunch", start, start), "lunch")
        val stored = ExposedNounReader(database).calendarEvent(EntityId(saved.id.toString()))!!.payload
        stored.url shouldBe null
        stored.repeats shouldBe null
    }
})
