package net.robog.clarity.app.ui.cards

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import net.robog.clarity.app.ui.held.HeldRowView
import net.robog.clarity.app.ui.held.heldRows
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.app.vault.CredentialFormCard
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.TaskId

private const val CURSOR = "▍"
private val MaxBubble = 340.dp
private val AgentShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 4.dp)
private val YouShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 4.dp, bottomStart = 20.dp)

/** Cards that read as words in the thread; everything else is an attachment or an action row. */
private fun Card.isMessage() = this is Card.Text || this is Card.Said || this is Card.Note

/**
 * One feed item as messages: its words as bubbles, then a row for each approval or login it asks
 * for (tap to open it), then its other cards gathered into one stack that opens a pager.
 */
@Composable
fun FeedItemView(item: FeedItem, modifier: Modifier = Modifier, env: CardEnv = CardEnv()) {
    val lastText = item.cards.indexOfLast { it is Card.Text }
    val actions = item.cards.filter { it is Card.Proposal || it is Card.CredentialRequest }
    val attachments = item.cards.filterNot { it.isMessage() || it in actions }
    var sheet by remember { mutableStateOf<List<Card>?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item.cards.forEachIndexed { i, card ->
            when (card) {
                is Card.Text -> AgentBubble(card.text, streaming = item.streaming && i == lastText)
                is Card.Said -> SaidBubble(card.text)
                is Card.Note -> NoteBubble(card.text)
                else -> Unit
            }
        }
        actions.forEach { card ->
            when (card) {
                is Card.Proposal -> ApprovalRow(card, env)
                is Card.CredentialRequest -> ActionRow(Icons.Outlined.Key, "a login to save", "Save it in your vault", { sheet = listOf(card) })
                else -> Unit
            }
        }
        if (attachments.isNotEmpty()) AttachmentStack(attachments, env.tasks, onOpen = { sheet = attachments })
    }
    sheet?.let { cards -> AttachmentSheet(cards, env.tasks, onDismiss = { sheet = null }) }
}

/** The agent speaking: a bubble on the left. */
@Composable
fun AgentBubble(text: String, modifier: Modifier = Modifier, streaming: Boolean = false) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Text(
            if (streaming) text + CURSOR else text,
            Modifier
                .widthIn(max = MaxBubble)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, AgentShape)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.clarityText.chat,
        )
    }
}

/** What the person said: a bubble on the right. */
@Composable
fun SaidBubble(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            Modifier
                .widthIn(max = MaxBubble)
                .background(MaterialTheme.colorScheme.primaryContainer, YouShape)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.clarityText.chat,
        )
    }
}

/** Words heard so far, not yet sent: an outlined bubble on the right. */
@Composable
fun LiveBubble(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            Modifier
                .widthIn(max = MaxBubble)
                .border(1.dp, MaterialTheme.colorScheme.outline, YouShape)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.clarityText.chat,
        )
    }
}

/** An aside from the agent (a status line, a problem): quieter than a bubble. */
@Composable
fun NoteBubble(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            Modifier.widthIn(max = MaxBubble),
            shape = AgentShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(
                text,
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.clarityText.chatSmall,
            )
        }
    }
}

/** An approval in the thread: the same row as on the Held screen, opening the same review. */
@Composable
private fun ApprovalRow(card: Card.Proposal, env: CardEnv) {
    val held = env.held[card.heldActionId]
    if (held == null) {
        ActionRow(Icons.Outlined.Description, "needs your OK", "Loading the details…", { env.onReview(card.heldActionId) })
        return
    }
    val row = remember(held) { heldRows(listOf(held), TimeZone.currentSystemDefault()).single() }
    RowCard { HeldRowView(row, onClick = { env.onReview(held.id) }) }
}

@Composable
private fun RowCard(content: @Composable () -> Unit) {
    OutlinedCard(Modifier.widthIn(max = MaxBubble).fillMaxWidth(), shape = MaterialTheme.shapes.large) { content() }
}

