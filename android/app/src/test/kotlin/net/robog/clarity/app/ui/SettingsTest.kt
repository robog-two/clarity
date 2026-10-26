package net.robog.clarity.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalTime
import net.robog.clarity.app.call.AlarmSlot
import net.robog.clarity.app.call.CallScheduler
import net.robog.clarity.app.connector.PhoneConnector
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.ConnectorsRepository
import net.robog.clarity.app.data.ConstitutionRepository
import net.robog.clarity.app.data.OnboardingFlagStore
import net.robog.clarity.app.data.SaveConnectorOutcome
import net.robog.clarity.app.data.SessionRepository
import net.robog.clarity.app.testutil.FakeAlarms
import net.robog.clarity.app.testutil.FakeCapabilities
import net.robog.clarity.app.testutil.FakeSessionStore
import net.robog.clarity.app.testutil.FixedClock
import net.robog.clarity.app.testutil.MemorySettingsCache
import net.robog.clarity.app.testutil.ScreenSettings
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.signedIn
import net.robog.clarity.app.testutil.withMain
import net.robog.clarity.app.ui.settings.SettingsViewModel
import net.robog.clarity.app.ui.settings.callTimeSummary
import net.robog.clarity.app.ui.settings.daysSummary
import net.robog.clarity.app.ui.settings.relativeTime
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.connector.ConnectorSummary
import net.robog.clarity.shared.connector.ConnectorTestResult
import net.robog.clarity.shared.connector.GrantState
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.noun.Noun
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.session.Settings.MorningCall

class SettingsRulesTest {
    @Test fun relativeTimeWords() {
        val now = T0 + 10.days
        assertEquals("never", relativeTime(null, now))
        assertEquals("just now", relativeTime(now - 30.seconds, now))
        assertEquals("2 min ago", relativeTime(now - 2.minutes, now))
        assertEquals("1 hr ago", relativeTime(now - 1.hours, now))
        assertEquals("yesterday", relativeTime(now - 1.days, now))
        assertEquals("3 days ago", relativeTime(now - 3.days, now))
    }

    @Test fun daysAreSummarisedLikeTheirNames() {
        assertEquals("weekdays", daysSummary(setOf(1, 2, 3, 4, 5)))
        assertEquals("every day", daysSummary((1..7).toSet()))
        assertEquals("weekends", daysSummary(setOf(6, 7)))
        assertEquals("Mon, Wed", daysSummary(setOf(3, 1)))
        assertEquals("no days", daysSummary(emptySet()))
        assertEquals("9:00 am · weekdays", callTimeSummary(MorningCall()))
    }
}

private class ListedConnectors(var result: ApiResult<List<ConnectorSummary>>) : ConnectorsRepository {
    override suspend fun list() = result
    override suspend fun storedConfig(id: ConnectorId) = null
    override suspend fun save(connector: ConnectorSummary, values: net.robog.clarity.app.connector.FormValues): SaveConnectorOutcome = SaveConnectorOutcome.Saved
    override suspend fun test(id: ConnectorId): ApiResult<ConnectorTestResult> = ApiResult.Success(ConnectorTestResult(true))
}

private class FakeConstitutionRepo(val result: ApiResult<Constitution>) : ConstitutionRepository {
    override suspend fun current() = result
}

private class FakePhoneConnector : PhoneConnector {
    var synced = 0
    val last = MutableStateFlow<Instant?>(null)
    override suspend fun syncOnce(): Int { synced++; return 0 }
    override suspend fun actionPass(waitSeconds: Int) = 0
    override suspend fun runPass() = Unit
    override suspend fun runActionLoop() = Unit
    override fun schedule() = Unit
    override val lastSyncAt: Flow<Instant?> = last
}

private class RecordingFlag : OnboardingFlagStore {
    val doneState = MutableStateFlow(true)
    val redoState = MutableStateFlow(false)
    override val done: Flow<Boolean> = doneState
    override val redo: Flow<Boolean> = redoState
    override suspend fun markDone() { doneState.value = true; redoState.value = false }
    override suspend fun startRedo() { redoState.value = true }
}

