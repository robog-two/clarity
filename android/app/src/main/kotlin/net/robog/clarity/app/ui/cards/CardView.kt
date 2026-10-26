package net.robog.clarity.app.ui.cards

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.time.Instant
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.InfoBox
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.PrimaryButton
import net.robog.clarity.app.ui.components.SlideToConfirm
import net.robog.clarity.app.ui.held.NotNow
import net.robog.clarity.app.ui.components.SecondaryButton
import net.robog.clarity.app.ui.theme.BubbleShape
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.app.ui.held.needsCode
import net.robog.clarity.app.vault.CredentialFormCard
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.Chip
import net.robog.clarity.shared.feed.ContextItem
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.governance.Decision
import net.robog.clarity.shared.governance.GuardVerdict
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.HeldDecision
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.Urgency

/**
 * What cards need besides themselves: proposals and task lists only carry ids, so the host
 * (feed, call, ask) passes the cached records and the callback for the proposal buttons.
 */
data class CardEnv(
    val held: Map<HeldActionId, HeldAction> = emptyMap(),
    val tasks: Map<TaskId, AgentTask> = emptyMap(),
    val onDecision: (HeldActionId, HeldDecision) -> Unit = { _, _ -> },
    /** Opens the full review screen (a two-factor code is typed there). */
    val onReview: (HeldActionId) -> Unit = {},
    /** "approve automatically from now on" with the (edited) rule text and reason. */
    val onAlways: (HeldActionId, String, String) -> Unit = { _, _, _ -> },
    /** Changes when a decision failed, so an approve slider springs back. */
    val resetKey: Any? = null,
)

private const val CURSOR = "▍"

/** One renderer for every [Card] type, shared by the call, ask and feed screens. */
@Composable
fun CardView(card: Card, modifier: Modifier = Modifier, streaming: Boolean = false, env: CardEnv = CardEnv()) {
    when (card) {
        is Card.Text -> AgentText(card.text, modifier, streaming)
        is Card.Said -> SaidBubble(card.text, modifier)
        is Card.Context -> ContextBox(card, modifier)
        is Card.Chips -> ChipList(card, modifier)
        is Card.Plan -> PlanBox(card, modifier)
        is Card.Note -> NoteBox(card.text, modifier)
        is Card.Proposal -> ProposalBox(env.held[card.heldActionId], env.onDecision, modifier, env.onReview, env.onAlways, env.resetKey)
        is Card.Tasks -> TaskList(card, env.tasks, modifier)
        is Card.CredentialRequest -> CredentialFormCard(card.requestId, modifier)
    }
}

/** All cards of one feed item, stacked. While the item streams, its last text card shows a cursor. */
@Composable
fun FeedItemView(item: FeedItem, modifier: Modifier = Modifier, env: CardEnv = CardEnv()) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item.cards.forEachIndexed { i, card ->
            CardView(card, streaming = item.streaming && i == item.cards.lastIndex, env = env)
        }
    }
}

/** `.ag`: the agent speaking. */
@Composable
fun AgentText(text: String, modifier: Modifier = Modifier, streaming: Boolean = false) {
    Text(
        if (streaming) text + CURSOR else text,
        modifier.fillMaxWidth(),
        style = MaterialTheme.clarityText.agent,
    )
}

/** `.you`: what the person said, right-aligned. */
@Composable
fun SaidBubble(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            Modifier
                .widthIn(max = 280.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, BubbleShape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.clarityText.reading,
        )
    }
}

/** `.live`: words heard so far (dashed, not yet sent). */
@Composable
fun LiveBubble(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            Modifier
                .widthIn(max = 280.dp)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), BubbleShape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.clarityText.reading,
        )
    }
}

/** `.ctx`: records looked up for something the person mentioned. */
@Composable
fun ContextBox(card: Card.Context, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .widthIn(max = 268.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Kicker(card.label)
            card.items.forEach { item ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        item.tag.uppercase(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(item.title, style = MaterialTheme.clarityText.contextBody)
                }
            }
        }
    }
}

/** `.chip` list under a caption. */
@Composable
fun ChipList(card: Card.Chips, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Kicker(card.label)
        card.items.forEach { chip ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(chip.text, Modifier.weight(1f), style = MaterialTheme.clarityText.readingSmall, color = MaterialTheme.colorScheme.onSurface)
                if (chip.sub.isNotBlank()) Kicker(chip.sub)
            }
        }
    }
}

/** The plan box: if-then sentences on the primary container. */
@Composable
fun PlanBox(card: Card.Plan, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Kicker("your plan")
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.large)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            card.items.forEach {
                Text(
                    it.sentence(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.clarityText.reading.copy(fontSize = MaterialTheme.clarityText.reading.fontSize * 15 / 16),
                )
            }
        }
    }
}

/** `.note`: an italic aside on the tertiary container. */
@Composable
fun NoteBox(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        style = MaterialTheme.clarityText.readingSmall.copy(fontStyle = FontStyle.Italic),
    )
}

