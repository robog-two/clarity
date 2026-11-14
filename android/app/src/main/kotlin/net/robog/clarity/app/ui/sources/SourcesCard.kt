package net.robog.clarity.app.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.robog.clarity.app.ui.components.CardGroup
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.SegmentCard
import net.robog.clarity.app.ui.theme.Space
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.source.SourceItem
import net.robog.clarity.shared.source.SourceKind

/** The widest a card in the thread gets (the same as a bubble). */
private val MaxCard = 340.dp

/**
 * The sources under a reply: what it rests on, one row each. A record opens in the viewer
 * ([onOpenRecord] with its id; null where there is nowhere to go, as on the call screen), a web page
 * in the browser with its site shown.
 */
@Composable
fun SourcesCardView(card: Card.Sources, onOpenRecord: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    Column(modifier.widthIn(max = MaxCard).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
        Kicker(sourcesHeading(card), heading = true)
        CardGroup(card.items) { item, shape, _ ->
            val open: (() -> Unit)? = when (item) {
                is SourceItem.Entity -> onOpenRecord?.let { { it(item.id) } }
                is SourceItem.Web -> if (isWebAddress(item.url)) ({ runCatching { uri.openUri(item.url) }; Unit }) else null
            }
            SourceRow(item, shape, open)
        }
    }
}

@Composable
private fun SourceRow(item: SourceItem, shape: Shape, onClick: (() -> Unit)?) {
    val line = sourceItemLine(item)
    SegmentCard(shape, Modifier.semantics { contentDescription = listOf(item.title, line).filter(String::isNotBlank).joinToString(", ") }, onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.Medium), verticalAlignment = Alignment.CenterVertically) {
            Icon(itemIcon(item), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                Text(item.title, style = MaterialTheme.clarityText.chat, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (line.isNotBlank()) Text(line, style = MaterialTheme.clarityText.chatSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onClick != null) {
                Icon(
                    if (item is SourceItem.Web) Icons.AutoMirrored.Outlined.OpenInNew else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun itemIcon(item: SourceItem): ImageVector = when (item) {
    is SourceItem.Web -> Icons.Outlined.Language
    is SourceItem.Entity -> when (item.kind) {
        SourceKind.MESSAGE -> Icons.Outlined.Email
        SourceKind.EVENT -> Icons.Outlined.CalendarMonth
        SourceKind.DOCUMENT -> Icons.Outlined.Description
        SourceKind.CONTACT -> Icons.Outlined.Person
    }
}
