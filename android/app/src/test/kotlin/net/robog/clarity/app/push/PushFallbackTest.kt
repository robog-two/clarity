@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package net.robog.clarity.app.push

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.robog.clarity.app.data.OnboardingFlagStore
import net.robog.clarity.app.data.feed.Arrival
import net.robog.clarity.app.data.feed.FeedSync
import net.robog.clarity.app.testutil.FakeBackend
import net.robog.clarity.app.testutil.FakeEventSource
import net.robog.clarity.app.testutil.FakePushPrefs
import net.robog.clarity.app.testutil.FakeSessionStore
import net.robog.clarity.app.testutil.FixedClock
import net.robog.clarity.app.testutil.InMemoryFeedStore
import net.robog.clarity.app.testutil.RecordingPushNotifier
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.agentItem
import net.robog.clarity.app.testutil.heldAction
import net.robog.clarity.app.testutil.signedIn
import net.robog.clarity.shared.governance.Urgency

/** The ledger kept in memory, with the same claim rules as the DataStore one. */
private class MemoryLedger(private val scope: String = "s") : NotifiedLedger {
    var state = Notified()
    override suspend fun claim(arrival: Arrival): Boolean {
        val next = state.claim(arrival, scope) ?: return false
        state = next
        return true
    }
}

private class RecordingWork : PushFallbackWork {
    val calls = mutableListOf<String>()
    override fun schedule() { calls += "schedule" }
    override fun cancel() { calls += "cancel" }
}

private class Flag(done: Boolean) : OnboardingFlagStore {
    val state = MutableStateFlow(done)
    override val done = state
    override suspend fun markDone() { state.value = true }
}

private fun item(seq: Long) = Arrival.Item(agentItem(seq, "hello $seq"))
private fun urgent(id: String) = Arrival.Held(heldAction(id).copy(urgency = Urgency.URGENT))

class NotifiedTest {
    @Test fun anItemIsClaimedOnceAndTheLastSeqMoves() {
        val first = assertNotNull(Notified().claim(item(5), "s"))
        assertEquals(5, first.lastSeq)
        assertNull(first.claim(item(5), "s"), "the second path loses")
    }

    @Test fun aLowerSeqThatFinishesLateIsStillClaimed() {
        // The service and the job may finish out of order; neither should swallow the other's item.
        val n = Notified().claim(item(11), "s")!!.claim(item(10), "s")
        assertNotNull(n)
        assertEquals(11, n.lastSeq)
        assertNull(n.claim(item(10), "s"))
    }

    @Test fun oldSeqsFoldIntoTheFloor() {
        var n = Notified()
        (1L..5L).forEach { n = n.claim(item(it), "s", keep = 2)!! }
        assertEquals(3, n.floor)
        assertEquals(listOf(4L, 5L), n.seqs)
        assertNull(n.claim(item(2), "s", keep = 2), "below the floor is done")
        assertEquals(5, n.lastSeq)
    }

    @Test fun heldActionsAreClaimedById() {
        val n = Notified().claim(urgent("h1"), "s")!!
        assertNull(n.claim(urgent("h1"), "s"))
        assertNotNull(n.claim(urgent("h2"), "s"))
    }

    @Test fun anotherServerStartsOver() {
        val n = Notified().claim(item(40), "old")!!.claim(urgent("h1"), "old")!!
        val fresh = assertNotNull(n.claim(item(3), "new"), "a new server's seqs mean something else")
        assertEquals("new", fresh.scope)
        assertNotNull(fresh.claim(urgent("h1"), "new"))
    }

    @Test fun theDataStoreLedgerKeepsClaimsAcrossInstances() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "ledger-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "t.preferences_pb") }
            val sessions = FakeSessionStore(signedIn())
            val service = DataStoreNotifiedLedger(store, sessions)
            val job = DataStoreNotifiedLedger(store, sessions)
            assertTrue(service.claim(item(7)))
            assertFalse(job.claim(item(7)), "the job sees what the service claimed")
            assertTrue(job.claim(urgent("h1")))
            assertFalse(service.claim(urgent("h1")))
            assertTrue(job.claim(item(8)))
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}

