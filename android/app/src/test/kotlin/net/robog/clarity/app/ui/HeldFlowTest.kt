package net.robog.clarity.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.testutil.FakeAmendments
import net.robog.clarity.app.testutil.FakeHeldRepo
import net.robog.clarity.app.testutil.RecordingGate
import net.robog.clarity.app.testutil.ScreenFeed
import net.robog.clarity.app.testutil.ScreenSpeech
import net.robog.clarity.app.testutil.ScriptedSessions
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.agentItem
import net.robog.clarity.app.testutil.callSession
import net.robog.clarity.app.testutil.amendment
import net.robog.clarity.app.testutil.emailDraft
import net.robog.clarity.app.testutil.heldAction
import net.robog.clarity.app.testutil.sendEmail
import net.robog.clarity.app.testutil.withMain
import net.robog.clarity.app.data.feed.SyncSignal
import net.robog.clarity.app.data.feed.SyncState
import net.robog.clarity.app.ui.amend.AmendViewModel
import net.robog.clarity.app.ui.amend.fallbackQuestion
import net.robog.clarity.app.ui.amend.proposalLabel
import net.robog.clarity.app.ui.amend.ruleSavedToast
import net.robog.clarity.app.ui.held.DraftReviewViewModel
import net.robog.clarity.app.ui.held.HeldViewModel
import net.robog.clarity.app.ui.held.allowedRows
import net.robog.clarity.app.ui.held.approvalFor
import net.robog.clarity.app.ui.held.checkedLine
import net.robog.clarity.app.ui.held.emailOf
import net.robog.clarity.app.ui.held.executedToast
import net.robog.clarity.app.ui.held.heldOutcomeToasts
import net.robog.clarity.app.ui.held.heldRowStatus
import net.robog.clarity.app.ui.held.heldRows
import net.robog.clarity.app.ui.held.orderedHeld
import net.robog.clarity.app.ui.held.recipientName
import net.robog.clarity.app.ui.held.withBody
import net.robog.clarity.app.voice.MicChipState
import net.robog.clarity.app.voice.SpeechEvent
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.ActivityEntry
import net.robog.clarity.shared.governance.Amendment
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.HeldDecision
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.SessionKind

private fun started(vararg p: Pair<SessionKind, HeldActionId?>) = p.toList()
private fun decided(vararg p: Pair<HeldActionId, HeldDecision>) = p.toList()

class HeldRulesTest {
    @Test fun editedApprovalCarriesTheNewBodyOnly() {
        val held = heldAction()
        assertEquals(HeldDecision.Approve(), approvalFor(held, emailOf(held.action)!!.body))
        val edited = approvalFor(held, "Hi Dana, Thursday works.")
        val action = edited.edited as ProposedAction.SendEmail
        assertEquals("Hi Dana, Thursday works.", action.email.body)
        assertEquals(held.action.idempotencyKey, action.idempotencyKey, "same action, new text")
    }

    @Test fun nonMailActionsNeverCarryAnEdit() {
        val move = ProposedAction.MoveEvent("k", "Move call", net.robog.clarity.shared.EntityId("e"), T0, T0, true)
        assertEquals(HeldDecision.Approve(), approvalFor(heldAction(action = move), "anything"))
        assertEquals(move, withBody(move, "x"))
    }

    @Test fun recipientIsTheFirstNameOrTheAddressStart() {
        assertEquals("Dana", recipientName(emailDraft()))
        assertEquals("pat", recipientName(emailDraft().copy(to = listOf(net.robog.clarity.shared.noun.Handle.email("pat@example.com")))))
        assertNull(recipientName(emailDraft().copy(to = emptyList())))
    }

    @Test fun toastsFollowTheActionKind() {
        assertEquals("Sent to Dana.", executedToast(heldAction()))
        assertEquals("Draft for Dana saved.", executedToast(heldAction(action = ProposedAction.SaveDraft("k", "s", emailDraft()))))
        assertEquals("Calendar updated.", executedToast(heldAction(action = ProposedAction.MoveEvent("k", "s", net.robog.clarity.shared.EntityId("e"), T0, T0, true))))
    }

