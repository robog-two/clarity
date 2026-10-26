package net.robog.clarity.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import net.robog.clarity.app.call.AlarmSlot
import net.robog.clarity.app.call.CallScheduler
import net.robog.clarity.app.call.CallService
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.feed.SyncSignal
import net.robog.clarity.app.testutil.FakeAlarms
import net.robog.clarity.app.testutil.FixedClock
import net.robog.clarity.app.testutil.MemorySettingsCache
import net.robog.clarity.app.testutil.RecordingNotifier
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.ScreenFeed
import net.robog.clarity.app.testutil.ScreenSettings
import net.robog.clarity.app.testutil.ScreenSpeech
import net.robog.clarity.app.testutil.ScriptedSessions
import net.robog.clarity.app.testutil.agentItem
import net.robog.clarity.app.testutil.callSession
import net.robog.clarity.app.testutil.withMain
import net.robog.clarity.app.ui.call.CallScreen
import net.robog.clarity.app.ui.call.CallUiState
import net.robog.clarity.app.ui.call.CallViewModel
import net.robog.clarity.app.ui.call.SegmentState
import net.robog.clarity.app.ui.call.StageLabels
import net.robog.clarity.app.ui.call.formatElapsed
import net.robog.clarity.app.ui.call.planOf
import net.robog.clarity.app.ui.call.primaryControl
import net.robog.clarity.app.ui.call.primaryLabel
import net.robog.clarity.app.ui.call.setupTasks
import net.robog.clarity.app.ui.call.stageSegments
import net.robog.clarity.app.voice.MicChipState
import net.robog.clarity.app.voice.SpeechEvent
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.FeedItemId
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.session.CallStage
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.Settings

private val call = SessionId("call1")

private class CallRig(active: Session? = null, settings: Settings = Settings(timeZone = "UTC")) {
    val clock = FixedClock(T0)
    val sessions = ScriptedSessions(active)
    val feed = ScreenFeed()
    val speech = ScreenSpeech()
    val settingsRepo = ScreenSettings(ApiResult.Success(settings))
    val alarms = FakeAlarms()
    val notifier = RecordingNotifier()
    val scheduler = CallScheduler(settingsRepo, MemorySettingsCache(), alarms, clock)
    val vm = CallViewModel(sessions, feed, settingsRepo, CallService(sessions, scheduler, notifier), notifier, speech, clock)
    val state: CallUiState get() = vm.state.value
}

private fun sessionItem(seq: Long, vararg cards: Card, author: FeedItem.Author = FeedItem.Author.AGENT) =
    FeedItem(FeedItemId("i$seq"), seq, T0, author, call, cards.toList())

class CallStageRulesTest {
    @Test fun primaryButtonReadsLikeThePrototype() {
        assertEquals("that’s all", primaryLabel(CallStage.CLEAR))
        assertEquals("next", primaryLabel(CallStage.CHOOSE))
        assertEquals("next", primaryLabel(CallStage.COMMIT))
        assertEquals("end call", primaryLabel(CallStage.HAND_OFF))
        assertEquals(SessionControl.Next, primaryControl(CallStage.COMMIT))
        assertEquals(SessionControl.End, primaryControl(CallStage.HAND_OFF))
    }

    @Test fun segmentsMarkDoneCurrentAndTodo() {
        assertEquals(listOf("clear", "choose", "commit", "hand off"), StageLabels)
        assertEquals(
            listOf(SegmentState.Done, SegmentState.Current, SegmentState.Todo, SegmentState.Todo),
            stageSegments(CallStage.CHOOSE),
        )
        assertEquals(List(4) { SegmentState.Done }, stageSegments(CallStage.HAND_OFF, ended = true))
        assertEquals(SegmentState.Current, stageSegments(null).first())
    }

    @Test fun elapsedTimeIsMinutesAndSeconds() {
        assertEquals("0:00", formatElapsed(0.seconds))
        assertEquals("7:05", formatElapsed(7.minutes + 5.seconds))
    }

    @Test fun planAndSetupComeFromTheCallsCards() {
        val t1 = AgentTask(TaskId("t1"), "reply to Dana", "mail", AgentTask.Status.DRAFTED, null, T0)
        val items = listOf(
            sessionItem(1, Card.Plan(listOf(IfThen("it’s 9:30", "spec")))),
            sessionItem(2, Card.Plan(listOf(IfThen("it’s 10", "break")))),
            sessionItem(3, Card.Tasks("setting up", listOf(TaskId("t1"), TaskId("missing")))),
        )
        assertEquals(listOf(IfThen("it’s 10", "break")), planOf(items))
        assertEquals(listOf(t1), setupTasks(items, mapOf(t1.id to t1)))
    }
}

class CallViewModelTest {
    @Test fun startsOnTheIncomingScreenWhenNoCallIsRunning() = withMain {
        val r = CallRig()
        assertEquals(CallScreen.Incoming, r.state.screen)
        assertEquals("9:00", r.state.callTime)
        assertTrue(r.sessions.started.isEmpty(), "nothing starts before answering")
    }