class PushListenerLedgerTest {
    @Test fun theServiceAndTheJobNeverBothPostTheSameArrival() = runTest {
        val ledger = MemoryLedger()
        val notifier = RecordingPushNotifier()
        fun listener() = PushListener(emptyFlow(), { Front.Background }, { true }, FixedClock(T0), notifier, ledger)
        val service = listener()
        val job = listener()
        assertTrue(service.deliver(item(1)))
        assertFalse(job.deliver(item(1)))
        assertTrue(job.deliver(urgent("h1")))
        assertFalse(service.deliver(urgent("h1")))
        assertEquals(2, notifier.posted.size)
    }

    @Test fun anArrivalTheServiceChoseNotToPostIsNotPostedLater() = runTest {
        val ledger = MemoryLedger()
        val notifier = RecordingPushNotifier()
        var front: Front = Front.Showing(Place.Tab(net.robog.clarity.app.nav.MainTab.Today))
        val listener = PushListener(emptyFlow(), { front }, { true }, FixedClock(T0), notifier, ledger)
        assertFalse(listener.deliver(item(2)), "the person was looking at it")
        front = Front.Background
        assertFalse(listener.deliver(item(2)))
        assertTrue(notifier.posted.isEmpty())
    }
}

class FallbackStepTest {
    @Test fun decidesFromTheConditionsAndTheService() {
        assertEquals(FallbackStep.Cancel, fallbackStep(shouldRun = false, serviceRunning = true, feedLive = true))
        assertEquals(FallbackStep.Cancel, fallbackStep(shouldRun = false, serviceRunning = false, feedLive = false))
        assertEquals(FallbackStep.Skip, fallbackStep(shouldRun = true, serviceRunning = true, feedLive = true))
        assertEquals(FallbackStep.CatchUp, fallbackStep(shouldRun = true, serviceRunning = true, feedLive = false))
        assertEquals(FallbackStep.CatchUp, fallbackStep(shouldRun = true, serviceRunning = false, feedLive = true))
        assertEquals(FallbackStep.CatchUp, fallbackStep(shouldRun = true, serviceRunning = false, feedLive = false))
    }

    @Test fun aBackgroundStartNeedsTheBatteryExemptionFromAndroid12() {
        assertTrue(canStartInBackground(sdk = 30, batteryUnrestricted = false))
        assertFalse(canStartInBackground(sdk = 31, batteryUnrestricted = false))
        assertTrue(canStartInBackground(sdk = 35, batteryUnrestricted = true))
    }
}

class PushFallbackTest {
    private val on = PushConditions(hasServer = true, setUp = true, enabled = true)

    private class Rig(
        var conditions: PushConditions,
        var running: Boolean = false,
        var live: Boolean = false,
        var mayStart: Boolean = true,
    ) {
        val backend = FakeBackend(pageSize = 10)
        val store = InMemoryFeedStore()
        val sync = FeedSync(backend, FakeEventSource(emptyList()), store)
        val ledger = MemoryLedger()
        val notifier = RecordingPushNotifier()
        val listener = PushListener(sync.arrivals, { Front.Background }, { true }, FixedClock(T0), notifier, ledger)
        val work = RecordingWork()
        var reconnects = 0
        var starts = 0
        var catchUps = 0
        val fallback = PushFallback(
            conditions = { conditions },
            serviceRunning = { running },
            feedLive = { live },
            catchUp = { catchUps++; sync.catchUp() },
            deliver = listener::deliver,
            reconnect = { reconnects++ },
            canStartService = { mayStart },
            startService = { starts++ },
            work = work,
        )
    }

    @Test fun withTheServiceGoneItCatchesUpPostsAndRestartsIt() = runTest {
        val rig = Rig(on)
        rig.backend.all = listOf(agentItem(1, "while you were away").copy(notify = "push_now"), agentItem(2, "and this"))
        rig.backend.held = listOf(heldAction("h1").copy(urgency = Urgency.URGENT))

        val outcome = rig.fallback.run()
        assertEquals(PushFallback.Outcome(FallbackStep.CatchUp, 3, true), outcome)
        assertEquals(listOf(PushChannel.Agent, PushChannel.Agent, PushChannel.Urgent), rig.notifier.posted.map { it.channel })
        assertEquals(1, rig.starts)

        // The next run finds nothing new, so nothing buzzes again.
        assertEquals(0, rig.fallback.run().posted)
        assertEquals(3, rig.notifier.posted.size)
    }

