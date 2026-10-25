package net.robog.clarity.backend.ingest

import arrow.core.raise.context.ensure
import arrow.core.raise.context.raise
import arrow.core.raise.Raise
import net.robog.clarity.backend.dedup.DedupKey
import net.robog.clarity.backend.dedup.dedupKey
import net.robog.clarity.backend.dedup.normalized
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.ContactPayload
import net.robog.clarity.shared.noun.DocumentPayload
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Noun
import net.robog.clarity.shared.noun.Payload

/** A record version this connector already delivered, and the entity it resolved to (if any). */
data class PriorSighting(val entityId: EntityId?)

/** What the effectful shell should do with one record. Decided purely by [planRecord]. */
sealed interface RecordPlan {
    /** Exact replay: answer `Unchanged`. */
    data class Replay(val entityId: EntityId?) : RecordPlan

    /** Store/merge the (already normalized) [payload] under [key]. */
    data class Apply(val payload: Payload, val key: DedupKey) : RecordPlan

    /** Soft-delete the source's record. */
    data class Remove(val noun: Noun) : RecordPlan
}

val IngestRecord.targetNoun: Noun
    get() = when (this) {
        is IngestRecord.Upsert -> payload.noun
        is IngestRecord.Tombstone -> noun
    }

/**
 * Pipeline steps 1-4 as a pure decision: idempotency, grant check, normalization, dedup key.
 * No I/O: the caller supplies what it looked up ([previous], [granted]). Rejections are raised
 * on the context's `Raise` channel.
 */
context(_: Raise<RecordRejection>)
fun planRecord(record: IngestRecord, previous: PriorSighting?, granted: Set<Noun>): RecordPlan {
    if (previous != null) return RecordPlan.Replay(previous.entityId)
    val noun = record.targetNoun
    ensure(noun in granted) { RecordRejection.NotGranted(noun) }
    return when (record) {
        is IngestRecord.Tombstone -> RecordPlan.Remove(noun)
        is IngestRecord.Upsert -> {
            val payload = record.payload.normalized()
            validate(payload)
            RecordPlan.Apply(payload, payload.dedupKey())
        }
    }
}

/** Minimal structural checks on a normalized payload; anything weaker would poison dedup keys. */
context(_: Raise<RecordRejection>)
private fun validate(payload: Payload) {
    fun invalid(why: String): Nothing = raise(RecordRejection.InvalidPayload(why))
    when (payload) {
        is MessagePayload -> {
            if (payload.sender.value.isEmpty()) invalid("message sender is empty")
            if (payload.account.value.isEmpty()) invalid("message account is empty")
        }
        is ContactPayload ->
            if (payload.displayName.isEmpty() && payload.handles.isEmpty()) invalid("contact needs a name or a handle")
        is CalendarEventPayload -> {
            if (payload.title.isEmpty()) invalid("event title is empty")
            if (payload.end < payload.start) invalid("event ends before it starts")
        }
        is DocumentPayload -> {
            if (payload.store.isEmpty()) invalid("document store is empty")
            if (payload.path == "/") invalid("document path is empty")
        }
    }
}