private fun connector(id: String, kind: String, seen: Instant?) = ConnectorSummary(
    ConnectorId(id),
    ConnectorManifest(kind, kind.uppercase(), "1", setOf(Noun.MESSAGE)),
    mapOf(Noun.MESSAGE to GrantState.GRANTED),
    seen,
)

private class SettingsRig(
    loaded: ApiResult<Settings> = ApiResult.Success(Settings(homeZone = "UTC")),
    connectors: ApiResult<List<ConnectorSummary>> = ApiResult.Success(emptyList()),
) {
    val clock = FixedClock(T0 + 1.hours)
    val repo = ScreenSettings(loaded)
    val alarms = FakeAlarms()
    val scheduler = CallScheduler(repo, MemorySettingsCache(), alarms, clock)
    val sessionStore = FakeSessionStore(signedIn("https://clarity.example"))
    val sessions = object : SessionRepository {
        override val session = sessionStore.session
        override suspend fun forget() = sessionStore.clear()
    }
    val caps = FakeCapabilities()
    val phone = FakePhoneConnector()
    val flag = RecordingFlag()
    val push = net.robog.clarity.app.testutil.FakePushController()
    val vm = SettingsViewModel(
        sessions, repo, ListedConnectors(connectors), FakeConstitutionRepo(ApiResult.Failure(ApiError.NotFound(null))),
        scheduler, caps, phone, flag, clock, push,
    )
    val state get() = vm.state.value
}

class SettingsViewModelTest {
    @Test fun loadsSettingsAndShowsTheServer() = withMain {
        val r = SettingsRig()
        assertFalse(r.state.loading)
        assertEquals(Settings(homeZone = "UTC"), r.state.settings)
        assertEquals("https://clarity.example", r.state.instanceUrl)
    }

    @Test fun changingTheTimeSavesAndRearmsTheCallAlarm() = withMain {
        val r = SettingsRig()
        r.vm.setCallTime(LocalTime(8, 30))
        assertEquals(LocalTime(8, 30), r.repo.saved.last().morningCall.at)
        // 2026-10-26 09:00Z + 1h = 10:00; weekday call at 8:30 is tomorrow.
        assertEquals(Instant.parse("2026-10-27T08:30:00Z"), r.alarms.set[AlarmSlot.Call])
    }

    @Test fun roundTripOfEveryToggle() = withMain {
        val r = SettingsRig()
        r.vm.toggleHeadsUp(); assertNull(r.state.settings!!.morningCall.headsUpMinutes)
        r.vm.toggleHeadsUp(); assertEquals(10, r.state.settings!!.morningCall.headsUpMinutes)
        r.vm.toggleMode(); assertEquals(MorningCall.Mode.NOTIFY, r.state.settings!!.morningCall.mode)
        r.vm.toggleMic(); assertFalse(r.state.settings!!.micAlwaysOn)
        r.vm.toggleBatch(); assertFalse(r.state.settings!!.batchLowUrgency)
        r.vm.togglePushUrgent(); assertFalse(r.state.settings!!.pushUrgent)
        r.vm.toggleDay(1); assertFalse(1 in r.state.settings!!.morningCall.days)
        r.vm.toggleDay(7); assertTrue(7 in r.state.settings!!.morningCall.days)
        r.vm.setEveningAt(LocalTime(19, 0)); assertEquals(LocalTime(19, 0), r.state.settings!!.eveningSummaryAt)
        assertEquals(r.state.settings, r.repo.saved.last(), "the last save carries every change")
        assertEquals(9, r.repo.saved.size)
    }

    @Test fun switchingTheCallToNotifyKeepsTheAlarms() = withMain {
        val r = SettingsRig()
        r.vm.toggleMode()
        assertNotNull(r.alarms.set[AlarmSlot.Call])
    }

    @Test fun anOlderServerKeepsChangesOnThePhoneOnly() = withMain {
        val r = SettingsRig()
        r.repo.saveResult = ApiResult.Failure(ApiError.NotFound(null))
        r.vm.toggleMic()
        assertTrue(r.state.localOnly)
        assertNull(r.state.message)
        assertNotNull(r.alarms.set[AlarmSlot.Call], "alarms still follow")
    }

