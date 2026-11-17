package net.robog.clarity.app.ui.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import net.robog.clarity.app.ui.components.SecondaryButton
import net.robog.clarity.app.ui.theme.Space
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.Choice

/** How long a tap waits for the server to record it before the buttons come back (the send failed). */
private const val TAP_PENDING_MS = 8_000L

/**
 * The buttons of a [Card.Choices] under its prompt. While unanswered each is an outlined button; a
 * tap sends the option's reply through [onChoose] and the tapped one shows progress until the
 * server records it on the card. Once answered ([Card.Choices.chosen], on every device) the buttons
 * are gone and only the prompt stays. With no [onChoose] (nobody to answer, as in a call) all stay off.
 */
@Composable
fun ChoicesView(card: Card.Choices, onChoose: ((Choice) -> Unit)?, modifier: Modifier = Modifier) {
    var tapped by remember(card.options) { mutableStateOf<String?>(null) }
    // A tap the server never recorded (the send failed) gives the buttons back.
    LaunchedEffect(tapped) {
        if (tapped != null) {
            delay(TAP_PENDING_MS)
            tapped = null
        }
    }
    val chosen = card.chosen
    val waiting = chosen == null && tapped != null
    val prompt = card.prompt?.takeIf { it.isNotBlank() }
    // Answered with nothing to say: no empty column and no gap in the thread.
    if (chosen != null && prompt == null) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
        prompt?.let { AgentBubble(it) }
        // Answered (on any device, a 409 included): the person's answer is their own bubble, so no buttons stay.
        if (chosen == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.Small), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                card.options.forEach { option ->
                    SecondaryButton(
                        option.label,
                        onClick = {
                            tapped = option.reply
                            onChoose?.invoke(option)
                        },
                        enabled = !waiting && onChoose != null,
                        busy = tapped == option.reply,
                    )
                }
            }
        }
    }
}
