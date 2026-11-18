package net.robog.clarity.backend.ingest

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.InboxStore
import net.robog.clarity.backend.agent.PendingItem
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.dedup.dedupKey
import net.robog.clarity.backend.dedup.messageContentKey
import net.robog.clarity.backend.dedup.normalized
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.Messages
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.scheduler.ExposedReviewChanges
import net.robog.clarity.backend.scheduler.soonWindow
import net.robog.clarity.backend.triggers.StoredTrigger
import net.robog.clarity.backend.triggers.TriggerMatcher
import net.robog.clarity.backend.triggers.TriggerStore
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.ingest.IngestBatch
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.ingest.RecordOutcome
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Noun
import net.robog.clarity.shared.query.RecordQuery
import net.robog.clarity.shared.query.TriggerSpec
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.testcontainers.postgresql.PostgreSQLContainer

/** Texts through the whole ingest path: separate texts stay separate, one text seen twice merges. */
@EnabledIf(ContainerRuntimeAvailable::class)
class MessageIngestDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine").withStartupAttempts(3)
    lateinit var service: IngestService
    lateinit var database: Database
    val published = CopyOnWriteArrayList<Ingested>()
    val phone = ConnectorId("phone")
    val day1 = Instant.parse("2026-11-14T15:05:00Z")

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
        transaction(database) {
            exec("INSERT INTO connector (id, manifest) VALUES ('phone', '{\"kind\":\"phone\",\"displayName\":\"Phone\",\"version\":\"1\",\"produces\":[\"message\"]}')")
            exec("INSERT INTO connector_grant (connector_id, noun, state, updated_at) VALUES ('phone', 'message', 'granted', now())")
        }
        val bus = object : IngestEventBus {
            override val events = MutableSharedFlow<Ingested>()
            override suspend fun publish(event: Ingested) { published += event }
        }
        service = IngestService(database, Clock.System, bus)
    }
    afterSpec { postgres.stop() }
    beforeTest {
        published.clear()
        transaction(database) { exec("TRUNCATE message CASCADE") }
    }

    fun text(body: String, at: Instant, from: String = "+15551234567") = MessagePayload(
        channel = MessagePayload.Channel.SMS,
        account = Handle.phone("+15550000000"),
        sender = Handle.phone(from),
        recipients = listOf(Handle.phone("+15550000000")),
        body = body,
        sentAt = at,
    )

    suspend fun push(source: String, payload: MessagePayload): RecordOutcome =
        service.ingest(phone, IngestBatch(listOf(IngestRecord.Upsert(SourceId(source), "1", payload.sentAt, payload))))
            .getOrNull()!!.results.single().outcome

    fun rows(): Long = transaction(database) { Messages.selectAll().count() }

    test("the same words on two days are two messages; the second fires the trigger and shows in the review") {
        val before = Clock.System.now() - 1.hours
        push("sms:v2:1", text("see you at 7", day1)).shouldBeInstanceOf<RecordOutcome.Created>()
        push("sms:v2:2", text("see you at 7", day1 + 1.days)).shouldBeInstanceOf<RecordOutcome.Created>()
        rows() shouldBe 2

        published.map { it.created } shouldBe listOf(true, true)
        val fired = mutableListOf<String?>()
        val inbox = object : InboxStore {
            override suspend fun enqueue(item: InboxItem, dedupKey: String?): Boolean { fired += dedupKey; return true }
            override suspend fun pending(limit: Int): List<PendingItem> = emptyList()
            override suspend fun markConsumed(seqs: Collection<Long>) = Unit
        }
        val stored = StoredTrigger("t1", TriggerSpec("texts", Noun.MESSAGE, RecordQuery.Contains("body", "see you"), "look"))
        val store = object : TriggerStore {
            override suspend fun list() = listOf(stored)
            override suspend fun enabledFor(noun: Noun) = listOf(stored)
            override suspend fun create(spec: TriggerSpec) = error("not used")
            override suspend fun delete(id: String) = false
        }
        val matcher = TriggerMatcher(InMemoryIngestEventBus(), store, inbox)
        published.forEach { matcher.handle(it) }
        fired shouldHaveSize 2

        val snapshot = ExposedReviewChanges(database).since(before, Clock.System.now(), soonWindow(2.hours))
        snapshot.messageCount shouldBe 2
    }

    test("one text seen under a legacy and a v2 source id merges; two identical texts a moment apart stay two") {
        val created = push("sms:17", text("ok", day1)).shouldBeInstanceOf<RecordOutcome.Created>()
        val merged = push("sms:v2:${day1.toEpochMilliseconds()}:abc", text("ok", day1)).shouldBeInstanceOf<RecordOutcome.Merged>()
        merged.entityId shouldBe created.entityId
        rows() shouldBe 1
        published.map { it.created } shouldBe listOf(true, false)
        push("sms:v2:${day1.toEpochMilliseconds() + 400}:xyz", text("ok", day1 + 400.milliseconds)).shouldBeInstanceOf<RecordOutcome.Created>()
        rows() shouldBe 2
    }

    test("rows stored with the old key get the new key, so an upgrade neither duplicates nor fires again") {
        val payload = text("running late", day1 + 123.milliseconds)
        val created = push("sms:5", payload).shouldBeInstanceOf<RecordOutcome.Created>()
        val mail = payload.copy(channel = MessagePayload.Channel.EMAIL, body = "a mail", sentAt = day1 + 456.milliseconds)
        push("mail:1", mail).shouldBeInstanceOf<RecordOutcome.Created>()
        // put the rows back in their pre-upgrade state, then run the migration
        transaction(database) {
            Messages.update({ Messages.channel eq "sms" }) { it[dedupKey] = messageContentKey(payload.normalized()).bytes() }
            exec(MessageIngestDbTest::class.java.getResource("/db/migration/V54__message_key_send_time.sql")!!.readText())
        }
        val keys = transaction(database) { Messages.selectAll().associate { it[Messages.channel] to it[Messages.dedupKey].toList() } }
        keys["sms"] shouldBe payload.dedupKey().bytes().toList()
        // email keeps its content-only key
        keys["email"] shouldBe mail.dedupKey().bytes().toList()
        keys["email"] shouldBe messageContentKey(mail.normalized()).bytes().toList()

        // the phone re-reads the same text under its other id: one row, no new arrival
        push("sms:v2:${day1.toEpochMilliseconds()}:def", payload).shouldBeInstanceOf<RecordOutcome.Merged>().entityId shouldBe created.entityId
        rows() shouldBe 2
        published.last().created shouldBe false
    }
})
