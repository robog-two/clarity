package net.robog.clarity.app.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import net.robog.clarity.app.data.feed.Arrival
import net.robog.clarity.app.data.feed.FeedSync
import net.robog.clarity.app.data.feed.holdReleased
import net.robog.clarity.app.testutil.FakeBackend
import net.robog.clarity.app.testutil.FakeEventSource
import net.robog.clarity.app.testutil.FixedClock
import net.robog.clarity.app.testutil.InMemoryFeedStore
import net.robog.clarity.app.testutil.RecordingPushNotifier
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.agentItem
import net.robog.clarity.shared.feed.FeedEvent

/** An urgent message held for protected time: silent while held, one notification when the server releases it. */
class HeldReleasePushTest {
    private class Ledger : NotifiedLedger {
        var state = Notified()
        override suspend fun claim(arrival: Arrival): Boolean {
            state = state.claim(arrival, "s") ?: return false
            return true
        }
    }

    private class Rig {
        val backend = FakeBackend(pageSize = 10)
        val store = InMemoryFeedStore()
        val sync = FeedSync(backend, FakeEventSource(emptyList()), store)
        val ledger = Ledger()
        val notifier = RecordingPushNotifier()
        fun listener() = PushListener(sync.arrivals, { Front.Background }, { true }, FixedClock(T0 + 1.minutes), notifier, ledger, sync.caughtUp, { store.notifiable() })
        val service = listener()

        fun fallback(running: Boolean = false) = PushFallback(
            conditions = { PushConditions(hasServer = true, setUp = true, enabled = true) },
            serviceRunning = { running },
            feedLive = { false },
            catchUp = sync::catchUp,
            deliver = listener()::deliverMissed,
            reconnect = {},
            canStartService = { false },
            startService = {},
            work = object : PushFallbackWork {
                override fun schedule() = Unit
                override fun cancel() = Unit
            },
        )
    }

    private val held = agentItem(7, "Dana called twice about the contract.").copy(notify = "hold_until:2026-10-26T09:00:00Z")
    private val released = held.copy(notify = "released:$T0")

    @Test fun aReleaseCountsOnlyOverACachedHold() {
        assertTrue(holdReleased(held, released))
        assertFalse(holdReleased(null, released), "first sight is the ordinary arrival")
        assertFalse(holdReleased(released, released), "a resent release")
        assertFalse(holdReleased(held, held))
        assertFalse(holdReleased(held.copy(notify = "push_now"), released))
        assertFalse(holdReleased(held, released.copy(streaming = true)))
    }

    @Test fun theLedgerKeepsReleasesApartFromTheSeqTheHoldClaimed() {
        val first = Notified().claim(Arrival.Item(held), "s")
        assertNotNull(first)
        assertNull(first.claim(Arrival.Item(held), "s"))
        val second = first.claim(Arrival.Item(released), "s")
        assertNotNull(second, "the hold's claim does not block its release")
        assertNull(second.claim(Arrival.Item(released), "s"))
    }

    @Test fun heldThenReleasedNotifiesOnceAndAResentReleaseDoesNot() = runTest {
        val rig = Rig()
        val live = launch(Dispatchers.Unconfined) { rig.service.run() }
        rig.sync.apply(FeedEvent.ItemAppended(held))
        rig.sync.apply(FeedEvent.ItemFinal(held))
        assertTrue(rig.notifier.posted.isEmpty(), "silent while protected time lasts")

        rig.sync.apply(FeedEvent.ItemFinal(released))
        rig.sync.apply(FeedEvent.ItemFinal(released)) // resent on reconnect
        rig.sync.apply(FeedEvent.ItemAppended(released))
        live.cancel()
        assertEquals(listOf("Dana called twice about the contract."), rig.notifier.posted.map { it.text })
        assertEquals(PushChannel.Agent, rig.notifier.posted.single().channel)
    }

    @Test fun theCatchUpFindsAReleaseTheServiceMissed() = runTest {
        val rig = Rig()
        // The service saw the hold and a later item, then was killed before the release.
        rig.sync.apply(FeedEvent.ItemAppended(held))
        rig.sync.apply(FeedEvent.ItemAppended(agentItem(8, "later").copy(notify = "in_app")))
        // ...and claimed both, as the running service does.
        rig.ledger.claim(Arrival.Item(held))
        rig.ledger.claim(Arrival.Item(agentItem(8, "later").copy(notify = "in_app")))
        rig.backend.all = listOf(released, agentItem(8, "later").copy(notify = "in_app"))

        assertEquals(1, rig.fallback().run().posted, "seq 7 is re-fetched although seq 8 is cached")
        assertEquals("Dana called twice about the contract.", rig.notifier.posted.single().text)
        assertEquals(6L, rig.backend.feedCalls.first(), "resumed from just before the held item")
        assertEquals(0, rig.fallback().run().posted)
        assertEquals(1, rig.notifier.posted.size)
    }

    @Test fun theServiceAndTheCatchUpRacingOverAReleaseStillPostOnce() = runTest {
        val rig = Rig()
        val live = launch(Dispatchers.Unconfined) { rig.service.run() }
        rig.sync.apply(FeedEvent.ItemAppended(held))
        rig.backend.all = listOf(released)
        rig.fallback(running = true).run()
        rig.sync.apply(FeedEvent.ItemFinal(released))
        live.cancel()
        assertEquals(1, rig.notifier.posted.size)
    }
}
