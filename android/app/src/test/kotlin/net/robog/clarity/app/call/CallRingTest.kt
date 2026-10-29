package net.robog.clarity.app.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import net.robog.clarity.app.testutil.FakeCapabilities
import net.robog.clarity.app.testutil.FixedClock
import net.robog.clarity.app.testutil.MemorySettingsCache
import net.robog.clarity.app.testutil.RecordingNotifier
import net.robog.clarity.app.testutil.ScriptedSessions
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.callService
import net.robog.clarity.app.testutil.withMain
import net.robog.clarity.app.ui.main.CallPermissionsViewModel
import net.robog.clarity.app.ui.main.callPermissionCopy
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.session.Settings.MorningCall

class CallChannelsTest {
    private val ring = CallChannels.ALL.single { it.id == CallChannels.RING }

    @Test fun theCallRingsWithTheRingtoneAndVibrates() {
        assertTrue(ring.ringtone, "the phone's ringtone, not the notification sound")
        assertEquals(ChannelSpec.Importance.HIGH, ring.importance)
        assertNotNull(ring.vibration)
        assertTrue(ring.publicOnLockScreen)
    }

    @Test fun theOldChannelIsRetiredAndNotReused() {
        // Channels cannot change their sound once created: the ringing one needs an id it never had.
        assertEquals(listOf("morning_call"), CallChannels.RETIRED)
        assertTrue(CallChannels.ALL.none { it.id in CallChannels.RETIRED })
        assertEquals(CallChannels.ALL.size, CallChannels.ALL.map { it.id }.toSet().size)
    }

    @Test fun onlyTheCallRings() {
        assertEquals(listOf(CallChannels.RING), CallChannels.ALL.filter { it.ringtone }.map { it.id })
    }
}

class IncomingStyleTest {
    @Test fun aCallIsACallStyleThatKeepsRingingForAMinute() {
        val style = incomingStyle(IncomingPresentation.FullScreenCall)!!
        assertEquals(CallChannels.RING, style.channel)
        assertTrue(style.callStyle)
        assertTrue(style.insistent)
        assertEquals(60.seconds, style.timeout)
        assertTrue(style.fullScreenIntent)
    }

    @Test fun withoutThePermissionItStillAsksForFullScreenSoAndroidAcceptsTheCallStyle() {
        // Android 12+ refuses a CallStyle with neither a full-screen intent nor a foreground service;
        // Android 14+ without the permission turns the request into a sticky heads-up.
        val style = incomingStyle(IncomingPresentation.HeadsUpCall)!!
        assertTrue(style.callStyle)
        assertTrue(style.fullScreenIntent)
        assertTrue(style.insistent)
    }

    @Test fun notifyModeIsAnOrdinaryNotificationThatDoesNotRing() {
        val style = incomingStyle(IncomingPresentation.Plain)!!
        assertEquals(CallChannels.NOTICE, style.channel)
        assertFalse(style.callStyle)
        assertFalse(style.insistent)
        assertNull(style.timeout)
    }

    @Test fun nothingWhenNotificationsAreOff() {
        assertNull(incomingStyle(IncomingPresentation.None))
    }
}

class RingEndedTest {
    @Test fun anUnansweredRingLeavesAMissedCall() {
        val sessions = ScriptedSessions()
        val tracker = CallTracker(sessions, FixedClock(T0))
        val notifier = RecordingNotifier()
        val service = callService(sessions, tracker = tracker, notifier = notifier)
        assertTrue(service.ringAgain())
        assertTrue(tracker.ringing.value)
        service.ringEnded()
        assertFalse(tracker.ringing.value, "calling back goes straight in")
        assertEquals("missed", notifier.log.last())
    }
}

private fun call(mode: MorningCall.Mode = MorningCall.Mode.CALL, enabled: Boolean = true, days: Set<Int> = setOf(1, 2, 3, 4, 5)) =
    Settings(morningCall = MorningCall(enabled = enabled, days = days, mode = mode))

class CallPermissionsTest {
    @Test fun allGrantedNeedsNothing() {
        assertEquals(emptyList(), missingCallPermissions(call(), true, true, true))
    }

    @Test fun asksInOrderNotificationsThenFullScreenThenExactAlarms() {
        assertEquals(
            listOf(CallPermission.Notifications, CallPermission.FullScreen, CallPermission.ExactAlarms),
            missingCallPermissions(call(), notificationsOn = false, canUseFullScreenIntent = false, canScheduleExact = false),
        )
    }

    @Test fun notifyModeDoesNotNeedFullScreen() {
        assertEquals(
            listOf(CallPermission.ExactAlarms),
            missingCallPermissions(call(MorningCall.Mode.NOTIFY), true, canUseFullScreenIntent = false, canScheduleExact = false),
        )
    }

    @Test fun noCallSetStillAsksForNotificationsOnly() {
        assertEquals(
            listOf(CallPermission.Notifications),
            missingCallPermissions(call(enabled = false), false, false, false),
        )
        assertEquals(emptyList(), missingCallPermissions(call(days = emptySet()), true, false, false))
    }

    @Test fun oneAtATimeAndNotAgainAfterAskingOrNotNow() {
        val missing = listOf(CallPermission.Notifications, CallPermission.FullScreen)
        assertEquals(CallPermission.Notifications, nextCallPermission(missing, emptySet(), declined = false))
        assertEquals(CallPermission.FullScreen, nextCallPermission(missing, setOf(CallPermission.Notifications), declined = false))
        assertNull(nextCallPermission(missing, missing.toSet(), declined = false))
        assertNull(nextCallPermission(missing, emptySet(), declined = true))
    }

    @Test fun everyPermissionIsExplained() {
        CallPermission.entries.forEach { assertTrue(callPermissionCopy(it).body.isNotBlank()) }
    }
}

class CallPermissionsViewModelTest {
    private fun vm(caps: FakeCapabilities, settings: Settings? = call()) =
        CallPermissionsViewModel(MemorySettingsCache(settings), caps).also { it.io = Dispatchers.Unconfined }

    @Test fun asksForWhatIsMissingAndStopsAfterNotNow() = withMain {
        val caps = FakeCapabilities(fullScreen = false, notifications = false)
        val vm = vm(caps)
        vm.refresh()
        assertEquals(CallPermission.Notifications, vm.state.value.showing)
        vm.asked(CallPermission.Notifications)
        // Came back from the prompt still denied: the next one, not the same one again.
        vm.refresh()
        assertEquals(CallPermission.FullScreen, vm.state.value.showing)
        vm.notNow()
        vm.refresh()
        assertNull(vm.state.value.showing, "no nagging while the app stays open")
        // The next time the app opens (a new screen), it asks again.
        val again = vm(caps)
        again.refresh()
        assertEquals(CallPermission.Notifications, again.state.value.showing)
    }

    @Test fun grantedInSettingsGoesAway() = withMain {
        val caps = FakeCapabilities(fullScreen = false)
        val vm = vm(caps)
        vm.refresh()
        assertEquals(CallPermission.FullScreen, vm.state.value.showing)
        caps.fullScreen = true
        vm.refresh()
        assertNull(vm.state.value.showing)
    }

    @Test fun beforeTheScheduleIsCachedItUsesTheDefaultCall() = withMain {
        val vm = vm(FakeCapabilities(fullScreen = false), settings = null)
        vm.refresh()
        assertEquals(listOf(CallPermission.FullScreen), vm.state.value.missing)
    }
}
