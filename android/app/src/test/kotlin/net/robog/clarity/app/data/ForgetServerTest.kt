@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package net.robog.clarity.app.data

import android.app.NotificationManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.robog.clarity.app.call.AlarmSlot
import net.robog.clarity.app.call.CallScheduler
import net.robog.clarity.app.call.DataStoreCallSettingsCache
import net.robog.clarity.app.connector.ConnectorWork
import net.robog.clarity.app.connector.DataStoreDndStateStore
import net.robog.clarity.app.connector.DataStoreSmsCursorStore
import net.robog.clarity.app.connector.DndActionHandler
import net.robog.clarity.app.connector.DndAlarms
import net.robog.clarity.app.connector.DndController
import net.robog.clarity.app.connector.DndState
import net.robog.clarity.app.connector.SmsAnchor
import net.robog.clarity.app.data.feed.Arrival
import net.robog.clarity.app.data.feed.FeedSync
import net.robog.clarity.app.nav.RootState
import net.robog.clarity.app.nav.RootViewModel
import net.robog.clarity.app.push.DataStoreNotifiedLedger
import net.robog.clarity.app.push.DataStorePushPrefs
import net.robog.clarity.app.push.PushFallbackWork
import net.robog.clarity.app.push.pushConditions
import net.robog.clarity.app.testutil.FakeAlarms
import net.robog.clarity.app.testutil.FakeBackend
import net.robog.clarity.app.testutil.FakeDeviceKey
import net.robog.clarity.app.testutil.FakeEventSource
import net.robog.clarity.app.testutil.FakeVaultKeys
import net.robog.clarity.app.testutil.FixedClock
import net.robog.clarity.app.testutil.InMemoryFeedStore
import net.robog.clarity.app.testutil.RecordingPushNotifier
import net.robog.clarity.app.testutil.ScreenSettings
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.agentItem
import net.robog.clarity.app.testutil.signedIn
import net.robog.clarity.app.ui.onboarding.ModelKeyOutcome
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.session.SetupStatus

/** "Forget this server" lets go of everything kept for it, so pairing again starts from nothing. */
class ForgetServerTest {
    private class Work : PushFallbackWork, ConnectorWork {
        val calls = mutableListOf<String>()
        override fun schedule() { calls += "schedule" }
        override fun cancel() { calls += "cancel" }
    }

    private class Dnd : DndController {
        var filter = NotificationManager.INTERRUPTION_FILTER_ALARMS
        override fun hasAccess() = true
        override fun currentFilter() = filter
        override fun enable(allowStarred: Boolean) { filter = NotificationManager.INTERRUPTION_FILTER_ALARMS }
        override fun restore(filter: Int) { this.filter = filter }
    }

    private class DndEnd : DndAlarms {
        var cancelled = 0
        override fun scheduleEnd(at: Instant) = Unit
        override fun cancelEnd() { cancelled++ }
    }

    private class Setup(val status: SetupStatus?) : SetupRepository {
        override suspend fun status() = status
        override suspend fun submitModelKey(apiKey: String) = ModelKeyOutcome.Works
    }

