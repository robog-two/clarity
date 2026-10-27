package net.robog.clarity.app.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import net.robog.clarity.app.testutil.T0
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import net.robog.clarity.app.data.feed.SyncSignal
import net.robog.clarity.app.testutil.FixedClock
import net.robog.clarity.app.testutil.ScriptedSessions
import net.robog.clarity.app.testutil.callSession
import net.robog.clarity.app.ui.call.agentReplies
import net.robog.clarity.app.ui.call.shouldBlip
import net.robog.clarity.app.testutil.agentItem
import net.robog.clarity.app.testutil.userItem
import net.robog.clarity.shared.session.CallStage
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.SessionKind

private val berlin = TimeZone.of("Europe/Berlin")
/** 07:30 in Berlin, when this morning's call started. */
private val morning = Instant.parse("2026-10-27T06:30:00Z")

private fun call(state: Session.State = Session.State.ACTIVE, stage: CallStage = CallStage.CHOOSE, startedAt: Instant = morning) =
    callSession(state = state, stage = stage).copy(startedAt = startedAt)

class LiveCallTest {
    @Test fun aRunningCallFromThisMorningIsStillGoingOnHoursLater() {
        assertEquals(call(), liveCall(call(), morning + 6.hours, berlin))
        assertEquals(call(Session.State.BUSY), liveCall(call(Session.State.BUSY), morning + 1.minutes, berlin))
    }

    @Test fun snoozedEndedOrOtherSessionsAreNotACallGoingOn() {
        assertNull(liveCall(call(Session.State.SNOOZED), morning, berlin))
        assertNull(liveCall(call(Session.State.ENDED), morning, berlin))
        assertNull(liveCall(callSession(kind = SessionKind.AMEND).copy(startedAt = morning), morning, berlin))
        assertNull(liveCall(null, morning, berlin))
    }

    @Test fun aCallFromAnEarlierDayWasAbandoned() {
        // Started yesterday morning, never ended: not shown, not offered, not rejoined.
        assertNull(liveCall(call(startedAt = morning - 24.hours), morning, berlin))
        // The day is the person's own: 23:30 UTC on the 27th is already the 28th in Berlin.
        val nextDayInBerlin = Instant.parse("2026-10-27T23:30:00Z")
        assertFalse(resumable(call(startedAt = morning), nextDayInBerlin, berlin))
        assertTrue(resumable(call(startedAt = morning), Instant.parse("2026-10-27T21:00:00Z"), berlin))
    }

    @Test fun aCallRunningPastMidnightStaysForAFewHours() {
        val beforeMidnight = Instant.parse("2026-10-27T22:50:00Z") // 23:50 Berlin
        assertTrue(resumable(call(startedAt = beforeMidnight), beforeMidnight + 1.hours, berlin))
        assertFalse(resumable(call(startedAt = beforeMidnight), beforeMidnight + 4.hours, berlin))
    }

    @Test fun theOngoingLineNamesTheStage() {
        assertEquals("morning meeting · hand off", ongoingCallText(CallStage.HAND_OFF))
        assertEquals("morning meeting", ongoingCallText(null))
    }

    @Test fun onlyACallPastItsFirstStageIsUnderWay() {
        assertTrue(underWay(call(stage = CallStage.COMMIT)))
        assertFalse(underWay(call(stage = CallStage.CLEAR)))
        assertFalse(underWay(call(Session.State.SNOOZED)))
    }
}

class CallEntryTest {
    private val now = morning + 3.hours

    @Test fun noCallOrAnEndedOneStartsOnTheIncomingScreen() {
        assertEquals(CallEntry(CallEntry.Screen.Incoming, null), callEntry(null, ringing = false, now, berlin))
        assertEquals(CallEntry(CallEntry.Screen.Incoming, null), callEntry(call(Session.State.ENDED), ringing = false, now, berlin))
    }

    @Test fun aSnoozedCallShowsAsSnoozed() {
        assertEquals(CallEntry.Screen.Snoozed, callEntry(call(Session.State.SNOOZED), ringing = true, now, berlin).screen)
    }

    @Test fun aRunningCallIsRejoinedUnlessThePhoneIsRinging() {
        assertEquals(CallEntry(CallEntry.Screen.InCall, call()), callEntry(call(), ringing = false, now, berlin))
        assertEquals(CallEntry(CallEntry.Screen.Incoming, call()), callEntry(call(), ringing = true, now, berlin))
    }

    @Test fun anAbandonedCallFromYesterdayIsLeftBehind() {
        assertEquals(CallEntry(CallEntry.Screen.Incoming, null), callEntry(call(startedAt = now - 30.hours), ringing = false, now, berlin))
    }
}

class CallBlipTest {
    private val id = SessionId("call1")

    private val now = T0 + 10.seconds

    @Test fun theHistoryOnOpenNeverBlips() {
        assertFalse(shouldBlip(null, listOf(agentItem(1, "Morning.", id)), now))
    }

    @Test fun aNewFinishedAgentReplyBlipsOnce() {
        val first = listOf(agentItem(1, "Morning.", id))
        val streaming = first + agentItem(3, "Got", id, streaming = true)
        assertFalse(shouldBlip(agentReplies(first), streaming, now), "not while it is still being written")
        val done = first + agentItem(3, "Got it.", id)
        assertTrue(shouldBlip(agentReplies(streaming), done, now))
        assertFalse(shouldBlip(agentReplies(done), done, now))
    }

