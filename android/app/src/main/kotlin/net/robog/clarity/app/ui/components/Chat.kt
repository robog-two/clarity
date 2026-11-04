package net.robog.clarity.app.ui.components

import net.robog.clarity.app.ui.theme.Space

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import net.robog.clarity.app.ui.cards.CardEnv
import net.robog.clarity.app.ui.cards.FeedItemView
import net.robog.clarity.app.ui.cards.LiveBubble
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.Reaction
import net.robog.clarity.shared.feed.latestReactions

/**
 * The layout of a conversation screen (Ask, the call): a header band, the transcript filling the
 * middle, and a footer band under it with the composer. The transcript keeps its own list, anchored
 * to the bottom, so this is the one screen layout that does not scroll as a column. Everything is
 * padded for the status bar, the nav bar and the keyboard, as [ClarityScreen] is.
 */
@Composable
fun ChatLayout(
    header: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    transcript: @Composable (Modifier) -> Unit,
) {
    Column(modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).statusBarsPadding()) { header() }
        transcript(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).navigationBarsPadding()) { footer() }
    }
}

/** One row of a chat transcript: a feed item, or a pill that names what follows (a meeting, a topic). */
sealed interface ChatEntry {
    val key: String

    /** [reaction]: Clarity's tapback on this message, if any. */
    data class Message(val item: FeedItem, val reaction: Reaction? = null) : ChatEntry {
        override val key: String get() = "m${item.seq}"
    }

    data class Pill(override val key: String, val label: String) : ChatEntry
}

/** Items as plain messages, no dividers; reactions go onto the messages they react to. */
fun chatEntries(items: List<FeedItem>): List<ChatEntry> {
    val reactions = latestReactions(items)
    return items.filter { it.reaction == null }.map { ChatEntry.Message(it, reactions[it.id]) }
}

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
    /** How many leading characters of [live] are settled (see [LiveBubble]). */
    liveConfirmed: Int = 0,
    newest: LazyListScope.() -> Unit = {},
    oldest: LazyListScope.() -> Unit = {},
) {
    val listState = rememberLazyListState()
    FollowNewest(listState, entries.size)
    LazyColumn(
        modifier.fillMaxWidth(),
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = ScreenGutter, vertical = Space.Medium),
        verticalArrangement = Arrangement.spacedBy(Space.Medium, Alignment.Bottom),
    ) {
        newest()
        live?.let { text -> item(key = "live") { LiveBubble(text, arrivingItem(), confirmed = liveConfirmed) } }
        items(entries.asReversed(), key = { it.key }) { entry ->
            when (entry) {
                is ChatEntry.Message -> FeedItemView(entry.item, arrivingItem(), env = env, reaction = entry.reaction)
                is ChatEntry.Pill -> ChatPill(entry.label, arrivingItem())
            }
        }
        oldest()
    }
}

/** A small centred pill, like a date chip in a messaging app. */
@Composable
fun ChatPill(label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = Space.Medium), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Text(
                label,
                Modifier.padding(horizontal = Space.Medium, vertical = Space.Small),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** How the composer's mic button looks: [live] while it listens, [muted] shows the crossed-out mic. */
data class ComposerMic(
    val live: Boolean,
    val muted: Boolean = false,
    val description: String,
    /** Spoken state ("On", "Muted"); with [action] the button reads as a state, "Microphone, on". */
    val state: String? = null,
    /** What a tap does ("Mute"), spoken as the click label. */
    val action: String? = null,
)

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
    placeholder: String = "Type or talk",
) {
    val buttonSize = IconButtonDefaults.mediumContainerSize()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier.fillMaxWidth().padding(horizontal = ScreenGutter, vertical = Space.Medium),
        horizontalArrangement = Arrangement.spacedBy(Space.Small),
        verticalAlignment = Alignment.Bottom,
    ) {
        TextField(
            value = draft,
            onValueChange = onDraft,
            interactionSource = interaction,
            // The field sits on a container 1.1:1 from the page, so it gets an outline (3.83:1 light) and,
            // while focused, a thicker primary one (6.03:1 light).
            modifier = Modifier
                .weight(1f)
                .heightIn(min = buttonSize.height)
                .border(
                    if (focused) 2.dp else 1.dp,
                    if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    MaterialTheme.shapes.extraLarge,
                ),
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
            modifier = Modifier.size(buttonSize).semantics(mergeDescendants = true) {
                if (mic.state != null) {
                    contentDescription = mic.description
                    stateDescription = mic.state
                    if (mic.action != null) onClick(label = mic.action) { onMic(); true }
                }
            },
        ) {
            Icon(
                if (mic.muted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                contentDescription = if (mic.state != null) null else mic.description,
                modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
            )
        }
        FilledIconButton(
            onClick = onSend,
            enabled = draft.isNotBlank(),
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(buttonSize),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(IconButtonDefaults.mediumIconSize))
        }
    }
}
