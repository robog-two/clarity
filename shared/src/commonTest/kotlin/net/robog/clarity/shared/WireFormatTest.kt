package net.robog.clarity.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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

    @Test
    fun historyFlagRoundTripsAndDefaultsToFalse() {
        val record = IngestRecord.Upsert(
            sourceId = SourceId("INBOX/7"),
            sourceVersion = "1",
            observedAt = Instant.parse("2026-10-25T12:00:00Z"),
            payload = MessagePayload(
                channel = MessagePayload.Channel.EMAIL,
                account = Handle.email("me@example.com"),
                sender = Handle.email("friend@example.com"),
                recipients = listOf(Handle.email("me@example.com")),
                subject = "Old",
                body = "From long ago",
                sentAt = Instant.parse("2024-01-01T09:00:00Z"),
            ),
            history = true,
        )
        val json = ClarityJson.encodeToString(IngestRecord.serializer(), record)
        assertEquals(record, ClarityJson.decodeFromString(IngestRecord.serializer(), json))
        // a record from a connector that predates the flag is a new arrival
        val legacy = json.replace(",\"history\":true", "")
        assertNotEquals(json, legacy)
        assertEquals(false, (ClarityJson.decodeFromString(IngestRecord.serializer(), legacy) as IngestRecord.Upsert).history)
    }
}
