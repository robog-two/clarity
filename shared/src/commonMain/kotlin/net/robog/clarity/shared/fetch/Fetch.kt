package net.robog.clarity.shared.fetch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.robog.clarity.shared.SourceId

/**
 * The fetch channel: read-only requests for the bytes of one part of a record (an email attachment)
 * that the connector keeps at its source. It is not an action: nothing changes upstream, so there is no
 * guard or approval. Connectors long-poll `GET /ingest/v1/fetches?wait=<seconds>`, then answer each
 * request with `PUT /ingest/v1/fetches/{id}?filename=&type=` (the raw bytes as the body) or
 * `POST /ingest/v1/fetches/{id}/error` ([FetchFailure]).
 */
@Serializable
data class FetchRequest(
    val id: String,
    /** The connector's own id for the record, as it reported it. */
    val sourceId: SourceId,
    /** The connector's address of the part (an email's MIME part path, `2` or `1.3`), or [LIST_PARTS]. */
    val part: String,
    /** The most bytes the backend accepts; a bigger part is refused with [FetchFailure.Code.TOO_LARGE]. */
    val maxBytes: Long,
) {
    companion object {
        /** Asks for the list of the record's parts (a JSON array of `AttachmentMeta`) instead of one part. */
        const val LIST_PARTS = "*"
    }
}

@Serializable
data class FetchPoll(val fetches: List<FetchRequest>)

@Serializable
data class FetchFailure(val code: Code, val message: String) {
    @Serializable
    enum class Code {
        @SerialName("not_found") NOT_FOUND,
        @SerialName("too_large") TOO_LARGE,
        @SerialName("failed") FAILED,
    }
}