    @Test fun otherSaveErrorsShowAMessage() = withMain {
        val r = SettingsRig()
        r.repo.saveResult = ApiResult.Failure(ApiError.Unreachable("x"))
        r.vm.toggleMic()
        assertNotNull(r.state.message)
        assertFalse(r.state.localOnly)
    }

    @Test fun missingSettingsRouteStillShowsDefaults() = withMain {
        val r = SettingsRig(loaded = ApiResult.Failure(ApiError.NotFound(null)))
        assertNotNull(r.state.settings)
        assertTrue(r.state.localOnly)
    }

    @Test fun serverSourcesListConnectorsButNotThePhone() = withMain {
        val r = SettingsRig(
            connectors = ApiResult.Success(
                listOf(
                    connector("mail-home", "mail", T0 + 1.hours - 2.minutes),
                    connector("device-dev-1", "phone", null),
                    connector("dav-home", "dav", null),
                ),
            ),
        )
        assertEquals(listOf("MAIL", "DAV"), r.state.serverSources.map { it.title })
        assertEquals("2 min ago", r.state.serverSources.first().sync)
        assertEquals("never", r.state.serverSources.last().sync)
    }

    @Test fun serverSourcesOpenTheConnectorAndShowOnesThatAreNotRunning() = withMain {
        val r = SettingsRig(
            connectors = ApiResult.Success(
                listOf(
                    connector("mail", "mail", null),
                    ConnectorSummary(ConnectorId("dav"), null, emptyMap(), null),
                ),
            ),
        )
        assertEquals(listOf("mail", "dav"), r.state.serverSources.map { it.id })
        assertEquals("not running", r.state.serverSources.last().sync)
        assertEquals("not running on your server", r.state.serverSources.last().sub)
    }

    @Test fun phoneSourcesReflectPermissionsAndLastSync() = withMain {
        val r = SettingsRig()
        r.caps.sms = false; r.caps.dnd = false
        r.vm.refreshDevice()
        assertFalse(r.state.smsGranted)
        assertFalse(r.state.dndGranted)
        r.caps.sms = true
        r.vm.onSmsGranted()
        assertTrue(r.state.smsGranted)
        assertEquals(1, r.phone.synced, "granting messages starts the sync")
        r.phone.last.value = T0 + 1.hours - 5.minutes
        assertEquals("5 min ago", r.state.smsLastSync)
    }

    @Test fun warnsWhenExactAlarmsOrNotificationsAreOff() = withMain {
        val r = SettingsRig()
        r.alarms.exact = false
        r.caps.notifications = false
        r.caps.fullScreen = false
        r.vm.refreshDevice()
        assertTrue(r.state.needsExactAlarms)
        assertFalse(r.state.notificationsOn)
        assertFalse(r.state.fullScreenOk)
    }

    @Test fun forgetClearsTheSessionAndStopsPush() = withMain {
        val r = SettingsRig()
        r.vm.forgetInstance()
        assertNull(r.sessionStore.state.value)
        assertEquals(1, r.push.reconciles, "the push service is told to follow the forgotten server")
    }

    @Test fun pushConnectionSwitchAndBatteryStateShowInSettings() = withMain {
        val r = SettingsRig()
        assertTrue(r.state.pushOn)
        assertTrue(r.state.batteryUnrestricted)
        r.vm.togglePushService()
        assertFalse(r.state.pushOn)
        assertFalse(r.push.enabledState.value)
        assertEquals(1, r.push.reconciles)
        r.vm.togglePushService()
        assertTrue(r.push.enabledState.value)

        r.caps.battery = false
        r.vm.refreshDevice()
        assertFalse(r.state.batteryUnrestricted)
        r.vm.batteryAsked()
        assertTrue(r.push.askedState.value)
    }

    @Test fun redoOnboardingSetsTheFlag() = withMain {
        val r = SettingsRig()
        r.vm.redoOnboarding()
        assertTrue(r.flag.redoState.value)
    }
}
