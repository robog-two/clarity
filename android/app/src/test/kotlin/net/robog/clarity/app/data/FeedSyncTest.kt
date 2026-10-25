package net.robog.clarity.app.data

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import net.robog.clarity.app.data.feed.Backoff
import net.robog.clarity.app.data.feed.FeedSync
import net.robog.clarity.app.data.feed.SyncSignal
import net.robog.clarity.app.data.feed.SyncState
import net.robog.clarity.app.data.feed.mergeItem
import net.robog.clarity.app.data.feed.withDelta
import net.robog.clarity.app.net.FeedStreamException
import net.robog.clarity.app.testutil.FakeBackend
import net.robog.clarity.app.testutil.FakeEventSource
import net.robog.clarity.app.testutil.FakeEventSource.Script
import net.robog.clarity.app.testutil.InMemoryFeedStore
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.agentItem
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedEvent
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.session.SessionId

class BackoffTest {
    @Test fun doublesUpToTheCap() {
        val b = Backoff(initial = 1.seconds, max = 10.seconds)
        assertEquals(listOf(1, 2, 4, 8, 10, 10), (0..5).map { b.delayFor(it).inWholeSeconds.toInt() })
    }

    @Test fun negativeAttemptIsTheInitialDelay() {
        assertEquals(1.seconds, Backoff().delayFor(-3))
    }
}

class FeedMergeTest {
    @Test fun deltaAppendsToLastTextCardAndMarksStreaming() {
        val item = agentItem(1, "Hello").withDelta(", world")
        assertEquals(listOf<Card>(Card.Text("Hello, world")), item.cards)
        assertTrue(item.streaming)
    }

    @Test fun deltaStartsATextCardAfterOtherCards() {
        val base = agentItem(1, "x").copy(cards = listOf(Card.Note("n")))
        assertEquals(listOf(Card.Note("n"), Card.Text("hi")), base.withDelta("hi").cards)
    }

    @Test fun mergeRules() {
        val done = agentItem(1, "done")
        val streaming = agentItem(1, "par", streaming = true)
        val replay = agentItem(1, "replay")
        assertEquals(replay, mergeItem(null, replay, final = false))
        assertNull(mergeItem(done, replay, final = false), "a replay never overwrites a finished item")
        assertEquals(replay, mergeItem(streaming, replay, final = false))
        assertEquals(replay, mergeItem(done, replay, final = true))
    }
}

class FeedSyncTest {
    private fun sync(
        backend: FakeBackend,
        source: FakeEventSource,
        store: InMemoryFeedStore,
        sleeps: MutableList<Duration> = mutableListOf(),
    ) = FeedSync(backend, source, store, Backoff(1.seconds, 8.seconds)) { sleeps += it }

    private suspend fun FeedSync.runUntilScriptEnds() {
        try { run() } catch (_: CancellationException) { /* the fake source ran out of scripts */ }
    }

