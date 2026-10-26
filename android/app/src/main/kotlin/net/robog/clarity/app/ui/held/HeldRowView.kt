package net.robog.clarity.app.ui.held

import net.robog.clarity.app.ui.components.sentenceCase
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PendingActions
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import net.robog.clarity.app.ui.theme.clarityText

/**
 * One held action as a row: what it is, when, and whether it still waits. The Held screen and the
 * chat thread both use it, and both open the same review screen on tap.
 */
@Composable
fun HeldRowView(row: HeldRow, onClick: () -> Unit, modifier: Modifier = Modifier, container: Color = Color.Transparent) {
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = container),
        leadingContent = {
            Icon(
                if (row.waiting) Icons.Outlined.PendingActions else Icons.Outlined.TaskAlt,
                contentDescription = null,
                tint = if (row.waiting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        overlineContent = { Text(sentenceCase(if (row.waiting) "needs your ok · ${row.whenText}" else row.whenText)) },
        headlineContent = { Text(row.title, style = MaterialTheme.clarityText.chat, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            if (row.waiting) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "review", tint = MaterialTheme.colorScheme.primary)
            } else {
                Text(sentenceCase(row.status), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
