package net.robog.clarity.backend.ingest

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.CalendarEvents
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.ingest.IngestBatch
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.ingest.RecordOutcome
import net.robog.clarity.shared.noun.CalendarEventPayload
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.postgresql.PostgreSQLContainer

/** A record whose identity changes (an event without a UID that moves) keeps its entity id. */
@EnabledIf(ContainerRuntimeAvailable::class)
class StableIdentityDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine").withStartupAttempts(3)
    lateinit var service: IngestService
    lateinit var database: Database
    val published = mutableListOf<Ingested>()
    val t0 = Instant.parse("2026-11-14T15:05:00Z")

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
        transaction(database) {
            for (c in listOf("dav", "dav2")) {
                exec("INSERT INTO connector (id, manifest) VALUES ('$c', '{\"kind\":\"dav\",\"displayName\":\"Dav\",\"version\":\"1\",\"produces\":[\"calendar_event\"]}')")
                exec("INSERT INTO connector_grant (connector_id, noun, state, updated_at) VALUES ('$c', 'calendar_event', 'granted', now())")
            }
        }
        val bus = object : IngestEventBus {
            override val events = MutableSharedFlow<Ingested>()
            override suspend fun publish(event: Ingested) { published += event }
        }
        service = IngestService(database, Clock.System, bus)
    }
    afterSpec { postgres.stop() }

    fun event(start: Instant, uid: String? = null, title: String = "Call the salon") =
        CalendarEventPayload(calendar = "Home", title = title, start = start, end = start + kotlin.time.Duration.parse("15m"), icalUid = uid)

    suspend fun push(connector: String, source: String, version: String, payload: CalendarEventPayload): RecordOutcome =
        service.ingest(ConnectorId(connector), IngestBatch(listOf(IngestRecord.Upsert(SourceId(source), version, t0, payload))))
            .getOrNull()!!.results.single().outcome

    fun live(): Long = transaction(database) { CalendarEvents.selectAll().where { CalendarEvents.deletedAt.isNull() }.count() }

    fun RecordOutcome.id(): EntityId = when (this) {
        is RecordOutcome.Created -> entityId
        is RecordOutcome.Merged -> entityId
        else -> error("no entity: $this")
    }

    test("an event without a UID that moves keeps its id and leaves no copy behind") {
        val before = live()
        val created = push("dav", "cal/a.ics", "1", event(t0)).shouldBeInstanceOf<RecordOutcome.Created>()
        val moved = push("dav", "cal/a.ics", "2", event(t0 + kotlin.time.Duration.parse("2d"))).shouldBeInstanceOf<RecordOutcome.Merged>()
        moved.entityId shouldBe created.entityId
        // a moved event is not a new one: nothing that waits for new events wakes again
        published.last().created shouldBe false
        live() shouldBe before + 1
        transaction(database) {
            CalendarEvents.selectAll().where { CalendarEvents.id eq kotlin.uuid.Uuid.parse(created.entityId.value) }.single()[CalendarEvents.startAt]
        } shouldBe t0 + kotlin.time.Duration.parse("2d")
    }

    test("a source that comes to match another record merges into it and its old record is deleted") {
        val before = live()
        val other = push("dav", "cal/b.ics", "1", event(t0, uid = "b@example.com")).id()
        push("dav", "cal/c.ics", "1", event(t0, title = "Other"))
        push("dav", "cal/c.ics", "2", event(t0, uid = "b@example.com")).id() shouldBe other
        live() shouldBe before + 1
    }

    test("a record two sources share is not moved when one of them changes") {
        val shared = push("dav", "cal/d.ics", "1", event(t0, uid = "d@example.com")).id()
        push("dav2", "other/d.ics", "1", event(t0, uid = "d@example.com")).id() shouldBe shared
        val split = push("dav2", "other/d.ics", "2", event(t0 + kotlin.time.Duration.parse("1h"), uid = "d2@example.com")).id()
        (split == shared) shouldBe false
        transaction(database) {
            CalendarEvents.selectAll().where { CalendarEvents.id eq kotlin.uuid.Uuid.parse(shared.value) }.single()[CalendarEvents.startAt]
        } shouldBe t0
        // the first source still has the shared record; the second has its own at the new time
        push("dav", "cal/d.ics", "1", event(t0, uid = "d@example.com")).shouldBeInstanceOf<RecordOutcome.Unchanged>().entityId shouldBe shared
        transaction(database) {
            CalendarEvents.selectAll().where { CalendarEvents.id eq kotlin.uuid.Uuid.parse(split.value) }.single()[CalendarEvents.startAt]
        } shouldBe t0 + kotlin.time.Duration.parse("1h")
    }

    test("a deleted event that comes back, moved, revives its own record") {
        val before = live()
        val id = push("dav", "cal/e.ics", "1", event(t0, title = "Dentist")).id()
        service.ingest(ConnectorId("dav"), IngestBatch(listOf(IngestRecord.Tombstone(SourceId("cal/e.ics"), "2", t0, net.robog.clarity.shared.noun.Noun.CALENDAR_EVENT))))
        live() shouldBe before
        push("dav", "cal/e.ics", "3", event(t0 + kotlin.time.Duration.parse("1d"), title = "Dentist")).id() shouldBe id
        live() shouldBe before + 1
    }

    test("re-reading an unchanged record under a new version does not mark it changed; a real change does") {
        fun updatedAt(id: EntityId) = transaction(database) {
            CalendarEvents.selectAll().where { CalendarEvents.id eq kotlin.uuid.Uuid.parse(id.value) }.single()[CalendarEvents.updatedAt]
        }
        val id = push("dav", "cal/f.ics", "1", event(t0, uid = "f@example.com", title = "Yoga")).id()
        val first = updatedAt(id)
        Thread.sleep(20)
        push("dav", "cal/f.ics", "1#m5", event(t0, uid = "f@example.com", title = "Yoga")).id() shouldBe id
        updatedAt(id) shouldBe first
        Thread.sleep(20)
        push("dav", "cal/f.ics", "2#m5", event(t0, uid = "f@example.com", title = "Hot yoga")).id() shouldBe id
        (updatedAt(id) > first) shouldBe true
    }
})
