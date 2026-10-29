package net.robog.clarity.app.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import kotlin.time.Instant
import net.robog.clarity.app.ui.cards.FeedItemView
import net.robog.clarity.app.ui.components.ChatEntry
import net.robog.clarity.app.ui.components.chatEntries
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.FeedItemId
import net.robog.clarity.shared.feed.ItemReaction

private val at = Instant.parse("2026-10-29T15:10:00Z")

private fun said(seq: Long, text: String) = FeedItem(FeedItemId("r$seq"), seq, at, FeedItem.Author.USER, cards = listOf(Card.Said(text)))
private fun text(seq: Long, text: String) = FeedItem(FeedItemId("r$seq"), seq, at, FeedItem.Author.AGENT, cards = listOf(Card.Text(text)))
private fun tapback(seq: Long, to: Long, kind: String) =
    FeedItem(FeedItemId("r$seq"), seq, at, FeedItem.Author.AGENT, cards = emptyList(), reaction = ItemReaction(FeedItemId("r$to"), kind))

/** One of each reaction; the 👍 on "ok" replaced an earlier ❤️ (only the newest shows). */
private val reactedThread = listOf(
    said(1, "Can you move my 3pm with Dana to 4?"),
    text(2, "Moved it to 4pm. Dana has the update."),
    said(3, "thanks!"),
    tapback(4, 3, "heart"),
    text(5, "Your Ulta appointment is tomorrow at 10. I’ll nudge you at 9:15."),
    said(6, "ok"),
    tapback(7, 6, "heart"),
    tapback(8, 6, "thumbs_up"),
    said(9, "the printer jammed AGAIN, it’s eating my report"),
    tapback(10, 9, "laugh"),
    said(11, "and the 5:40 train is cancelled"),
    tapback(12, 11, "thumbs_down"),
)

private val longThread = listOf(
    text(1, "The draft to Nick is ready for your review: it says the deck moves to Friday and asks whether the numbers on slide 6 are final."),
    said(
        2,
        "Sounds good. I read it on the bus and it covers everything, including the bit about slide 6 and the budget table. " +
            "No changes from me, send it whenever you think the timing is right and I’ll look for his answer tomorrow morning.",
    ),
    tapback(3, 2, "thumbs_up"),
)

@Composable
private fun Thread(items: List<FeedItem>, dark: Boolean) = ClarityTheme(darkTheme = dark) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            chatEntries(items).filterIsInstance<ChatEntry.Message>().forEach { FeedItemView(it.item, reaction = it.reaction) }
        }
    }
}

@PreviewTest
@Preview(widthDp = 412)
@Composable
fun ReactionsLight() = Thread(reactedThread, dark = false)

@PreviewTest
@Preview(widthDp = 412)
@Composable
fun ReactionsDark() = Thread(reactedThread, dark = true)

@PreviewTest
@Preview(widthDp = 412)
@Composable
fun ReactionLongMessageLight() = Thread(longThread, dark = false)

@PreviewTest
@Preview(widthDp = 412)
@Composable
fun ReactionLongMessageDark() = Thread(longThread, dark = true)
