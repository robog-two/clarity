package net.robog.clarity.shared.ingest

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.noun.Noun
import net.robog.clarity.shared.noun.Payload

/**
 * One unit of data a connector pushes. `(connector, noun, sourceId, sourceVersion)` is the
 * idempotency key: re-sending the same record is always safe and returns [RecordOutcome.Unchanged].
 * Connectors bump [sourceVersion] whenever the record changes in their source (ETag, MODSEQ, hash...).
 */
@Serializable
sealed interface IngestRecord {
    val sourceId: SourceId
    val sourceVersion: String
    val observedAt: Instant

    /**
     * A created or updated record. [history] marks data the source already had before the
     * connector looked (a first import, or older mail fetched after a longer history was chosen):
     * it is stored and searchable like anything else, but it is not a new arrival, so no trigger
     * fires for it and nothing wakes the assistant.
     */
    @Serializable
    @SerialName("upsert")
    data class Upsert(
        override val sourceId: SourceId,
        override val sourceVersion: String,
        override val observedAt: Instant,
        val payload: Payload,
        val history: Boolean = false,
    ) : IngestRecord

    /** The record no longer exists in the source. */
    @Serializable
    @SerialName("tombstone")
    data class Tombstone(
        override val sourceId: SourceId,
        override val sourceVersion: String,
        override val observedAt: Instant,
        val noun: Noun,
    ) : IngestRecord
}

@Serializable
data class IngestBatch(val records: List<IngestRecord>) {
    companion object { const val MAX_RECORDS = 500 }
}

@Serializable
data class IngestResponse(val results: List<RecordResult>)

@Serializable
data class RecordResult(val sourceId: SourceId, val outcome: RecordOutcome)

@Serializable
sealed interface RecordOutcome {
    /** New canonical entity created. */
    @Serializable @SerialName("created")
    data class Created(val entityId: EntityId) : RecordOutcome

    /** Deduplicated into an entity that already existed (possibly from another connector). */
    @Serializable @SerialName("merged")
    data class Merged(val entityId: EntityId) : RecordOutcome

    /** This exact (sourceId, sourceVersion) was already ingested: idempotent replay. */
    @Serializable @SerialName("unchanged")
    data class Unchanged(val entityId: EntityId?) : RecordOutcome

    /** Tombstone applied. */
    @Serializable @SerialName("removed")
    data object Removed : RecordOutcome

    /** Not accepted (e.g. noun not granted to this connector, validation failure). */
    @Serializable @SerialName("rejected")
    data class Rejected(val reason: String) : RecordOutcome
}
