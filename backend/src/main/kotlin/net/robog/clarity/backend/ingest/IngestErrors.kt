package net.robog.clarity.backend.ingest

import io.ktor.http.HttpStatusCode
import net.robog.clarity.backend.http.HttpProblem
import net.robog.clarity.shared.noun.Noun

/** Failures that reject a whole batch. */
sealed interface IngestError : HttpProblem {
    data class BatchTooLarge(val size: Int, val max: Int) : IngestError {
        override val status = HttpStatusCode.PayloadTooLarge
        override val title = "Batch too large"
        override val detail = "batch has $size records; the maximum is $max"
    }
}

/** Why a single record was not accepted; surfaces as `RecordOutcome.Rejected(reason)`. */
sealed interface RecordRejection {
    val reason: String

    data class NotGranted(val noun: Noun) : RecordRejection {
        override val reason get() = "noun '${noun.name.lowercase()}' is not granted to this connector"
    }

    data class InvalidPayload(val why: String) : RecordRejection {
        override val reason get() = "invalid payload: $why"
    }
}
