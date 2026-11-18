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
import net.robog.clarity.app.data.feed.supersededChoices
import net.robog.clarity.app.ui.cards.CardEnv
import net.robog.clarity.app.ui.cards.FeedItemView
import net.robog.clarity.app.ui.settings.SettingsActions
import net.robog.clarity.app.ui.settings.SettingsContent
import net.robog.clarity.app.ui.settings.SettingsPage
import net.robog.clarity.app.ui.settings.SettingsUiState
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.Choice
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.FeedItemId
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.feed.MessageKind
import net.robog.clarity.shared.session.Settings

@Composable
private fun Frame(content: @Composable () -> Unit) = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) { content() }
}

private val at = Instant.parse("2026-10-26T14:04:00Z")

// ---------- Settings, Held actions page: "Notifications a day" ----------

private val settingsState = SettingsUiState(
    instanceUrl = "https://clarity.home.example.org", deviceId = "dev_8f3k2m9q", loading = false,
    settings = Settings(), constitution = "Version 3, 14 lines, amended yesterday",
)

@PreviewTest @Screen @Composable
fun NotifyStepperDefault() = Frame { SettingsContent(settingsState, SettingsActions(), startPage = SettingsPage.HeldActions) }

@PreviewTest @Screen @Composable
fun NotifyStepperAtTheEnd() = Frame {
    SettingsContent(
        settingsState.copy(settings = Settings(pushesPerDay = 10, batchLowUrgency = false)), SettingsActions(), startPage = SettingsPage.HeldActions,
    )
}

// ---------- the choices card in the thread ----------

private val options = listOf(Choice("Starting", "Starting"), Choice("Later", "Later"), Choice("Make it smaller", "Make it smaller"))

private fun asked(choices: Card.Choices) = FeedItem(
    id = FeedItemId("c1"), seq = 1, at = at, author = FeedItem.Author.AGENT,
    cards = listOf(Card.Text("The ML report is due before 8:30."), choices),
    kind = MessageKind.CHECK_IN,
)

private val said = FeedItem(
    id = FeedItemId("u1"), seq = 2, at = at, author = FeedItem.Author.USER,
    cards = listOf(Card.Said("Later", InputSource.TYPED)),
)

@Composable
private fun ChoicesInThread(answered: Boolean, enabled: Boolean = true) = Frame {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val card = Card.Choices("Ready to start it?", options, chosen = if (answered) "Later" else null)
        FeedItemView(asked(card), env = CardEnv(onChoose = if (enabled) { _, _ -> } else null))
        if (answered) FeedItemView(said)
    }
}

@PreviewTest @Part @Composable
fun ChoicesCardAsking() = ChoicesInThread(answered = false)

@PreviewTest @Part @Composable
fun ChoicesCardAnswered() = ChoicesInThread(answered = true)

@PreviewTest @Part @Composable
fun ChoicesCardOff() = ChoicesInThread(answered = false, enabled = false)

@Composable
private fun ChoicesThenReply() = Frame {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val asked = asked(Card.Choices("Ready to start it?", options))
        val typed = said.copy(cards = listOf(Card.Said("Give me ten minutes", InputSource.TYPED)))
        val env = CardEnv(onChoose = { _, _ -> }, supersededChoices = supersededChoices(listOf(asked, typed)))
        FeedItemView(asked, env = env)
        FeedItemView(typed, env = env)
    }
}

@PreviewTest @Part @Composable
fun ChoicesCardTypedReply() = ChoicesThenReply()
