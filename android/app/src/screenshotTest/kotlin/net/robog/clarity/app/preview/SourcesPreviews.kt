package net.robog.clarity.app.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import net.robog.clarity.app.ui.cards.AgentBubble
import net.robog.clarity.app.ui.cards.SaidBubble
import net.robog.clarity.app.ui.sources.FileState
import net.robog.clarity.app.ui.sources.SourceContent
import net.robog.clarity.app.ui.sources.SourceUiState
import net.robog.clarity.app.ui.sources.SourcesCardView
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.source.SourceAttachment
import net.robog.clarity.shared.source.SourceItem
import net.robog.clarity.shared.source.SourceKind
import net.robog.clarity.shared.source.SourceView

@Composable
private fun Sources(dark: Boolean, content: @Composable () -> Unit) {
    ClarityTheme(darkTheme = dark) { Surface(color = MaterialTheme.colorScheme.surface) { content() } }
}

// ---------- the card in the thread ----------

private val card = Card.Sources(
    listOf(
        SourceItem.Entity("m1", SourceKind.MESSAGE, "Lunch on Friday", "Sam Example · Sun Oct 25, 9:00 pm", attachments = 2, by = "Sam Example", at = Instant.parse("2026-10-26T01:00:00Z")),
        SourceItem.Entity("e1", SourceKind.EVENT, "Dentist", "Tue Oct 27, 10:00 am", at = Instant.parse("2026-10-27T14:00:00Z")),
        SourceItem.Entity("d1", SourceKind.DOCUMENT, "Taxes 2025", "/Taxes/2025.pdf"),
        SourceItem.Web("https://example.com/guide", "Garden guide for spring planting", "example.com"),
    ),
    "Sources.",
)

@Composable
private fun CardInThread(dark: Boolean) = Sources(dark) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SaidBubble("When is lunch with Sam and what is the dentist doing")
        AgentBubble("Lunch with Sam is Friday at noon. The dentist is Tuesday at 10.")
        SourcesCardView(card, onOpenRecord = {})
    }
}

@PreviewTest @HalfPreview @Composable fun SourcesCardLight() = CardInThread(false)
@PreviewTest @HalfPreview @Composable fun SourcesCardDark() = CardInThread(true)

// ---------- the viewer ----------

private val message = SourceView.Message(
    id = "m1",
    title = "Lunch on Friday",
    channel = "email",
    from = "Sam Example <sam@example.com>",
    to = listOf("me@example.com"),
    sentAt = Instant.parse("2026-10-25T15:00:00Z"),
    account = "me@example.com",
    body = "Hi,\n\nLunch is at noon on Friday at the cafe on Main Street. I put the menu and the map in the attachments.\n\nSam",
    attachments = listOf(
        SourceAttachment("2", "menu.pdf", "application/pdf", 1_240_000),
        SourceAttachment("3", "map.png", "image/png", 88_000),
    ),
)

private val loaded = SourceUiState(loading = false, view = message)

@Composable
private fun Viewer(dark: Boolean, state: SourceUiState) =
    Sources(dark) { SourceContent(state, {}, "Ask", {}, {}, zone = TimeZone.of("America/New_York")) }

@PreviewTest @LongPreview @Composable fun SourceMessageLight() = Viewer(false, loaded)
@PreviewTest @LongPreview @Composable fun SourceMessageDark() = Viewer(true, loaded)

@PreviewTest @LongPreview @Composable fun SourceMessageFileStatesLight() = Viewer(
    false,
    loaded.copy(files = mapOf("2" to FileState.Loading, "3" to FileState.Failed("The mail server took too long. Try again."))),
)

private val event = SourceView.Event(
    id = "e1", title = "Dentist", calendar = "Home",
    start = Instant.parse("2026-10-27T14:00:00Z"), end = Instant.parse("2026-10-27T15:00:00Z"),
    location = "12 Main Street", description = "Bring the forms.", repeats = "every 6 months",
)

@PreviewTest @HalfPreview @Composable fun SourceEventLight() = Viewer(false, SourceUiState(loading = false, view = event))
@PreviewTest @HalfPreview @Composable fun SourceEventDark() = Viewer(true, SourceUiState(loading = false, view = event))

@PreviewTest @ShortPreview @Composable fun SourceLoading() = Viewer(false, SourceUiState())
@PreviewTest @ShortPreview @Composable fun SourceErrorLight() = Viewer(false, SourceUiState(loading = false, error = "Can’t find this. It may have been removed."))
@PreviewTest @ShortPreview @Composable fun SourceErrorDark() = Viewer(true, SourceUiState(loading = false, error = "Can’t find this. It may have been removed."))

@PreviewTest @LongPreview @Composable fun SourceMessageShortenedLight() = Viewer(false, SourceUiState(loading = false, view = message.copy(truncated = true, attachments = emptyList())))
