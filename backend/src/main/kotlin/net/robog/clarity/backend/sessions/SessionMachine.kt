package net.robog.clarity.backend.sessions

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import net.robog.clarity.backend.http.HttpProblem
import net.robog.clarity.shared.feed.Chip
import net.robog.clarity.shared.governance.AmendmentId
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.Line
import net.robog.clarity.shared.session.CallStage
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.SessionKind

/** Why a session request was refused. Each carries its wire status. */
sealed interface SessionError : HttpProblem {
    data class NotFound(val id: String) : SessionError {
        override val status = HttpStatusCode.NotFound
        override val title = "Unknown session"
        override val detail = "no session '$id'"
    }

    /** `Next` while the agent is still producing the stage's output. */
    data object Busy : SessionError {
        override val status = HttpStatusCode.Conflict
        override val title = "Session is busy"
        override val detail = "the agent is still working on this step; try again when it is done"
    }

    /** The session already ended (or is snoozed). */
    data class NotActive(val state: Session.State) : SessionError {
        override val status = HttpStatusCode.Conflict
        override val title = "Session is not active"
        override val detail = "the session is ${state.name.lowercase()}"
    }

    /** A control that makes no sense for this kind of session or at this point. */
    data class Illegal(override val detail: String) : SessionError {
        override val status = HttpStatusCode.Conflict
        override val title = "Not possible now"
    }

    data class Invalid(override val detail: String) : SessionError {
        override val status = HttpStatusCode.BadRequest
        override val title = "Invalid request"
    }
}

/** One autonomy tier as the person described it ("do it, then tell me: holds on my calendar..."). */
@Serializable
data class TierNote(val level: AutonomyTier, val examples: String)

/** Working data of a session that does not belong in the shared [Session]. */
@Serializable
data class SessionData(
    /** Morning call: everything captured so far. */
    val captured: List<Chip> = emptyList(),
    /** The feed item holding the "captured" chips, updated in place as more is captured. */
    val capturedItemId: String? = null,
    /** Values interview: the lines the person has kept so far, in question order. */
    val lines: List<Line> = emptyList(),
    /** Values interview: the autonomy tiers captured with question 3 (the line in progress). */
    val tiers: List<TierNote> = emptyList(),
    /** `(runId:toolUseId)` of the `hand_off` call that already created the tasks (a re-run must not duplicate them). */
    val handOffKey: String? = null,
)

/** A session with the parts that are only for the server. */
data class SessionRecord(
    val session: Session,
    val snoozedUntil: Instant? = null,
    /** Values interview: all questions are answered and the constitution is being written. */
    val finalizing: Boolean = false,
    val version: Int = 0,
    val data: SessionData = SessionData(),
) {
    val id: SessionId get() = session.id
    val kind: SessionKind get() = session.kind
    val state: Session.State get() = session.state
    val live: Boolean get() = state != Session.State.ENDED
}

/** The seven questions of the values interview; see [Playbooks.QUESTIONS]. */
const val INTERVIEW_QUESTIONS = 7

/** Snoozes outside this range are refused. */
val SNOOZE_MINUTES = 1..240

/** The first state of a new session. The agent opens it (and calls `stage_done`) before the person can move on. */
fun startRecord(id: SessionId, kind: SessionKind, now: Instant, heldActionId: HeldActionId? = null): SessionRecord {
    val base = Session(id, kind, Session.State.BUSY, startedAt = now)
    val session = when (kind) {
        SessionKind.MORNING_CALL -> base.copy(stage = CallStage.CLEAR)
        SessionKind.VALUES_INTERVIEW -> base.copy(question = 0, questionCount = INTERVIEW_QUESTIONS)
        SessionKind.AMEND -> base.copy(state = Session.State.ACTIVE, heldActionId = heldActionId)
    }
    return SessionRecord(session)
}

private fun SessionRecord.update(now: Instant? = null, block: (Session) -> Session): SessionRecord =
    copy(session = block(session).let { if (now != null) it.copy(endedAt = now) else it }, version = version + 1)

private fun SessionRecord.ended(now: Instant): SessionRecord =
    update(now) { it.copy(state = Session.State.ENDED) }.copy(snoozedUntil = null)

/** Pure: the session after the person's [control], or why it is refused. */
fun SessionRecord.control(control: SessionControl, now: Instant): Either<SessionError, SessionRecord> = when (control) {
    SessionControl.End ->
        if (live) ended(now).right() else SessionError.NotActive(state).left()

    is SessionControl.Snooze -> when {
        kind != SessionKind.MORNING_CALL -> SessionError.Illegal("only a morning call can be snoozed").left()
        control.minutes !in SNOOZE_MINUTES -> SessionError.Invalid("minutes must be ${SNOOZE_MINUTES.first}..${SNOOZE_MINUTES.last}").left()
        state != Session.State.ACTIVE && state != Session.State.BUSY -> SessionError.NotActive(state).left()
        session.stage != CallStage.CLEAR -> SessionError.Illegal("a call that is under way cannot be snoozed; end it instead").left()
        else -> update { it.copy(state = Session.State.SNOOZED) }.copy(snoozedUntil = now + control.minutes.minutes).right()
    }

    SessionControl.Next -> next(now)
    SessionControl.Reword -> reword()
}

