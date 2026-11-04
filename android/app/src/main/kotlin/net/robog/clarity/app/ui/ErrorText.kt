package net.robog.clarity.app.ui

import net.robog.clarity.app.data.ApiError

/** One plain sentence for the person, for any failed call. Active voice, and what to do comes first. */
fun ApiError.sentence(): String = when (this) {
    is ApiError.Unreachable -> "Can’t reach server. Check connection."
    is ApiError.Unauthorized, is ApiError.Forbidden -> "Server rejected this phone. Set it up again in Settings."
    is ApiError.NotFound -> "Server can’t show this yet. Update it and try again."
    is ApiError.Http -> if (status == 409) "Still busy. Try again shortly." else
        problem?.detail ?: problem?.title ?: "Server error ($status). Try again."
    is ApiError.Malformed -> "Couldn’t read the server’s answer. Try again."
    ApiError.NotConfigured -> "Connect to your server in Settings."
}
