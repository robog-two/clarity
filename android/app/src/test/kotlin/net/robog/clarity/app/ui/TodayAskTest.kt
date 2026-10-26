package net.robog.clarity.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.testutil.FakeAmendments
import net.robog.clarity.app.testutil.FakeToday
import net.robog.clarity.app.testutil.FixedClock
import net.robog.clarity.app.testutil.RecordingGate
import net.robog.clarity.app.testutil.ScreenFeed
import net.robog.clarity.app.testutil.ScreenSpeech
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.agentItem
import net.robog.clarity.app.testutil.heldAction
import net.robog.clarity.app.testutil.userItem
import net.robog.clarity.app.testutil.withMain
import net.robog.clarity.app.ui.ask.AskSuggestions
import net.robog.clarity.app.ui.ask.AskViewModel
import net.robog.clarity.app.ui.ask.askItems
import net.robog.clarity.app.ui.ask.showSuggestions
import net.robog.clarity.app.ui.today.TodayViewModel
import net.robog.clarity.app.ui.today.dateKicker
import net.robog.clarity.app.ui.today.doingRows
import net.robog.clarity.app.ui.today.heldLine
import net.robog.clarity.app.ui.today.mergeTasks
import net.robog.clarity.app.ui.today.parkedLine
import net.robog.clarity.app.voice.MicChipState
import net.robog.clarity.app.voice.SpeechEvent
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.HeldDecision
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.Today

private fun task(id: String, status: AgentTask.Status, held: String? = null, at: kotlin.time.Instant = T0) =
    AgentTask(TaskId(id), "task $id", "detail $id", status, held?.let { HeldActionId(it) }, at)

class TodayRulesTest {
    @Test fun draftedTaskWithAWaitingHeldActionGetsAReviewButton() {
        val rows = doingRows(
            listOf(task("t1", AgentTask.Status.DRAFTED, "h1"), task("t2", AgentTask.Status.SCHEDULED)),
            mapOf(HeldActionId("h1") to heldAction("h1")),
        )
        assertEquals(HeldActionId("h1"), rows[0].review)
        assertNull(rows[1].review)
    }

    @Test fun settledHeldActionMeansNoReviewButton() {
        val rows = doingRows(
            listOf(task("t1", AgentTask.Status.DRAFTED, "h1")),
            mapOf(HeldActionId("h1") to heldAction("h1", status = HeldAction.Status.EXECUTED)),
        )
        assertNull(rows.single().review)
    }

    @Test fun unknownHeldActionStillOffersReviewWhileTheTaskWaits() {
        assertEquals(HeldActionId("h9"), doingRows(listOf(task("t1", AgentTask.Status.WAITING_FOR_YOU, "h9")), emptyMap()).single().review)
        assertNull(doingRows(listOf(task("t1", AgentTask.Status.DONE, "h9")), emptyMap()).single().review)
    }

    @Test fun liveTasksOverrideTheServerListWhenNewer() {
        val server = listOf(task("t1", AgentTask.Status.WORKING, at = T0), task("t2", AgentTask.Status.WORKING, at = T0))
        val live = listOf(task("t1", AgentTask.Status.DRAFTED, at = T0 + 1.minutes), task("t2", AgentTask.Status.DONE, at = T0 - 1.minutes))
        val merged = mergeTasks(server, live)
        assertEquals(AgentTask.Status.DRAFTED, merged[0].status)
        assertEquals(AgentTask.Status.WORKING, merged[1].status, "an older live copy never wins")
    }

    @Test fun textsMatchThePrototype() {
        assertEquals("monday, October 26 · 10:04 am", dateKicker(LocalDate(2026, 10, 26), LocalTime(10, 4)))
        assertEquals("nothing held today", heldLine(0))
        assertEquals("2 held today", heldLine(2))
        assertEquals("Feedback for Alex, expense report. Back in tomorrow’s call.", parkedLine(listOf("feedback for Alex", "expense report")))
        assertNull(parkedLine(emptyList()))
    }
}

