package net.robog.clarity.app.call

import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.SessionsRepository
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionKind

const val SNOOZE_MINUTES = 15

/**
 * Call actions that do not need the call screen: "not now" and "hang up" from a notification, and
 * the same from the incoming screen. The local alarm is set even when the server is unreachable,
 * so a declined call always rings again.
 */
class CallService(
    private val sessions: SessionsRepository,
    private val scheduler: CallScheduler,
    private val notifier: CallNotifier,
) {
    /** Declines the call: tells the server (`Snooze`), then re-rings in [minutes]. */
    suspend fun snooze(minutes: Int = SNOOZE_MINUTES): ApiResult<Session> {
        notifier.cancelIncoming()
        scheduler.snooze(minutes)
        val session = when (val active = sessions.active(SessionKind.MORNING_CALL)) {
            is ApiResult.Failure -> return active
            is ApiResult.Success -> active.value ?: when (val started = sessions.start(SessionKind.MORNING_CALL)) {
                is ApiResult.Failure -> return started
                is ApiResult.Success -> started.value
            }
        }
        return sessions.control(session.id, SessionControl.Snooze(minutes))
    }

    /** Ends the running call (the ongoing-call notification's hang-up button). */
    suspend fun hangUp(): ApiResult<Session?> {
        notifier.cancelOngoing()
        return when (val active = sessions.active(SessionKind.MORNING_CALL)) {
            is ApiResult.Failure -> active
            is ApiResult.Success -> {
                val session = active.value ?: return ApiResult.Success(null)
                when (val r = sessions.control(session.id, SessionControl.End)) {
                    is ApiResult.Success -> ApiResult.Success(r.value)
                    is ApiResult.Failure -> r
                }
            }
        }
    }
}
