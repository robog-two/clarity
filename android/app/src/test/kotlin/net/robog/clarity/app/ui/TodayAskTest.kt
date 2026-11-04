package net.robog.clarity.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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
import net.robog.clarity.app.ui.ask.AskSessionKinds
import net.robog.clarity.app.ui.ask.askEntries
import net.robog.clarity.app.ui.ask.askItems
import net.robog.clarity.app.ui.ask.sessionIds
import net.robog.clarity.app.ui.ask.sessionLabel
import net.robog.clarity.app.ui.ask.showSuggestions
import net.robog.clarity.app.ui.components.ChatEntry
import net.robog.clarity.app.data.SessionsRepository
import net.robog.clarity.app.testutil.callSession
import kotlinx.datetime.TimeZone
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionKind
import net.robog.clarity.app.ui.today.TodayViewModel
import net.robog.clarity.app.ui.today.dateKicker
import net.robog.clarity.app.ui.today.doingRows
import net.robog.clarity.app.ui.today.Doing
import net.robog.clarity.app.ui.today.DoingGroup
import net.robog.clarity.app.ui.today.DoingRow
import net.robog.clarity.app.ui.today.HIDDEN_AFTER
import net.robog.clarity.app.ui.today.INACTIVE_AFTER
import net.robog.clarity.app.ui.today.InactiveState
import net.robog.clarity.app.ui.today.inactiveLine
import net.robog.clarity.app.ui.today.inactiveState
import net.robog.clarity.app.ui.today.keptSince
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import net.robog.clarity.app.ui.today.heldLine
import net.robog.clarity.app.ui.today.mergeTasks
import net.robog.clarity.app.ui.today.startOfLocalDay
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
            T0,
        ).main
        assertEquals(HeldActionId("h1"), rows[0].review)
        assertNull(rows[1].review)
    }

    @Test fun settledHeldActionMeansNoReviewButton() {
        val rows = doingRows(
            listOf(task("t1", AgentTask.Status.DRAFTED, "h1")),
            mapOf(HeldActionId("h1") to heldAction("h1", status = HeldAction.Status.EXECUTED)),
            T0,
        ).rows
        assertNull(rows.single().review)
    }

    @Test fun unknownHeldActionStillOffersReviewWhileTheTaskWaits() {
        assertEquals(HeldActionId("h9"), doingRows(listOf(task("t1", AgentTask.Status.WAITING_FOR_YOU, "h9")), emptyMap(), T0).rows.single().review)
        assertNull(doingRows(listOf(task("t1", AgentTask.Status.DONE, "h9")), emptyMap(), T0).rows.single().review)
    }

    @Test fun liveTasksOverrideTheServerListWhenNewer() {
        val server = listOf(task("t1", AgentTask.Status.WORKING, at = T0), task("t2", AgentTask.Status.WORKING, at = T0))
        val live = listOf(task("t1", AgentTask.Status.DRAFTED, at = T0 + 1.minutes), task("t2", AgentTask.Status.DONE, at = T0 - 1.minutes))
        val merged = mergeTasks(server, live)
        assertEquals(AgentTask.Status.DRAFTED, merged[0].status)
        assertEquals(AgentTask.Status.WORKING, merged[1].status, "an older live copy never wins")
    }

    @Test fun liveTasksTheServerDidNotListJoinWhenFromToday() {
        val since = T0 - 2.hours
        val server = listOf(task("t1", AgentTask.Status.WORKING, at = T0))
        val live = listOf(
            task("t1", AgentTask.Status.DONE, at = T0 + 1.minutes),
            task("quick", AgentTask.Status.DONE, at = T0 + 80.seconds),
            task("new", AgentTask.Status.WORKING, at = T0 + 2.minutes),
            task("yesterday", AgentTask.Status.FAILED, at = since - 1.minutes),
        )
        val merged = mergeTasks(server, live, since)
        assertEquals(listOf("t1", "quick", "new"), merged.map { it.id.value })
        assertEquals(AgentTask.Status.DONE, merged[0].status)
        assertEquals(listOf("t1"), mergeTasks(server, live).map { it.id.value }, "without a day start nothing is added")

        val doing = doingRows(merged, emptyMap(), T0 + 3.minutes)
        assertEquals(listOf("new"), doing.main.map { it.task.id.value })
        assertEquals(listOf("quick", "t1"), doing.inactive.map { it.task.id.value })
    }

    @Test fun localDayStartsAtMidnightInTheZone() {
        val ny = kotlinx.datetime.TimeZone.of("America/New_York")
        assertEquals(kotlin.time.Instant.parse("2026-10-26T04:00:00Z"), startOfLocalDay(T0, ny))
        assertEquals(kotlin.time.Instant.parse("2026-10-25T04:00:00Z"), startOfLocalDay(kotlin.time.Instant.parse("2026-10-26T03:00:00Z"), ny))
    }

    @Test fun textsMatchThePrototype() {
        assertEquals("Monday, October 26, 10:04 am", dateKicker(LocalDate(2026, 10, 26), LocalTime(10, 4)))
        assertEquals("Nothing waiting", heldLine(0))
        assertEquals("2 waiting", heldLine(2))
    }
}

