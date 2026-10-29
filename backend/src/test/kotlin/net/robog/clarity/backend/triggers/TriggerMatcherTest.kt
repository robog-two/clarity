package net.robog.clarity.backend.triggers

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Instant
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
    override suspend fun enqueue(item: InboxItem, dedupKey: String?): Boolean {
        item.shouldBeInstanceOf<InboxItem.TriggerFired>()
        enqueued += dedupKey
        return true
    }
    override suspend fun pending(limit: Int): List<PendingItem> = emptyList()
    override suspend fun markConsumed(seqs: Collection<Long>) = Unit
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
})