private fun SessionRecord.next(now: Instant): Either<SessionError, SessionRecord> {
    when (state) {
        Session.State.BUSY -> return SessionError.Busy.left()
        Session.State.ACTIVE -> Unit
        else -> return SessionError.NotActive(state).left()
    }
    return when (kind) {
        SessionKind.MORNING_CALL -> {
            val next = when (session.stage) {
                CallStage.CLEAR -> CallStage.CHOOSE
                CallStage.CHOOSE -> CallStage.COMMIT
                CallStage.COMMIT -> CallStage.HAND_OFF
                CallStage.HAND_OFF, null -> null
            }
            if (next == null) ended(now).right()
            else update { it.copy(stage = next, state = Session.State.BUSY) }.right()
        }
        SessionKind.VALUES_INTERVIEW -> {
            val line = session.capturedLine ?: return SessionError.Illegal("nothing has been captured for this question yet").left()
            val index = session.question ?: 0
            val saved = data.copy(lines = data.lines + line)
            val last = index >= (session.questionCount ?: INTERVIEW_QUESTIONS) - 1
            if (last) {
                update { it.copy(state = Session.State.BUSY, capturedLine = null) }.copy(finalizing = true, data = saved).right()
            } else {
                update { it.copy(question = index + 1, state = Session.State.BUSY, capturedLine = null) }.copy(data = saved).right()
            }
        }
        SessionKind.AMEND -> SessionError.Illegal("an amend session has no steps; end it when you are done").left()
    }
}

/** The question (0-based) whose answer yields the autonomy tiers. */
const val TIERS_QUESTION = 2

private fun SessionRecord.reword(): Either<SessionError, SessionRecord> = when {
    kind != SessionKind.VALUES_INTERVIEW -> SessionError.Illegal("only the values interview can reword a line").left()
    state == Session.State.BUSY -> SessionError.Busy.left()
    state != Session.State.ACTIVE -> SessionError.NotActive(state).left()
    session.capturedLine == null -> SessionError.Illegal("nothing has been captured for this question yet").left()
    else -> update { it.copy(state = Session.State.BUSY) }.right()
}

/** Pure: the agent finished producing the step's output (`stage_done`): BUSY becomes ACTIVE. */
fun SessionRecord.stageDone(): Either<SessionError, SessionRecord> = when (state) {
    Session.State.BUSY -> update { it.copy(state = Session.State.ACTIVE) }.right()
    Session.State.ACTIVE -> SessionError.Illegal("the step is already done; the person decides when to move on").left()
    else -> SessionError.NotActive(state).left()
}

/**
 * Pure: the agent captured a line for the current question (`propose_line`): it replaces any earlier
 * one and releases the step (BUSY becomes ACTIVE) so "keep" is enabled.
 */
fun SessionRecord.lineCaptured(line: Line, tiers: List<TierNote>): Either<SessionError, SessionRecord> = when {
    kind != SessionKind.VALUES_INTERVIEW -> SessionError.Illegal("there is no values interview in progress").left()
    state != Session.State.BUSY && state != Session.State.ACTIVE -> SessionError.NotActive(state).left()
    finalizing -> SessionError.Illegal("every question is answered; call finalize_constitution").left()
    else -> update { it.copy(capturedLine = line, state = Session.State.ACTIVE) }
        .copy(data = data.copy(tiers = if (session.question == TIERS_QUESTION) tiers else data.tiers)).right()
}

/** Pure: the values interview ended with the constitution written. */
fun SessionRecord.interviewFinished(now: Instant): Either<SessionError, SessionRecord> = when {
    kind != SessionKind.VALUES_INTERVIEW -> SessionError.Illegal("there is no values interview in progress").left()
    !live -> SessionError.NotActive(state).left()
    !finalizing -> SessionError.Illegal("the interview still has questions; the person moves on with \"next\"").left()
    else -> ended(now).right()
}

/** Pure: an amendment was proposed in this amend session. */
fun SessionRecord.amendmentProposed(id: AmendmentId): Either<SessionError, SessionRecord> = when {
    kind != SessionKind.AMEND -> SessionError.Illegal("there is no amend session in progress").left()
    !live -> SessionError.NotActive(state).left()
    else -> update { it.copy(amendmentId = id) }.right()
}

/** Pure: a snoozed call is due again: it rings (BUSY while the agent opens it). */
fun SessionRecord.recalled(): SessionRecord =
    update { it.copy(state = Session.State.BUSY, stage = CallStage.CLEAR) }.copy(snoozedUntil = null)

/** Pure: whether a snoozed call should ring again at [now]. */
fun SessionRecord.recallDue(now: Instant): Boolean =
    state == Session.State.SNOOZED && snoozedUntil?.let { it <= now } == true

/** Pure: the session ended on the server's own accord (an amendment was decided, the call dropped). */
fun SessionRecord.closed(now: Instant): SessionRecord = if (live) ended(now) else this
