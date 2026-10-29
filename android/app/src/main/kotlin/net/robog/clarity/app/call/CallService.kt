package net.robog.clarity.app.call

import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.SessionsRepository
import net.robog.clarity.app.device.DeviceCapabilities
import net.robog.clarity.shared.session.CallStage
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionKind
import net.robog.clarity.shared.session.Settings

const val SNOOZE_MINUTES = 15

/**
 * Call actions that do not need the call screen: "not now" and "hang up" from a notification, the
 * same from the incoming screen, and "call me again". The local alarm is set even when the server is
 * unreachable, so a declined call always rings again.
 */
class CallService(
    private val sessions: SessionsRepository,
    private val scheduler: CallScheduler,
    private val notifier: CallNotifier,
    private val tracker: CallTracker,
    private val capabilities: DeviceCapabilities,
) {
    /**
     * Declines the call and re-rings in [minutes]. A call that has not got going is snoozed on the
     * server too (`Snooze`). One already under way (the person was rung again to rejoin it) cannot be
     * snoozed there: it stays running, and only the phone rings again.
     */
    suspend fun snooze(minutes: Int = SNOOZE_MINUTES): ApiResult<Session> {
        notifier.cancelIncoming()
        tracker.ring(false)
        scheduler.snooze(minutes)
        val session = when (val active = sessions.active(SessionKind.MORNING_CALL)) {
            is ApiResult.Failure -> return active
            is ApiResult.Success -> active.value ?: when (val started = sessions.start(SessionKind.MORNING_CALL)) {
                is ApiResult.Failure -> return started
                is ApiResult.Success -> started.value
            }
        }
        if (underWay(session)) {
            tracker.report(session)
            return ApiResult.Success(session)
        }
        val result = sessions.control(session.id, SessionControl.Snooze(minutes))
        (result as? ApiResult.Success)?.value?.let(tracker::report)
        return result
    }

    /**
     * The ringing notification went without an answer: it timed out, or was swiped away. (Answering
     * and "not now" cancel it from the app, which does not count.) It leaves a missed call to call
     * back from, or to ring again later.
     */
    fun ringEnded() {
        tracker.ring(false)
        notifier.missed()
    }

    /** Ends the running call (the ongoing call notification's hang-up button). */
    suspend fun hangUp(): ApiResult<Session?> {
        tracker.ring(false)
        return when (val active = sessions.active(SessionKind.MORNING_CALL)) {
            is ApiResult.Failure -> active
            is ApiResult.Success -> {
                val session = active.value
                if (session == null) {
                    tracker.refresh()
                    return ApiResult.Success(null)
                }
                when (val r = sessions.control(session.id, SessionControl.End)) {
                    is ApiResult.Success -> {
                        tracker.report(r.value)
                        ApiResult.Success(r.value)
                    }
                    is ApiResult.Failure -> r
                }
            }
        }
    }

    /**
     * "Call me again": the phone rings like the morning call, and answering picks the running call
     * back up ([callEntry]). Rings as a call whatever the call mode, since the person asked for it.
     * Returns false when the phone cannot show it (notifications are off).
     */
    fun ringAgain(): Boolean {
        val presentation = incomingPresentation(
            Settings.MorningCall.Mode.CALL, capabilities.canUseFullScreenIntent(), capabilities.notificationsEnabled(),
        )
        if (presentation == IncomingPresentation.None) return false
        tracker.ring(true)
        notifier.incoming(presentation, callTime = "")
        return true
    }
}

/** A running call past its first stage: the server refuses to snooze it. */
fun underWay(session: Session): Boolean =
    (session.state == Session.State.ACTIVE || session.state == Session.State.BUSY) &&
        session.stage != null && session.stage != CallStage.CLEAR
