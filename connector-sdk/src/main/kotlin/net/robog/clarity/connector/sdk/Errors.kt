package net.robog.clarity.connector.sdk

import net.robog.clarity.shared.api.Problem

/** Typed failure of a call to the Clarity backend. */
sealed interface ClientError {
    /** Human readable summary, safe to log. */
    val message: String

    /**
     * The backend refused the request and retrying will not help (bad key, noun not allowed,
     * malformed payload...). Fix the connector or its grants.
     */
    data class Rejected(val status: Int, val problem: Problem?) : ClientError {
        override val message get() =
            "backend rejected request with HTTP $status" + (problem?.let { ": ${it.title}${it.detail?.let { d -> " ($d)" } ?: ""}" } ?: "")
    }

    /** Every retry attempt failed with a transient problem (network error, 5xx, 429...). */
    data class Unavailable(val attempts: Int, val lastFailure: String) : ClientError {
        override val message get() = "backend unavailable after $attempts attempts: $lastFailure"
    }

    /** The backend answered successfully but the body was not what the contract promises. */
    data class InvalidResponse(val detail: String) : ClientError {
        override val message get() = "unexpected backend response: $detail"
    }
}

/** Thrown by the throwing variants (for example [SyncScope.push]) wrapping a [ClientError]. */
class ClientException(val error: ClientError) : RuntimeException(error.message)