    @Test fun executedTransitionTriggersTheToastOnce() {
        val before = listOf(heldAction("h1", status = HeldAction.Status.APPROVED))
        val after = listOf(heldAction("h1", status = HeldAction.Status.EXECUTED))
        assertEquals(listOf("Sent to Dana."), heldOutcomeToasts(before, after))
        assertTrue(heldOutcomeToasts(after, after).isEmpty())
        assertTrue(heldOutcomeToasts(emptyList(), after).isEmpty(), "an item first seen as executed is not news")
        assertEquals(1, heldOutcomeToasts(before, listOf(heldAction("h1", status = HeldAction.Status.FAILED))).size)
    }

    @Test fun waitingOnesComeFirstThenNewest() {
        val old = heldAction("a", createdAt = T0)
        val new = heldAction("b", createdAt = T0 + 5.minutes)
        val done = heldAction("c", status = HeldAction.Status.KEPT, createdAt = T0 + 9.minutes)
        assertEquals(listOf("b", "a", "c"), orderedHeld(listOf(old, done, new)).map { it.id.value })
    }

    @Test fun rowWordsMatchThePrototype() {
        assertEquals("→", heldRowStatus(HeldAction.Status.HELD))
        assertEquals("→", heldRowStatus(HeldAction.Status.BLOCKED))
        assertEquals("kept", heldRowStatus(HeldAction.Status.KEPT))
        assertEquals("rule updated", heldRowStatus(HeldAction.Status.RULE_UPDATED))
        val rows = heldRows(listOf(heldAction()), TimeZone.UTC)
        assertEquals("9:00 · send email", rows.single().whenText)
    }

    @Test fun emailDraftsOpenTheReviewScreenAndOtherRowsTheTalkThrough() {
        val draft = heldAction("d", action = sendEmail())
        val event = heldAction("e", action = ProposedAction.CreateEvent("k2", "add lunch", title = "Lunch", start = T0, end = T0 + 30.minutes))
        val done = heldAction("f", action = sendEmail(), status = HeldAction.Status.KEPT)
        val rows = heldRows(listOf(draft, event, done), TimeZone.UTC).associateBy { it.id.value }
        assertTrue(rows.getValue("d").review, "a draft opens the review (with approve); amend is one tap away from there")
        assertFalse(rows.getValue("e").review)
        assertFalse(rows.getValue("f").review, "a settled row has nothing to approve")
    }

    @Test fun checkedLineCountsTheLinesThatApply() {
        assertEquals("checked against your lines · 1 apply", checkedLine(heldAction()))
        assertEquals("checked against your lines · none apply", checkedLine(heldAction(lines = emptyList())))
    }

    @Test fun allowedActionsListNewestFirst() {
        val rows = allowedRows(
            listOf(
                ActivityEntry(T0, "held 9:30–12 for the spec", ActionKind.CREATE_EVENT, true),
                ActivityEntry(T0 + 4.minutes, "drafted reply to Dana", ActionKind.SAVE_DRAFT, false),
            ),
            TimeZone.UTC,
        )
        assertEquals(listOf("drafted reply to Dana", "held 9:30–12 for the spec"), rows.map { it.title })
        assertEquals("9:04", rows.first().whenText)
        assertFalse(rows.first().ok)
    }

    @Test fun proposalAndToastText() {
        assertEquals("change to a line · values", proposalLabel(amendment()))
        assertEquals("new line · values", proposalLabel(amendment().copy(lineId = null)))
        assertEquals("Rule saved. Constitution v2.", ruleSavedToast(2))
        assertEquals("What would you like to change?", fallbackQuestion(heldPresent = false))
    }
}

class HeldViewModelTest {
    @Test fun listsHeldAndCountsAllowedActions() = withMain {
        val feed = ScreenFeed()
        feed.heldFlow.value = listOf(heldAction("h1"), heldAction("h2", status = HeldAction.Status.KEPT))
        val repo = FakeHeldRepo(activity = ApiResult.Success(listOf(ActivityEntry(T0, "muted Slack", ActionKind.SET_DO_NOT_DISTURB, true))))
        val vm = HeldViewModel(feed, repo)
        val s = vm.state.value
        assertEquals(2, s.rows.size)
        assertEquals(1, s.waiting)
        assertEquals("1 held today · 1 allowed", s.kicker)
        assertFalse(s.showAllowed)
        vm.toggleAllowed()
        assertTrue(vm.state.value.showAllowed)
        assertEquals("muted Slack", vm.state.value.allowed.single().title)
    }

