package net.robog.clarity.app.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.testutil.FakeAlarms
import net.robog.clarity.app.testutil.FakeCapabilities
import net.robog.clarity.app.testutil.FixedClock
import net.robog.clarity.app.testutil.MemorySettingsCache
import net.robog.clarity.app.testutil.RecordingNotifier
import net.robog.clarity.app.testutil.ScreenSettings
import net.robog.clarity.app.testutil.InMemoryFeedStore
import net.robog.clarity.app.testutil.agentItem
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.session.Settings.MorningCall

private fun settings(
    at: LocalTime = LocalTime(9, 0),
    days: Set<Int> = setOf(1, 2, 3, 4, 5),
    headsUp: Int? = 10,
    zone: String = "UTC",
    enabled: Boolean = true,
    mode: MorningCall.Mode = MorningCall.Mode.CALL,
) = Settings(morningCall = MorningCall(enabled, at, days, headsUp, mode), homeZone = zone)

// 2026-10-26 is a Monday.
private fun at(iso: String) = Instant.parse(iso)

class NextCallPlanTest {
    @Test fun todayWhenTheCallIsStillAhead() {
        val plan = nextCallPlan(settings(), at("2026-10-26T08:00:00Z"))!!
        assertEquals(at("2026-10-26T09:00:00Z"), plan.callAt)
        assertEquals(at("2026-10-26T08:50:00Z"), plan.headsUpAt)
    }

    @Test fun nextSelectedDayAfterTheCallPassed() {
        // Friday 2026-10-30 after the call: the next weekday call is Monday.
        val plan = nextCallPlan(settings(), at("2026-10-30T09:30:00Z"))!!
        assertEquals(at("2026-11-02T09:00:00Z"), plan.callAt)
    }

    @Test fun headsUpIsDroppedWhenAlreadyPast() {
        val plan = nextCallPlan(settings(), at("2026-10-26T08:55:00Z"))!!
        assertEquals(at("2026-10-26T09:00:00Z"), plan.callAt)
        assertNull(plan.headsUpAt)
    }

    @Test fun noHeadsUpWhenSwitchedOff() {
        assertNull(nextCallPlan(settings(headsUp = null), at("2026-10-26T07:00:00Z"))!!.headsUpAt)
    }

    @Test fun disabledOrNoDaysMeansNoCall() {
        assertNull(nextCallPlan(settings(enabled = false), at("2026-10-26T07:00:00Z")))
        assertNull(nextCallPlan(settings(days = emptySet()), at("2026-10-26T07:00:00Z")))
    }

    @Test fun exactlyAtCallTimeMovesToTheNextDay() {
        val plan = nextCallPlan(settings(), at("2026-10-26T09:00:00Z"))!!
        assertEquals(at("2026-10-27T09:00:00Z"), plan.callAt)
    }

    @Test fun theStoredTimeIsOnTheHomeClock() {
        // 9:00 in New York is 13:00 UTC while New York is on daylight time (UTC-4).
        val plan = nextCallPlan(settings(at = LocalTime(9, 0), zone = "America/New_York"), at("2026-10-26T10:00:00Z"))!!
        assertEquals(at("2026-10-26T13:00:00Z"), plan.callAt)
    }

    @Test fun wallClockHoldsAcrossDst() {
        // US clocks go back on 2026-11-01 (a Sunday). Saturday's call is UTC-4, Monday's is UTC-5.
        val s = settings(at = LocalTime(9, 0), zone = "America/New_York", days = setOf(6, 1))
        val saturday = nextCallPlan(s, at("2026-10-31T00:00:00Z"))!!
        assertEquals(at("2026-10-31T13:00:00Z"), saturday.callAt)
        val monday = nextCallPlan(s, at("2026-10-31T14:00:00Z"))!!
        assertEquals(at("2026-11-02T14:00:00Z"), monday.callAt)
    }

    @Test fun wallClockHoldsAcrossDstInBerlin() {
        // Berlin clocks go back on 2026-10-25 (a Sunday): Saturday's 9:00 is UTC+2, Monday's is UTC+1.
        val s = settings(at = LocalTime(9, 0), zone = "Europe/Berlin", days = setOf(6, 1))
        assertEquals(at("2026-10-24T07:00:00Z"), nextCallPlan(s, at("2026-10-24T00:00:00Z"))!!.callAt)
        assertEquals(at("2026-10-26T08:00:00Z"), nextCallPlan(s, at("2026-10-24T08:00:00Z"))!!.callAt)
    }

