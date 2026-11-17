package net.robog.clarity.app.preview

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import net.robog.clarity.app.ui.ask.AskContent
import net.robog.clarity.app.ui.ask.AskUiState
import net.robog.clarity.app.ui.ask.askEntries
import net.robog.clarity.app.ui.ask.askItems
import net.robog.clarity.app.ui.cards.AttachmentPager
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.app.voice.MicAccess
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.Chip
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.FeedItemId

// The evening summary as the Ask thread shows it. The cards are the ones the backend's
// eveningSummaryCards builds (a counts line, then one Chips card per section), the screen is the real one.

private val evening = Instant.parse("2026-10-26T18:00:00Z")

private fun agent(seq: Long, vararg cards: Card) =
    FeedItem(FeedItemId("e$seq"), seq, evening, FeedItem.Author.AGENT, cards = cards.toList())

private val fullSummary = listOf(
    agent(1, Card.Text("Sam Example confirmed the dentist for Tuesday.")),
    agent(
        2,
        Card.Text("2 coming up. 3 done today. 4 held."),
        Card.Chips("Coming up", listOf(Chip("Jazz at the Blue Room, Thursday 19:00"), Chip("Dinner with Sam Example, Saturday 18:30"))),
        Card.Chips("Done today", listOf(Chip("Reply to Sam Example about the contract"), Chip("Book the dentist for Tuesday"), Chip("File the expense report"))),
        Card.Chips(
            "Held for tonight",
            listOf(
                Chip("Newsletter from example.com has a new price for your plan"),
                Chip("The school form is due Friday and still unsigned"),
                Chip("Sam Example moved Thursday’s call to 2:00 pm"),
                Chip("A receipt from example.com looks charged twice for the same order and may need a refund"),
            ),
        ),
    ),
)

private val doneOnly = listOf(
    agent(1, Card.Text("Sam Example confirmed the dentist for Tuesday.")),
    agent(
        2,
        Card.Text("2 done today."),
        Card.Chips("Done today", listOf(Chip("Reply to Sam Example about the contract"), Chip("File the expense report"))),
    ),
)

@Composable
private fun AskEvening(thread: List<FeedItem>, dark: Boolean) = ClarityTheme(darkTheme = dark) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        val items = askItems(thread, emptyMap())
        AskContent(
            AskUiState(items = items, entries = askEntries(items, emptyMap(), TimeZone.UTC, LocalDate(2026, 10, 26))),
            MicAccess(granted = true, refused = false, request = {}, openSettings = {}),
            {}, {}, {}, {}, { _, _ -> },
        )
    }
}

@PreviewTest
@PhonePreview
@Composable
fun EveningSummaryFullLight() = AskEvening(fullSummary, dark = false)

@PreviewTest
@PhonePreview
@Composable
fun EveningSummaryFullDark() = AskEvening(fullSummary, dark = true)

@PreviewTest
@PhonePreview
@Composable
fun EveningSummaryDoneOnlyLight() = AskEvening(doneOnly, dark = false)

@PreviewTest
@PhonePreview
@Composable
fun EveningSummaryDoneOnlyDark() = AskEvening(doneOnly, dark = true)

/** What the stack opens: the sheet's first page (Coming up) and, with the held page first, the longest headlines. */
@Composable
private fun Sheet(dark: Boolean, cards: List<Card>) = ClarityTheme(darkTheme = dark) {
    Surface(color = MaterialTheme.colorScheme.surface) { AttachmentPager(cards, emptyMap()) }
}

private val fullChips = fullSummary.last().cards.filterIsInstance<Card.Chips>()

@PreviewTest
@PartPreview
@Composable
fun EveningSheetComingUpLight() = Sheet(dark = false, fullChips)

@PreviewTest
@PartPreview
@Composable
fun EveningSheetComingUpDark() = Sheet(dark = true, fullChips)

@PreviewTest
@PartPreview
@Composable
fun EveningSheetHeldLight() = Sheet(dark = false, fullChips.reversed())