    @Test fun updatesWhenTheFeedChanges() = withMain {
        val feed = ScreenFeed()
        val vm = HeldViewModel(feed, FakeHeldRepo())
        assertEquals("nothing held today · 0 allowed", vm.state.value.kicker)
        feed.heldFlow.value = listOf(heldAction())
        assertEquals(1, vm.state.value.waiting)
    }
}

private class DraftRig(held: HeldAction = heldAction()) {
    val feed = ScreenFeed().also { it.heldFlow.value = listOf(held) }
    val repo = FakeHeldRepo(mapOf(held.id.value to held))
    val sessions = ScriptedSessions()
    val gate = RecordingGate()
    val speech = ScreenSpeech()
    val vm = DraftReviewViewModel(feed, repo, sessions, gate, speech)
    val state get() = vm.state.value
    init { vm.load(held.id) }
}

class DraftReviewViewModelTest {
    @Test fun opensFromTheCacheWithTheAgentsDraft() = withMain {
        val r = DraftRig()
        assertFalse(r.state.loading)
        assertEquals(emailDraft().body, r.state.body)
        assertEquals("Re: Revised Terms", r.state.email?.subject)
    }

    @Test fun fallsBackToTheServerWhenNotCached() = withMain {
        val held = heldAction("h9")
        val feed = ScreenFeed()
        val vm = DraftReviewViewModel(feed, FakeHeldRepo(mapOf("h9" to held)), ScriptedSessions(), RecordingGate(), ScreenSpeech())
        vm.load(held.id)
        assertEquals(held, vm.state.value.held)
    }

    @Test fun unknownItemShowsAMessage() = withMain {
        val vm = DraftReviewViewModel(ScreenFeed(), FakeHeldRepo(), ScriptedSessions(), RecordingGate(), ScreenSpeech())
        vm.load(HeldActionId("nope"))
        assertNull(vm.state.value.held)
        assertNotNull(vm.state.value.message)
        assertFalse(vm.state.value.loading)
    }

    @Test fun sendingTheUntouchedDraftApprovesAsIs() = withMain {
        val r = DraftRig()
        r.vm.approve()
        assertEquals(decided(HeldActionId("h1") to HeldDecision.Approve()), r.feed.decisions)
        assertTrue(r.state.finished)
    }

    @Test fun sendingAnEditedDraftApprovesTheEditedAction() = withMain {
        val r = DraftRig()
        r.vm.edit("Hi Dana,\n\nShorter, and Thursday.\n\nSam")
        r.vm.approve()
        val decision = r.feed.decisions.single().second as HeldDecision.Approve
        val action = decision.edited as ProposedAction.SendEmail
        assertEquals("Hi Dana,\n\nShorter, and Thursday.\n\nSam", action.email.body)
        assertEquals("Re: Revised Terms", action.email.subject)
    }

    @Test fun theGateIsAskedBeforeApprovingAndCanRefuse() = withMain {
        val r = DraftRig()
        r.gate.allow = false
        r.vm.approve()
        assertEquals(listOf(HeldActionId("h1")), r.gate.asked)
        assertTrue(r.feed.decisions.isEmpty())
        assertFalse(r.state.finished)
        assertEquals("Not approved.", r.state.message)
        assertFalse(r.state.busy)
    }

    @Test fun aServerErrorKeepsTheScreenOpen() = withMain {
        val r = DraftRig()
        r.feed.decideResult = ApiResult.Failure(ApiError.Unreachable("x"))
        r.vm.approve()
        assertFalse(r.state.finished)
        assertNotNull(r.state.message)
        assertFalse(r.state.busy)
    }

    @Test fun notNowDeclines() = withMain {
        val r = DraftRig()
        r.vm.decline()
        assertEquals(HeldDecision.Decline, r.feed.decisions.single().second)
    }

    @Test fun talkOpensAnAmendSessionOnThisActionAndListens() = withMain {
        val r = DraftRig()
        r.vm.talk()
        assertEquals(started(SessionKind.AMEND to HeldActionId("h1")), r.sessions.started)
        assertEquals(listOf("start(false)"), r.speech.calls)
        r.vm.talk() // the session is reused
        assertEquals(1, r.sessions.started.size)
    }

