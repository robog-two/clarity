package net.robog.clarity.app.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import net.robog.clarity.app.data.feed.Arrival
import net.robog.clarity.app.data.feed.SyncState
import net.robog.clarity.app.nav.Amend
import net.robog.clarity.app.nav.ConstitutionView
import net.robog.clarity.app.nav.DeepLink
import net.robog.clarity.app.nav.DraftReview
import net.robog.clarity.app.nav.Main
import net.robog.clarity.app.nav.MainTab
import net.robog.clarity.app.nav.Onboarding
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.agentItem
import net.robog.clarity.app.testutil.heldAction
import net.robog.clarity.app.testutil.userItem
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.Choice
import net.robog.clarity.shared.feed.FeedItemId
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.Urgency
import net.robog.clarity.shared.session.SessionId

private val NOW = T0 + 1.minutes

private fun item(notify: String, seq: Long = 7, text: String = "Dana needs the contract by noon.") =
    agentItem(seq, text).copy(notify = notify)

private fun urgentHeld(id: String = "h1") = heldAction(id).copy(urgency = Urgency.URGENT)

private fun plan(arrival: Arrival, front: Front = Front.Background, canPost: Boolean = true) =
    decideNotification(arrival, front, canPost, NOW)

class ServiceDecisionTest {
    @Test fun runsOnlyWhenEnrolledSetUpAndSwitchedOn() {
        for (server in listOf(true, false)) for (setUp in listOf(true, false)) for (on in listOf(true, false)) {
            assertEquals(server && setUp && on, PushConditions(server, setUp, on).shouldRun, "server=$server setUp=$setUp on=$on")
        }
    }

    @Test fun forgettingTheServerStopsIt() {
        assertFalse(PushConditions(hasServer = false, setUp = true, enabled = true).shouldRun)
    }

    @Test fun startAndStopFollowTheConditionsWithoutRestarting() {
        assertEquals(ServiceAction.Start, serviceAction(shouldRun = true, running = false))
        assertEquals(ServiceAction.None, serviceAction(shouldRun = true, running = true))
        assertEquals(ServiceAction.Stop, serviceAction(shouldRun = false, running = true))
        assertEquals(ServiceAction.None, serviceAction(shouldRun = false, running = false))
    }

    @Test fun theBatteryExplanationIsOfferedOnceAndOnlyWhenRestricted() {
        assertTrue(shouldAskBattery(exempt = false, alreadyAsked = false))
        assertFalse(shouldAskBattery(exempt = false, alreadyAsked = true))
        assertFalse(shouldAskBattery(exempt = true, alreadyAsked = false))
    }

    @Test fun statusLineNamesTheServer() {
        assertEquals("Connected to clarity.example", statusText(SyncState.Live, "clarity.example"))
        assertEquals("Offline. Reconnecting.", statusText(SyncState.Offline, "clarity.example"))
        assertEquals("clarity.example rejected this phone. Set it up again.", statusText(SyncState.Unauthorized, "clarity.example"))
        assertEquals("your server", hostOf(null))
        assertEquals("clarity.example", hostOf("https://clarity.example/"))
    }
}

class ReconnectPolicyTest {
    @Test fun aNetworkThatAppearsWhileNotLiveReconnectsAtOnce() {
        for (state in listOf(SyncState.Connecting, SyncState.Offline, SyncState.Unauthorized, SyncState.Unavailable)) {
            assertTrue(reconnectOn(NetworkEvent.Available("wifi"), state, previousNetwork = null), "$state")
        }
    }

    @Test fun theSameNetworkWhileLiveChangesNothing() {
        assertFalse(reconnectOn(NetworkEvent.Available("wifi"), SyncState.Live, previousNetwork = "wifi"))
        assertFalse(reconnectOn(NetworkEvent.Available("wifi"), SyncState.Live, previousNetwork = null))
    }

    @Test fun aDifferentNetworkWhileLiveRestartsADeadSocket() {
        assertTrue(reconnectOn(NetworkEvent.Available("mobile"), SyncState.Live, previousNetwork = "wifi"))
    }

    @Test fun losingANetworkNeverReconnects() {
        for (state in SyncState.entries) assertFalse(reconnectOn(NetworkEvent.Lost, state, previousNetwork = "wifi"))
    }

