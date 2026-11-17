package net.robog.clarity.app.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import net.robog.clarity.shared.feed.Card
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import net.robog.clarity.app.data.feed.Arrival
import net.robog.clarity.app.data.feed.FeedSync
import net.robog.clarity.app.data.feed.becameComplete
import net.robog.clarity.app.testutil.FakeBackend
import net.robog.clarity.app.testutil.FakeEventSource
import net.robog.clarity.app.testutil.FixedClock
import net.robog.clarity.app.testutil.InMemoryFeedStore
import net.robog.clarity.app.testutil.RecordingPushNotifier
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.agentItem
import net.robog.clarity.shared.feed.FeedEvent

/** An agent reply streamed into the feed: it notifies once, when it is complete, and never as a partial. */
class StreamedPushTest {
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
        fun listener() = PushListener(sync.arrivals, { Front.Background }, { true }, FixedClock(T0), notifier, ledger, sync.caughtUp, { store.notifiable() })
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

    private val partial = agentItem(7, "", streaming = true).copy(notify = "push_now")
    private val complete = agentItem(7, "Your flight moved to 9:40.").copy(notify = "push_now")

    @Test fun onlyTheFirstCompleteCopyCounts() {
        assertTrue(becameComplete(null, complete))
        assertTrue(becameComplete(partial, complete))
        assertFalse(becameComplete(null, partial))
        assertFalse(becameComplete(complete, complete))
        assertFalse(becameComplete(partial, partial))
    }

    @Test fun aStreamedReplyNotifiesOnceWhenItIsFinal() = runTest {
        val rig = Rig()
        val live = launch(Dispatchers.Unconfined) { rig.service.run() }
        rig.sync.apply(FeedEvent.ItemAppended(partial))
        rig.sync.apply(FeedEvent.TextDelta(partial.id, "Your flight "))
        rig.sync.apply(FeedEvent.TextDelta(partial.id, "moved"))
        assertTrue(rig.notifier.posted.isEmpty(), "no buzz for a half-written reply")

        rig.sync.apply(FeedEvent.ItemFinal(complete))
        rig.sync.apply(FeedEvent.ItemFinal(complete))
        rig.sync.apply(FeedEvent.ItemAppended(complete))
        live.cancel()
        assertEquals(listOf("Your flight moved to 9:40."), rig.notifier.posted.map { it.text })
    }

    @Test fun aTopicMarkerIsNeverANotification() = runTest {
        val rig = Rig()
        val marker = agentItem(8, "").copy(cards = emptyList(), topic = "Ulta Hair Appt")
        val live = launch(Dispatchers.Unconfined) { rig.service.run() }
        rig.sync.apply(FeedEvent.ItemAppended(marker))
        live.cancel()
        assertTrue(rig.notifier.posted.isEmpty())
        assertTrue(rig.store.notifiable().isEmpty(), "nor a missed one")
    }

    @Test fun aReplyThatArrivesFinalNotifiesOnce() = runTest {
        val rig = Rig()
        val live = launch(Dispatchers.Unconfined) { rig.service.run() }
        rig.sync.apply(FeedEvent.ItemFinal(complete))
        rig.sync.apply(FeedEvent.ItemAppended(complete))
        live.cancel()
        assertEquals(1, rig.notifier.posted.size)
    }

    @Test fun aPartialNeverNotifiesEvenFromTheGapFill() = runTest {
        val rig = Rig()
        val live = launch(Dispatchers.Unconfined) { rig.service.run() }
        rig.sync.apply(FeedEvent.ItemAppended(partial))
        rig.sync.apply(FeedEvent.TextDelta(partial.id, "Your fl"))
        // The server still has it streaming.
        rig.backend.all = listOf(partial.copy(cards = complete.cards))
        rig.sync.fillGap()
        live.cancel()
        assertTrue(rig.notifier.posted.isEmpty())
    }

    @Test fun aChoicesCardAnsweredElsewhereWhilePhoneWasAwayIsFetchedAgainAndNeverNotifies() = runTest {
        val rig = Rig()
        val options = listOf(net.robog.clarity.shared.feed.Choice("Starting", "Starting"), net.robog.clarity.shared.feed.Choice("Later", "Later"))
        val asked = agentItem(7, "unused").copy(notify = "push_now", cards = listOf(Card.Choices("Ready?", options)))
        rig.sync.apply(FeedEvent.ItemFinal(asked))
        rig.sync.apply(FeedEvent.ItemFinal(agentItem(8, "a later quiet note")))
        // the person tapped on another device; this phone was offline and never heard of it
        val answered = asked.copy(cards = listOf(Card.Choices("Ready?", options, chosen = "Later")))
        rig.backend.all = listOf(answered, agentItem(8, "a later quiet note"))
        rig.sync.fillGap()
        assertEquals("Later", (rig.store.itemAt(7)!!.cards.single() as Card.Choices).chosen)
        assertEquals(6L, rig.backend.feedCalls.first(), "resumed from just before the unanswered card")
        rig.backend.feedCalls.clear()
        rig.sync.fillGap()
        assertEquals(8L, rig.backend.feedCalls.first(), "answered, so no longer fetched again")
    }

