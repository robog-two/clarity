package net.robog.clarity.app.ui

import net.robog.clarity.app.data.ApiError

/** One plain sentence for the person, for any failed call. Active voice, and what to do comes first. */
fun ApiError.sentence(): String = when (this) {
    is ApiError.Unreachable -> "Can’t reach your server. Check your connection."
    is ApiError.Unauthorized, is ApiError.Forbidden -> "Your server rejected this phone. Open Settings and set it up again."
    is ApiError.NotFound -> "Your server can’t show this yet. Update it, then try again."
    is ApiError.Http -> if (status == 409) "I’m still on the last step. Try again in a moment." else
        problem?.detail ?: problem?.title ?: "Your server hit an error ($status). Try again."
    is ApiError.Malformed -> "I couldn’t read your server’s answer. Try again."
    ApiError.NotConfigured -> "Connect this phone to your server in Settings."
}
