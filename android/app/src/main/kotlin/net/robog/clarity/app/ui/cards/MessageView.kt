package net.robog.clarity.app.ui.cards

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.TimeZone
import net.robog.clarity.app.ui.held.HeldRowView
import net.robog.clarity.app.ui.held.heldRows
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.app.vault.CredentialFormCard
import net.robog.clarity.app.vault.LocalVaultRepository
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.vault.credentialRowText
import net.robog.clarity.shared.vault.CredentialRequest
import androidx.compose.runtime.LaunchedEffect
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.Reaction
import net.robog.clarity.shared.feed.TaskId

private const val CURSOR = "▍"
private val MaxBubble = 340.dp
private val AgentShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 4.dp)
private val YouShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 4.dp, bottomStart = 20.dp)

/** Cards that read as words in the thread (and Goods, shown as their own cards); everything else is an attachment or an action row. */
private fun Card.isMessage() = this is Card.Text || this is Card.Said || this is Card.Note || this is Card.Report || this is Card.Unknown

/**
 * One feed item as messages: its words as bubbles, then a row for each approval or login it asks
 * for (tap to open it), then its other cards gathered into one stack that opens a pager.
 * [reaction] is Clarity's tapback on it, drawn on the corner of the person's (last) bubble.
 */
@Composable
fun FeedItemView(item: FeedItem, modifier: Modifier = Modifier, env: CardEnv = CardEnv(), reaction: Reaction? = null) {
    val lastText = item.cards.indexOfLast { it is Card.Text }
    val lastSaid = item.cards.indexOfLast { it is Card.Said }
    // A reaction is drawn on its target, never as an item of its own.
    if (item.reaction != null) return
    val actions = item.cards.filter { it is Card.Proposal || it is Card.CredentialRequest }
    val attachments = item.cards.filterNot { it.isMessage() || it in actions }
    var sheet by remember { mutableStateOf<List<Card>?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item.cards.forEachIndexed { i, card ->
            when (card) {
                is Card.Text -> AgentBubble(card.text, streaming = item.streaming && i == lastText)
                is Card.Said -> SaidBubble(card.text, reaction = reaction?.takeIf { i == lastSaid })
                is Card.Note -> NoteBubble(card.text)
                is Card.Report -> net.robog.clarity.app.ui.goods.ReportCardView(card, env.onOpenReport)
                // A newer server's card: its own words when it sent some, else nothing.
                is Card.Unknown -> card.fallbackText?.let { NoteBubble(it) }
                else -> Unit
            }
        }
        actions.forEach { card ->
            when (card) {
                is Card.Proposal -> ApprovalRow(card, env)
                is Card.CredentialRequest -> CredentialRequestRow(card, sheetOpen = sheet != null) { sheet = listOf(card) }
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

/**
 * What the person said: a bubble on the right. Clarity's [reaction] sits on its bottom-start
 * corner, like a tapback; the bubble moves in by the badge's overhang so nothing overlaps its neighbours.
 */
@Composable
fun SaidBubble(text: String, modifier: Modifier = Modifier, reaction: Reaction? = null) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(Modifier.widthIn(max = MaxBubble + ReactionOverhang)) {
            Text(
                text,
                Modifier
                    .padding(start = if (reaction != null) ReactionOverhang else 0.dp, bottom = if (reaction != null) ReactionDrop else 0.dp)
                    .widthIn(max = MaxBubble)
                    .background(MaterialTheme.colorScheme.primaryContainer, YouShape)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.clarityText.chat,
            )
            if (reaction != null) ReactionBadge(reaction, Modifier.align(Alignment.BottomStart))
        }
    }
}

private val ReactionOverhang = 14.dp
private val ReactionDrop = 16.dp

/** "Clarity reacted 👍": what a screen reader says for a reaction. */
fun reactionDescription(reaction: Reaction): String = "Clarity reacted ${reaction.emoji}"

/**
 * Clarity's tapback: the emoji in a small tonal pill, ringed in the surface colour so it reads as
 * cut out of the bubble's corner. A new reaction swaps in on the expressive spatial spring.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReactionBadge(reaction: Reaction, modifier: Modifier = Modifier) {
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        reaction,
        modifier.clearAndSetSemantics { contentDescription = reactionDescription(reaction) },
        transitionSpec = { (scaleIn(spatial, initialScale = 0.4f) + fadeIn(effects)) togetherWith (scaleOut(spatial) + fadeOut(effects)) },
        label = "reaction",
    ) { shown ->
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
            shadowElevation = 1.dp,
        ) {
            Text(
                shown.emoji,
                Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
            )
        }
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

/**
 * A login the agent asked for. It looks the request up, so one that was saved, cancelled or closed
 * by the server (the same login saved another way, or never filled in) does not read as waiting.
 */
@Composable
private fun CredentialRequestRow(card: Card.CredentialRequest, sheetOpen: Boolean, onOpen: () -> Unit) {
    val vault = LocalVaultRepository.current
    var request by remember(card.requestId) { mutableStateOf<CredentialRequest?>(null) }
    // Looked up again when the sheet closes: the person may have just saved it.
    LaunchedEffect(card.requestId, vault, sheetOpen) {
        if (vault != null && !sheetOpen) (vault.request(card.requestId) as? ApiResult.Success)?.let { request = it.value }
    }
    val (title, supporting) = credentialRowText(request)
    ActionRow(Icons.Outlined.Key, "a login to save", title, onOpen, supporting)
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
    is Card.Report -> card.title
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
        is Card.Report -> c.formats.joinToString(" · ")
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
