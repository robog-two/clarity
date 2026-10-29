package net.robog.clarity.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import net.robog.clarity.app.data.feed.Arrival
import net.robog.clarity.app.data.feed.FeedSync
import net.robog.clarity.app.push.Front
import net.robog.clarity.app.push.Notified
import net.robog.clarity.app.push.NotifiedLedger
import net.robog.clarity.app.push.PushListener
import net.robog.clarity.app.push.decideNotification
import net.robog.clarity.app.push.notifiable
import net.robog.clarity.app.testutil.FakeBackend
import net.robog.clarity.app.testutil.FakeEventSource
import net.robog.clarity.app.testutil.FixedClock
import net.robog.clarity.app.testutil.InMemoryFeedStore
import net.robog.clarity.app.testutil.RecordingPushNotifier
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.agentItem
import net.robog.clarity.app.testutil.userItem
import net.robog.clarity.app.ui.ask.askEntries
import net.robog.clarity.app.ui.cards.reactionDescription
import net.robog.clarity.app.ui.components.ChatEntry
import net.robog.clarity.app.ui.components.chatEntries
import net.robog.clarity.shared.feed.FeedEvent
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.ItemReaction
import net.robog.clarity.shared.feed.Reaction

private fun reaction(seq: Long, to: FeedItem, kind: String) =
    agentItem(seq, "").copy(cards = emptyList(), reaction = ItemReaction(to.id, kind), sessionId = to.sessionId)

/** Clarity's tapbacks: drawn on the person's message, never a message or a notification of their own. */
class ReactionTest {
    private val ok = userItem(1, "ok thanks")
    private val reply = agentItem(2, "Moved it to 4pm.")
    private val done = userItem(3, "done!")

    @Test fun aReactionGoesOntoItsMessageAndIsNotAnEntryOfItsOwn() {
        val items = listOf(ok, reply, done, reaction(4, ok, "thumbs_up"))
        val expected = listOf(ChatEntry.Message(ok, Reaction.THUMBS_UP), ChatEntry.Message(reply), ChatEntry.Message(done))
        assertEquals(expected, askEntries(items, emptyMap(), TimeZone.UTC, LocalDate(2026, 10, 26)))
        assertEquals(expected, chatEntries(items))
    }

    @Test fun aLaterReactionReplacesTheEarlierOne() {
        val items = listOf(ok, reaction(2, ok, "thumbs_up"), done, reaction(4, ok, "heart"), reaction(5, done, "laugh"))
        assertEquals(
            listOf(ChatEntry.Message(ok, Reaction.HEART), ChatEntry.Message(done, Reaction.LAUGH)),
            chatEntries(items),
        )
    }

    @Test fun aReactionThisBuildDoesNotKnowShowsNothing() {
        assertEquals(listOf(ChatEntry.Message(ok)), chatEntries(listOf(ok, reaction(2, ok, "party"))))
    }

    @Test fun theScreenReaderHearsWhoReactedWithWhat() {
        assertEquals("Clarity reacted 👍", reactionDescription(Reaction.THUMBS_UP))
        assertEquals("Clarity reacted ❤️", reactionDescription(Reaction.HEART))
    }

    @Test fun aReactionNeverNotifiesEvenWithTheAppInTheBackground() {
        // An in-app agent item does notify in the background; a reaction (also in-app) does not.
        assertTrue(decideNotification(Arrival.Item(reply), Front.Background, canPost = true, now = T0 + 1.minutes) != null)
        assertNull(decideNotification(Arrival.Item(reaction(4, ok, "heart")), Front.Background, canPost = true, now = T0 + 1.minutes))
    }

    private class Ledger : NotifiedLedger {
        var state = Notified()
        override suspend fun claim(arrival: Arrival): Boolean {
            state = state.claim(arrival, "s") ?: return false
            return true
        }
    }

    @Test fun aReactionReachesAPhoneThatHasTheMessageByItsOwnSeqQuietly() = runTest {
        val backend = FakeBackend(pageSize = 10)
        val store = InMemoryFeedStore()
        val sync = FeedSync(backend, FakeEventSource(emptyList()), store)
        val notifier = RecordingPushNotifier()
        val listener = PushListener(sync.arrivals, { Front.Background }, { true }, FixedClock(T0), notifier, Ledger(), sync.caughtUp, { store.notifiable() })
        val live = launch(Dispatchers.Unconfined) { listener.run() }
        sync.apply(FeedEvent.ItemAppended(ok))
        val tapback = reaction(5, ok, "thumbs_up")
        sync.apply(FeedEvent.ItemAppended(tapback))
        live.cancel()

        assertEquals(5, store.lastSeq())
        assertEquals(ok, store.item(ok.id), "the message itself is untouched")
        assertEquals(listOf(ChatEntry.Message(ok, Reaction.THUMBS_UP)), chatEntries(store.recentItems(10)))
        assertTrue(notifier.posted.isEmpty())
        assertTrue(store.notifiable().isEmpty(), "nor a missed one")
    }
}
