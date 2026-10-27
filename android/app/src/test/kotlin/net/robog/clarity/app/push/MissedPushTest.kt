@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package net.robog.clarity.app.push

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.robog.clarity.app.data.Session
import net.robog.clarity.app.data.feed.Arrival
import net.robog.clarity.app.data.feed.FeedSync
import net.robog.clarity.app.nav.DeepLink
import net.robog.clarity.app.nav.MainTab
import net.robog.clarity.app.testutil.FakeBackend
import net.robog.clarity.app.testutil.FakeEventSource
import net.robog.clarity.app.testutil.FakeSessionStore
import net.robog.clarity.app.testutil.FixedClock
import net.robog.clarity.app.testutil.InMemoryFeedStore
import net.robog.clarity.app.testutil.RecordingPushNotifier
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.agentItem
import net.robog.clarity.app.testutil.heldAction
import net.robog.clarity.app.testutil.signedIn
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.feed.FeedEvent
import net.robog.clarity.shared.governance.Urgency

/** The ledger in memory, with the DataStore one's rules, including when it began. */
private class Ledger(private val began: Instant? = null) : NotifiedLedger {
    var state = Notified()
    override suspend fun claim(arrival: Arrival): Boolean {
        state = state.claim(arrival, "s") ?: return false
        return true
    }
    override suspend fun since(): Instant? = began
}

private fun loud(seq: Long, text: String = "missed $seq", at: Instant = T0) =
    agentItem(seq, text).copy(notify = "push_now", at = at)

private fun urgent(id: String, at: Instant = T0) = heldAction(id, createdAt = at).copy(urgency = Urgency.URGENT)

class DecideMissedTest {
    private val now = T0 + 1.minutes

    @Test fun aFewMissedPostOnTheirOwnAndManyBecomeOneSummary() {
        val three = (1L..3L).map { Arrival.Item(loud(it)) }
        assertEquals(3, decideMissed(three, Front.Background, true, now, since = null).size)

        val many = (1L..5L).map { Arrival.Item(loud(it)) } + Arrival.Held(urgent("h1"))
        val summary = decideMissed(many, Front.Background, true, now, since = null).single()
        assertEquals(SUMMARY_ID, summary.id)
        assertEquals("While you were away", summary.title)
        assertEquals(PushChannel.Urgent, summary.channel, "the most urgent of what it covers")
        assertTrue(summary.text.startsWith("5 messages, 1 thing needs your ok."), summary.text)
        assertEquals(DeepLink.Today, summary.link)
    }

    @Test fun onlyApprovalsLeadToTheHeldList() {
        val held = (1..4).map { Arrival.Held(urgent("h$it")) }
        val summary = decideMissed(held, Front.Background, true, now, since = null).single()
        assertEquals(DeepLink.Held, summary.link)
        assertTrue(summary.text.startsWith("4 things need your ok."))
    }

    @Test fun missedThingsNotifyUpToADayOldNotOlder() {
        val now = T0 + 30.hours
        val yesterdayEvening = Arrival.Item(loud(1, at = T0 + 7.hours)) // 23 hours old: past the live 2 hours
        val dayAndAHalf = Arrival.Item(loud(2, at = T0))
        assertEquals(listOf("missed 1"), decideMissed(listOf(yesterdayEvening, dayAndAHalf), Front.Background, true, now, null).map { it.text })
        assertNull(decideNotification(yesterdayEvening, Front.Background, true, now), "live arrivals keep the 2 hour limit")
    }

    @Test fun historyFromBeforeTheLedgerBeganIsNotMissed() {
        val since = T0 + 1.hours
        val before = Arrival.Item(loud(1, at = T0))
        val after = Arrival.Item(loud(2, at = T0 + 2.hours))
        val plans = decideMissed(listOf(before, after), Front.Background, true, T0 + 3.hours, since)
        assertEquals(listOf("missed 2"), plans.map { it.text })
    }

    @Test fun holdsWaitAndAReleaseCountsFromTheRelease() {
        val stillHeld = Arrival.Item(agentItem(1, "quiet").copy(notify = "hold_until:2026-10-27T08:00:00Z", at = T0))
        val released = Arrival.Item(agentItem(2, "released now").copy(notify = "released:${T0 + 26.hours}", at = T0))
        val now = T0 + 27.hours
        assertEquals(listOf("released now"), decideMissed(listOf(stillHeld, released), Front.Background, true, now, since = T0 + 1.hours).map { it.text })
    }