@Composable
private fun ActionRow(icon: ImageVector, overline: String, title: String, onClick: () -> Unit, supporting: String? = null) {
    RowCard {
        ListItem(
            modifier = Modifier.clickable(onClick = onClick),
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            overlineContent = { Text(overline) },
            headlineContent = { Text(title, style = MaterialTheme.clarityText.chat, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = supporting?.let { { Text(it, style = MaterialTheme.clarityText.chatSmall) } },
            trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
        )
    }
}

/** What an attachment is called in the stack and at the top of its page. */
internal fun attachmentTitle(card: Card): String = when (card) {
    is Card.Context -> card.label
    is Card.Chips -> card.label
    is Card.Plan -> "Your plan"
    is Card.Tasks -> card.label
    is Card.CredentialRequest -> "A login to save"
    else -> ""
}.replaceFirstChar { it.uppercase() }

private fun attachmentIcon(card: Card): ImageVector = when (card) {
    is Card.Context -> Icons.Outlined.Description
    is Card.Plan -> Icons.Outlined.CalendarMonth
    is Card.Tasks -> Icons.Outlined.Checklist
    is Card.CredentialRequest -> Icons.Outlined.Key
    else -> Icons.AutoMirrored.Outlined.List
}

/** "3 items" under the title of a one-card stack; the other titles when there are more. */
internal fun attachmentSummary(cards: List<Card>): String = when {
    cards.size > 1 -> cards.drop(1).joinToString(" · ") { attachmentTitle(it) }
    else -> when (val c = cards.single()) {
        is Card.Context -> c.items.joinToString(" · ") { it.title }
        is Card.Chips -> c.items.joinToString(" · ") { it.text }
        is Card.Plan -> c.items.joinToString(" · ") { it.action }
        is Card.Tasks -> "${c.taskIds.size} tasks"
        else -> ""
    }
}

/** The attachments of one message: a card with the first title, and an edge per extra card under it. */
@Composable
private fun AttachmentStack(cards: List<Card>, tasks: Map<TaskId, AgentTask>, onOpen: () -> Unit) {
    Column(Modifier.widthIn(max = MaxBubble).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                leadingContent = { Icon(attachmentIcon(cards.first()), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(attachmentTitle(cards.first()), style = MaterialTheme.clarityText.chat, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(attachmentSummary(cards), style = MaterialTheme.clarityText.chatSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (cards.size > 1) Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) { Text("${cards.size}") }
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "open ${cards.size} attachments")
                    }
                },
            )
        }
        val edge = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
        if (cards.size > 1) StackEdge(0.94f, edge)
        if (cards.size > 2) StackEdge(0.88f, edge)
    }
}

@Composable
private fun StackEdge(width: Float, shape: RoundedCornerShape) {
    Surface(
        Modifier.fillMaxWidth(width).height(6.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {}
}

/** The attachments in a bottom sheet, one per page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentSheet(cards: List<Card>, tasks: Map<TaskId, AgentTask>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AttachmentPager(cards, tasks)
    }
}

/** The sheet's content (also used by previews): swipe between attachments, dots below. */
@Composable
fun AttachmentPager(cards: List<Card>, tasks: Map<TaskId, AgentTask>, modifier: Modifier = Modifier) {
    val pager = rememberPagerState { cards.size }
    Column(modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        HorizontalPager(pager, Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, pageSpacing = 16.dp) { page ->
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val card = cards[page]
                Text(
                    attachmentTitle(card),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                AttachmentPage(card, tasks)
            }
        }
        if (cards.size > 1) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
                repeat(cards.size) { i ->
                    Box(
                        Modifier.padding(horizontal = 4.dp).size(8.dp).background(
                            if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentPage(card: Card, tasks: Map<TaskId, AgentTask>) {
    val clear = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
    when (card) {
        is Card.Context -> card.items.forEach { item ->
            ListItem(
                colors = clear,
                overlineContent = { Text(item.tag) },
                headlineContent = { Text(item.title, style = MaterialTheme.clarityText.chat) },
            )
        }
        is Card.Chips -> card.items.forEach { chip ->
            ListItem(
                colors = clear,
                headlineContent = { Text(chip.text, style = MaterialTheme.clarityText.chat) },
                supportingContent = chip.sub.takeIf { it.isNotBlank() }?.let { { Text(it, style = MaterialTheme.clarityText.chatSmall) } },
            )
        }
        is Card.Plan -> card.items.forEach { step ->
            ListItem(colors = clear, headlineContent = { Text(step.sentence(), style = MaterialTheme.clarityText.chat) })
        }
        is Card.Tasks -> card.taskIds.forEach { id ->
            val task = tasks[id]
            ListItem(
                colors = clear,
                headlineContent = { Text(task?.title ?: "…", style = MaterialTheme.clarityText.chat) },
                supportingContent = task?.let { { Text(it.detail, style = MaterialTheme.clarityText.chatSmall) } },
                trailingContent = task?.let { { Text(taskStatusLabel(it.status), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) } },
            )
        }
        is Card.CredentialRequest -> CredentialFormCard(card.requestId, Modifier.padding(horizontal = 8.dp))
        else -> Unit
    }
}