    @Test fun unknownHomeZoneIsTreatedAsUtc() {
        assertEquals(at("2026-10-26T09:00:00Z"), nextCallPlan(settings(zone = "Mars/Olympus"), at("2026-10-26T00:00:00Z"))!!.callAt)
    }

    @Test fun onlyTheSelectedDaysRing() {
        val wed = nextCallPlan(settings(days = setOf(3)), at("2026-10-26T10:00:00Z"))!!
        assertEquals(at("2026-10-28T09:00:00Z"), wed.callAt)
    }
}

class AlarmPolicyTest {
    @Test fun kindFollowsThePermission() {
        assertEquals(AlarmKind.Exact, alarmKind(true))
        assertEquals(AlarmKind.Inexact, alarmKind(false))
    }

    @Test fun asksOnlyWhenACallIsScheduledAndExactIsMissing() {
        assertTrue(shouldAskForExactAlarms(settings(), canScheduleExact = false))
        assertFalse(shouldAskForExactAlarms(settings(), canScheduleExact = true))
        assertFalse(shouldAskForExactAlarms(settings(enabled = false), canScheduleExact = false))
        assertFalse(shouldAskForExactAlarms(settings(days = emptySet()), canScheduleExact = false))
    }

    @Test fun snoozeIsFifteenMinutesFromNow() {
        assertEquals(at("2026-10-26T09:15:00Z"), snoozedCallAt(at("2026-10-26T09:00:00Z"), 15))
    }
}

class IncomingPresentationTest {
    @Test fun fullScreenWhenAllowed() {
        assertEquals(IncomingPresentation.FullScreenCall, incomingPresentation(MorningCall.Mode.CALL, true, true))
    }

    @Test fun fallsBackToAHeadsUpCallWithoutTheFullScreenPermission() {
        assertEquals(IncomingPresentation.HeadsUpCall, incomingPresentation(MorningCall.Mode.CALL, false, true))
    }

    @Test fun notifyModePostsAPlainNotification() {
        assertEquals(IncomingPresentation.Plain, incomingPresentation(MorningCall.Mode.NOTIFY, true, true))
    }

    @Test fun nothingWhenNotificationsAreOff() {
        assertEquals(IncomingPresentation.None, incomingPresentation(MorningCall.Mode.CALL, true, false))
    }
}

class HeadsUpTextTest {
    private val now = at("2026-10-26T08:50:00Z")

    @Test fun titleIsThePrototypeLine() {
        assertEquals("We’re scheduled in 10! :)", headsUpTitle(10))
        assertEquals("We’re scheduled in 5! :)", headsUpTitle(5))
    }

    @Test fun fixedLineWhenThePrepJobLeftNothing() {
        assertEquals("I’ll call at 9:00 am.", headsUpBody(LocalTime(9, 0), emptyList(), now))
    }

    @Test fun usesTheAgentsFirstLineFromTheFeed() {
        val items = listOf(
            agentItem(1, "Old news"),
            agentItem(2, "Five things on my list from yesterday.\nMore detail"),
        ).map { it.copy(at = at("2026-10-26T08:40:00Z")) }
        assertEquals("Five things on my list from yesterday.", headsUpBody(LocalTime(9, 0), items, now))
    }

    @Test fun ignoresCallItemsOldItemsAndStreamingOnes() {
        val items: List<FeedItem> = listOf(
            agentItem(1, "In a call", sessionId = net.robog.clarity.shared.session.SessionId("s")).copy(at = at("2026-10-26T08:45:00Z")),
            agentItem(2, "Yesterday").copy(at = at("2026-10-25T08:00:00Z")),
            agentItem(3, "Still typing", streaming = true).copy(at = at("2026-10-26T08:45:00Z")),
        )
        assertEquals("I’ll call at 9:30 am.", headsUpBody(LocalTime(9, 30), items, now))
    }
}

class CallSchedulerTest {
    private val clock = FixedClock(at("2026-10-26T08:00:00Z"))
    private val alarms = FakeAlarms()
    private val cache = MemorySettingsCache()
    private val repo = ScreenSettings(ApiResult.Success(settings()))
    private val scheduler = CallScheduler(repo, cache, alarms, clock)

    @Test fun refreshArmsHeadsUpAndCallAndCachesTheSettings() = runBlocking {
        scheduler.refresh()
        assertEquals(at("2026-10-26T08:50:00Z"), alarms.set[AlarmSlot.HeadsUp])
        assertEquals(at("2026-10-26T09:00:00Z"), alarms.set[AlarmSlot.Call])
        assertEquals(settings(), cache.settings)
    }

