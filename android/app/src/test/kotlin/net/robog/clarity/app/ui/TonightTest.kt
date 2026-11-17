package net.robog.clarity.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import net.robog.clarity.app.ui.components.ChatEntry
import net.robog.clarity.app.ui.components.chatEntries
import net.robog.clarity.app.ui.components.chatEntryIndex
import net.robog.clarity.app.ui.today.Tonight
import net.robog.clarity.app.ui.components.topOffset
import net.robog.clarity.app.ui.today.nextLocalMidnight
import net.robog.clarity.app.ui.today.tonightOf
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.Chip
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.FeedItemId

private val POSTED = Instant.parse("2026-10-26T18:00:00Z")
private val UTC = TimeZone.UTC

private fun summary(seq: Long, at: Instant = POSTED, vararg extra: Card, counts: String = "2 done.") = FeedItem(
    FeedItemId("s$seq"), seq, at, FeedItem.Author.AGENT,
    cards = listOf<Card>(Card.Text(counts)) + extra.ifEmpty { arrayOf(Card.Chips("Done today", listOf(Chip("File receipts")))) },
    notify = "evening_batch:2026-10-26T18:00:00Z",
)

private fun plain(seq: Long, at: Instant = POSTED) = FeedItem(FeedItemId("p$seq"), seq, at, FeedItem.Author.AGENT, cards = listOf(Card.Text("Hello.")))

class TonightTest {
    private val comingUp = Card.Chips("Coming up", listOf(Chip("Jazz at the Blue Room, Thursday 19:00"), Chip("Dinner with Sam Example")))

    @Test fun theCardHasTheCountsLineAndTheFirstThingComingUp() {
        val items = listOf(plain(1), summary(2, POSTED, comingUp, Card.Chips("Done today", listOf(Chip("File receipts"))), counts = "2 coming up. 1 done."))
        assertEquals(
            Tonight(FeedItemId("s2"), "2 coming up. 1 done.", "Jazz at the Blue Room, Thursday 19:00"),
            tonightOf(items, POSTED + 1.hours, UTC),
        )
    }

    @Test fun withNothingComingUpThereIsNoSecondLine() {
        val items = listOf(summary(2, POSTED, Card.Chips("Done today", listOf(Chip("File receipts")))))
        assertEquals(Tonight(FeedItemId("s2"), "2 done.", null), tonightOf(items, POSTED + 1.hours, UTC))
    }

    @Test fun noSummaryNoCard() {
        assertNull(tonightOf(emptyList(), POSTED, UTC))
        assertNull(tonightOf(listOf(plain(1)), POSTED, UTC), "only an evening summary counts")
    }

    @Test fun itGoesAtLocalMidnight() {
        val items = listOf(summary(2))
        assertEquals(FeedItemId("s2"), tonightOf(items, Instant.parse("2026-10-26T23:59:00Z"), UTC)?.itemId)
        assertNull(tonightOf(items, Instant.parse("2026-10-27T00:00:00Z"), UTC))
        // the day is the person's: 18:00 UTC is already the 27th in Auckland, so it ended there at local midnight
        val auckland = TimeZone.of("Pacific/Auckland")
        assertEquals(FeedItemId("s2"), tonightOf(items, Instant.parse("2026-10-26T18:30:00Z"), auckland)?.itemId)
        assertNull(tonightOf(items, Instant.parse("2026-10-27T11:00:00Z"), auckland))
    }

    @Test fun theNewestSummaryOfTheDayWins() {
        val items = listOf(summary(2), summary(5, POSTED + 1.hours))
        assertEquals(FeedItemId("s5"), tonightOf(items, POSTED + 2.hours, UTC)?.itemId)
    }

    @Test fun aSummaryStillStreamingOrWithoutWordsIsSkipped() {
        val streaming = summary(2).copy(streaming = true)
        val bare = summary(3).copy(cards = emptyList())
        assertNull(tonightOf(listOf(streaming, bare), POSTED, UTC))
    }

    @Test fun aHeldFindingWithTheEveningDecisionIsNotTheSummary() {
        val finding = plain(4).copy(notify = "evening_batch:2026-10-26T18:00:00Z")
        assertNull(tonightOf(listOf(finding), POSTED, UTC))
        assertEquals(FeedItemId("s2"), tonightOf(listOf(summary(2), finding), POSTED, UTC)?.itemId)
    }

    @Test fun releasedAndHeldItemsAreNotTheSummary() {
        val chips = Card.Chips("Done today", listOf(Chip("a")))
        for (notify in listOf("released:2026-10-26T18:00:00Z", "hold_until:2026-10-26T20:00:00Z", "push_now", "in_app")) {
            assertNull(tonightOf(listOf(summary(2, POSTED, chips).copy(notify = notify)), POSTED, UTC), notify)
        }
    }

    @Test fun theNextLocalMidnightEndsTheDay() {
        assertEquals(Instant.parse("2026-10-27T00:00:00Z"), nextLocalMidnight(Instant.parse("2026-10-26T18:00:00Z"), UTC))
        assertEquals(Instant.parse("2026-10-27T00:00:00Z"), nextLocalMidnight(Instant.parse("2026-10-26T23:59:59Z"), UTC))
        assertEquals(Instant.parse("2026-10-28T00:00:00Z"), nextLocalMidnight(Instant.parse("2026-10-27T00:00:00Z"), UTC))
        // Auckland is +13 in October: 18:00 UTC is 07:00 on the 27th, whose midnight is 11:00 UTC the same day
        assertEquals(Instant.parse("2026-10-27T11:00:00Z"), nextLocalMidnight(Instant.parse("2026-10-26T18:00:00Z"), TimeZone.of("Pacific/Auckland")))
    }

    @Test fun theUsersOwnItemNeverCounts() {
        assertNull(tonightOf(listOf(summary(2).copy(author = FeedItem.Author.USER)), POSTED, UTC))
    }
}

class ChatFocusTest {
    private val entries: List<ChatEntry> = chatEntries(listOf(plain(1), summary(2), plain(3)))

    @Test fun theTranscriptIsNewestFirstSoTheNewestIsRowZero() {
        assertEquals(0, chatEntryIndex(entries, FeedItemId("p3")))
        assertEquals(1, chatEntryIndex(entries, FeedItemId("s2")))
        assertEquals(2, chatEntryIndex(entries, FeedItemId("p1")))
    }

    @Test fun rowsAboveTheMessagesShiftTheIndex() {
        assertEquals(3, chatEntryIndex(entries, FeedItemId("s2"), leading = 2))
    }

    @Test fun aTallItemIsScrolledBackToItsTop() {
        assertEquals(0, topOffset(300, 800), "fits")
        assertEquals(0, topOffset(800, 800))
        assertEquals(450, topOffset(1250, 800))
    }

    @Test fun anItemNotThereYetHasNoRow() {
        assertNull(chatEntryIndex(entries, FeedItemId("missing")))
    }
}
