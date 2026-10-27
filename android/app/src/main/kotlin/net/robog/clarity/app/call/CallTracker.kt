package net.robog.clarity.app.call

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.app.data.SessionsRepository
import net.robog.clarity.app.data.feed.SyncSignal
import net.robog.clarity.shared.session.CallStage
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.SessionKind

// --- what counts as a call still going on (pure) ---

/** The line under the ongoing call: "morning meeting · choose". */
fun ongoingCallText(stage: CallStage?): String =
    "morning meeting" + (stage?.let { " · " + it.name.lowercase().replace('_', ' ') } ?: "")

/** A call that crosses midnight stays resumable this long after it started. */
val RESUMABLE_ACROSS_MIDNIGHT: Duration = 3.hours

/**
 * Whether a running [session] can still be picked up at [now]. A morning call is about the day it
 * started on: one from earlier today (even hours ago, say swiped away at 9 and picked up at 2) is
 * paused, and the person can go back to it. One from an earlier day was abandoned: it is neither shown
 * nor offered, and the next call starts afresh (the server ends the old session when a new one
 * starts). A call that runs past midnight stays resumable for a few hours.
 */
fun resumable(session: Session, now: Instant, zone: TimeZone): Boolean =
    session.startedAt.toLocalDateTime(zone).date == now.toLocalDateTime(zone).date ||
        now - session.startedAt in Duration.ZERO..RESUMABLE_ACROSS_MIDNIGHT

/**
 * The morning call that is going on at [now], if [session] is one: active or busy (snoozed rings again
 * by itself, ended is over) and [resumable]. This is what the ongoing call notification shows, what the
 * agent's messages lead back to, and what "call me again" rejoins.
 */
fun liveCall(session: Session?, now: Instant, zone: TimeZone): Session? = session?.takeIf {
    it.kind == SessionKind.MORNING_CALL &&
        (it.state == Session.State.ACTIVE || it.state == Session.State.BUSY) &&
        resumable(it, now, zone)
}

/** How the call screen opens on [active] (the server's running morning call, if any). */
data class CallEntry(val screen: Screen, val session: Session?) {
    enum class Screen { Incoming, Snoozed, InCall }
}

/**
 * Where the call screen starts. No call, or one that ended: the incoming screen. A snoozed call: the
 * snoozed screen. A call from an earlier day: the incoming screen as if there were none, so answering
 * starts a new one. A running call: straight back in, unless the phone is [ringing] (the alarm, or
 * "call me again"), in which case the person answers first, so the mic never opens by itself on a
 * screen that just woke up.
 */
fun callEntry(active: Session?, ringing: Boolean, now: Instant, zone: TimeZone): CallEntry = when {
    active == null || active.state == Session.State.ENDED -> CallEntry(CallEntry.Screen.Incoming, null)
    active.state == Session.State.SNOOZED -> CallEntry(CallEntry.Screen.Snoozed, active)
    liveCall(active, now, zone) == null -> CallEntry(CallEntry.Screen.Incoming, null)
    ringing -> CallEntry(CallEntry.Screen.Incoming, active)
    else -> CallEntry(CallEntry.Screen.InCall, active)
}

// --- the call, app-wide ---

/**
 * The morning call as the whole app knows it, independent of the call screen: the screen reports
 * what it does, notifications and the hang-up button report theirs, and session changes from the feed
 * are fetched. The ongoing call notification, Today's "call me again" and the push decisions all
 * follow [session], so the call outlives its screen being swiped away.
 */
class CallTracker(private val sessions: SessionsRepository, private val clock: AppClock) {
    private val _session = MutableStateFlow<Session?>(null)

    /** The latest morning call session seen (any state), or null when there is none. */
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _ringing = MutableStateFlow(false)

    /** The phone is ringing for the call (the alarm or "call me again") and nobody answered yet. */
    val ringing: StateFlow<Boolean> = _ringing.asStateFlow()

    /** The call going on right now, if any (see [liveCall]). */
    fun live(): Session? = liveCall(_session.value, clock.now(), clock.zone())

    /** A session the app just got from the server. Only morning calls count; others leave it alone. */
    fun report(session: Session) {
        if (session.kind != SessionKind.MORNING_CALL) return
        _session.value = session
    }

    fun ring(on: Boolean) {
        _ringing.value = on
    }

    /** Asks the server for the running morning call. Returns false when it could not be reached. */
    suspend fun refresh(): Boolean = when (val r = sessions.active(SessionKind.MORNING_CALL)) {
        is ApiResult.Success -> {
            val active = r.value
            if (active != null) report(active) else _session.value = null
            true
        }
        is ApiResult.Failure -> false
    }

    /** Follows session changes from the feed until cancelled. */
    suspend fun follow(signals: Flow<SyncSignal>) {
        signals.filterIsInstance<SyncSignal.SessionChanged>().collect { changed(it.sessionId) }
    }

    private suspend fun changed(id: SessionId) {
        if (id == _session.value?.id) {
            (sessions.get(id) as? ApiResult.Success)?.value?.let(::report)
        } else {
            // Another session started (which ends ours) or ours is new to this process: ask.
            refresh()
        }
    }
}