    @Test fun itDoesNotRepostWhatTheServiceAlreadyNotified() = runTest {
        val rig = Rig(on)
        val seen = agentItem(1, "the service got this one").copy(notify = "push_now")
        rig.backend.all = listOf(seen, agentItem(2, "this one it missed").copy(notify = "push_now"))
        // The service claimed seq 1 just before it was killed, but the cache never stored it.
        rig.listener.deliver(Arrival.Item(seen))
        assertEquals(1, rig.notifier.posted.size)

        assertEquals(1, rig.fallback.run().posted)
        assertEquals(listOf("the service got this one", "this one it missed"), rig.notifier.posted.map { it.text })
    }

    @Test fun aLiveServiceIsLeftAlone() = runTest {
        val rig = Rig(on, running = true, live = true)
        rig.backend.all = listOf(agentItem(1, "the service has it"))
        assertEquals(FallbackStep.Skip, rig.fallback.run().step)
        assertEquals(0, rig.catchUps)
        assertEquals(0, rig.reconnects + rig.starts)
        assertTrue(rig.notifier.posted.isEmpty())
    }

    @Test fun aStalledServiceIsNudgedNotRestarted() = runTest {
        val rig = Rig(on, running = true, live = false)
        rig.backend.all = listOf(agentItem(1, "stuck behind a dead socket").copy(notify = "push_now"))
        val outcome = rig.fallback.run()
        assertEquals(PushFallback.Outcome(FallbackStep.CatchUp, 1, false), outcome)
        assertEquals(1, rig.reconnects)
        assertEquals(0, rig.starts)
    }

    @Test fun withoutTheExemptionItStillNotifiesButDoesNotTryToStart() = runTest {
        val rig = Rig(on, mayStart = false)
        rig.backend.all = listOf(agentItem(1, "late but here").copy(notify = "push_now"))
        val outcome = rig.fallback.run()
        assertEquals(PushFallback.Outcome(FallbackStep.CatchUp, 1, false), outcome)
        assertEquals(0, rig.starts)
    }

    @Test fun anUnreachableServerPostsNothingAndDoesNotFail() = runTest {
        val rig = Rig(on)
        rig.backend.failure = net.robog.clarity.app.data.ApiError.Unreachable("down")
        assertEquals(0, rig.fallback.run().posted)
        assertTrue(rig.notifier.posted.isEmpty())
    }

    @Test fun pushTurnedOffCancelsTheJobWithoutFetching() = runTest {
        val rig = Rig(on.copy(enabled = false), running = false)
        rig.backend.all = listOf(agentItem(1, "never fetched"))
        assertEquals(FallbackStep.Cancel, rig.fallback.run().step)
        assertEquals(listOf("cancel"), rig.work.calls)
        assertEquals(0, rig.catchUps)
        assertEquals(0, rig.starts)
    }
}

class PushFallbackScheduleTest {
    @Test fun followsTheConditions() {
        val work = RecordingWork()
        work.follow(PushConditions(hasServer = true, setUp = true, enabled = true))
        work.follow(PushConditions(hasServer = true, setUp = true, enabled = false))
        work.follow(PushConditions(hasServer = false, setUp = true, enabled = true))
        work.follow(PushConditions(hasServer = true, setUp = false, enabled = true))
        assertEquals(listOf("schedule", "cancel", "cancel", "cancel"), work.calls)
    }

    @Test fun scheduledWhenSetUpAndCancelledWhenTurnedOffOrForgotten() = runTest {
        val sessions = FakeSessionStore(signedIn())
        val flag = Flag(done = false)
        val prefs = FakePushPrefs(enabled = true)
        val work = RecordingWork()
        suspend fun reconcile() = work.follow(pushConditions(sessions, flag, prefs).first())

        reconcile()
        assertEquals(listOf("cancel"), work.calls, "not before onboarding finishes")
        flag.markDone(); reconcile()
        assertEquals("schedule", work.calls.last())
        prefs.setEnabled(false); reconcile()
        assertEquals("cancel", work.calls.last())
        prefs.setEnabled(true); reconcile()
        assertEquals("schedule", work.calls.last())
        sessions.clear(); reconcile()
        assertEquals("cancel", work.calls.last(), "forgetting the server stops it")
    }
}
