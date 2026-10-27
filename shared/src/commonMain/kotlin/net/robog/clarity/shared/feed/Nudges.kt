package net.robog.clarity.shared.feed

import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable @JvmInline
value class NudgeId(val value: String) { override fun toString(): String = value }

/**
 * A check-in the agent scheduled for itself with its `nudge` tool: at [at] it wakes up and decides
 * whether to ask the person how [note] is going. A pending nudge shows on Today; a resolved one
 * (fired, skipped, missed or cancelled) is a timeline entry at [resolvedAt], with [reason].
 */
@Serializable
data class Nudge(
    val id: NudgeId,
    /** When it is (or was) due. */
    val at: Instant,
    /** What the agent means to check, in its own words. */
    val note: String,
    /** The task it is about, if any. */
    val taskId: TaskId? = null,
    val status: Status,
    val createdAt: Instant,
    /** When it fired, was skipped, missed or cancelled; null while pending. */
    val resolvedAt: Instant? = null,
    /** Why it was skipped, missed or cancelled, in plain words; null while pending and when it fired. */
    val reason: String? = null,
) {
    @Serializable
    enum class Status {
        @SerialName("pending") PENDING,

        /** Woke the agent. Whether it then wrote to the person was its own choice. */
        @SerialName("fired") FIRED,

        /** Due but not needed: the task was finished, the person had just given the update, or the day's cap was reached. */
        @SerialName("skipped") SKIPPED,

        /** Due while the backend was down, and too late to be useful when it came back. */
        @SerialName("missed") MISSED,

        @SerialName("cancelled") CANCELLED,
    }
}

/** `GET /api/v1/nudges`: what is pending (soonest first) and the timeline of resolved nudges (newest first). */
@Serializable
data class Nudges(val pending: List<Nudge> = emptyList(), val history: List<Nudge> = emptyList())