    @Test fun nothingWhenNotificationsAreOffOrThePersonIsLooking() {
        val items = (1L..5L).map { Arrival.Item(loud(it)) }
        assertTrue(decideMissed(items, Front.Background, false, now, null).isEmpty())
        assertTrue(decideMissed(items, Front.Showing(Place.Tab(MainTab.Today)), true, now, null).isEmpty())
    }

    @Test fun whatIsOnScreenCountsAsSeen() {
        val items = listOf(loud(1), agentItem(2, "", streaming = true))
        val held = listOf(urgent("h1"), urgent("h2"))
        val today = seenArrivals(Front.Showing(Place.Tab(MainTab.Today)), items, held)
        assertEquals(listOf<Arrival>(Arrival.Item(loud(1))), today, "a reply still streaming is not read yet")
        assertEquals(2, seenArrivals(Front.Showing(Place.Tab(MainTab.Held)), items, held).size)
        assertEquals(listOf<Arrival>(Arrival.Held(held[1])), seenArrivals(Front.Showing(Place.HeldItem(held[1].id)), items, held))
        assertTrue(seenArrivals(Front.Showing(Place.Tab(MainTab.Settings)), items, held).isEmpty())
        assertTrue(seenArrivals(Front.Background, items, held).isEmpty())
    }
}

class MissedPushTest {
    private class Rig(began: Instant? = null) {
        val backend = FakeBackend(pageSize = 10)
        val store = InMemoryFeedStore()
        val sync = FeedSync(backend, FakeEventSource(emptyList()), store)
        val ledger = Ledger(began)
        val notifier = RecordingPushNotifier()
        var front: Front = Front.Background
        val clock = FixedClock(T0 + 5.minutes)
        fun listener() = PushListener(sync.arrivals, { front }, { true }, clock, notifier, ledger, sync.caughtUp, { store.notifiable() })
        fun fallback() = PushFallback(
            conditions = { PushConditions(hasServer = true, setUp = true, enabled = true) },
            serviceRunning = { false },
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

    @Test fun whatTheAppCachedWhileTheServiceWasDeadStillNotifies() = runTest {
        val rig = Rig()
        // The app was open on Settings with the service dead: the items reached the cache, nobody claimed them.
        rig.front = Front.Showing(Place.Tab(MainTab.Settings))
        rig.sync.apply(FeedEvent.ItemFinal(loud(1, "your flight moved")))
        rig.sync.apply(FeedEvent.ItemFinal(agentItem(2, "a quiet note").copy(notify = "in_app")))
        rig.front = Front.Background

        // The server has nothing newer, so the catch-up itself brings nothing; the cache does.
        rig.backend.all = listOf(loud(1, "your flight moved"), agentItem(2, "a quiet note").copy(notify = "in_app"))
        assertEquals(2, rig.fallback().run().posted)
        assertEquals(listOf("your flight moved", "a quiet note"), rig.notifier.posted.map { it.text })
        assertEquals(0, rig.fallback().run().posted, "once")
    }

    @Test fun whatThePersonSawOnScreenIsNotNotifiedLater() = runTest {
        val rig = Rig()
        val front = MutableStateFlow<Front>(Front.Showing(Place.Tab(MainTab.Today)))
        val seen = SeenOnScreen(front, rig.store, rig.ledger)
        val tracking = launch { seen.track() }
        runCurrent()
        rig.sync.apply(FeedEvent.ItemFinal(loud(1, "read on Today")))
        rig.store.replaceHeld(listOf(urgent("h1")))
        runCurrent()
        front.value = Front.Showing(Place.Tab(MainTab.Held))
        runCurrent()
        front.value = Front.Showing(Place.Tab(MainTab.Settings))
        runCurrent()
        rig.sync.apply(FeedEvent.ItemFinal(loud(2, "arrived while on Settings")))
        runCurrent()
        tracking.cancel()

        rig.backend.all = listOf(loud(1, "read on Today"), loud(2, "arrived while on Settings"))
        rig.fallback().run()
        assertEquals(listOf("arrived while on Settings"), rig.notifier.posted.map { it.text })
    }

    @Test fun theServiceComingBackPostsWhatItMissed() = runTest {
        val rig = Rig()
        rig.sync.apply(FeedEvent.ItemFinal(loud(1, "while the service was dead")))
        val service = launch(Dispatchers.Unconfined) { rig.listener().run() }
        assertEquals(listOf("while the service was dead"), rig.notifier.posted.map { it.text })
        // And live arrivals still post one by one.
        rig.sync.apply(FeedEvent.ItemFinal(loud(2, "live")))
        service.cancel()
        assertEquals(2, rig.notifier.posted.size)
    }

    @Test fun aLongGapOnReconnectBecomesOneSummary() = runTest {
        val backend = FakeBackend((1L..6L).map { loud(it) }, pageSize = 10)
        val store = InMemoryFeedStore()
        // One connection: the gap fill brings six items, then the stream opens and ends.
        val sync = FeedSync(backend, FakeEventSource(listOf(FakeEventSource.Script())), store)
        val notifier = RecordingPushNotifier()
        val listener = PushListener(sync.arrivals, { Front.Background }, { true }, FixedClock(T0 + 5.minutes), notifier, Ledger(), sync.caughtUp, { store.notifiable() })
        val service = launch(Dispatchers.Unconfined) { listener.run() }
        val running = launch { runCatching { sync.run() } }
        runCurrent()
        running.cancel()
        service.cancel()
        val plan = notifier.posted.single()
        assertEquals(SUMMARY_ID, plan.id)
        assertTrue(plan.text.startsWith("6 messages."), plan.text)
    }

    @Test fun aFreshPairingDoesNotNotifyTheFeedsHistory() = runTest {
        // The ledger began just now (this phone paired): the history the first gap fill brings is not "missed".
        val rig = Rig(began = T0 + 4.minutes)
        rig.backend.all = (1L..5L).map { loud(it, at = T0) } + loud(6, "new since pairing", at = T0 + 4.minutes)
        rig.fallback().run()
        assertEquals(listOf("new since pairing"), rig.notifier.posted.map { it.text })
    }
}

class LedgerSinceTest {
    @Test fun aNewScopeRecordsWhenItBegan() {
        val n = Notified().claim(Arrival.Item(loud(1)), "a", now = 100)!!
        assertEquals(100, n.since)
        assertEquals(100, n.claim(Arrival.Item(loud(2)), "a", now = 200)!!.since, "the same server keeps it")
        assertEquals(300, n.claim(Arrival.Item(loud(2)), "b", now = 300)!!.since, "another server starts over")
    }