    @Test fun anAnswerSignalReachesTheNotifier() = runTest {
        val rig = Rig()
        val options = listOf(net.robog.clarity.shared.feed.Choice("Later", "Later"))
        val asked = agentItem(7, "unused").copy(notify = "push_now", cards = listOf(Card.Choices("Ready?", options)))
        val answers = kotlinx.coroutines.flow.MutableSharedFlow<net.robog.clarity.shared.feed.FeedItemId>(extraBufferCapacity = 4)
        val listener = PushListener(rig.sync.arrivals, { Front.Background }, { true }, FixedClock(T0), rig.notifier, rig.ledger, answers = answers)
        val live = launch(Dispatchers.Unconfined) { listener.run() }
        answers.tryEmit(asked.id)
        live.cancel()
        assertEquals(listOf(asked.id), rig.notifier.answered)
    }

    @Test fun anAnswerOnAnotherDeviceReachesTheNotifierThroughTheSync() = runTest {
        val rig = Rig()
        val options = listOf(net.robog.clarity.shared.feed.Choice("Later", "Later"))
        val asked = agentItem(7, "unused").copy(notify = "push_now", cards = listOf(Card.Choices("Ready?", options)))
        val answers = rig.sync.signals.filterIsInstance<net.robog.clarity.app.data.feed.SyncSignal.ChoicesAnswered>().map { it.item }
        val listener = PushListener(rig.sync.arrivals, { Front.Background }, { true }, FixedClock(T0), rig.notifier, rig.ledger, answers = answers)
        val live = launch(Dispatchers.Unconfined) { listener.run() }
        rig.sync.apply(FeedEvent.ItemFinal(asked))
        rig.sync.apply(FeedEvent.ItemFinal(asked.copy(cards = listOf(Card.Choices("Ready?", options, chosen = "Later")))))
        live.cancel()
        assertEquals(listOf(asked.id), rig.notifier.answered)
    }

    @Test fun anAnsweredChoicesItemNeverNotifies() {
        val options = listOf(net.robog.clarity.shared.feed.Choice("Later", "Later"))
        val item = agentItem(7, "unused").copy(notify = "push_now", cards = listOf(Card.Choices("Ready?", options, chosen = "Later")))
        assertNull(net.robog.clarity.app.push.decideNotification(net.robog.clarity.app.data.feed.Arrival.Item(item), net.robog.clarity.app.push.Front.Background, canPost = true, now = T0 + 1.minutes))
        val open = item.copy(cards = listOf(Card.Choices("Ready?", options)))
        assertNotNull(net.robog.clarity.app.push.decideNotification(net.robog.clarity.app.data.feed.Arrival.Item(open), net.robog.clarity.app.push.Front.Background, canPost = true, now = T0 + 1.minutes))
    }

    @Test fun theCatchUpNotifiesAReplyItFirstSeesFinal() = runTest {
        val rig = Rig()
        rig.backend.all = listOf(complete)
        assertEquals(1, rig.fallback().run().posted)
        assertEquals(0, rig.fallback().run().posted)
        assertEquals(1, rig.notifier.posted.size)
    }

    @Test fun theCatchUpFinishesAReplyTheServiceOnlySawStreaming() = runTest {
        val rig = Rig()
        // The service saw the start of the reply, then was killed before the final version.
        rig.sync.apply(FeedEvent.ItemAppended(partial))
        rig.sync.apply(FeedEvent.ItemAppended(agentItem(8, "a later quiet note").copy(notify = "hold_until:2026-10-27T08:00:00Z")))
        rig.backend.all = listOf(complete, agentItem(8, "a later quiet note").copy(notify = "hold_until:2026-10-27T08:00:00Z"))

        assertEquals(1, rig.fallback().run().posted, "seq 7 is re-fetched although seq 8 is cached")
        assertEquals("Your flight moved to 9:40.", rig.notifier.posted.single().text)
        assertFalse(rig.store.snapshot.first().streaming)
        assertEquals(0, rig.fallback().run().posted)
        assertEquals(6L, rig.backend.feedCalls.first(), "resumed from just before the streaming item")
    }

    @Test fun theServiceAndTheCatchUpRacingStillPostOnce() = runTest {
        val rig = Rig()
        // The service is collecting the same arrivals the catch-up returns.
        val live = launch(Dispatchers.Unconfined) { rig.service.run() }
        rig.sync.apply(FeedEvent.ItemAppended(partial))
        rig.backend.all = listOf(complete, agentItem(9, "and one more").copy(notify = "push_now"))
        val outcome = rig.fallback(running = true).run()
        live.cancel()
        // Whichever path claims an arrival first posts it; the other skips it.
        assertTrue(outcome.posted in 0..2)
        assertEquals(listOf("Your flight moved to 9:40.", "and one more"), rig.notifier.posted.map { it.text })
    }
}
