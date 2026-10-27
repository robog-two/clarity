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
import net.robog.clarity.app.ui.cards.AttachmentPager
import net.robog.clarity.app.ui.cards.CardEnv
import net.robog.clarity.app.ui.cards.FeedItemView
import net.robog.clarity.app.ui.cards.LiveBubble
import net.robog.clarity.app.ui.held.HeldContent
import net.robog.clarity.app.ui.held.HeldUiState
import net.robog.clarity.app.ui.held.heldRows
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.Chip
import net.robog.clarity.shared.feed.ContextItem
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.FeedItemId
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.governance.Decision
import net.robog.clarity.shared.governance.GuardVerdict
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.Urgency
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import net.robog.clarity.app.ui.ask.AskContent
import net.robog.clarity.app.ui.ask.AskUiState
import net.robog.clarity.app.ui.ask.askEntries
import net.robog.clarity.app.ui.ask.askItems
import net.robog.clarity.app.ui.components.ChatComposer
import net.robog.clarity.app.ui.components.ComposerMic
import net.robog.clarity.app.voice.MicAccess
import net.robog.clarity.shared.session.CallStage
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.SessionKind

private val at = Instant.parse("2026-10-26T14:04:00Z")

private fun held(id: String, summary: String, status: HeldAction.Status) = HeldAction(
    id = HeldActionId(id),
    createdAt = at,
    action = ProposedAction.MoveEvent(idempotencyKey = id, summary = summary, event = EntityId("e"), start = at, end = at, notifyAttendees = true),
    verdict = GuardVerdict(Decision.ASK, rationale = "It’s her invite."),
    agentView = "Thursday 2:00 is clear for both of you.",
    urgency = Urgency.NORMAL,
    status = status,
)

private val waiting = held("h1", "Move call with Dana to Thursday 2:00", HeldAction.Status.HELD)
private val done = held("h2", "Add “Devils with Nick” to Tuesday 8:30pm", HeldAction.Status.EXECUTED)

private val context = Card.Context(
    "“Dana’s contract” · 2 found",
    listOf(ContextItem("mail · yesterday", "Re: Revised Terms"), ContextItem("files · tue", "Contract_v3.pdf")),
)
private val waitingOn = Card.Chips(
    "Waiting on",
    listOf(
        Chip("Ulta store’s phone number", "want me to look it up?"),
        Chip("Duplicate movie event", "if a second held copy is in your feed, discard it"),
        Chip("Old Nick draft", "out of date now, please trash it"),
    ),
)
private val plan = Card.Plan(listOf(IfThen("it’s 9:30", "spec at my desk until 12"), IfThen("Dana emails", "it waits until 12")))

private fun item(seq: Long, author: FeedItem.Author, vararg cards: Card) =
    FeedItem(FeedItemId("f$seq"), seq, at, author, cards = cards.toList())

private val sampleThread = listOf(
    item(1, FeedItem.Author.USER, Card.Said("Dana wants the contract signed by Friday. Can we move our call?", InputSource.TYPED)),
    item(2, FeedItem.Author.AGENT, Card.Text("Found the thread and the latest draft. Thursday 2:00 is clear for both of you."), context, Card.Proposal(waiting.id)),
    item(3, FeedItem.Author.USER, Card.Said("what are you working on?", InputSource.TYPED)),
    item(
        4, FeedItem.Author.AGENT,
        Card.Text("Nothing is running right now. Here’s what’s open."), waitingOn, plan,
        Card.Chips("Watching", listOf(Chip("New mail from Nick", "I’ll tell you"))),
        Card.Note("I couldn’t reach the model just now. I’ll try again shortly."),
    ),
    item(5, FeedItem.Author.AGENT, Card.Text("Added the movie. Tuesday reminders are at 7:30 and 8:15."), Card.Proposal(done.id)),
)

private val env = CardEnv(held = mapOf(waiting.id to waiting, done.id to done))

@Composable
private fun Frame(dark: Boolean, content: @Composable () -> Unit) {
    ClarityTheme(darkTheme = dark) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

@PreviewTest
@Preview(widthDp = 412)
@Composable
fun ChatThreadLight() = Frame(dark = false) {
    sampleThread.forEach { FeedItemView(it, env = env) }
    LiveBubble("and then I need to call the school about")
}

@PreviewTest
@Preview(widthDp = 412)
@Composable
fun ChatThreadDark() = Frame(dark = true) {
    sampleThread.forEach { FeedItemView(it, env = env) }
    LiveBubble("and then I need to call the school about")
}

@PreviewTest
@Preview(widthDp = 412)
@Composable
fun AttachmentSheetPage() = Frame(dark = false) {
    AttachmentPager(listOf(waitingOn, plan, context), emptyMap())
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 700)
@Composable
fun HeldList() = ClarityTheme(darkTheme = false) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        HeldContent(HeldUiState(rows = heldRows(listOf(waiting, done), TimeZone.UTC), waiting = 1, allowedCount = 14), {}, {})
    }
}

// ---------- Ask: the main conversation with a morning meeting in it ----------

private val meeting = Session(SessionId("m1"), SessionKind.MORNING_CALL, Session.State.ENDED, Instant.parse("2026-10-26T13:00:00Z"), stage = CallStage.HAND_OFF)

private fun inMeeting(seq: Long, author: FeedItem.Author, vararg cards: Card) =
    FeedItem(FeedItemId("f$seq"), seq, at, author, sessionId = meeting.id, cards = cards.toList())

private val askThread = listOf(
    item(1, FeedItem.Author.USER, Card.Said("Did Alex send the deck yet?", InputSource.TYPED)),
    item(2, FeedItem.Author.AGENT, Card.Text("Not yet. I’ll tell you when it lands.")),
    inMeeting(3, FeedItem.Author.AGENT, Card.Text("Morning. Dana’s contract came in overnight.")),
    inMeeting(4, FeedItem.Author.USER, Card.Said("Let’s do Dana first.", InputSource.VOICE)),
    inMeeting(5, FeedItem.Author.AGENT, Card.Text("Found the thread. Thursday 2:00 is clear for both of you."), Card.Proposal(waiting.id)),
    item(6, FeedItem.Author.USER, Card.Said("what are you working on?", InputSource.TYPED)),
    item(7, FeedItem.Author.AGENT, Card.Text("Nothing is running right now. Here’s what’s open."), waitingOn, plan),
)

private val askSessions = mapOf(meeting.id to meeting)

@Composable
private fun AskMeeting(dark: Boolean) = ClarityTheme(darkTheme = dark) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        val items = askItems(askThread, askSessions)
        AskContent(
            AskUiState(
                items = items,
                entries = askEntries(items, askSessions, TimeZone.UTC, LocalDate(2026, 10, 26)),
                held = env.held,
                draft = "and remind me about the school form",
            ),
            MicAccess(granted = true, refused = false, request = {}, openSettings = {}),
            {}, {}, {}, {}, { _, _ -> },
        )
    }
}

@PreviewTest
@Preview(widthDp = 412, heightDp = 915)
@Composable
fun AskWithMeetingLight() = AskMeeting(dark = false)

@PreviewTest
@Preview(widthDp = 412, heightDp = 915)
@Composable
fun AskWithMeetingDark() = AskMeeting(dark = true)

/** The composer empty (send disabled) and with the mic muted in a call. */
@PreviewTest
@Preview(widthDp = 412)
@Composable
fun ComposerStates() = Frame(dark = false) {
    ChatComposer("", {}, {}, ComposerMic(live = false, description = "talk"), {})
    ChatComposer("", {}, {}, ComposerMic(live = false, muted = true, description = "Unmute"), {})
}