private val Doing.rows: List<DoingRow> get() = main + inactive

class DoingGroupsTest {
    private fun groups(vararg tasks: AgentTask, held: Map<HeldActionId, HeldAction> = emptyMap(), now: kotlin.time.Instant = T0) =
        doingRows(tasks.toList(), held, now).let { d -> d.main.map { it.task.id.value } to d.inactive.map { it.task.id.value } }

    @Test fun groupsGoNeedsYouRunningScheduledNewestFirstWithin() {
        val (main, quiet) = groups(
            task("sched-old", AgentTask.Status.SCHEDULED, at = T0 - 3.hours),
            task("run-old", AgentTask.Status.WORKING, at = T0 - 2.hours),
            task("review-old", AgentTask.Status.DRAFTED, "h1", at = T0 - 1.hours),
            task("run-new", AgentTask.Status.WORKING, at = T0 - 1.minutes),
            task("sched-new", AgentTask.Status.SCHEDULED, at = T0 - 5.minutes),
            task("review-new", AgentTask.Status.WAITING_FOR_YOU, "h2", at = T0 - 10.minutes),
        )
        assertEquals(listOf("review-new", "review-old", "run-new", "run-old", "sched-new", "sched-old"), main)
        assertEquals(emptyList(), quiet)
    }

    @Test fun aFreshQuestionWithoutAHeldActionStaysOnTopUntilItAges() {
        val asked = task("asked", AgentTask.Status.WAITING_FOR_YOU, at = T0 - 1.hours)
        val (main, _) = groups(asked, task("run", AgentTask.Status.WORKING, at = T0))
        assertEquals(listOf("asked", "run"), main, "a fresh question sits with the needs-you group")
        val rows = doingRows(listOf(asked), emptyMap(), T0).main
        assertEquals(DoingGroup.NEEDS_YOU, rows.single().group)
        assertNull(rows.single().review, "but it has nothing to review")

        val later = T0 - 1.hours + INACTIVE_AFTER
        assertEquals(emptyList<String>() to listOf("asked"), groups(asked, now = later))
        assertEquals(listOf("asked") to emptyList(), groups(asked, now = later - 1.minutes))
    }

    @Test fun waitingOrDraftedWithASettledHeldActionGoesInactiveAfterTwoDays() {
        val held = mapOf(HeldActionId("h1") to heldAction("h1", status = HeldAction.Status.EXECUTED))
        val (main, inactive) = groups(
            task("nick", AgentTask.Status.WAITING_FOR_YOU, at = T0 - 3.days),
            task("drafted", AgentTask.Status.DRAFTED, "h1", at = T0 - INACTIVE_AFTER),
            task("fresh", AgentTask.Status.WAITING_FOR_YOU, at = T0 - INACTIVE_AFTER + 1.minutes),
            held = held,
        )
        assertEquals(listOf("fresh"), main)
        assertEquals(listOf("drafted", "nick"), inactive, "newest first")
    }

    @Test fun aLiveHeldActionAlwaysShowsInTheMainList() {
        val (main, inactive) = groups(task("old-review", AgentTask.Status.DRAFTED, "h1", at = T0 - 9.days), held = mapOf(HeldActionId("h1") to heldAction("h1")))
        assertEquals(listOf("old-review"), main)
        assertEquals(emptyList(), inactive)
    }