    /** Every store on one DataStore file, as in the app. */
    private class Rig(val scope: CoroutineScope, dir: File) {
        val file = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "clarity.preferences_pb") }
        val sessionStore = DataStoreSessionStore(file)
        val flag = DataStoreOnboardingFlagStore(file)
        val clock = FixedClock(T0)
        val ledger = DataStoreNotifiedLedger(file, sessionStore, clock)
        val cursor = DataStoreSmsCursorStore(file)
        val callCache = DataStoreCallSettingsCache(file)
        val alarms = FakeAlarms()
        val scheduler = CallScheduler(ScreenSettings(net.robog.clarity.app.data.ApiResult.Success(Settings(homeZone = "UTC"))), callCache, alarms, clock)
        val dndState = DataStoreDndStateStore(file)
        val dndController = Dnd()
        val dndEnd = DndEnd()
        val dnd = DndActionHandler(dndController, dndState, dndEnd, clock)
        val prefs = DataStorePushPrefs(file)
        val cache = InMemoryFeedStore()
        val sync = FeedSync(FakeBackend(), FakeEventSource(emptyList()), cache)
        val pushWork = Work()
        val connectorWork = Work()
        val notifier = RecordingPushNotifier()
        val vault = FakeVaultKeys()
        val sessions = DefaultSessionRepository(
            sessionStore, FakeDeviceKey(), cache,
            perServerState(sync, pushWork, connectorWork, scheduler, dnd, notifier, flag, ledger, cursor, vault),
        )

        /** A phone paired and set up, with something of everything. */
        suspend fun pairedAndUsed() {
            sessionStore.save(signedIn())
            flag.markDone()
            ledger.claim(Arrival.Item(agentItem(7, "notified")))
            cursor.advance(1819, T0, SmsAnchor(1, "abc"))
            callCache.save(Settings(homeZone = "UTC"))
            AlarmSlot.entries.forEach { alarms.set(it, T0 + 1.hours) }
            dndState.save(DndState(T0 + 2.hours, NotificationManager.INTERRUPTION_FILTER_ALL))
            prefs.setEnabled(false)
            prefs.markBatteryAsked()
            cache.upsertItem(agentItem(7, "notified"))
        }
    }

    private fun withRig(block: suspend (Rig) -> Unit) = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "forget-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(Rig(scope, dir))
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test fun forgettingClearsEveryPerServerStore() = withRig { r ->
        r.pairedAndUsed()
        r.sessions.forget()

        assertNull(r.sessionStore.session.first())
        assertFalse(r.flag.done.first(), "onboarding is not done any more")
        assertTrue(r.flag.redo.first(), "and the next pairing walks through it")
        assertNull(r.ledger.since())
        assertTrue(r.ledger.claim(Arrival.Item(agentItem(7, "notified"))), "the old server's claims are gone")
        assertEquals(0, r.cursor.lastId())
        assertNull(r.cursor.anchor())
        assertNull(r.cursor.lastSyncAt.first(), "\"last synced\" starts over")
        assertNull(r.callCache.load(), "a reboot cannot re-arm the old schedule")
        assertTrue(r.alarms.set.isEmpty(), "no call, heads-up or snooze")
        assertNull(r.dndState.load())
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, r.dndController.filter, "the person's own setting is back")
        assertEquals(1, r.dndEnd.cancelled)
        assertEquals(listOf("cancel"), r.pushWork.calls)
        assertEquals(listOf("cancel"), r.connectorWork.calls)
        assertEquals(1, r.notifier.clearedAll)
        assertEquals(1, r.vault.deletes)
        assertTrue(r.cache.snapshot.isEmpty())
        // Choices about this phone stay.
        assertFalse(r.prefs.enabled.first())
        assertTrue(r.prefs.batteryAsked.first())
    }

    @Test fun nothingRunsForTheForgottenServerAndRePairingStartsSetupAgain() {
        withRig { r ->
            r.pairedAndUsed()
            r.prefs.setEnabled(true)
            assertTrue(pushConditions(r.sessionStore, r.flag, r.prefs).first().shouldRun)
            r.sessions.forget()
            assertFalse(pushConditions(r.sessionStore, r.flag, r.prefs).first().shouldRun)

            // Paired again, with a server whose setup is complete: still walked through setup on this phone.
            r.sessionStore.save(signedIn("https://clarity.example"))
            assertFalse(pushConditions(r.sessionStore, r.flag, r.prefs).first().shouldRun, "not before onboarding finishes")
            val main = UnconfinedTestDispatcher()
            Dispatchers.setMain(main)
            try {
                runTest(main) {
                    val complete = SetupStatus(SetupStatus.KeyState.VALID, 2, 1, Settings())
                    val vm = net.robog.clarity.app.nav.RootViewModel(r.sessions, r.flag, Setup(complete))
                    backgroundScope.launch { vm.state.collect {} }
                    advanceUntilIdle()
                    assertEquals(RootState.Onboarding, vm.state.value)
                    assertFalse(r.flag.done.first(), "not marked done from the server's status")
                    r.flag.markDone() // the last onboarding screen
                    advanceUntilIdle()
                    assertEquals(RootState.Ready, vm.state.value)
                }
            } finally {
                Dispatchers.resetMain()
            }
            assertTrue(pushConditions(r.sessionStore, r.flag, r.prefs).first().shouldRun)
        }
    }

    @Test fun oneFailingPieceDoesNotKeepTheOthers() = runBlocking {
        val done = mutableListOf<String>()
        val failed = forgetAll(
            listOf(
                ServerScopedState("a") { done += "a" },
                ServerScopedState("b") { error("keystore says no") },
                ServerScopedState("c") { done += "c" },
            ),
        )
        assertEquals(listOf("b"), failed)
        assertEquals(listOf("a", "c"), done)
    }

    @Test fun clearingTheSessionLeavesTheRestOfTheFileAlone() = withRig { r ->
        r.pairedAndUsed()
        r.sessionStore.clear()
        assertNull(r.sessionStore.session.first())
        assertTrue(r.flag.done.first(), "only forgetting the server clears the rest, on purpose")
        assertEquals(DndState(T0 + 2.hours, NotificationManager.INTERRUPTION_FILTER_ALL), r.dndState.load())
    }
}
