package net.robog.clarity.app.data

import net.robog.clarity.shared.api.Problem

/** Why an API call failed; the UI maps these to messages. No exceptions cross this boundary. */
sealed interface ApiError {
    /** Network failure: DNS, connect, TLS, timeout, cleartext blocked. */
    data class Unreachable(val cause: String?) : ApiError
    /** 401: missing/invalid enrollment token or JWT rejected. */
    data class Unauthorized(val problem: Problem?) : ApiError
    /** 403: valid credentials but not allowed (e.g. enrollment closed). */
    data class Forbidden(val problem: Problem?) : ApiError
    data class Http(val status: Int, val problem: Problem?) : ApiError
    /** Response body didn't match the wire contract. */
    data class Malformed(val cause: String?) : ApiError
    /** No instance has been configured yet. */
    data object NotConfigured : ApiError
}

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(f: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(f(value))
    is ApiResult.Failure -> this
}