    @Test fun runningWorkIsInactiveAfterTwoDaysAndHiddenAfterFourButScheduledNeverAges() {
        val (main, inactive) = groups(
            task("stuck", AgentTask.Status.WORKING, at = T0 - INACTIVE_AFTER),
            task("busy", AgentTask.Status.WORKING, at = T0 - INACTIVE_AFTER + 1.minutes),
            task("gone", AgentTask.Status.WORKING, at = T0 - HIDDEN_AFTER),
            task("gone-waiting", AgentTask.Status.WAITING_FOR_YOU, at = T0 - HIDDEN_AFTER - 1.hours),
            task("last-before-hidden", AgentTask.Status.WORKING, at = T0 - HIDDEN_AFTER + 1.minutes),
            task("next-week", AgentTask.Status.SCHEDULED, at = T0 - 10.days),
        )
        assertEquals(listOf("busy", "next-week"), main)
        assertEquals(listOf("stuck", "last-before-hidden"), inactive)
    }

    @Test fun failedStaysInTheMainListOnTheDayItFailedThenGoesInactive() {
        val failedToday = task("today", AgentTask.Status.FAILED, at = T0 - 8.hours) // 01:00 the same local day
        val failedYesterday = task("yesterday", AgentTask.Status.FAILED, at = T0 - 10.hours) // 23:00 the day before
        val doing = doingRows(listOf(failedYesterday, failedToday, task("run", AgentTask.Status.WORKING, at = T0)), emptyMap(), T0)
        assertEquals(listOf("today", "run"), doing.main.map { it.task.id.value }, "failed sits above running")
        assertEquals(DoingGroup.FAILED, doing.main[0].group)
        assertEquals(listOf("yesterday"), doing.inactive.map { it.task.id.value })
        assertEquals(InactiveState.FAILED, inactiveState(failedYesterday))
    }

    @Test fun failedFollowsTheLocalDayNotUtc() {
        val ny = kotlinx.datetime.TimeZone.of("America/New_York")
        // 09:00Z is 05:00 in New York; 03:00Z was 23:00 the evening before there.
        val doing = doingRows(listOf(task("late", AgentTask.Status.FAILED, at = T0 - 6.hours)), emptyMap(), T0, ny)
        assertEquals(emptyList(), doing.main)
        assertEquals(listOf("late"), doing.inactive.map { it.task.id.value })
    }

    @Test fun doneAndCancelledGoInactiveAndAgeOutAfterTheKeptWindow() {
        val kept = keptSince(T0, kotlinx.datetime.TimeZone.UTC)
        assertEquals(kotlin.time.Instant.parse("2026-10-24T00:00:00Z"), kept)
        val doing = doingRows(
            listOf(
                task("done", AgentTask.Status.DONE, at = T0),
                task("cancelled", AgentTask.Status.CANCELLED, at = T0 - 2.minutes),
                task("two-days", AgentTask.Status.DONE, at = kept),
                task("too-old", AgentTask.Status.DONE, at = kept - 1.minutes),
                task("failed-too-old", AgentTask.Status.FAILED, at = kept - 1.minutes),
                // A settled task keeps no Review button, even with a held action still waiting.
                task("done-held", AgentTask.Status.DONE, "h1", at = T0 - 3.minutes),
            ),
            mapOf(HeldActionId("h1") to heldAction("h1")),
            T0,
        )
        assertEquals(emptyList(), doing.main)
        assertEquals(listOf("done", "cancelled", "done-held", "two-days"), doing.inactive.map { it.task.id.value })
        assertTrue(doing.inactive.all { it.group == DoingGroup.INACTIVE && it.review == null })
        assertFalse(Doing(inactive = doing.inactive).isEmpty, "inactive rows alone still show the fold")
    }

    @Test fun inactiveStatesFollowTheTaskStatus() {
        assertEquals(InactiveState.DONE, inactiveState(task("a", AgentTask.Status.DONE)))
        assertEquals(InactiveState.CANCELLED, inactiveState(task("a", AgentTask.Status.CANCELLED)))
        assertEquals(InactiveState.STALE, inactiveState(task("a", AgentTask.Status.WORKING)))
    }

