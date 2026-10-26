@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package net.robog.clarity.app.push

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.robog.clarity.app.data.feed.Arrival
import net.robog.clarity.app.data.feed.Backoff
import net.robog.clarity.app.data.feed.FeedSync
import net.robog.clarity.app.data.feed.SyncState
import net.robog.clarity.app.net.FeedEventSource
import net.robog.clarity.app.testutil.FakeBackend
import net.robog.clarity.app.testutil.FakeEventSource
import net.robog.clarity.app.testutil.FakeEventSource.Script
import net.robog.clarity.app.testutil.FixedClock
import net.robog.clarity.app.testutil.InMemoryFeedStore
import net.robog.clarity.app.testutil.RecordingPushNotifier
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.agentItem
import net.robog.clarity.app.testutil.heldAction
import net.robog.clarity.shared.feed.FeedEvent
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.Urgency

class SharedRunTest {
    @Test fun oneBodyServesEveryHolderAndStopsWithTheLast() = runTest {
        var starts = 0
        var stops = 0
        val run = SharedRun(backgroundScope) {
            starts++
            try { awaitCancellation() } finally { stops++ }
        }
        val app = launch { run.hold() }
        runCurrent()
        val service = launch { run.hold() }
        runCurrent()
        assertEquals(1, starts, "the service joins the connection the app opened")
        assertTrue(run.running)

        app.cancel(); runCurrent()
        assertEquals(0, stops, "the service still needs it")
        assertTrue(run.running)

        service.cancel(); runCurrent()
        assertEquals(1, stops)
        assertFalse(run.running)

        val again = launch { run.hold() }
        runCurrent()
        assertEquals(2, starts, "a new holder starts it afresh")
        again.cancel(); runCurrent()
        assertEquals(2, stops)
    }

    @Test fun theActionLoopRunsWhileTheServiceHoldsItEvenWithTheAppClosed() = runTest {
        var loops = 0
        var feeds = 0
        val holder = ConnectionHolder(
            backgroundScope,
            feed = { feeds++; awaitCancellation() },
            actions = { loops++; awaitCancellation() },
        )
        val service = launch { holder.holdFeed() }
        val serviceLoop = launch { holder.holdActions() }
        runCurrent()
        assertTrue(holder.feedRunning && holder.actionsRunning)

        val app = launch { holder.holdFeed() }
        val appLoop = launch { holder.holdActions() }
        runCurrent()
        assertEquals(1, feeds)
        assertEquals(1, loops, "the app in front shares the service's loop")

        app.cancel(); appLoop.cancel(); runCurrent()
        assertTrue(holder.feedRunning && holder.actionsRunning, "closing the app does not stop the service's work")

        service.cancel(); serviceLoop.cancel(); runCurrent()
        assertFalse(holder.feedRunning || holder.actionsRunning, "turning push off ends it")
    }
}

class PushListenerTest {
    @Test fun postsOnlyWhatThePolicyAllows() = runTest {
        val arrivals = MutableSharedFlow<Arrival>(extraBufferCapacity = 8)
        val notifier = RecordingPushNotifier()
        var front: Front = Front.Background
        var canPost = true
        val listener = PushListener(arrivals, { front }, { canPost }, FixedClock(T0), notifier)
        val job = launch(Dispatchers.Unconfined) { listener.run() }

        arrivals.emit(Arrival.Item(agentItem(1, "held for later").copy(notify = "hold_until:2026-10-26T12:00:00Z")))
        arrivals.emit(Arrival.Item(agentItem(2, "quiet one, but the app is closed")))
        arrivals.emit(Arrival.Item(agentItem(3, "loud one").copy(notify = "push_now")))
        arrivals.emit(Arrival.Held(heldAction("h1").copy(urgency = Urgency.URGENT)))
        assertEquals(listOf(PushChannel.Agent, PushChannel.Agent, PushChannel.Urgent), notifier.posted.map { it.channel })

        front = Front.Showing(Place.Tab(net.robog.clarity.app.nav.MainTab.Today))
        arrivals.emit(Arrival.Item(agentItem(3, "seen already").copy(notify = "push_now")))
        canPost = false
        front = Front.Background
        arrivals.emit(Arrival.Held(heldAction("h2").copy(urgency = Urgency.URGENT)))
        assertEquals(3, notifier.posted.size)
        job.cancel()
    }
}