    @Test fun theReconnectorRemembersTheNetworkBetweenEvents() {
        var state = SyncState.Live
        var reconnects = 0
        val r = NetworkReconnector({ state }, { reconnects++ })
        r.onEvent(NetworkEvent.Available("wifi"))
        assertEquals(0, reconnects, "first network while live")
        r.onEvent(NetworkEvent.Available("mobile"))
        assertEquals(1, reconnects, "wifi to mobile")
        r.onEvent(NetworkEvent.Lost)
        state = SyncState.Offline
        r.onEvent(NetworkEvent.Available("mobile"))
        assertEquals(2, reconnects, "back online while the feed is down")
    }
}

class NotificationDecisionTest {
    @Test fun pushNowAlwaysNotifiesAReplyOnlyWhileTheAppIsClosedAndQuietItemsAndHoldsNever() {
        val elsewhere = Front.Showing(Place.Tab(MainTab.Settings))
        assertNotNull(plan(Arrival.Item(item("push_now")), elsewhere))
        assertNull(plan(Arrival.Item(item("in_app")), elsewhere))
        assertNull(plan(Arrival.Item(agentItem(7, "old client item")), elsewhere), "an item without a stored decision is in-app")
        assertNull(plan(Arrival.Item(item("in_app"))), "a quiet item does not notify even with the app closed")
        assertNull(plan(Arrival.Item(agentItem(7, "old client item"))), "an old server's item without a decision is quiet too")
        assertNull(plan(Arrival.Item(item("hold_until:2026-10-26T12:00:00Z"))))
        assertNull(plan(Arrival.Item(item("reply")), elsewhere), "a reply waits for the app to be closed")
        assertNull(plan(Arrival.Item(item("reply")), Front.Showing(Place.Tab(MainTab.Ask))), "the feed shows it")
        assertNotNull(plan(Arrival.Item(item("reply"))), "a reply notifies while the app is closed")
    }

    @Test fun aRepliesNotificationIsAMessageThatLeadsToTheFeed() {
        val p = plan(Arrival.Item(item("reply")))!!
        assertEquals(PushChannel.Agent, p.channel)
        assertEquals(PlanStyle.Message, p.style)
        assertEquals(DeepLink.Today, p.link)
        // old enough to be stale: not worth a buzz
        assertNull(decideNotification(Arrival.Item(item("reply")), Front.Background, true, T0 + 3.hours))
    }

    @Test fun aChoicesCardNotifiesWithItsPrompt() {
        val asked = agentItem(11, "unused").copy(
            notify = "push_now",
            cards = listOf(Card.Choices("Ready to start the report?", listOf(Choice("Starting", "Starting"), Choice("Later", "Later")))),
        )
        assertEquals("Ready to start the report?", plan(Arrival.Item(asked))!!.text)
    }

    private val ready = listOf(Choice("Starting", "Starting now"), Choice("Later", "Later"))

    @Test fun anUnansweredChoicesCardCarriesItsItemAndOptionsOnThePlan() {
        val asked = agentItem(11, "unused").copy(notify = "push_now", cards = listOf(Card.Choices("Ready?", ready)))
        val p = plan(Arrival.Item(asked))!!
        assertEquals(asked.id, p.choicesItem)
        assertEquals(ready, p.choices)
        assertTrue(p.withoutChoices().choices.isEmpty())
        assertNull(p.withoutChoices().choicesItem)
    }

    @Test fun anAnsweredChoicesCardNeverNotifiesSoNoChips() {
        val asked = agentItem(11, "unused").copy(notify = "push_now", cards = listOf(Card.Choices("Ready?", ready, chosen = "Later")))
        assertNull(plan(Arrival.Item(asked)))
    }

    @Test fun aPlainMessageHasNoChips() {
        val p = plan(Arrival.Item(item("push_now")))!!
        assertNull(p.choicesItem)
        assertTrue(p.choices.isEmpty())
    }

    @Test fun anExactLabelIsATapOnItsOption() {
        val id = FeedItemId("f1")
        assertEquals(NotificationReply("Starting now", id), replyOf("Starting", ready, id))
        assertEquals(NotificationReply("Later", id), replyOf("later", ready, id))
        assertEquals(NotificationReply("Starting now", id), replyOf("  STARTING \n", ready, id))
    }

    @Test fun labelsThatMatchOnlyByCaseResolveToTheFirstOption() {
        val twins = listOf(Choice("Ok", "first"), Choice("OK", "second"))
        assertEquals(NotificationReply("first", FeedItemId("f1")), replyOf("ok", twins, FeedItemId("f1")))
        assertEquals(NotificationReply("second", FeedItemId("f1")), replyOf("OK", listOf(twins[1], twins[0]), FeedItemId("f1")))
    }