    @Test fun theFoldLineIsJustTheCount() {
        assertEquals("6 inactive", inactiveLine(4, 2))
        assertEquals("1 inactive", inactiveLine(1, 0))
        assertEquals("3 inactive", inactiveLine(0, 3))
        assertNull(inactiveLine(0, 0))
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
        val vm = todayVm(repo, feed, clock)
        val s = vm.state.value
        assertEquals(today, s.today)
        assertEquals(HeldActionId("h1"), s.doing.main.single().review)
        assertEquals(1, s.heldWaiting)
        assertFalse(s.loading)
        assertTrue(s.kicker.startsWith("Monday, October 26"))
    }

    @Test fun staleTasksFoldIntoInactiveByTheInjectedClock() = withMain {
        val today = Today(
            LocalDate(2026, 10, 26),
            doing = listOf(
                task("old", AgentTask.Status.WAITING_FOR_YOU, at = T0 - 3.days),
                task("run", AgentTask.Status.WORKING, at = T0 - 1.hours),
            ),
        )
        val vm = todayVm(FakeToday(ApiResult.Success(today)), ScreenFeed(), clock)
        assertEquals(listOf("run"), vm.state.value.doing.main.map { it.task.id.value })
        assertEquals(listOf("old"), vm.state.value.doing.inactive.map { it.task.id.value })
    }

    @Test fun anOlderServerWithoutTodayIsNotAnError() = withMain {
        val vm = todayVm(FakeToday(ApiResult.Failure(ApiError.NotFound(null))), ScreenFeed(), clock)
        assertTrue(vm.state.value.unavailable)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }

    @Test fun otherFailuresShowAMessage() = withMain {
        val vm = todayVm(FakeToday(ApiResult.Failure(ApiError.Unreachable("down"))), ScreenFeed(), clock)
        assertNotNull(vm.state.value.error)
    }

    @Test fun taskChangesInTheFeedUpdateRowsAndRefetch() = withMain {
        val today = Today(LocalDate(2026, 10, 26), doing = listOf(task("t1", AgentTask.Status.WORKING)))
        val repo = FakeToday(ApiResult.Success(today))
        val feed = ScreenFeed()
        val vm = todayVm(repo, feed, clock)
        assertEquals(1, repo.calls)
        feed.tasksFlow.value = listOf(task("t1", AgentTask.Status.DRAFTED, at = T0 + 1.minutes))
        assertEquals(AgentTask.Status.DRAFTED, vm.state.value.doing.main.single().task.status)
        assertEquals(2, repo.calls, "a task event refreshes Today")
    }

    @Test fun aTaskThatFinishesOrStartsLiveShowsWithoutARefresh() = withMain {
        val today = Today(LocalDate(2026, 10, 26), doing = listOf(task("t1", AgentTask.Status.WORKING, at = T0 - 1.hours)))
        val repo = FakeToday(ApiResult.Success(today))
        val feed = ScreenFeed()
        val vm = todayVm(repo, feed, clock)
        // The refetch returns the same old list; the live rows must stay anyway.
        feed.tasksFlow.value = listOf(
            task("t1", AgentTask.Status.DONE, at = T0 - 1.minutes),
            task("helper", AgentTask.Status.FAILED, at = T0 - 30.seconds),
            task("fresh", AgentTask.Status.WORKING, at = T0),
            task("last-week", AgentTask.Status.DONE, at = T0 - 7.days),
        )
        val doing = vm.state.value.doing
        assertEquals(listOf("helper", "fresh"), doing.main.map { it.task.id.value }, "a task that failed today needs a look")
        assertEquals(listOf("t1"), doing.inactive.map { it.task.id.value })
    }

    @Test fun heldBadgeFollowsTheLiveFeed() = withMain {
        val repo = FakeToday(ApiResult.Success(Today(LocalDate(2026, 10, 26), heldCount = 0)))
        val feed = ScreenFeed()
        val vm = todayVm(repo, feed, clock)
        feed.heldFlow.value = listOf(heldAction("h1"), heldAction("h2"))
        assertEquals(2, vm.state.value.heldWaiting)
    }