class TodayViewModelTest {
    private val clock = FixedClock(T0)

    @Test fun showsThePlanTasksAndHeldCount() = withMain {
        val today = Today(
            LocalDate(2026, 10, 26),
            plan = listOf(IfThen("it’s 9:30", "spec at my desk until 12")),
            doing = listOf(task("t1", AgentTask.Status.DRAFTED, "h1")),
            heldCount = 1,
            parked = listOf("feedback for Alex"),
            called = true,
        )
        val repo = FakeToday(ApiResult.Success(today))
        val feed = ScreenFeed()
        val vm = TodayViewModel(repo, feed, clock)
        val s = vm.state.value
        assertEquals(today, s.today)
        assertEquals(HeldActionId("h1"), s.doing.single().review)
        assertEquals(1, s.heldWaiting)
        assertFalse(s.loading)
        assertTrue(s.kicker.startsWith("monday, October 26"))
    }

    @Test fun anOlderServerWithoutTodayIsNotAnError() = withMain {
        val vm = TodayViewModel(FakeToday(ApiResult.Failure(ApiError.NotFound(null))), ScreenFeed(), clock)
        assertTrue(vm.state.value.unavailable)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }

    @Test fun otherFailuresShowAMessage() = withMain {
        val vm = TodayViewModel(FakeToday(ApiResult.Failure(ApiError.Unreachable("down"))), ScreenFeed(), clock)
        assertNotNull(vm.state.value.error)
    }

    @Test fun taskChangesInTheFeedUpdateRowsAndRefetch() = withMain {
        val today = Today(LocalDate(2026, 10, 26), doing = listOf(task("t1", AgentTask.Status.WORKING)))
        val repo = FakeToday(ApiResult.Success(today))
        val feed = ScreenFeed()
        val vm = TodayViewModel(repo, feed, clock)
        assertEquals(1, repo.calls)
        feed.tasksFlow.value = listOf(task("t1", AgentTask.Status.DRAFTED, at = T0 + 1.minutes))
        assertEquals(AgentTask.Status.DRAFTED, vm.state.value.doing.single().task.status)
        assertEquals(2, repo.calls, "a task event refreshes Today")
    }

    @Test fun heldBadgeFollowsTheLiveFeed() = withMain {
        val repo = FakeToday(ApiResult.Success(Today(LocalDate(2026, 10, 26), heldCount = 0)))
        val feed = ScreenFeed()
        val vm = TodayViewModel(repo, feed, clock)
        feed.heldFlow.value = listOf(heldAction("h1"), heldAction("h2"))
        assertEquals(2, vm.state.value.heldWaiting)
    }

    @Test fun pullToRefreshFetchesAgain() = withMain {
        val repo = FakeToday()
        val vm = TodayViewModel(repo, ScreenFeed(), clock)
        vm.refresh()
        assertEquals(2, repo.calls)
        assertFalse(vm.state.value.refreshing)
    }
}

class AskRulesTest {
    @Test fun askShowsOnlyItemsOutsideSessions() {
        val items = listOf(agentItem(2, "answer"), agentItem(1, "in call", SessionId("s")), userItem(0, "question"))
        assertEquals(listOf(0L, 2L), askItems(items).map { it.seq })
    }

    @Test fun suggestionsOnlyWhileEmptyAndQuiet() {
        assertTrue(showSuggestions(emptyList(), busy = false, live = null))
        assertFalse(showSuggestions(emptyList(), busy = true, live = null))
        assertFalse(showSuggestions(emptyList(), busy = false, live = "hello"))
        assertFalse(showSuggestions(listOf(agentItem(1, "hi")), busy = false, live = null))
        assertEquals(3, AskSuggestions.size)
    }
}