    @Test fun thePersonsOwnWordsNeverBlip() {
        val first = listOf(agentItem(1, "Morning.", id))
        assertFalse(shouldBlip(agentReplies(first), first + userItem(2, "the spec", id), now))
    }

    @Test fun oldRepliesCatchingUpDoNotBlip() {
        assertFalse(shouldBlip(emptySet(), listOf(agentItem(1, "Morning.", id)), T0 + 5.minutes))
    }
}

class CallTrackerTest {
    @Test fun reportsAndRefreshesFollowTheServer() = runBlocking {
        val clock = FixedClock(morning + 1.hours, berlin)
        val sessions = ScriptedSessions(call())
        val tracker = CallTracker(sessions, clock)
        assertNull(tracker.live())
        assertTrue(tracker.refresh())
        assertEquals(call(), tracker.live())

        tracker.report(callSession(kind = SessionKind.AMEND, id = "amend1"))
        assertEquals(call(), tracker.live(), "other kinds of session leave the call alone")

        sessions.current = null
        tracker.refresh()
        assertNull(tracker.session.value, "no call running on the server: none here")
    }

    @Test fun followsSessionChangesFromTheFeed() = runBlocking {
        val clock = FixedClock(morning + 1.hours, berlin)
        val sessions = ScriptedSessions(call())
        val tracker = CallTracker(sessions, clock).also { it.report(call()) }
        val signals = MutableSharedFlow<SyncSignal>(extraBufferCapacity = 4)
        val job = CoroutineScope(Dispatchers.Unconfined).launch { tracker.follow(signals) }
        sessions.current = call(Session.State.ENDED)
        signals.emit(SyncSignal.SessionChanged(SessionId("call1")))
        assertNull(tracker.live(), "hung up elsewhere: the ongoing call goes")
        job.cancel()
    }

    @Test fun openingTheAppUsesAFreshStateOrAQuickRefresh() = runBlocking {
        val clock = FixedClock(morning + 1.hours, berlin)
        val sessions = CountingSessions(ScriptedSessions(call()))
        val tracker = CallTracker(sessions, clock)
        assertEquals(call(), tracker.liveNow(), "nothing known yet: asks the server")
        assertEquals(1, sessions.actives)
        clock.now += 10.seconds
        assertEquals(call(), tracker.liveNow())
        assertEquals(1, sessions.actives, "confirmed moments ago: no second request")
        clock.now += 5.minutes
        tracker.liveNow()
        assertEquals(2, sessions.actives, "stale: asks again")
    }

    @Test fun anUnreachableServerOnOpenMeansNoCall() = runBlocking {
        val sessions = ScriptedSessions(call())
        val down = object : net.robog.clarity.app.data.SessionsRepository by sessions {
            override suspend fun active(kind: SessionKind) =
                net.robog.clarity.app.data.ApiResult.Failure(net.robog.clarity.app.data.ApiError.Unreachable("down"))
        }
        assertNull(CallTracker(down, FixedClock(morning, berlin)).liveNow(), "Today shows as usual")
    }
}

private class CountingSessions(private val inner: ScriptedSessions) : net.robog.clarity.app.data.SessionsRepository by inner {
    var actives = 0
    override suspend fun active(kind: SessionKind): net.robog.clarity.app.data.ApiResult<Session?> {
        actives++
        return inner.active(kind)
    }
}

class ResumeOnLaunchTest {
    private val live = call()

    @Test fun theLauncherOrRecentsGoStraightBackIntoTodaysCall() {
        assertTrue(resumeOnLaunch(LaunchKind.Launcher, live, choseAppOver = null))
        assertTrue(resumeOnLaunch(LaunchKind.Returned, live, choseAppOver = null))
    }

    @Test fun noCallGoingOnOpensTheAppAsUsual() {
        assertFalse(resumeOnLaunch(LaunchKind.Launcher, null, null))
    }

    @Test fun aLinkOrNotificationOpensWhatItAskedFor() {
        assertFalse(resumeOnLaunch(LaunchKind.Link, live, null))
        assertFalse(resumeOnLaunch(LaunchKind.Other, live, null))
    }

    @Test fun leavingTheCallForTheAppIsRespected() {
        assertFalse(resumeOnLaunch(LaunchKind.Launcher, live, choseAppOver = live.id))
        assertFalse(resumeOnLaunch(LaunchKind.Returned, live, choseAppOver = live.id))
        // That choice was about another call: today's new one still comes back.
        assertTrue(resumeOnLaunch(LaunchKind.Launcher, live, choseAppOver = SessionId("yesterday")))
    }

    @Test fun theLaunchKindComesFromTheIntent() {
        assertEquals(LaunchKind.Launcher, launchKind("android.intent.action.MAIN", hasLink = false))
        assertEquals(LaunchKind.Link, launchKind("android.intent.action.VIEW", hasLink = true))
        assertEquals(LaunchKind.Link, launchKind("android.intent.action.MAIN", hasLink = true))
        assertEquals(LaunchKind.Other, launchKind(null, hasLink = false))
    }
}