    @Test fun fillsTheGapInPagesBeforeStreaming() = runTest {
        val store = InMemoryFeedStore()
        val backend = FakeBackend((1L..5L).map { agentItem(it, "m$it") }, pageSize = 2)
        val source = FakeEventSource(listOf(Script()))
        sync(backend, source, store).runUntilScriptEnds()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), store.snapshot.map { it.seq })
        assertEquals(listOf(0L, 2L, 4L), backend.feedCalls.take(3))
        assertEquals(listOf(5L), source.connectedAfter, "streams after the last filled seq")
    }

    @Test fun reconnectsWithBackoffAndResumesFromTheLastSeq() = runTest {
        val store = InMemoryFeedStore()
        val backend = FakeBackend(emptyList())
        val source = FakeEventSource(
            listOf(
                Script(listOf(FeedEvent.ItemAppended(agentItem(1, "a")), FeedEvent.ItemAppended(agentItem(2, "b"))), failWith = IOException("drop")),
                Script(listOf(FeedEvent.ItemAppended(agentItem(3, "c"))), failWith = IOException("drop again")),
                Script(open = false, failWith = IOException("down")),
                Script(),
            ),
        )
        val sleeps = mutableListOf<Duration>()
        sync(backend, source, store, sleeps).runUntilScriptEnds()
        assertEquals(listOf(0L, 2L, 3L, 3L), source.connectedAfter)
        assertEquals(listOf(1, 1, 2).map { it.seconds }, sleeps.take(3), "an open connection resets the backoff; failing to open grows it")
        assertEquals(listOf(1L, 2L, 3L), store.snapshot.map { it.seq })
    }

    @Test fun gapIsFilledAgainAfterEachReconnect() = runTest {
        val store = InMemoryFeedStore()
        val backend = FakeBackend(listOf(agentItem(1, "a")), pageSize = 10)
        val source = FakeEventSource(listOf(Script(failWith = IOException("drop")), Script()))
        val fs = sync(backend, source, store)
        // Items appear on the server while the stream is down.
        backend.all = listOf(agentItem(1, "a"), agentItem(2, "missed"), agentItem(3, "missed too"))
        fs.runUntilScriptEnds()
        assertEquals(listOf(1L, 2L, 3L), store.snapshot.map { it.seq })
        assertEquals(listOf(3L, 3L), source.connectedAfter)
    }

    @Test fun duplicatesFromTheGapAndTheStreamAreDeduplicatedBySeq() = runTest {
        val store = InMemoryFeedStore()
        val backend = FakeBackend(listOf(agentItem(1, "a"), agentItem(2, "b")), pageSize = 10)
        val source = FakeEventSource(
            listOf(Script(listOf(FeedEvent.ItemAppended(agentItem(2, "b")), FeedEvent.ItemAppended(agentItem(2, "b")), FeedEvent.ItemAppended(agentItem(3, "c"))))),
        )
        sync(backend, source, store).runUntilScriptEnds()
        assertEquals(listOf(1L, 2L, 3L), store.snapshot.map { it.seq })
        // seq 2 arrived through the page and the finished copy is never rewritten by the replays.
        assertEquals(1, store.writes.count { it == 2L })
    }

    @Test fun streamingDeltasAccumulateAndTheFinalItemReplacesThem() = runTest {
        val store = InMemoryFeedStore()
        val first = agentItem(1, "", streaming = true)
        val source = FakeEventSource(
            listOf(
                Script(
                    listOf(
                        FeedEvent.ItemAppended(first),
                        FeedEvent.TextDelta(first.id, "Morn"),
                        FeedEvent.TextDelta(first.id, "ing."),
                    ),
                ),
            ),
        )
        val fs = sync(FakeBackend(), source, store)
        fs.runUntilScriptEnds()
        assertEquals(listOf<Card>(Card.Text("Morning.")), store.snapshot.single().cards)
        assertTrue(store.snapshot.single().streaming)

        fs.apply(FeedEvent.ItemFinal(agentItem(1, "Morning. What’s on your mind?")))
        assertEquals(listOf<Card>(Card.Text("Morning. What’s on your mind?")), store.snapshot.single().cards)
        assertEquals(false, store.snapshot.single().streaming)
    }

    @Test fun aDeltaForAnUnknownItemIsDropped() = runTest {
        val store = InMemoryFeedStore()
        val fs = sync(FakeBackend(), FakeEventSource(emptyList()), store)
        fs.apply(FeedEvent.TextDelta(agentItem(9, "x").id, "lost"))
        assertTrue(store.snapshot.isEmpty())
    }

    @Test fun tasksHeldBusyAndSignals() = runTest {
        val store = InMemoryFeedStore()
        val fs = sync(FakeBackend(), FakeEventSource(emptyList()), store)
        val signals = mutableListOf<SyncSignal>()
        val job = launch(Dispatchers.Unconfined) { fs.signals.collect { signals += it } }
        fs.apply(FeedEvent.TaskChanged(AgentTask(TaskId("t"), "x", "y", AgentTask.Status.WORKING, null, T0)))
        fs.apply(FeedEvent.Busy(true))
        fs.apply(FeedEvent.SessionChanged(SessionId("s1")))
        fs.apply(FeedEvent.ConstitutionChanged(2))
        job.cancel()
        assertTrue(fs.agentBusy.value)
        assertEquals(listOf(SyncSignal.SessionChanged(SessionId("s1")), SyncSignal.ConstitutionChanged(2)), signals)
    }

    @Test fun stateReflectsRejectionAndMissingRoutes() = runTest {
        suspend fun stateAfter(fail: Throwable?, backendError: ApiError?): SyncState {
            val backend = FakeBackend().apply { failure = backendError }
            val source = FakeEventSource(listOf(Script(failWith = fail)))
            // Stop at the first pause: the state set by the failed attempt is what the screens would see.
            val fs = FeedSync(backend, source, InMemoryFeedStore(), Backoff()) { throw CancellationException("stop") }
            try { fs.run() } catch (_: CancellationException) { }
            return fs.state.value
        }
        assertEquals(SyncState.Unauthorized, stateAfter(null, ApiError.Unauthorized(null)))
        assertEquals(SyncState.Unavailable, stateAfter(null, ApiError.NotFound(null)))
        assertEquals(SyncState.Offline, stateAfter(null, ApiError.Unreachable("x")))
        assertEquals(SyncState.Unauthorized, stateAfter(FeedStreamException(401, "no"), null))
        assertEquals(SyncState.Unavailable, stateAfter(FeedStreamException(404, "none"), null))
    }
}