    private val chipsPlan = plan(Arrival.Item(agentItem(11, "unused").copy(notify = "push_now", cards = listOf(Card.Choices("Ready?", ready)))))!!
    private val chipsItem = chipsPlan.choicesItem!!

    @Test fun answeredRepostsOnlyWhatThisProcessPostedAndIsStillShowing() {
        assertEquals(ChipsAction.Repost, answeredAction(true, chipsPlan, chipsItem, chipsItem.value))
        assertEquals(ChipsAction.Nothing, answeredAction(false, chipsPlan, chipsItem, null), "swiped away stays away")
        assertEquals(ChipsAction.Nothing, answeredAction(true, chipsPlan, FeedItemId("other"), chipsItem.value), "another question")
        assertEquals(ChipsAction.Nothing, answeredAction(true, chipsPlan.withoutChoices(), chipsItem, null), "no chips already")
    }

    @Test fun answeredAfterARestartCancelsOnlyANotificationShowingThatQuestion() {
        assertEquals(ChipsAction.Cancel, answeredAction(true, null, chipsItem, chipsItem.value))
        assertEquals(ChipsAction.Nothing, answeredAction(true, null, chipsItem, "other"))
        assertEquals(ChipsAction.Nothing, answeredAction(true, null, chipsItem, null))
        assertEquals(ChipsAction.Nothing, answeredAction(false, null, chipsItem, chipsItem.value))
    }

    @Test fun aReplyOrRefreshRepostsCancelsOrLeavesAlone() {
        assertEquals(ChipsAction.Repost, refreshAction(true, chipsPlan))
        assertEquals(ChipsAction.Cancel, refreshAction(true, null))
        assertEquals(ChipsAction.Nothing, refreshAction(false, chipsPlan))
        assertEquals(ChipsAction.Nothing, refreshAction(false, null))
    }

    @Test fun anythingElseIsTypedWordsWithNoReplyTo() {
        val id = FeedItemId("f1")
        assertEquals(NotificationReply("Starting soon", null), replyOf("Starting soon", ready, id))
        assertEquals(NotificationReply("Starting", null), replyOf("Starting", ready, null))
        assertEquals(NotificationReply("hello", null), replyOf(" hello ", emptyList(), id))
        assertNull(replyOf("   ", ready, id))
        assertNull(replyOf(null, ready, id))
    }

    @Test fun aMessageOfTheRunningCallStillNotifiesWhileQuiet() {
        val p = decideNotification(Arrival.Item(item("in_app").copy(sessionId = SessionId("s1"))), Front.Showing(Place.Tab(MainTab.Settings)), true, T0, call = SessionId("s1"))
        assertNotNull(p)
        assertEquals(DeepLink.Call(), p.link)
    }

    @Test fun aMessageWhileClosedIsOneConversationWithAReplyForItsSession() {
        val p = plan(Arrival.Item(item("reply").copy(sessionId = SessionId("s1"))))!!
        assertEquals(PlanStyle.Message, p.style)
        assertEquals(MESSAGE_ID, p.id)
        assertEquals(SessionId("s1"), p.sessionId)
        assertEquals(MESSAGE_ID, plan(Arrival.Item(item("push_now", seq = 8)))!!.id)
    }

    @Test fun somethingToApproveOrALoginToEnterOnlyOpensTheApp() {
        val proposal = agentItem(9, "unused").copy(notify = "push_now", cards = listOf(Card.Proposal(HeldActionId("h1"))))
        val p = plan(Arrival.Item(proposal))!!
        assertEquals(PlanStyle.OpenApp, p.style)
        assertEquals(DeepLink.HeldItem(HeldActionId("h1")), p.link)
        assertEquals("Open Clarity to review it.", p.text)
        // the same notification as the urgent held action itself
        assertEquals(plan(Arrival.Held(urgentHeld("h1")))!!.id, p.id)
        val login = agentItem(10, "I need your Ulta login").copy(
            notify = "reply",
            cards = listOf(Card.CredentialRequest(net.robog.clarity.shared.vault.CredentialRequestId("r1")), Card.Text("I need your Ulta login")),
        )
        val q = plan(Arrival.Item(login))!!
        assertEquals(PlanStyle.OpenApp, q.style)
        assertEquals("I need your Ulta login", q.text)
    }

    @Test fun anItemWithTheEveningSpellingUsesTheLowChannel() {
        assertEquals(PushChannel.Evening, plan(Arrival.Item(item("evening_batch:2026-10-26T18:00:00Z")))!!.channel)
    }