    @Test fun spokenRequestGoesToTheSessionAndTheButtonsWaitForTheRevision() = withMain {
        val r = DraftRig()
        r.vm.talk()
        val session = r.sessions.current!!
        r.speech.flow.tryEmit(SpeechEvent.Partial("make it shorter"))
        assertEquals("make it shorter", r.state.live)
        r.speech.flow.tryEmit(SpeechEvent.Final("make it shorter and say Thursday"))
        assertEquals(listOf(Triple<String, InputSource, SessionId?>("make it shorter and say Thursday", InputSource.VOICE, session.id)), r.feed.sent)
        assertTrue(r.state.busy, "waiting for the agent")

        // The agent revises: the held action updates and the field takes the new text.
        val revised = heldAction(action = sendEmail("Hi Dana,\n\nThe revised terms work. I’ll sign Thursday.\n\nSam"))
        r.feed.heldFlow.value = listOf(revised)
        assertEquals("Hi Dana,\n\nThe revised terms work. I’ll sign Thursday.\n\nSam", r.state.body)
        assertFalse(r.state.busy)
    }

    @Test fun anAgentReplyAlsoEndsTheWait() = withMain {
        val r = DraftRig()
        r.vm.talk()
        val session = r.sessions.current!!
        r.speech.flow.tryEmit(SpeechEvent.Final("hmm"))
        assertTrue(r.state.busy)
        r.feed.itemsFlow.value = r.feed.itemsFlow.value + agentItem(2000, "Shorter, and Thursday. Anything else?", session.id)
        assertFalse(r.state.busy)
        assertEquals(2, r.state.talk.size)
    }

    @Test fun leavingEndsTheConversation() = withMain {
        val r = DraftRig()
        r.vm.talk()
        r.vm.close()
        assertEquals(SessionControl.End, r.sessions.controls.last())
        assertTrue("stop" in r.speech.calls)
    }

    @Test fun anAlreadySettledActionCannotBeApprovedAgain() = withMain {
        val r = DraftRig(heldAction(status = HeldAction.Status.EXECUTED))
        r.vm.approve()
        assertTrue(r.feed.decisions.isEmpty())
        assertTrue(r.state.decided)
    }
}

private class AmendRig(heldFor: HeldAction? = heldAction(), val sessions: ScriptedSessions = ScriptedSessions()) {
    val feed = ScreenFeed().also { f -> heldFor?.let { f.heldFlow.value = listOf(it) } }
    val amendments = FakeAmendments()
    val toasts = Toasts()
    val speech = ScreenSpeech()
    val vm = AmendViewModel(sessions, feed, amendments, toasts, speech)
    val state get() = vm.state.value
    val heldId = heldFor?.id
    init {
        vm.load(heldId)
    }

    /** The agent proposes a change: the session gets an amendment id and the server lists it. */
    fun agentProposes() {
        val a = amendment()
        amendments.proposed = a
        sessions.current = sessions.current!!.copy(amendmentId = a.id)
        feed.signalFlow.tryEmit(SyncSignal.SessionChanged(sessions.current!!.id))
    }
}

class AmendViewModelTest {
    @Test fun startsAnAmendSessionForTheHeldAction() = withMain {
        val r = AmendRig()
        assertEquals(started(SessionKind.AMEND to HeldActionId("h1")), r.sessions.started)
        assertNotNull(r.state.session)
        assertNotNull(r.state.held)
        r.vm.listen()
        assertEquals(MicChipState.MicOn, r.state.mic)
    }

    @Test fun amendingTheConstitutionByTalkingHasNoHeldAction() = withMain {
        val r = AmendRig(heldFor = null)
        assertEquals(started(SessionKind.AMEND to null), r.sessions.started)
        assertNull(r.state.held)
    }

    @Test fun cannotSaveUntilTheAgentProposesAChange() = withMain {
        val r = AmendRig()
        assertFalse(r.state.canSave)
        r.vm.save()
        assertTrue(r.amendments.saved.isEmpty())
        r.agentProposes()
        assertTrue(r.state.canSave)
        assertEquals("No AI-style formatting in anything sent as me.", r.state.proposal?.old)
    }

    @Test fun spokenAnswerGoesToTheSessionAndTheAgentsReplyIsShown() = withMain {
        val r = AmendRig()
        val id = r.sessions.current!!.id
        r.vm.listen()
        r.speech.flow.tryEmit(SpeechEvent.Final("Fine for short scheduling replies."))
        assertEquals(InputSource.VOICE, r.feed.sent.single().second)
        assertEquals(id, r.feed.sent.single().third)
        assertTrue(r.state.waitingAgent)
        assertFalse(r.state.canKeep, "no decisions while the agent thinks")
        r.feed.itemsFlow.value = r.feed.itemsFlow.value + agentItem(3000, "Short confirmations only?", id)
        assertFalse(r.state.waitingAgent)
        assertEquals(2, r.state.talk.size)
    }