class AskViewModelTest {
    private class Rig {
        val feed = ScreenFeed()
        val speech = ScreenSpeech()
        val gate = RecordingGate()
        val vm = AskViewModel(feed, gate, speech, net.robog.clarity.app.ui.held.AlwaysAllow(FakeAmendments()))
        val state get() = vm.state.value
    }

    @Test fun emptyStateOffersSuggestions() = withMain {
        assertEquals(AskSuggestions, Rig().state.suggestions)
    }

    @Test fun typedInputIsSentWithoutASession() = withMain {
        val r = Rig()
        r.vm.onDraft("What’s left for me today?")
        assertTrue(r.state.canSend)
        r.vm.submit()
        assertEquals(listOf(Triple<String, InputSource, SessionId?>("What’s left for me today?", InputSource.TYPED, null)), r.feed.sent)
        assertEquals("", r.state.draft)
        assertTrue(r.state.suggestions.isEmpty(), "the conversation started")
    }

    @Test fun blankInputIsNotSent() = withMain {
        val r = Rig()
        r.vm.onDraft("   ")
        r.vm.submit()
        assertTrue(r.feed.sent.isEmpty())
    }

    @Test fun aFailedSendGivesTheTextBackWithAMessage() = withMain {
        val r = Rig()
        r.feed.sendResult = ApiResult.Failure(ApiError.Unreachable("x"))
        r.vm.onDraft("hello")
        r.vm.submit()
        assertEquals("hello", r.state.draft)
        assertNotNull(r.state.message)
    }

    @Test fun tappingASuggestionSendsIt() = withMain {
        val r = Rig()
        r.vm.suggest(AskSuggestions[1])
        assertEquals(AskSuggestions[1], r.feed.sent.single().first)
    }

    @Test fun talkListensForOneUtteranceAndSendsItAsVoice() = withMain {
        val r = Rig()
        r.vm.talk()
        assertEquals(listOf("start(false)"), r.speech.calls)
        assertEquals(MicChipState.MicOn, r.state.mic)
        r.speech.flow.tryEmit(SpeechEvent.Partial("move my call with"))
        assertEquals("move my call with", r.state.live)
        r.speech.flow.tryEmit(SpeechEvent.Final("move my call with Dana to Thursday"))
        assertEquals(InputSource.VOICE, r.feed.sent.single().second)
        assertNull(r.state.live)
    }

    @Test fun talkAgainStopsListening() = withMain {
        val r = Rig()
        r.vm.talk()
        r.vm.talk()
        assertEquals("stop", r.speech.calls.last())
    }

    @Test fun proposalsShowInlineWithTheirHeldAction() = withMain {
        val r = Rig()
        r.feed.heldFlow.value = listOf(heldAction("h1"))
        assertNotNull(r.state.held[HeldActionId("h1")])
    }

    @Test fun approvingAProposalAsksTheGateFirst() = withMain {
        val r = Rig()
        r.feed.heldFlow.value = listOf(heldAction("h1"))
        r.vm.decide(HeldActionId("h1"), HeldDecision.Approve())
        assertEquals(listOf(HeldActionId("h1")), r.gate.asked)
        assertEquals(1, r.feed.decisions.size)

        r.gate.allow = false
        r.vm.decide(HeldActionId("h1"), HeldDecision.Approve())
        assertEquals(1, r.feed.decisions.size, "refused by the gate")
    }

    @Test fun notNowNeedsNoGate() = withMain {
        val r = Rig()
        r.feed.heldFlow.value = listOf(heldAction("h1"))
        r.vm.decide(HeldActionId("h1"), HeldDecision.Decline)
        assertTrue(r.gate.asked.isEmpty())
        assertEquals(HeldDecision.Decline, r.feed.decisions.single().second)
    }

    @Test fun agentBusyShowsWhileItWorks() = withMain {
        val r = Rig()
        r.feed.busyFlow.value = true
        assertTrue(r.state.busy)
        assertTrue(r.state.suggestions.isEmpty())
    }
}