    @Test fun pushNowItemsUseTheAgentChannelAndOpenToday() {
        val p = plan(Arrival.Item(item("push_now")))!!
        assertEquals(PushChannel.Agent, p.channel)
        assertEquals(DeepLink.Today, p.link)
        assertEquals("Dana needs the contract by noon.", p.text)
    }

    @Test fun anItemFromASessionOpensAsk() {
        val p = plan(Arrival.Item(item("push_now").copy(sessionId = SessionId("s1"))))!!
        assertEquals(DeepLink.Ask, p.link)
    }

    @Test fun itemsAreNotNotifiedWhileTheFeedIsOnScreen() {
        val arrival = Arrival.Item(item("push_now"))
        assertNull(plan(arrival, Front.Showing(Place.Tab(MainTab.Today))))
        assertNull(plan(arrival, Front.Showing(Place.Tab(MainTab.Ask))))
        assertNotNull(plan(arrival, Front.Showing(Place.Tab(MainTab.Held))))
        assertNotNull(plan(arrival, Front.Showing(Place.Tab(MainTab.Settings))))
        assertNotNull(plan(arrival, Front.Showing(Place.HeldItem(HeldActionId("h1")))))
        assertNotNull(plan(arrival, Front.Showing(Place.Other)))
        assertNotNull(plan(arrival, Front.Background))
    }

    @Test fun onlyUrgentHeldActionsThatWaitNotify() {
        for (urgency in Urgency.entries) for (status in HeldAction.Status.entries) {
            val held = heldAction().copy(urgency = urgency, status = status)
            val expected = urgency == Urgency.URGENT && status == HeldAction.Status.HELD
            assertEquals(expected, plan(Arrival.Held(held)) != null, "$urgency $status")
        }
    }

    @Test fun anUrgentHeldActionUsesTheUrgentChannelAndOpensItsReview() {
        val p = plan(Arrival.Held(urgentHeld("h9")))!!
        assertEquals(PushChannel.Urgent, p.channel)
        assertEquals(DeepLink.HeldItem(HeldActionId("h9")), p.link)
        assertEquals("Needs your OK", p.title)
        assertEquals("reply to dana@example.com", p.text)
    }

    @Test fun heldActionsAreNotNotifiedWhileTheyAreOnScreen() {
        val arrival = Arrival.Held(urgentHeld("h1"))
        assertNull(plan(arrival, Front.Showing(Place.Tab(MainTab.Held))))
        assertNull(plan(arrival, Front.Showing(Place.HeldItem(HeldActionId("h1")))))
        assertNotNull(plan(arrival, Front.Showing(Place.HeldItem(HeldActionId("other")))))
        assertNotNull(plan(arrival, Front.Showing(Place.Tab(MainTab.Today))))
        assertNotNull(plan(arrival, Front.Background))
    }

    @Test fun nothingIsPostedWhenNotificationsAreOff() {
        assertNull(plan(Arrival.Item(item("push_now")), canPost = false))
        assertNull(plan(Arrival.Held(urgentHeld()), canPost = false))
    }

    @Test fun staleArrivalsAreDropped() {
        val late = T0 + 3.hours
        assertNull(decideNotification(Arrival.Item(item("push_now")), Front.Background, true, late))
        assertNull(decideNotification(Arrival.Held(urgentHeld()), Front.Background, true, late))
        assertNotNull(decideNotification(Arrival.Item(item("push_now")), Front.Background, true, T0 + 1.hours))
    }

    @Test fun theUsersOwnWordsAndStreamingItemsNeverNotify() {
        assertNull(plan(Arrival.Item(userItem(3, "hi").copy(notify = "push_now"))))
        assertNull(plan(Arrival.Item(item("push_now").copy(streaming = true))))
    }

    @Test fun theSameThingGetsTheSameNotificationId() {
        assertEquals(plan(Arrival.Item(item("push_now")))!!.id, plan(Arrival.Item(item("push_now")))!!.id)
        assertEquals(plan(Arrival.Held(urgentHeld("h1")))!!.id, plan(Arrival.Held(urgentHeld("h1")))!!.id)
        // messages are one conversation; different evening summaries are not
        assertEquals(plan(Arrival.Item(item("push_now", seq = 7)))!!.id, plan(Arrival.Item(item("push_now", seq = 8)))!!.id)
        assertFalse(plan(Arrival.Item(item("evening_batch:x", seq = 7)))!!.id == plan(Arrival.Item(item("evening_batch:x", seq = 8)))!!.id)
        assertFalse(plan(Arrival.Held(urgentHeld("h1")))!!.id == plan(Arrival.Held(urgentHeld("h2")))!!.id)
    }