/** Connects, reports open, then holds the stream until cancelled. */
private class OpenSource : FeedEventSource {
    val connects = mutableListOf<Long>()
    override suspend fun connect(after: Long, onOpen: () -> Unit, onEvent: suspend (FeedEvent) -> Unit) {
        connects += after
        onOpen()
        awaitCancellation()
    }
}

class FeedSyncPushTest {
    private fun sync(backend: FakeBackend, source: FeedEventSource, store: InMemoryFeedStore) =
        FeedSync(backend, source, store, Backoff(10.seconds, 60.seconds)) { delay(it) }

    @Test fun eachNewItemArrivesOnceAndReplaysDoNotRepeatIt() = runTest {
        val store = InMemoryFeedStore()
        val fs = sync(FakeBackend(), FakeEventSource(emptyList()), store)
        val arrived = mutableListOf<FeedItem>()
        val job = launch(Dispatchers.Unconfined) { fs.arrivals.collect { if (it is Arrival.Item) arrived += it.item } }
        val pushed = agentItem(4, "urgent").copy(notify = "push_now")
        fs.apply(FeedEvent.ItemAppended(pushed))
        fs.apply(FeedEvent.ItemAppended(pushed))
        fs.apply(FeedEvent.ItemFinal(pushed))
        job.cancel()
        assertEquals(listOf(pushed), arrived)
        assertEquals("push_now", store.snapshot.single().notify, "the decision is kept with the cached item")
    }

    @Test fun anUrgentHeldActionArrivesOnceWhenItFirstWaits() = runTest {
        val store = InMemoryFeedStore()
        val backend = FakeBackend()
        val fs = sync(backend, FakeEventSource(emptyList()), store)
        val arrived = mutableListOf<HeldActionId>()
        val job = launch(Dispatchers.Unconfined) { fs.arrivals.collect { if (it is Arrival.Held) arrived += it.action.id } }
        backend.held = listOf(heldAction("h1").copy(urgency = Urgency.URGENT))
        fs.apply(FeedEvent.HeldChanged(HeldActionId("h1")))
        fs.apply(FeedEvent.HeldChanged(HeldActionId("h1")))
        job.cancel()
        assertEquals(listOf(HeldActionId("h1")), arrived)
    }

    @Test fun reconnectNowRestartsALiveStreamAndResumesFromTheLastSeq() = runTest {
        val backend = FakeBackend(listOf(agentItem(1, "a")), pageSize = 10)
        val source = OpenSource()
        val fs = sync(backend, source, InMemoryFeedStore())
        val job = launch { fs.run() }
        runCurrent()
        assertEquals(listOf(1L), source.connects)
        assertEquals(SyncState.Live, fs.state.value)

        fs.reconnectNow()
        runCurrent()
        assertEquals(listOf(1L, 1L), source.connects, "a new connection, resuming after seq 1")
        assertEquals(SyncState.Live, fs.state.value)
        assertEquals(0, currentTime, "no backoff wait for a network change")
        job.cancel()
    }

    @Test fun reconnectNowCutsABackoffWaitShort() = runTest {
        var attempts = 0
        val gate = CompletableDeferred<Unit>()
        val source = object : FeedEventSource {
            override suspend fun connect(after: Long, onOpen: () -> Unit, onEvent: suspend (FeedEvent) -> Unit) {
                attempts++
                if (attempts < 3) throw IOException("down")
                onOpen()
                gate.await()
            }
        }
        val fs = sync(FakeBackend(), source, InMemoryFeedStore())
        val job = launch { fs.run() }
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(SyncState.Offline, fs.state.value)

        fs.reconnectNow()
        runCurrent()
        assertEquals(2, attempts, "retried without waiting out the 10 second backoff")
        assertEquals(0, currentTime)

        fs.reconnectNow()
        runCurrent()
        assertEquals(3, attempts)
        assertEquals(SyncState.Live, fs.state.value)
        job.cancel()
    }
}
