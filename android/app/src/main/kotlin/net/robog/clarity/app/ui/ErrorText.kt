package net.robog.clarity.app.ui

import net.robog.clarity.app.data.ApiError

/** One plain sentence for the person, for any failed call. */
fun ApiError.sentence(): String = when (this) {
    is ApiError.Unreachable -> "Can’t reach your server right now."
    is ApiError.Unauthorized, is ApiError.Forbidden -> "Your server doesn’t accept this phone. Set it up again in settings."
    is ApiError.NotFound -> "Your server doesn’t have this yet."
    is ApiError.Http -> if (status == 409) "I’m still working on the last step. Try again in a moment." else
        problem?.detail ?: problem?.title ?: "Your server answered with an error ($status)."
    is ApiError.Malformed -> "Your server sent something I couldn’t read."
    ApiError.NotConfigured -> "This phone isn’t connected to a server yet."
}