    @Test fun offlineRefreshUsesTheCachedSettings() = runBlocking {
        cache.settings = settings(at = LocalTime(9, 30))
        repo.loaded = ApiResult.Failure(ApiError.Unreachable("down"))
        scheduler.refresh()
        assertEquals(at("2026-10-26T09:30:00Z"), alarms.set[AlarmSlot.Call])
    }

    @Test fun switchingTheCallOffCancelsTheAlarms() = runBlocking {
        scheduler.refresh()
        scheduler.apply(settings(enabled = false))
        assertTrue(alarms.set.isEmpty())
    }

    @Test fun nothingScheduledWithoutAnySettings() = runBlocking {
        repo.loaded = ApiResult.Failure(ApiError.Unreachable("down"))
        assertNull(scheduler.refresh())
        assertTrue(alarms.set.isEmpty())
    }

    @Test fun snoozeSetsItsOwnAlarmThatARefreshKeeps() = runBlocking {
        val at = scheduler.snooze(15)
        assertEquals(at("2026-10-26T08:15:00Z"), at)
        scheduler.refresh()
        assertEquals(at("2026-10-26T08:15:00Z"), alarms.set[AlarmSlot.Snooze])
        scheduler.cancelSnooze()
        assertFalse(AlarmSlot.Snooze in alarms.set)
    }

    @Test fun movingTheTimeMovesTheAlarms() = runBlocking {
        scheduler.refresh()
        scheduler.apply(settings(at = LocalTime(8, 30), headsUp = 5))
        assertEquals(at("2026-10-26T08:25:00Z"), alarms.set[AlarmSlot.HeadsUp])
        assertEquals(at("2026-10-26T08:30:00Z"), alarms.set[AlarmSlot.Call])
    }

    @Test fun reportsWhenExactAlarmsAreMissing() {
        alarms.exact = false
        assertTrue(scheduler.needsExactAlarmPermission(settings()))
    }
}

class CallAlarmHandlerTest {
    private val clock = FixedClock(at("2026-10-26T09:00:00Z"))
    private val alarms = FakeAlarms()
    private val cache = MemorySettingsCache(settings())
    private val notifier = RecordingNotifier()
    private val caps = FakeCapabilities()
    private val feed = InMemoryFeedStore()
    private val scheduler = CallScheduler(ScreenSettings(ApiResult.Success(settings())), cache, alarms, clock)
    private val handler = CallAlarmHandler(cache, feed, notifier, caps, scheduler, clock)

    @Test fun callAlarmPostsTheFullScreenCallAndArmsTomorrow() = runBlocking {
        handler.onAlarm(AlarmSlot.Call)
        assertEquals(listOf("incoming:FullScreenCall@9:00 am", "cancelHeadsUp"), notifier.log)
        assertEquals(at("2026-10-27T09:00:00Z"), alarms.set[AlarmSlot.Call])
    }

    @Test fun withoutFullScreenPermissionItIsAHeadsUpCall() = runBlocking {
        caps.fullScreen = false
        handler.onAlarm(AlarmSlot.Call)
        assertEquals("incoming:HeadsUpCall@9:00 am", notifier.log.first())
    }

    @Test fun notifyModeDoesNotRing() = runBlocking {
        cache.settings = settings(mode = MorningCall.Mode.NOTIFY)
        handler.onAlarm(AlarmSlot.Call)
        assertEquals("incoming:Plain@9:00 am", notifier.log.first())
    }

    @Test fun noNotificationsMeansNothingIsPosted() = runBlocking {
        caps.notifications = false
        handler.onAlarm(AlarmSlot.Call)
        handler.onAlarm(AlarmSlot.HeadsUp)
        assertTrue(notifier.log.none { it.startsWith("incoming") || it.startsWith("headsUp:") })
    }

    @Test fun headsUpUsesTheFixedLineWithoutAPreparedOne() = runBlocking {
        handler.onAlarm(AlarmSlot.HeadsUp)
        assertEquals(listOf("headsUp:We’re scheduled in 10! :)|I’ll call at 9:00 am."), notifier.log)
    }

    @Test fun snoozedCallDoesNotMoveTheRegularAlarms() = runBlocking {
        handler.onAlarm(AlarmSlot.Snooze)
        assertFalse(AlarmSlot.Call in alarms.set)
    }
}