    @Test fun pullToRefreshFetchesAgain() = withMain {
        val repo = FakeToday()
        val vm = todayVm(repo, ScreenFeed(), clock)
        vm.refresh()
        assertEquals(2, repo.calls)
        assertFalse(vm.state.value.refreshing)
    }
}

/** Sessions by id; [get] counts its lookups and fails for ids in [failing]. */
private class SessionsById(vararg sessions: Session) : SessionsRepository {
    val byId = sessions.associateBy { it.id }
    val lookups = mutableListOf<SessionId>()
    val failing = mutableSetOf<SessionId>()
    override suspend fun start(kind: SessionKind, heldActionId: HeldActionId?) = ApiResult.Failure(ApiError.NotFound(null))
    override suspend fun get(id: SessionId): ApiResult<Session> {
        lookups += id
        if (id in failing) return ApiResult.Failure(ApiError.Unreachable("x"))
        return byId[id]?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.NotFound(null))
    }
    override suspend fun control(id: SessionId, control: SessionControl) = ApiResult.Failure(ApiError.NotFound(null))
    override suspend fun active(kind: SessionKind): ApiResult<Session?> = ApiResult.Success(null)
}

private fun topicMarker(seq: Long, title: String) =
    net.robog.clarity.shared.feed.FeedItem(net.robog.clarity.shared.feed.FeedItemId("i$seq"), seq, T0, net.robog.clarity.shared.feed.FeedItem.Author.AGENT, cards = emptyList(), topic = title)

// T0 is 9:00 UTC.
private val morning = callSession(id = "m1", state = Session.State.ENDED)

class AskRulesTest {
    @Test fun askHidesSessionItemsUntilTheirKindIsKnown() {
        val items = listOf(agentItem(2, "answer"), agentItem(1, "in call", SessionId("s")), userItem(0, "question"))
        assertEquals(listOf(0L, 2L), askItems(items).map { it.seq })
    }

    @Test fun askShowsMorningCallItemsInOrder() {
        val items = listOf(agentItem(3, "after"), agentItem(2, "in call", morning.id), userItem(1, "said in call", morning.id), userItem(0, "before"))
        assertEquals(listOf(0L, 1L, 2L, 3L), askItems(items, mapOf(morning.id to morning)).map { it.seq })
    }

    @Test fun everySessionKindRunsOnTheLeadThreadSoAllShow() {
        val kinds = SessionKind.entries.map { kind -> callSession(id = kind.name, kind = kind) }
        val items = kinds.mapIndexed { i, s -> agentItem(i.toLong(), "x", s.id) }
        assertEquals(kinds.size, askItems(items, kinds.associateBy { it.id }).size)
        assertEquals(SessionKind.entries.toSet(), AskSessionKinds)
    }

    @Test fun sessionIdsAreTheDistinctSessionsOfTheItems() {
        val items = listOf(agentItem(0, "a"), agentItem(1, "b", SessionId("s")), agentItem(2, "c", SessionId("s")), agentItem(3, "d", SessionId("t")))
        assertEquals(setOf(SessionId("s"), SessionId("t")), sessionIds(items))
    }

    @Test fun sessionLabelsNameTheMeetingAndItsTime() {
        val today = LocalDate(2026, 10, 26)
        assertEquals("Morning meeting, 9:00 am", sessionLabel(morning, TimeZone.UTC, today))
        assertEquals("Morning meeting, Oct 26 at 9:00 am", sessionLabel(morning, TimeZone.UTC, LocalDate(2026, 10, 27)))
        assertEquals("Values interview, 9:00 am", sessionLabel(morning.copy(kind = SessionKind.VALUES_INTERVIEW), TimeZone.UTC, today))
        assertEquals("Morning meeting, 5:00 am", sessionLabel(morning, TimeZone.of("America/New_York"), today))
    }

