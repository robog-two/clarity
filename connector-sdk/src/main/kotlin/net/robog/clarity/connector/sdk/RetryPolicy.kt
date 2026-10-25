package net.robog.clarity.connector.sdk

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What to do with an HTTP status. */
enum class Verdict { SUCCESS, RETRY, FATAL }

/**
 * Pure retry decisions. Retrying is safe because ingest is idempotent
 * (`connector, noun, sourceId, sourceVersion`).
 *
 * @property maxAttempts total attempts including the first.
 * @property baseDelay delay before the first retry; doubles each attempt.
 * @property maxDelay ceiling for a single delay.
 */
data class RetryPolicy(
    val maxAttempts: Int = 6,
    val baseDelay: Duration = 500.milliseconds,
    val maxDelay: Duration = 30.seconds,
) {
    init { require(maxAttempts >= 1) { "maxAttempts must be >= 1" } }

    /**
     * Delay before retry number [failedAttempt] (1 = after the first failure): "equal jitter",
     * uniformly in [cap/2, cap] where cap = min(maxDelay, baseDelay * 2^(failedAttempt-1)).
     * [jitter] is a uniform sample in [0, 1) supplied by the caller to keep this function pure.
     * A server-provided [retryAfter] wins, bounded by [maxDelay].
     */
    fun delayBefore(failedAttempt: Int, jitter: Double, retryAfter: Duration? = null): Duration {
        if (retryAfter != null) return minOf(retryAfter, maxDelay)
        val exponent = (failedAttempt - 1).coerceIn(0, 30)
        val cap = minOf(maxDelay, baseDelay * (1L shl exponent).toDouble())
        return cap / 2 + (cap / 2) * jitter.coerceIn(0.0, 1.0)
    }

    companion object {
        val Default = RetryPolicy()

        /** Classifies a response status code. 3xx other than 304 is not followed and is fatal. */
        fun verdict(status: Int): Verdict = when (status) {
            in 200..299, 304 -> Verdict.SUCCESS
            408, 425, 429 -> Verdict.RETRY
            in 500..599 -> Verdict.RETRY
            else -> Verdict.FATAL
        }

        /** Parses a `Retry-After` header given in delta-seconds; HTTP-dates are ignored. */
        fun parseRetryAfter(header: String?): Duration? =
            header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.seconds
    }
}