    @Test fun channelsHaveTheRightImportance() {
        assertEquals(ChannelImportance.High, importanceOf(PushChannel.Urgent))
        assertEquals(ChannelImportance.Default, importanceOf(PushChannel.Agent))
        assertEquals(ChannelImportance.Low, importanceOf(PushChannel.Evening))
        assertEquals(listOf("urgent", "agent", "evening"), PushChannel.entries.map { it.id })
    }

    @Test fun notifyValuesParse() {
        assertEquals(NotifyKind.PushNow, parseNotify("push_now"))
        assertEquals(NotifyKind.InApp, parseNotify("in_app"))
        assertEquals(NotifyKind.Reply, parseNotify("reply"))
        assertEquals(NotifyKind.InApp, parseNotify(null))
        assertEquals(NotifyKind.InApp, parseNotify("something_new"))
        assertEquals(NotifyKind.Hold, parseNotify("hold_until:2026-10-26T12:00:00Z"))
        assertEquals(NotifyKind.EveningBatch, parseNotify("evening_batch:2026-10-26T18:00:00Z"))
        assertEquals(NotifyKind.Released(kotlin.time.Instant.parse("2026-10-26T12:00:00Z")), parseNotify("released:2026-10-26T12:00:00Z"))
        assertEquals(NotifyKind.Released(null), parseNotify("released:garbled"))
    }

    @Test fun aReleasedHoldNotifiesLikePushNowWithItsAgeCountedFromTheRelease() {
        val elsewhere = Front.Showing(Place.Tab(MainTab.Settings))
        // sent three hours ago during protected time, released just now
        val released = item("released:$NOW").copy(at = NOW - 3.hours)
        val p = plan(Arrival.Item(released), elsewhere)!!
        assertEquals(PushChannel.Agent, p.channel)
        assertEquals(plan(Arrival.Item(item("push_now")), elsewhere)!!.channel, p.channel, "the channel push-now uses")
        assertEquals(PlanStyle.Message, p.style)
        assertNull(plan(Arrival.Item(item("released:${NOW - 3.hours}"))), "released long ago")
        assertNull(plan(Arrival.Item(released), Front.Showing(Place.Tab(MainTab.Today))), "the feed shows it")
        assertNull(plan(Arrival.Item(released), canPost = false))
    }

    @Test fun textIsTheFirstLineOfTheItemsOwnWordsAndIsShortened() {
        assertEquals("First line", itemText(agentItem(1, "\n First line \nsecond")))
        assertEquals("New from Clarity", itemText(agentItem(1, "x").copy(cards = listOf(Card.Chips("c", emptyList())))))
        val long = itemText(agentItem(1, "a".repeat(300)), limit = 20)
        assertEquals(20, long.length)
        assertTrue(long.endsWith("…"))
    }
}

class FrontScreenTest {
    @Test fun placeFollowsTheTopOfTheStackAndTheTab() {
        assertEquals(Place.Tab(MainTab.Held), placeFor(Main, MainTab.Held))
        assertEquals(Place.HeldItem(HeldActionId("h1")), placeFor(DraftReview("h1"), MainTab.Today))
        assertEquals(Place.HeldItem(HeldActionId("h2")), placeFor(Amend("h2"), MainTab.Today))
        assertEquals(Place.Other, placeFor(Amend(null), MainTab.Today))
        assertEquals(Place.Other, placeFor(ConstitutionView, MainTab.Today))
        assertEquals(Place.Other, placeFor(Onboarding, MainTab.Today))
        assertEquals(Place.Other, placeFor(null, MainTab.Today))
    }

    @Test fun backgroundUntilTheAppIsVisible() {
        val front = FrontScreen()
        assertEquals(Front.Background, front.front.value)
        front.showingTab(MainTab.Ask)
        assertEquals(Front.Background, front.front.value, "a place is not enough: the app must be on screen")
        front.appVisible(true)
        assertEquals(Front.Showing(Place.Tab(MainTab.Ask)), front.front.value)
        front.showing(Place.HeldItem(HeldActionId("h1")))
        assertEquals(Front.Showing(Place.HeldItem(HeldActionId("h1"))), front.front.value)
        front.appVisible(false)
        assertEquals(Front.Background, front.front.value)
    }
}
