package net.robog.clarity.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import net.robog.clarity.app.ui.cards.CardEnv
import net.robog.clarity.app.ui.cards.FeedItemView
import net.robog.clarity.app.ui.cards.LiveBubble
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.shared.feed.FeedItem

/** One row of a chat transcript: a feed item, or a pill that names what follows (a meeting, a topic). */
sealed interface ChatEntry {
    val key: String

    data class Message(val item: FeedItem) : ChatEntry {
        override val key: String get() = "m${item.seq}"
    }

    data class Pill(override val key: String, val label: String) : ChatEntry
}

/** Items as plain messages, no dividers. */
fun chatEntries(items: List<FeedItem>): List<ChatEntry> = items.map { ChatEntry.Message(it) }

/**
 * The conversation, oldest at the top. Built bottom-up (newest first, reverseLayout) so it opens at
 * the latest message and a reply that grows while streaming stays pinned to the bottom.
 * [newest] goes under everything (below the live bubble); [oldest] above everything.
 */
@Composable
fun ChatTranscript(
    entries: List<ChatEntry>,
    live: String?,
    env: CardEnv,
    modifier: Modifier = Modifier,
    newest: LazyListScope.() -> Unit = {},
    oldest: LazyListScope.() -> Unit = {},
) {
    val listState = rememberLazyListState()
    FollowNewest(listState, entries.size)
    LazyColumn(
        modifier.fillMaxWidth(),
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = ScreenGutter, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
    ) {
        newest()
        live?.let { text -> item(key = "live") { LiveBubble(text, arrivingItem()) } }
        items(entries.asReversed(), key = { it.key }) { entry ->
            when (entry) {
                is ChatEntry.Message -> FeedItemView(entry.item, arrivingItem(), env = env)
                is ChatEntry.Pill -> ChatPill(entry.label, arrivingItem())
            }
        }
        oldest()
    }
}

/** A small centred outlined pill, like a date chip in a messaging app. */
@Composable
fun ChatPill(label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(
                label,
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** How the composer's mic button looks: [live] while it listens, [muted] shows the crossed-out mic. */
data class ComposerMic(val live: Boolean, val muted: Boolean = false, val description: String)

/**
 * The text field with the mic and send buttons, shared by Ask and the call. The mic is an expressive
 * toggle that changes shape while it is live; send is enabled once there is text.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatComposer(
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    mic: ComposerMic,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Type, or tap the mic",
) {
    val buttonSize = IconButtonDefaults.mediumContainerSize()
    Row(
        modifier.fillMaxWidth().padding(horizontal = ScreenGutter, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextField(
            value = draft,
            onValueChange = onDraft,
            modifier = Modifier.weight(1f).heightIn(min = buttonSize.height).semantics { contentDescription = "message" },
            placeholder = { Text(placeholder, style = MaterialTheme.clarityText.chat) },
            textStyle = MaterialTheme.clarityText.chat,
            shape = MaterialTheme.shapes.extraLarge,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            maxLines = 4,
        )
        FilledTonalIconToggleButton(
            checked = mic.live,
            onCheckedChange = { onMic() },
            shapes = IconButtonDefaults.toggleableShapes(),
            modifier = Modifier.size(buttonSize),
        ) {
            Icon(
                if (mic.muted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                contentDescription = mic.description,
                modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
            )
        }
        FilledIconButton(
            onClick = onSend,
            enabled = draft.isNotBlank(),
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(buttonSize),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "send", modifier = Modifier.size(IconButtonDefaults.mediumIconSize))
        }
    }
}