    @Test fun rejoinsARunningCallAndListens() = withMain {
        val r = CallRig(active = callSession(stage = CallStage.COMMIT))
        assertEquals(CallScreen.InCall, r.state.screen)
        assertEquals(CallStage.COMMIT, r.state.stage)
        assertEquals(listOf("start(true)"), r.speech.calls)
    }

    @Test fun aSnoozedCallShowsTheSnoozedScreen() = withMain {
        val r = CallRig(active = callSession(state = Session.State.SNOOZED))
        assertEquals(CallScreen.Snoozed, r.state.screen)
    }

    @Test fun answerStartsAMorningCallSession() = withMain {
        val r = CallRig()
        r.vm.answer()
        assertEquals(listOf(net.robog.clarity.shared.session.SessionKind.MORNING_CALL), r.sessions.started.map { it.first })
        assertEquals(CallScreen.InCall, r.state.screen)
        assertTrue("cancelIncoming" in r.notifier.log)
        assertEquals(MicChipState.MicOn, r.state.mic)
    }

    @Test fun answerFailureKeepsTheIncomingScreenWithAMessage() = withMain {
        val r = CallRig()
        r.sessions.startResult = ApiResult.Failure(ApiError.NotFound(null))
        r.vm.answer()
        assertEquals(CallScreen.Incoming, r.state.screen)
        assertNotNull(r.state.message)
        assertFalse(r.state.working)
    }

    @Test fun declineSnoozesFifteenMinutesAndRingsAgain() = withMain {
        val r = CallRig()
        r.vm.decline()
        assertEquals(CallScreen.Snoozed, r.state.screen)
        assertEquals(listOf<SessionControl>(SessionControl.Snooze(15)), r.sessions.controls)
        assertEquals("9:15", r.state.snoozedUntil)
        assertEquals(T0 + 15.minutes, r.alarms.set[AlarmSlot.Snooze])
    }

    @Test fun declineStillSchedulesTheRecallWhenTheServerIsUnreachable() = withMain {
        val r = CallRig()
        r.sessions.startResult = ApiResult.Failure(ApiError.Unreachable("down"))
        r.vm.decline()
        assertEquals(CallScreen.Snoozed, r.state.screen)
        assertNotNull(r.alarms.set[AlarmSlot.Snooze])
        assertNotNull(r.state.message)
    }

    @Test fun callMeNowAnswersAgainAfterASnooze() = withMain {
        val r = CallRig()
        r.vm.decline()
        r.vm.answer()
        assertEquals(CallScreen.InCall, r.state.screen)
        assertEquals(2, r.sessions.started.size) // one to snooze, one for the real call
    }

    @Test fun primaryButtonWalksTheStagesAndEndsTheCall() = withMain {
        val r = CallRig(active = callSession(stage = CallStage.CLEAR))
        repeat(3) { r.vm.advance() }
        assertEquals(CallStage.HAND_OFF, r.state.stage)
        assertEquals(listOf<SessionControl>(SessionControl.Next, SessionControl.Next, SessionControl.Next), r.sessions.controls)
        r.vm.advance()
        assertEquals(SessionControl.End, r.sessions.controls.last())
        assertEquals(CallScreen.Ended, r.state.screen)
        assertTrue("stop" in r.speech.calls, "the mic stops when the call ends")
    }

    @Test fun nextIsDisabledWhileTheAgentIsBusy() = withMain {
        val r = CallRig(active = callSession(stage = CallStage.CLEAR))
        r.sessions.busyAfterNext = true
        r.vm.advance()
        assertEquals(Session.State.BUSY, r.state.session?.state)
        assertFalse(r.state.canAdvance)
        r.vm.advance() // ignored
        assertEquals(1, r.sessions.controls.size)

        r.sessions.finishStage()
        r.feed.signalFlow.tryEmit(SyncSignal.SessionChanged(call))
        assertTrue(r.state.canAdvance)
        r.sessions.busyAfterNext = false
        r.vm.advance()
        assertEquals(2, r.sessions.controls.size)
    }

    @Test fun aServerRefusalShowsAMessageAndFreesTheButton() = withMain {
        val r = CallRig(active = callSession())
        r.sessions.controlFailure = ApiError.Http(409, null)
        r.vm.advance()
        assertNotNull(r.state.message)
        assertFalse(r.state.working)
        assertEquals(CallScreen.InCall, r.state.screen)
    }

    @Test fun showsOnlyThisCallsItemsAndLivePartialSpeech() = withMain {
        val r = CallRig(active = callSession())
        r.feed.itemsFlow.value = listOf(
            agentItem(1, "Not in the call"),
            sessionItem(2, Card.Text("Morning. What’s on your mind?")),
        )
        assertEquals(listOf(2L), r.state.items.map { it.seq })

        r.speech.flow.tryEmit(SpeechEvent.Partial("the onboarding spec and"))
        assertEquals("the onboarding spec and", r.state.live)
    }

