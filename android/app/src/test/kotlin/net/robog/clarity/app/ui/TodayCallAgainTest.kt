package net.robog.clarity.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.robog.clarity.app.call.CallTracker
import net.robog.clarity.app.data.FeedRepository
import net.robog.clarity.app.data.TodayRepository
import net.robog.clarity.app.testutil.FakeCapabilities
import net.robog.clarity.app.testutil.FakeToday
import net.robog.clarity.app.testutil.FixedClock
import net.robog.clarity.app.testutil.RecordingNotifier
import net.robog.clarity.app.testutil.ScreenFeed
import net.robog.clarity.app.testutil.ScriptedSessions
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.callService
import net.robog.clarity.app.testutil.callSession
import net.robog.clarity.app.testutil.withMain
import net.robog.clarity.app.call.callEntry
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.ui.today.CallOffer
import net.robog.clarity.app.ui.today.TodayViewModel
import net.robog.clarity.app.ui.today.callOffer
import net.robog.clarity.app.call.CallEntry
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import net.robog.clarity.shared.session.CallStage
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.Today

internal fun todayVm(
    repo: TodayRepository,
    feed: FeedRepository,
    clock: FixedClock,
    sessions: ScriptedSessions = ScriptedSessions(),
    notifier: RecordingNotifier = RecordingNotifier(),
    capabilities: FakeCapabilities = FakeCapabilities(),
): TodayViewModel {
    val tracker = CallTracker(sessions, clock)
    return TodayViewModel(repo, feed, clock, tracker, callService(sessions, clock, tracker, notifier, capabilities = capabilities))
}

class TodayCallAgainTest {
    private val clock = FixedClock(T0 + 5.hours)
    private val day = LocalDate(2026, 10, 26)

    @Test fun whatTodayOffersFollowsTheDayAndTheCall() {
        val now = T0 + 5.hours
        val utc = TimeZone.UTC
        val fresh = Today(day)
        val endedEarly = Today(day, callStarted = true)
        val handedOff = Today(day, called = true, callStarted = true)
        assertEquals(CallOffer.NONE, callOffer(null, null, now, utc), "nothing before the day is known")
        assertEquals(CallOffer.START, callOffer(fresh, null, now, utc))
        assertEquals(CallOffer.RESTART, callOffer(endedEarly, null, now, utc))
        assertEquals(CallOffer.RESTART, callOffer(endedEarly, callSession(state = Session.State.ENDED), now, utc))
        assertEquals(CallOffer.NONE, callOffer(handedOff, null, now, utc), "the plan is made: no button")
        assertEquals(CallOffer.REJOIN, callOffer(endedEarly, callSession(stage = CallStage.CHOOSE), now, utc))
        assertEquals(CallOffer.NONE, callOffer(endedEarly, callSession(state = Session.State.SNOOZED), now, utc), "a snoozed call rings by itself")
        assertEquals(CallOffer.START, callOffer(fresh, callSession(state = Session.State.SNOOZED), T0 + 2.days, utc), "yesterday's snooze is over")
    }

    @Test fun noCallYetOffersToStartOneAndAnsweringStartsIt() = withMain {
        val notifier = RecordingNotifier()
        val sessions = ScriptedSessions()
        val vm = todayVm(FakeToday(), ScreenFeed(), clock, sessions, notifier)
        assertEquals(CallOffer.START, vm.state.value.callOffer)
        vm.callAgain()
        runBlocking { withTimeout(2_000) { while (notifier.log.isEmpty()) delay(5) } }
        assertEquals(listOf("incoming:FullScreenCall@"), notifier.log)
        // The call screen then opens on the incoming screen with no session, so answering starts one.
        assertEquals(CallEntry(CallEntry.Screen.Incoming, null), callEntry(null, ringing = true, clock.now(), TimeZone.UTC))
    }

    @Test fun aCallThatEndedEarlyOffersToCallAgain() = withMain {
        val ended = ScriptedSessions(callSession(state = Session.State.ENDED))
        val vm = todayVm(FakeToday(ApiResult.Success(Today(day, callStarted = true))), ScreenFeed(), clock, ended)
        assertEquals(CallOffer.RESTART, vm.state.value.callOffer)
        val none = todayVm(FakeToday(ApiResult.Success(Today(day, callStarted = true))), ScreenFeed(), clock)
        assertEquals(CallOffer.RESTART, none.state.value.callOffer, "the server no longer lists an ended call")
    }

    @Test fun afterTheHandOffThereIsNoButton() = withMain {
        val vm = todayVm(FakeToday(ApiResult.Success(Today(day, called = true, callStarted = true))), ScreenFeed(), clock)
        assertEquals(CallOffer.NONE, vm.state.value.callOffer)
    }

    @Test fun anUnfinishedCallFromThisMorningOffersToRingAgain() = withMain {
        val sessions = ScriptedSessions(callSession(stage = CallStage.CHOOSE))
        val notifier = RecordingNotifier()
        val vm = todayVm(FakeToday(), ScreenFeed(), clock, sessions, notifier)
        assertEquals(CallOffer.REJOIN, vm.state.value.callOffer, "five hours later the call is still there to pick up")
        vm.callAgain()
        runBlocking { withTimeout(2_000) { while (notifier.log.isEmpty()) delay(5) } }
        assertEquals(listOf("incoming:FullScreenCall@"), notifier.log)
    }

    @Test fun noOfferWithoutARunningCall() = withMain {
        val snoozed = ScriptedSessions(callSession(state = Session.State.SNOOZED))
        assertEquals(CallOffer.NONE, todayVm(FakeToday(), ScreenFeed(), clock, snoozed).state.value.callOffer, "a snoozed call rings by itself")
    }

    @Test fun aCallLeftFromYesterdayIsNotOffered() = withMain {
        val old = ScriptedSessions(callSession(stage = CallStage.CHOOSE))
        assertEquals(CallOffer.START, todayVm(FakeToday(), ScreenFeed(), FixedClock(T0 + 1.days), old).state.value.callOffer, "a new day, a new call")
    }

    @Test fun withNotificationsOffItSaysWhy() = withMain {
        val sessions = ScriptedSessions(callSession())
        val vm = todayVm(FakeToday(), ScreenFeed(), clock, sessions, capabilities = FakeCapabilities(notifications = false))
        vm.callAgain()
        runBlocking { withTimeout(2_000) { while (vm.state.value.callAgainProblem == null) delay(5) } }
        assertNotNull(vm.state.value.callAgainProblem)
    }
}