    @Test fun claimAllReturnsOnlyTheNewOnes() {
        val (state, fresh) = Notified().claim(Arrival.Item(loud(2)), "s")!!.claimAll(
            listOf(Arrival.Item(loud(1)), Arrival.Item(loud(2)), Arrival.Held(urgent("h1")), Arrival.Item(loud(1))), "s",
        )
        assertEquals(listOf<Arrival>(Arrival.Item(loud(1)), Arrival.Held(urgent("h1"))), fresh)
        assertEquals(listOf(1L, 2L), state.seqs)
    }

    @Test fun theDataStoreLedgerKeepsSinceAndResetForgetsEverything() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "ledger-since-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "t.preferences_pb") }
            val sessions = FakeSessionStore(signedIn())
            val clock = FixedClock(T0)
            val ledger = DataStoreNotifiedLedger(store, sessions, clock)
            assertNull(ledger.since())
            assertEquals(2, ledger.claimAll(listOf(Arrival.Item(loud(1)), Arrival.Held(urgent("h1")))).size)
            assertEquals(T0, ledger.since())
            assertTrue(ledger.claimAll(listOf(Arrival.Item(loud(1)))).isEmpty())

            ledger.reset()
            assertNull(ledger.since())
            clock.now = T0 + 1.hours
            assertTrue(ledger.claim(Arrival.Item(loud(1))), "after a reset nothing is known")
            assertEquals(T0 + 1.hours, ledger.since())

            // Paired with another server: its ledger begins when it first claims.
            sessions.state.value = Session("https://other.example", DeviceId("dev-2"))
            assertNull(ledger.since())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
