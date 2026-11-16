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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import net.robog.clarity.app.ui.components.PrimaryButton
import net.robog.clarity.app.ui.components.SecondaryButton
import net.robog.clarity.app.ui.theme.Space
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.Choice

/** How long a tap waits for the server to record it before the buttons come back (the send failed). */
private const val TAP_PENDING_MS = 8_000L

/**
 * The buttons of a [Card.Choices] under its prompt. While unanswered each is an outlined button; a
 * tap sends the option's reply through [onChoose] and the tapped one shows progress until the
 * server records it on the card. Once answered ([Card.Choices.chosen], on every device) the chosen
 * one is filled and the rest are off. With no [onChoose] (nobody to answer, as in a call) all stay off.
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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
        card.prompt?.takeIf { it.isNotBlank() }?.let { AgentBubble(it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.Small), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
            card.options.forEach { option ->
                val isChosen = chosen == option.reply
                val isTapped = chosen == null && tapped == option.reply
                when {
                    isChosen -> PrimaryButton(option.label, onClick = {}, Modifier.semantics { selected = true })
                    else -> SecondaryButton(
                        option.label,
                        onClick = {
                            tapped = option.reply
                            onChoose?.invoke(option)
                        },
                        enabled = chosen == null && !waiting && onChoose != null,
                        busy = isTapped,
                    )
                }
            }
        }
    }
}
