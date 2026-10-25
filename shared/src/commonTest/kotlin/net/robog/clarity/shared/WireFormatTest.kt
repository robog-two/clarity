package net.robog.clarity.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import net.robog.clarity.shared.ingest.IngestBatch
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload

class WireFormatTest {
    @Test
    fun ingestBatchRoundTrips() {
        val batch = IngestBatch(
            listOf(
                IngestRecord.Upsert(
                    sourceId = SourceId("INBOX/42"),
                    sourceVersion = "1",
                    observedAt = Instant.parse("2026-10-25T12:00:00Z"),
                    payload = MessagePayload(
                        channel = MessagePayload.Channel.EMAIL,
                        account = Handle.email("me@example.com"),
                        sender = Handle.email("friend@example.com"),
                        recipients = listOf(Handle.email("me@example.com")),
                        subject = "Hi",
                        body = "Hello there",
                        sentAt = Instant.parse("2026-10-25T11:59:00Z"),
                    ),
                ),
            ),
        )
        val json = ClarityJson.encodeToString(IngestBatch.serializer(), batch)
        assertEquals(batch, ClarityJson.decodeFromString(IngestBatch.serializer(), json))
    }
}
