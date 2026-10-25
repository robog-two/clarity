package net.robog.clarity.shared.session

import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.Chip
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.governance.AmendmentId
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.Line

/**
 * Sessions are structured episodes on the agent's thread: the morning call, the values
 * interview, an amendment conversation. Free-form "ask" input needs no session.
 */
@Serializable @JvmInline
value class SessionId(val value: String) { override fun toString(): String = value }

@Serializable
enum class SessionKind {
    @SerialName("morning_call") MORNING_CALL,
    @SerialName("values_interview") VALUES_INTERVIEW,
    /** Talk through a held action or amend a line by talking. */
    @SerialName("amend") AMEND,
}

/** Morning call stages, in order. */
@Serializable
enum class CallStage {
    @SerialName("clear") CLEAR,
    @SerialName("choose") CHOOSE,
    @SerialName("commit") COMMIT,
    @SerialName("hand_off") HAND_OFF,
}

@Serializable
data class Session(
    val id: SessionId,
    val kind: SessionKind,
    val state: State,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    /** Morning call only. */
    val stage: CallStage? = null,
    /** Values interview only: 0-based question index and total. */
    val question: Int? = null,
    val questionCount: Int? = null,
    /** Values interview only: the line captured for the current question. */
    val capturedLine: Line? = null,
    /** Amend only. */
    val heldActionId: HeldActionId? = null,
    val amendmentId: AmendmentId? = null,
) {
    @Serializable
    enum class State {
        @SerialName("active") ACTIVE,
        /** The agent is producing the stage's output; the "next" button is disabled. */
        @SerialName("busy") BUSY,
        @SerialName("ended") ENDED,
        @SerialName("snoozed") SNOOZED,
    }
}

/** `POST /api/v1/sessions`. */
@Serializable
data class StartSession(val kind: SessionKind, val heldActionId: HeldActionId? = null)

/** `POST /api/v1/sessions/{id}/control`. */
@Serializable
sealed interface SessionControl {
    /** "that’s all" / "next" / "keep →": move to the next stage or question. */
    @Serializable @SerialName("next")
    data object Next : SessionControl

    /** Values interview: ask for another phrasing of the captured line ("reword"). */
    @Serializable @SerialName("reword")
    data object Reword : SessionControl

    /** Morning call declined from the incoming screen: call again in [minutes]. */
    @Serializable @SerialName("snooze")
    data class Snooze(val minutes: Int = 15) : SessionControl

    @Serializable @SerialName("end")
    data object End : SessionControl
}

/** `GET /api/v1/today`. */
@Serializable
data class Today(
    val date: LocalDate,
    val priorities: List<Chip> = emptyList(),
    val plan: List<IfThen> = emptyList(),
    val doing: List<AgentTask> = emptyList(),
    val heldCount: Int = 0,
    val parked: List<String> = emptyList(),
    /** True once today's morning call has happened. */
    val called: Boolean = false,
)

/** `GET/PUT /api/v1/settings`. */
@Serializable
data class Settings(
    val morningCall: MorningCall = MorningCall(),
    /** "low-urgency holds": reviewed together in the evening, or right away. */
    val batchLowUrgency: Boolean = true,
    val eveningSummaryAt: LocalTime = LocalTime(18, 0),
    /** "urgent holds": a notification as it happens. */
    val pushUrgent: Boolean = true,
    /** "mic during calls": always on, or push to talk. */
    val micAlwaysOn: Boolean = true,
    /** IANA zone of the person; schedules are evaluated in it. */
    val timeZone: String = "UTC",
) {
    @Serializable
    data class MorningCall(
        val enabled: Boolean = true,
        val at: LocalTime = LocalTime(9, 0),
        /** ISO days, 1 = Monday. */
        val days: Set<Int> = setOf(1, 2, 3, 4, 5),
        val headsUpMinutes: Int? = 10,
        val mode: Mode = Mode.CALL,
    ) {
        @Serializable
        enum class Mode {
            @SerialName("call") CALL,
            @SerialName("notify") NOTIFY,
        }
    }
}

/** `PUT /api/v1/setup/model-key`. */
@Serializable
data class ModelKeyRequest(val apiKey: String)

@Serializable
data class SetupStatus(
    val modelKey: KeyState,
    val connectorsReady: Int,
    val constitutionVersion: Int?,
    val settings: Settings,
) {
    @Serializable
    enum class KeyState {
        @SerialName("missing") MISSING,
        @SerialName("valid") VALID,
        @SerialName("invalid") INVALID,
    }
}