/** The proposal box: the action, what the agent thinks, and the buttons while it still waits for an OK. */
@Composable
fun ProposalBox(
    held: HeldAction?,
    onDecision: (HeldActionId, HeldDecision) -> Unit,
    modifier: Modifier = Modifier,
    onReview: (HeldActionId) -> Unit = {},
    onAlways: (HeldActionId, String, String) -> Unit = { _, _, _ -> },
    resetKey: Any? = null,
) {
    InfoBox(modifier, border = MaterialTheme.colorScheme.primary) {
        if (held == null) {
            Kicker("needs your ok")
            Hint("Loading the details…")
            return@InfoBox
        }
        Kicker(proposalKicker(held))
        Text(held.action.summary, style = MaterialTheme.typography.titleMedium)
        Kicker("what I think", Modifier.padding(top = 6.dp))
        Hint(held.agentView)
        Hint(held.verdict.rationale)
        val done = heldStatusLabel(held.status)
        if (done == null) {
            if (held.needsCode) {
                PrimaryButton("enter the code", { onReview(held.id) }, Modifier.fillMaxWidth().padding(top = 6.dp))
            } else {
                SlideToConfirm(slideLabel(held.action.kind), { onDecision(held.id, HeldDecision.Approve()) }, Modifier.padding(top = 6.dp), resetKey = held to resetKey)
            }
            NotNow(held, onDeny = { onDecision(held.id, HeldDecision.Decline) }, onAlways = { t, b -> onAlways(held.id, t, b) })
        } else {
            Text(done, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Progress of tasks the agent is doing: title, detail, status. */
@Composable
fun TaskList(card: Card.Tasks, tasks: Map<TaskId, AgentTask>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Kicker(card.label)
        card.taskIds.forEach { id ->
            val task = tasks[id]
            HorizontalDivider(Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(task?.title ?: "…", style = MaterialTheme.clarityText.reading.copy(fontSize = MaterialTheme.clarityText.reading.fontSize * 15 / 16))
                    if (task != null) Kicker(task.detail)
                }
                if (task != null) {
                    Text(taskStatusLabel(task.status), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

// --- previews ---

private val at = Instant.parse("2026-10-26T10:04:00Z")

private val sampleHeld = HeldAction(
    id = HeldActionId("h1"),
    createdAt = at,
    action = ProposedAction.MoveEvent(
        idempotencyKey = "k", summary = "Move call with Dana to thursday 2:00",
        event = EntityId("e"), start = at, end = at, notifyAttendees = true,
    ),
    verdict = GuardVerdict(Decision.ASK, rationale = "I’m asking because it’s her invite, and moving other people’s events always needs your review."),
    agentView = "Thursday 2:00 is clear for both of you, and Dana hasn’t confirmed today yet.",
    urgency = Urgency.NORMAL,
    status = HeldAction.Status.HELD,
)

private val sampleTask = AgentTask(TaskId("t1"), "reply to Dana", "drafted · waits for you", AgentTask.Status.WAITING_FOR_YOU, null, at)

@Composable
private fun PreviewColumn(content: @Composable () -> Unit) {
    ClarityTheme {
        Column(
            Modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun TextCardsPreview() = PreviewColumn {
    CardView(Card.Text("Morning. What’s on your mind?"))
    CardView(Card.Text("Looking at your calendar so far"), streaming = true)
    CardView(Card.Said("Dana wants the contract signed by Friday.", InputSource.VOICE))
    LiveBubble("and then I need to call the school about")
    CardView(Card.Note("Nothing goes out as you without your review."))
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun ContextAndChipsPreview() = PreviewColumn {
    CardView(
        Card.Context(
            "“Dana’s contract” · 2 found",
            listOf(ContextItem("mail · yesterday", "Re: Revised Terms"), ContextItem("files · tue", "Contract_v3.pdf")),
        ),
    )
    CardView(Card.Chips("captured · 3", listOf(Chip("Reply to Dana", "mail"), Chip("Expense report", "due fri"), Chip("Feedback for Alex"))))
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun PlanPreview() = PreviewColumn {
    CardView(Card.Plan(listOf(IfThen("it’s 9:30", "spec at my desk until 12"), IfThen("Dana emails", "it waits until 12"))))
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun ProposalPreview() = PreviewColumn {
    val env = CardEnv(held = mapOf(sampleHeld.id to sampleHeld))
    CardView(Card.Proposal(sampleHeld.id), env = env)
    CardView(Card.Proposal(sampleHeld.id), env = CardEnv(held = mapOf(sampleHeld.id to sampleHeld.copy(status = HeldAction.Status.EXECUTED))))
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun TasksPreview() = PreviewColumn {
    CardView(
        Card.Tasks("setting up", listOf(sampleTask.id, TaskId("t2"))),
        env = CardEnv(tasks = mapOf(sampleTask.id to sampleTask)),
    )
}