    @Test fun aMeetingGetsAPillAndTheMainChatResumesWithoutOne() {
        val sessions = mapOf(morning.id to morning)
        val items = askItems(listOf(userItem(0, "before"), agentItem(1, "call", morning.id), userItem(2, "call", morning.id), agentItem(3, "after")), sessions)
        val entries = askEntries(items, sessions, TimeZone.UTC, LocalDate(2026, 10, 26))
        assertEquals(
            listOf(
                ChatEntry.Message(items[0]),
                ChatEntry.Pill("start-m1-1", "Morning meeting, 9:00 am"),
                ChatEntry.Message(items[1]),
                ChatEntry.Message(items[2]),
                ChatEntry.Message(items[3]),
            ),
            entries,
        )
        assertEquals(entries.size, entries.map { it.key }.distinct().size, "keys are unique")
    }

    @Test fun noPillsWithoutSessionsOrTopics() {
        val items = listOf(userItem(0, "a"), agentItem(1, "b"))
        assertEquals(items.map { ChatEntry.Message(it) }, askEntries(items, emptyMap(), TimeZone.UTC, LocalDate(2026, 10, 26)))
    }

    @Test fun aTopicPillHeadsTheStretchItTitles() {
        val sessions = mapOf(morning.id to morning)
        val items = listOf(
            userItem(0, "book ulta"), agentItem(1, "booked"), topicMarker(2, "Ulta Hair Appt"),
            agentItem(3, "call", morning.id), topicMarker(4, "Dana Contract"),
            userItem(5, "not compacted yet"),
        )
        val entries = askEntries(askItems(items, sessions), sessions, TimeZone.UTC, LocalDate(2026, 10, 26))
        assertEquals(
            listOf(
                ChatEntry.Pill("topic-2", "Ulta Hair Appt"),
                ChatEntry.Message(items[0]),
                ChatEntry.Message(items[1]),
                ChatEntry.Pill("topic-4", "Dana Contract"),
                ChatEntry.Pill("start-m1-3", "Morning meeting, 9:00 am"),
                ChatEntry.Message(items[3]),
                ChatEntry.Message(items[5]),
            ),
            entries,
        )
    }

    @Test fun aTopicWithNothingBeforeItShowsNoPill() {
        // Two compactions back to back, or a cache that starts at the marker.
        val items = listOf(topicMarker(0, "Old"), topicMarker(1, "Older"), userItem(2, "hi"))
        assertEquals(listOf(ChatEntry.Message(items[2])), askEntries(items, emptyMap(), TimeZone.UTC, LocalDate(2026, 10, 26)))
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
    private class Rig(val sessions: SessionsById = SessionsById(morning)) {
        val feed = ScreenFeed()
        val speech = ScreenSpeech()
        val gate = RecordingGate()
        val vm = AskViewModel(feed, gate, speech, net.robog.clarity.app.ui.held.AlwaysAllow(FakeAmendments()), sessions, FixedClock(T0))
        val state get() = vm.state.value
    }

    @Test fun aMorningCallShowsInAskUnderItsHeader() = withMain {
        val r = Rig()
        r.feed.itemsFlow.value = listOf(userItem(0, "hi"), agentItem(1, "Morning.", morning.id))
        assertEquals(listOf(0L, 1L), r.state.items.map { it.seq })
        assertEquals(ChatEntry.Pill("start-m1-1", "Morning meeting, 9:00 am"), r.state.entries[1])
    }

    @Test fun eachSessionIsLookedUpOnce() = withMain {
        val r = Rig()
        r.feed.itemsFlow.value = listOf(agentItem(1, "Morning.", morning.id))
        r.feed.itemsFlow.value = r.feed.itemsFlow.value + userItem(2, "ok", morning.id) + agentItem(3, "a")
        assertEquals(listOf(morning.id), r.sessions.lookups)
    }

    @Test fun aFailedLookupKeepsTheItemsHiddenAndIsTriedAgain() = withMain {
        val r = Rig()
        r.sessions.failing += morning.id
        r.feed.itemsFlow.value = listOf(agentItem(1, "Morning.", morning.id))
        assertTrue(r.state.items.isEmpty())
        r.sessions.failing.clear()
        r.feed.itemsFlow.value = r.feed.itemsFlow.value + agentItem(2, "other", SessionId("gone"))
        assertEquals(listOf(1L), r.state.items.map { it.seq })
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
