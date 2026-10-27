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
import net.robog.clarity.app.ui.today.TodayViewModel
import net.robog.clarity.shared.session.CallStage
import net.robog.clarity.shared.session.Session

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

    @Test fun anUnfinishedCallFromThisMorningOffersToRingAgain() = withMain {
        val sessions = ScriptedSessions(callSession(stage = CallStage.CHOOSE))
        val notifier = RecordingNotifier()
        val vm = todayVm(FakeToday(), ScreenFeed(), clock, sessions, notifier)
        assertTrue(vm.state.value.callAgain, "five hours later the call is still there to pick up")
        vm.callAgain()
        runBlocking { withTimeout(2_000) { while (notifier.log.isEmpty()) delay(5) } }
        assertEquals(listOf("incoming:FullScreenCall@"), notifier.log)
    }

    @Test fun noOfferWithoutARunningCall() = withMain {
        assertFalse(todayVm(FakeToday(), ScreenFeed(), clock).state.value.callAgain)
        val snoozed = ScriptedSessions(callSession(state = Session.State.SNOOZED))
        assertFalse(todayVm(FakeToday(), ScreenFeed(), clock, snoozed).state.value.callAgain, "a snoozed call rings by itself")
    }

    @Test fun aCallLeftFromYesterdayIsNotOffered() = withMain {
        val old = ScriptedSessions(callSession(stage = CallStage.CHOOSE))
        assertFalse(todayVm(FakeToday(), ScreenFeed(), FixedClock(T0 + 1.days), old).state.value.callAgain)
    }

    @Test fun withNotificationsOffItSaysWhy() = withMain {
        val sessions = ScriptedSessions(callSession())
        val vm = todayVm(FakeToday(), ScreenFeed(), clock, sessions, capabilities = FakeCapabilities(notifications = false))
        vm.callAgain()
        runBlocking { withTimeout(2_000) { while (vm.state.value.callAgainProblem == null) delay(5) } }
        assertNotNull(vm.state.value.callAgainProblem)
    }
}
