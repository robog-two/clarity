package net.robog.clarity.app.ui.goods

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.shared.feed.Card

/** The widest a card in the thread gets (the same as a bubble). */
private val MaxCard = 340.dp

/**
 * A Good handed over in the thread (`present_report`): "In your Goods", its title and kind. Tapping
 * it opens the viewer ([onOpen]; null where there is nowhere to go, as on the call screen), where
 * Download and Share are.
 */
@Composable
fun ReportCardView(card: Card.Report, onOpen: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    val body: @Composable () -> Unit = {
        Row(
            Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GoodsGlyph()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Kicker("in your Goods")
                Text(card.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text("Document", style = MaterialTheme.clarityText.chatSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onOpen != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val cardModifier = modifier.widthIn(max = MaxCard).fillMaxWidth()
    if (onOpen != null) {
        OutlinedCard(
            onClick = { onOpen(card.reportId) },
            modifier = cardModifier.semantics { contentDescription = "${card.title}, a document in your Goods. Open it." },
            shape = MaterialTheme.shapes.large,
        ) { body() }
    } else {
        OutlinedCard(cardModifier, shape = MaterialTheme.shapes.large) { body() }
    }
}

/** The mark of a Good: a document on a tonal tile. */
@Composable
fun GoodsGlyph(modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
