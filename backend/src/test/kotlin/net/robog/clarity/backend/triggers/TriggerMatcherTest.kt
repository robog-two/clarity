package net.robog.clarity.backend.triggers

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import net.robog.clarity.backend.config.AgentConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.InboxStore
import net.robog.clarity.backend.agent.PendingItem
import net.robog.clarity.backend.ingest.InMemoryIngestEventBus
import net.robog.clarity.backend.ingest.Ingested
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Noun
import net.robog.clarity.shared.query.RecordQuery
import net.robog.clarity.shared.query.TriggerSpec

private fun mail(body: String) = MessagePayload(
    channel = MessagePayload.Channel.EMAIL,
    account = Handle.email("me@example.com"),
    sender = Handle.email("boss@example.com"),
    recipients = listOf(Handle.email("me@example.com")),
    subject = "Invoice",
    body = body,
    sentAt = Instant.parse("2025-03-01T10:00:00Z"),
)

private class OneTrigger(spec: TriggerSpec) : TriggerStore {
    private val stored = StoredTrigger("t1", spec)
    override suspend fun list() = listOf(stored)
    override suspend fun enabledFor(noun: Noun) = listOf(stored).filter { it.spec.noun == noun }
    override suspend fun create(spec: TriggerSpec) = error("not used")
    override suspend fun delete(id: String) = false
}

private class RecordingInbox : InboxStore {
    val enqueued = CopyOnWriteArrayList<String?>()
    val items = CopyOnWriteArrayList<InboxItem>()
    override suspend fun enqueue(item: InboxItem, dedupKey: String?): Boolean {
        item.shouldBeInstanceOf<InboxItem.TriggerFired>()
        items += item
        enqueued += dedupKey
        return true
    }
    override suspend fun pending(limit: Int): List<PendingItem> = emptyList()
    override suspend fun markConsumed(seqs: Collection<Long>) = Unit
    override suspend fun countKeyed(keyPrefix: String, since: Instant): Int = enqueued.count { it?.startsWith(keyPrefix) == true }
}

class TriggerMatcherTest : FunSpec({
    test("history a connector imports (a first import, a mail backfill) fires no trigger; a new arrival does") {
        runTest {
            val bus = InMemoryIngestEventBus()
            val inbox = RecordingInbox()
            val matcher = TriggerMatcher(bus, OneTrigger(TriggerSpec("invoices", Noun.MESSAGE, RecordQuery.Contains("body", "invoice"), "tell me")), inbox)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { matcher.run() }

            val connector = ConnectorId("mail")
            bus.publish(Ingested(connector, EntityId("old"), mail("the invoice from last year"), created = true, history = true))
            bus.publish(Ingested(connector, EntityId("merged"), mail("the invoice again"), created = false))
            bus.publish(Ingested(connector, EntityId("new"), mail("a new invoice"), created = true))
            testScheduler.advanceUntilIdle()

            inbox.enqueued shouldContainExactly listOf("trigger:t1:new")
        }
    }

    // Guards against: one trigger waking the lead for every matching record when they keep arriving.
    test("a trigger wakes the lead at most its hourly limit") {
        runTest {
            val bus = InMemoryIngestEventBus()
            val inbox = RecordingInbox()
            val config = AgentConfig(reviewInterval = 2.hours, triggerFiresPerHour = 2)
            val matcher = TriggerMatcher(bus, OneTrigger(TriggerSpec("invoices", Noun.MESSAGE, RecordQuery.Contains("body", "invoice"), "tell me")), inbox, config = config)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { matcher.run() }
            (1..5).forEach { bus.publish(Ingested(ConnectorId("mail"), EntityId("n$it"), mail("invoice $it"), created = true)) }
            testScheduler.advanceUntilIdle()
            inbox.enqueued shouldContainExactly listOf("trigger:t1:n1", "trigger:t1:n2")
        }
    }

    // Guards against: a message the agent sent from the person's account matching the trigger that made it send it.
    test("a message sent from the person's own account fires no trigger") {
        runTest {
            val bus = InMemoryIngestEventBus()
            val inbox = RecordingInbox()
            val matcher = TriggerMatcher(bus, OneTrigger(TriggerSpec("invoices", Noun.MESSAGE, RecordQuery.Contains("body", "invoice"), "tell me")), inbox)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { matcher.run() }
            bus.publish(Ingested(ConnectorId("mail"), EntityId("sent"), mail("re: your invoice").copy(sender = Handle.email("me@example.com")), created = true))
            bus.publish(Ingested(ConnectorId("mail"), EntityId("in"), mail("an invoice"), created = true))
            testScheduler.advanceUntilIdle()
            inbox.enqueued shouldContainExactly listOf("trigger:t1:in")
        }
    }

    // Guards against: a phone that was offline uploading many texts at once and the agent hearing of only a few.
    test("matches past the hourly limit are held and wake the lead once together") {
        runTest {
            val inbox = RecordingInbox()
            val config = AgentConfig(reviewInterval = 2.hours, triggerFiresPerHour = 2)
            val matcher = TriggerMatcher(InMemoryIngestEventBus(), OneTrigger(TriggerSpec("invoices", Noun.MESSAGE, RecordQuery.Contains("body", "invoice"), "tell me")), inbox, config = config)
            (1..5).forEach { matcher.handle(Ingested(ConnectorId("mail"), EntityId("n$it"), mail("invoice $it"), created = true)) }
            inbox.enqueued shouldContainExactly listOf("trigger:t1:n1", "trigger:t1:n2")

            matcher.flushHeld() // the hour is still full: nothing goes out
            inbox.enqueued.size shouldBe 2

            inbox.enqueued.clear() // the hour has room again
            matcher.flushHeld()
            inbox.enqueued.size shouldBe 1
            val batch = inbox.items.last().shouldBeInstanceOf<InboxItem.TriggerFired>()
            batch.recordSource shouldContain "3 message records"
            listOf("invoice 3", "invoice 4", "invoice 5").forEach { batch.record shouldContain it }

            matcher.flushHeld() // nothing is sent twice
            inbox.enqueued.size shouldBe 1
        }
    }

    test("a long burst lists the first matches and counts the rest") {
        runTest {
            val inbox = RecordingInbox()
            val config = AgentConfig(reviewInterval = 2.hours, triggerFiresPerHour = 1)
            val matcher = TriggerMatcher(InMemoryIngestEventBus(), OneTrigger(TriggerSpec("invoices", Noun.MESSAGE, RecordQuery.Contains("body", "invoice"), "tell me")), inbox, config = config)
            (1..40).forEach { matcher.handle(Ingested(ConnectorId("mail"), EntityId("n$it"), mail("invoice $it"), created = true)) }
            inbox.enqueued.clear()
            matcher.flushHeld()
            val batch = inbox.items.last().shouldBeInstanceOf<InboxItem.TriggerFired>()
            batch.recordSource shouldContain "39 message records"
            batch.record shouldContain "14 more matched"
        }
    }
})