    @Test fun keepAsIsKeepsTheHeldActionsLineAndEndsTheTalk() = withMain {
        val r = AmendRig()
        r.agentProposes()
        r.vm.keep()
        assertEquals(decided(HeldActionId("h1") to HeldDecision.Keep), r.feed.decisions)
        assertEquals(listOf(amendment().id), r.amendments.discarded)
        assertEquals(SessionControl.End, r.sessions.controls.last())
        assertTrue(r.state.finished)
    }

    @Test fun savingTheRuleSavesTheAmendmentAndToastsTheNewVersion() = withMain {
        val r = AmendRig()
        r.agentProposes()
        val toasts = mutableListOf<String>()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            r.toasts.messages.collect { toasts += it }
        }
        r.vm.save()
        assertEquals(listOf(amendment().id), r.amendments.saved)
        assertEquals(listOf("Rule saved. Constitution v3."), toasts)
        assertTrue(r.state.finished)
        assertTrue(r.feed.decisions.isEmpty(), "the server moves the held action to rule_updated itself")
        job.cancel()
    }

    @Test fun aFailedSaveStaysOnTheScreen() = withMain {
        val r = AmendRig()
        r.agentProposes()
        r.amendments.saveFailure = ApiError.Http(409, null)
        r.vm.save()
        assertFalse(r.state.finished)
        assertNotNull(r.state.message)
        assertFalse(r.state.working)
    }

    @Test fun backingOutEndsTheSession() = withMain {
        val r = AmendRig()
        r.vm.close()
        assertEquals(SessionControl.End, r.sessions.controls.last())
    }

    @Test fun aSettledHeldActionStartsNoConversation() = withMain {
        val r = AmendRig(heldFor = heldAction(status = HeldAction.Status.KEPT))
        assertTrue(r.sessions.started.isEmpty())
        assertTrue(r.state.settled)
        assertFalse(r.state.canKeep)
        assertFalse(r.state.canSave)
    }

    @Test fun keepingWithoutAHeldActionJustEndsTheTalk() = withMain {
        val r = AmendRig(heldFor = null)
        r.vm.keep()
        assertTrue(r.feed.decisions.isEmpty())
        assertTrue(r.state.finished)
    }

    @Test fun reopeningPicksUpTheTalkAlreadyGoingWithItsProposal() = withMain {
        val a = amendment()
        val ongoing = callSession(kind = SessionKind.AMEND).copy(heldActionId = HeldActionId("h1"), amendmentId = a.id)
        val r = AmendRig(sessions = ScriptedSessions(active = ongoing)).also { it.amendments.proposed = a }
        r.vm.load(r.heldId) // no-op: already loading; the rig's own load adopted the session
        assertTrue(r.sessions.started.isEmpty(), "no new session replaces the one holding the proposal")
        assertEquals(ongoing.id, r.state.session?.id)
    }

    @Test fun aTalkAboutAnotherHeldActionIsNotPickedUp() = withMain {
        val other = callSession(kind = SessionKind.AMEND).copy(heldActionId = HeldActionId("other"))
        val r = AmendRig(sessions = ScriptedSessions(active = other))
        assertEquals(started(SessionKind.AMEND to HeldActionId("h1")), r.sessions.started)
    }

    @Test fun aProposalMissedWhileTheStreamWasDownShowsAfterReconnecting() = withMain {
        val r = AmendRig()
        val a = amendment()
        r.amendments.proposed = a
        r.sessions.current = r.sessions.current!!.copy(amendmentId = a.id) // its SessionChanged never arrived
        assertFalse(r.state.canSave)
        r.feed.syncFlow.value = SyncState.Offline
        r.feed.syncFlow.value = SyncState.Live
        assertTrue(r.state.canSave)
    }

    @Test fun aBusySessionDisablesBothButtons() = withMain {
        val r = AmendRig()
        r.agentProposes()
        r.sessions.current = r.sessions.current!!.copy(state = Session.State.BUSY)
        r.feed.signalFlow.tryEmit(SyncSignal.SessionChanged(r.sessions.current!!.id))
        assertFalse(r.state.canKeep)
        assertFalse(r.state.canSave)
    }
}
