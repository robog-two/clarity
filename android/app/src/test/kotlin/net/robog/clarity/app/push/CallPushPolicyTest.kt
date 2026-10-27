package net.robog.clarity.app.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import net.robog.clarity.app.data.feed.Arrival
import net.robog.clarity.app.nav.DeepLink
import net.robog.clarity.app.nav.MainTab
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.agentItem
import net.robog.clarity.app.testutil.userItem
import net.robog.clarity.shared.session.SessionId

private val NOW = T0 + 1.minutes
private val CALL = SessionId("call1")
private val inCall = Front.Showing(Place.Call(CALL))

/** An agent message of the call, as the server marks them all: in-app. */
private fun reply(seq: Long = 7, text: String = "Which of those matters most today?") =
    agentItem(seq, text, sessionId = CALL).copy(notify = "in_app")

private fun decide(front: Front, item: net.robog.clarity.shared.feed.FeedItem = reply(), call: SessionId? = CALL) =
    decideNotification(Arrival.Item(item), front, canPost = true, now = NOW, call = call)

/** One message of the call is delivered once: on the call screen, or as a notification. */
class CallPushPolicyTest {
    @Test fun aReplyIsNotNotifiedWhileTheCallIsOnScreen() {
        assertNull(decide(inCall))
        assertNull(decide(inCall, reply().copy(notify = "push_now")), "not even push-now: it is right there")
    }

    @Test fun aReplyNotifiesOnceTheCallIsLeftAndLeadsBackToIt() {
        val p = assertNotNull(decide(Front.Background))
        assertEquals(DeepLink.Call(), p.link)
        assertEquals(PlanStyle.Message, p.style)
        assertEquals(CALL, p.sessionId, "a reply from the notification goes to the call")
        assertEquals(PushChannel.Agent, p.channel)
    }

    @Test fun aReplyNotifiesWhileTheRestOfTheAppIsOpen() {
        // Swiped out of the call to check something in the app: the conversation goes on as notifications.
        for (place in listOf(Place.Tab(MainTab.Today), Place.Tab(MainTab.Ask), Place.Tab(MainTab.Held), Place.Other)) {
            assertEquals(DeepLink.Call(), decide(Front.Showing(place))?.link, "$place")
        }
    }

    @Test fun theCallScreenOfAnotherSessionDoesNotShowIt() {
        assertNotNull(decide(Front.Showing(Place.Call(SessionId("other")))))
        assertNotNull(decide(Front.Showing(Place.Call(null))))
    }

    @Test fun inAppMessagesOutsideTheCallStayQuietWhileTheAppOrCallIsOpen() {
        val ask = agentItem(8, "Done.").copy(notify = "in_app")
        assertNull(decide(inCall, ask))
        assertNull(decide(Front.Showing(Place.Tab(MainTab.Settings)), ask))
        assertNotNull(decide(Front.Background, ask))
    }

    @Test fun aSessionMessageWithNoCallGoingOnStillOpensAsk() {
        val p = assertNotNull(decide(Front.Background, call = null))
        assertEquals(DeepLink.Ask, p.link)
        assertNull(decide(Front.Showing(Place.Tab(MainTab.Today)), call = null), "in-app, with the app open")
    }

    @Test fun theFeedOnTodayAndAskDoesNotShowSessionItems() {
        val push = reply().copy(notify = "push_now")
        assertNotNull(decide(Front.Showing(Place.Tab(MainTab.Ask)), push, call = null))
        assertNull(decide(Front.Showing(Place.Tab(MainTab.Ask)), agentItem(9, "Hi").copy(notify = "push_now"), call = null))
    }

    @Test fun theCallScreenClaimsItsFinishedItemsOnly() {
        val items = listOf(
            reply(1),
            userItem(2, "the spec", CALL),
            reply(3).copy(streaming = true),
            agentItem(4, "Not in the call"),
            reply(5),
        )
        val seen = seenArrivals(inCall, items, emptyList()).map { (it as Arrival.Item).item.seq }
        assertEquals(listOf(1L, 2L, 5L), seen)
        assertEquals(listOf(4L), seenArrivals(Front.Showing(Place.Tab(MainTab.Ask)), items, emptyList()).map { (it as Arrival.Item).item.seq })
        assertEquals(emptyList(), seenArrivals(Front.Showing(Place.Call(null)), items, emptyList()))
    }

    @Test fun missedRepliesOfTheCallLeadBackToItToo() {
        val plans = decideMissed(listOf(Arrival.Item(reply())), Front.Background, canPost = true, now = NOW, since = null, call = CALL)
        assertEquals(listOf<DeepLink>(DeepLink.Call()), plans.map { it.link })
    }
}

class FrontScreenCallTest {
    @Test fun theCallScreenWinsWhileItIsInFront() {
        val front = FrontScreen()
        front.appVisible(true)
        front.showingTab(MainTab.Today)
        front.callShowing(true, CALL)
        assertEquals(inCall, front.front.value)
        front.callShowing(false, CALL)
        assertEquals(Front.Showing(Place.Tab(MainTab.Today)), front.front.value)
        front.appVisible(false)
        assertEquals(Front.Background, front.front.value)
    }
}