    @Test fun finishedSpeechIsSentAsVoiceInputOnTheSession() = withMain {
        val r = CallRig(active = callSession())
        r.speech.flow.tryEmit(SpeechEvent.Partial("the spec"))
        r.speech.flow.tryEmit(SpeechEvent.Final("  the spec, Dana’s contract  "))
        assertEquals(listOf(Triple<String, InputSource, SessionId?>("the spec, Dana’s contract", InputSource.VOICE, call)), r.feed.sent)
        assertNull(r.state.live)
    }

    @Test fun muteAndUnmuteFollowTheMicState() = withMain {
        val r = CallRig(active = callSession())
        r.vm.toggleMute()
        assertTrue(r.state.muted)
        r.vm.toggleMute()
        assertFalse(r.state.muted)
        assertEquals(listOf("start(true)", "mute", "unmute"), r.speech.calls)
    }

    @Test fun pushToTalkReplacesMuteWithTalk() = withMain {
        val r = CallRig(active = callSession(), settings = Settings(micAlwaysOn = false, timeZone = "UTC"))
        assertTrue(r.state.pushToTalk)
        assertEquals(listOf("start(false)"), r.speech.calls)
        r.vm.toggleMute()
        assertEquals(listOf("start(false)", "start(false)"), r.speech.calls)
    }

    @Test fun leavingTheScreenStopsTheMicAndShowsTheOngoingNotification() = withMain {
        val r = CallRig(active = callSession())
        r.vm.setForeground(false)
        assertEquals("stop", r.speech.calls.last())
        assertTrue("ongoing:call1" in r.notifier.log)
        r.vm.setForeground(true)
        assertEquals("start(true)", r.speech.calls.last())
        assertEquals("cancelOngoing", r.notifier.log.last())
    }

    @Test fun endedScreenShowsPlanAndLiveTaskStatusesAndCanReplay() = withMain {
        val r = CallRig(active = callSession(stage = CallStage.HAND_OFF))
        val task = AgentTask(TaskId("t1"), "reply to Dana", "mail · waits for your review", AgentTask.Status.WORKING, null, T0)
        r.feed.tasksFlow.value = listOf(task)
        r.feed.itemsFlow.value = listOf(
            sessionItem(1, Card.Plan(listOf(IfThen("it’s 9:30", "spec at my desk until 12")))),
            sessionItem(2, Card.Tasks("setting up", listOf(TaskId("t1")))),
        )
        r.vm.advance()
        assertEquals(CallScreen.Ended, r.state.screen)
        assertEquals(listOf("If it’s 9:30, then spec at my desk until 12."), r.state.plan.map { it.sentence() })
        assertEquals(AgentTask.Status.WORKING, r.state.setup.single().status)

        // The task changes through the feed: the ended screen follows.
        r.feed.tasksFlow.value = listOf(task.copy(status = AgentTask.Status.DRAFTED))
        assertEquals(AgentTask.Status.DRAFTED, r.state.setup.single().status)

        r.vm.replay()
        assertEquals(CallScreen.Replay, r.state.screen)
        assertEquals(2, r.state.items.size)
        r.vm.closeReplay()
        assertEquals(CallScreen.Ended, r.state.screen)
        assertTrue(r.sessions.started.isEmpty(), "replay never starts a session")
    }

    @Test fun anEndedSessionFromTheServerShowsTheEndedScreen() = withMain {
        val r = CallRig(active = callSession(stage = CallStage.HAND_OFF))
        r.sessions.current = r.sessions.current!!.copy(state = Session.State.ENDED, endedAt = T0 + 8.minutes)
        r.feed.signalFlow.tryEmit(SyncSignal.SessionChanged(call))
        assertEquals(CallScreen.Ended, r.state.screen)
    }
}

class CallServiceTest {
    @Test fun hangUpEndsTheRunningCall() = runBlocking {
        val sessions = ScriptedSessions(callSession())
        val notifier = RecordingNotifier()
        val clock = FixedClock(T0)
        val scheduler = CallScheduler(ScreenSettings(), MemorySettingsCache(), FakeAlarms(), clock)
        CallService(sessions, scheduler, notifier).hangUp()
        assertEquals(listOf<SessionControl>(SessionControl.End), sessions.controls)
        assertTrue("cancelOngoing" in notifier.log)
        Unit
    }

    @Test fun snoozeFromTheNotificationReusesARunningSession() = runBlocking {
        val sessions = ScriptedSessions(callSession())
        val clock = FixedClock(T0)
        val alarms = FakeAlarms()
        val scheduler = CallScheduler(ScreenSettings(), MemorySettingsCache(), alarms, clock)
        CallService(sessions, scheduler, RecordingNotifier()).snooze()
        assertTrue(sessions.started.isEmpty())
        assertEquals(listOf<SessionControl>(SessionControl.Snooze(15)), sessions.controls)
        assertNotNull(alarms.set[AlarmSlot.Snooze])
        Unit
    }
}
