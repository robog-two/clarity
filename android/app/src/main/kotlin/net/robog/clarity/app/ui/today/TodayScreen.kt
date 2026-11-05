package net.robog.clarity.app.ui.today

import net.robog.clarity.app.ui.theme.Space

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import net.robog.clarity.R
import net.robog.clarity.app.ui.components.Drawing
import net.robog.clarity.app.ui.theme.ClarityFonts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.graphics.shapes.RoundedPolygon
import net.robog.clarity.app.ui.components.CompactTaskRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import net.robog.clarity.app.ui.cards.taskStatusLabel
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.LoadingSpinner
import net.robog.clarity.app.ui.components.ReviewButton
import net.robog.clarity.app.ui.components.ScreenGutter
import net.robog.clarity.app.ui.components.TaskRow
import net.robog.clarity.app.ui.components.TaskShape
import net.robog.clarity.app.ui.components.TaskStatus
import net.robog.clarity.app.ui.components.groupShape
import net.robog.clarity.app.ui.components.ClarityScreen
import net.robog.clarity.app.ui.components.Reveal
import net.robog.clarity.app.ui.components.ScreenLevel
import net.robog.clarity.app.ui.components.relativeTime
import net.robog.clarity.app.ui.components.springEnter
import net.robog.clarity.app.ui.components.springExit
import net.robog.clarity.app.ui.components.sentenceCase
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.app.ui.theme.onSecondaryContainerMuted
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.session.Today

@Composable
fun TodayScreen(
    onReview: (HeldActionId) -> Unit,
    onOpenHeld: () -> Unit,
    modifier: Modifier = Modifier,
    /** A task tapped: its thread (the Agent's Path). */
    onOpenTask: (TaskId) -> Unit = {},
    viewModel: TodayViewModel = metroViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TodayContent(state, viewModel::refresh, onReview, onOpenHeld, modifier, onCallAgain = viewModel::callAgain, onOpenTask = onOpenTask)
}

/**
 * Today, laid out with the Material 3 Expressive tactics: the plan is the one hero moment (a Sage
 * container with shaped step badges), "I'm doing" is a contained group with hand-drawn solids for running work, Peach is kept for the single
 * key action (Review), and everything arrives on expressive springs.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TodayContent(
    state: TodayUiState,
    onRefresh: () -> Unit,
    onReview: (HeldActionId) -> Unit,
    onOpenHeld: () -> Unit,
    modifier: Modifier = Modifier,
    /** Whether the Inactive fold starts unfolded (previews and screenshots). It starts closed on every visit. */
    inactiveExpanded: Boolean = false,
    /** "Call now" / "Call again", until today's call reached the hand-off ([TodayUiState.callOffer]). */
    onCallAgain: () -> Unit = {},
    /** A task tapped: its thread (the Agent's Path). */
    onOpenTask: (TaskId) -> Unit = {},
) {
    val phase = when {
        state.loading -> Phase.Loading
        state.unavailable -> Phase.Unavailable
        state.today == null -> Phase.Nothing
        else -> Phase.Ready
    }
    // Loading gives way to the day (the screen swaps its body, and the day's blocks rise in turn).
    ClarityScreen(
        title = "Today",
        modifier = modifier,
        level = ScreenLevel.Top,
        kicker = state.kicker.takeIf { it.isNotEmpty() },
        phase = phase,
        refreshing = state.refreshing,
        onRefresh = onRefresh,
    ) { shown ->
        Reveal(state.callOffer == CallOffer.REJOIN) { CallAgainCard(state.callAgainProblem, onCallAgain) }
        state.error?.let {
            ErrorBox(it)
            TextButton(onClick = onRefresh) { Text("Try again") }
        }
        when (shown) {
            Phase.Loading -> LoadingSpinner(Modifier.padding(top = Space.Large))
            Phase.Unavailable -> Hint("Server isn’t ready for Today.")
            Phase.Nothing -> Unit
            Phase.Ready -> state.today?.let { DayBody(it, state, onReview, onOpenHeld, inactiveExpanded, onOpenTask, onCallAgain) }
        }
    }
}

private enum class Phase { Loading, Unavailable, Nothing, Ready }

@Composable
private fun DayBody(
    today: Today,
    state: TodayUiState,
    onReview: (HeldActionId) -> Unit,
    onOpenHeld: () -> Unit,
    inactiveExpanded: Boolean,
    onOpenTask: (TaskId) -> Unit,
    onCall: () -> Unit,
) {
    when {
        today.plan.isNotEmpty() -> PlanHero(today.plan)
        !today.called -> NoPlanYet(state.callOffer, state.callAgainProblem, onCall)
    }
    // Held first: anything waiting on an OK must be seen.
    HeldCard(state.heldWaiting, onOpenHeld)
    val doing = state.doing
    if (doing.main.isNotEmpty()) {
        TodayLabel("Doing", Modifier.padding(top = Space.Medium))
        Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
            doing.main.forEachIndexed { i, row ->
                DoingRowView(row, groupShape(i, doing.main.size), onReview, onOpen = { onOpenTask(row.task.id) })
            }
        }
    }
    // Last: everything inactive under one fold, closed on every visit.
    inactiveLine(doing.inactive.size, today.parked.size)?.let { line ->
        Folded(line, "inactive items", inactiveExpanded) {
            InactiveRows(doing.inactive, today.parked, doing.now, onOpenTask)
        }
    }
}

// ---------- The call, still going on ----------

/** Today's call was left unfinished (swiped away, or left to look something up): ring again to pick it up. */
@Composable
private fun CallAgainCard(problem: String?, onCallAgain: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), color = colors.surfaceContainerHigh, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(horizontal = Space.Medium, vertical = Space.Medium), verticalArrangement = Arrangement.spacedBy(Space.Medium)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.Medium), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Call, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text("Meeting not finished", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                    Text("Call again to continue.", style = MaterialTheme.clarityText.chatSmall)
                }
            }
            problem?.let { Text(it, style = MaterialTheme.clarityText.chatSmall, color = colors.error) }
            FilledTonalButton(onClick = onCallAgain, Modifier.align(Alignment.End)) { Text("Call again") }
        }
    }
}

// ---------- Plan: the hero (also on the call's "Your Morning") ----------

/** One badge shape per step, so the plan reads as a sequence of distinct moments. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val stepShapes = listOf(MaterialShapes.Cookie4Sided, MaterialShapes.Sunny, MaterialShapes.Clover4Leaf, MaterialShapes.Pentagon, MaterialShapes.Cookie6Sided)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlanHero(plan: List<IfThen>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(Modifier.padding(horizontal = Space.Medium, vertical = Space.Large), verticalArrangement = Arrangement.spacedBy(Space.Medium)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Your plan", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
                Text(if (plan.size == 1) "1 step" else "${plan.size} steps", style = MaterialTheme.typography.labelLarge)
            }
            plan.forEachIndexed { i, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.Medium), verticalAlignment = Alignment.Top) {
                    StepBadge(i + 1, stepShapes[i % stepShapes.size].toShape())
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                        Text(sentenceCase("if ${step.cue}"), style = MaterialTheme.typography.labelLarge, color = colors.onSecondaryContainerMuted)
                        Text(sentenceCase(step.action), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
    }
}

@Composable
private fun StepBadge(n: Int, shape: Shape) {
    Box(
        Modifier.size(36.dp).background(MaterialTheme.colorScheme.surface, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Before the morning call: Frederick's cheese and his line about gathering words for the winter,
 * where the plan will land. Until a call reached the hand-off it also offers to ring for one:
 * [CallOffer.START] when none ran today, [CallOffer.RESTART] when one ended early. A call still going
 * on is offered above the day instead ([CallAgainCard]).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NoPlanYet(offer: CallOffer, problem: String?, onCall: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), color = colors.surfaceContainer, shape = RoundedCornerShape(28.dp)) {
        Column(
            Modifier.padding(horizontal = Space.Large, vertical = Space.Large),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.Small),
        ) {
            Drawing(R.drawable.drawing_cheese, Modifier.size(120.dp), tint = colors.onSurface)
            Text(
                "“I gather words. For the winter days are long and many, and we’ll run out of things to say.”",
                Modifier.padding(top = Space.Small),
                style = MaterialTheme.clarityText.reading.copy(fontFamily = ClarityFonts.reading, fontStyle = FontStyle.Italic),
                textAlign = TextAlign.Center,
            )
            Kicker("Frederick, Leo Lionni")
            val (title, hint, action) = when (offer) {
                CallOffer.START -> Triple("Start morning meeting", "Plan appears after the call.", "Call now")
                CallOffer.RESTART -> Triple("Meeting not finished", "Plan appears when it ends.", "Call again")
                CallOffer.NONE, CallOffer.REJOIN -> Triple("No plan yet", "Plan appears after the morning call.", null)
            }
            Text(
                title,
                Modifier.padding(top = Space.Medium),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
            )
            Hint(hint)
            if (action != null) {
                problem?.let { Text(it, style = MaterialTheme.clarityText.chatSmall, color = colors.error, textAlign = TextAlign.Center) }
                FilledTonalButton(onClick = onCall, shapes = ButtonDefaults.shapes(), modifier = Modifier.padding(top = Space.Medium)) {
                    Icon(Icons.Outlined.Call, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                    Text(action, Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }
        }
    }
}

// ---------- I'm doing: a contained group ----------

@Composable
internal fun DoingRowView(
    row: DoingRow,
    shape: Shape,
    onReview: (HeldActionId) -> Unit,
    modifier: Modifier = Modifier,
    /** Whether the row shows the "needs you" shape: only when there is something to review. */
    needsYou: Boolean = row.review != null,
    /** Opens the task's thread; Review stays its own button. */
    onOpen: (() -> Unit)? = null,
) {
    TaskRow(
        title = row.task.title,
        detail = row.task.detail,
        shape = shape,
        glyph = { StatusGlyph(row.task, needsYou) },
        // The one Peach element on the screen: the action that needs the person.
        trailing = { if (row.review != null) ReviewButton({ onReview(row.review) }, row.task.title) else TaskStatus(taskStatusLabel(row.task.status)) },
        modifier = modifier,
        onClick = onOpen,
    )
}

/**
 * A section folded under one small [line] with a turning chevron: Inactive, closed on every visit
 * (plain `remember`, so no open state outlives the screen). [what] names the contents for the click label.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Folded(
    line: String,
    what: String,
    expandedInitially: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(expandedInitially) }
    val colors = MaterialTheme.colorScheme
    val turn by animateFloatAsState(if (expanded) 180f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "fold chevron")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide $what" else "Show $what") { expanded = !expanded }
                .heightIn(min = 48.dp)
                .padding(horizontal = Space.Medium),
            horizontalArrangement = Arrangement.spacedBy(Space.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(line, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = turn },
            )
        }
        Reveal(expanded) { content() }
    }
}

/**
 * The Inactive fold's rows: the task's title and a relative time, newest first, then the parked
 * items (no time). The mark's shape says why the row is here and TalkBack says it too. A task opens
 * its Agent's Path.
 */
@Composable
private fun InactiveRows(rows: List<DoingRow>, parked: List<String>, now: Instant, onOpenTask: (TaskId) -> Unit) {
    val count = rows.size + parked.size
    Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
        rows.forEachIndexed { i, row ->
            val state = inactiveState(row.task)
            val (mark, fill) = inactiveMark(state)
            CompactTaskRow(
                row.task.title, relativeTime(row.task.updatedAt, now), inactiveStateLabel(state), groupShape(i, count), mark, fill,
                onClick = { onOpenTask(row.task.id) },
            )
        }
        parked.forEachIndexed { j, item ->
            val (mark, fill) = parkedMark()
            CompactTaskRow(sentenceCase(item), "", "Next call", groupShape(rows.size + j, count), mark, fill)
        }
    }
}

/** What an Inactive row's mark means, for TalkBack. */
private fun inactiveStateLabel(state: InactiveState): String = when (state) {
    InactiveState.DONE -> "Done"
    InactiveState.FAILED -> "Failed"
    InactiveState.CANCELLED -> "Cancelled"
    InactiveState.STALE -> "No update"
}

/** One shape per Inactive state, so the state never rests on color alone. All fills are >=3:1 on the row. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun inactiveMark(state: InactiveState): Pair<RoundedPolygon, Color> {
    val colors = MaterialTheme.colorScheme
    return when (state) {
        InactiveState.DONE -> MaterialShapes.Circle to colors.secondary
        InactiveState.FAILED -> MaterialShapes.Burst to colors.error
        InactiveState.CANCELLED -> MaterialShapes.Diamond to colors.outline
        InactiveState.STALE -> MaterialShapes.Square to colors.outline
    }
}

/** A parked item's mark: its own shape, waiting for the next call. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun parkedMark(): Pair<RoundedPolygon, Color> = MaterialShapes.Pentagon to MaterialTheme.colorScheme.outline

/** The three solids, one per running task (picked by task id so a task keeps its solid). */
private val solids = listOf(R.drawable.drawing_sphere, R.drawable.drawing_cube, R.drawable.drawing_pyramid)

private fun solidFor(id: TaskId): Int = solids[Math.floorMod(id.value.hashCode(), solids.size)]

/**
 * A shape per status, drawings only (no Material icons in this section). A running task gets a
 * hand-drawn solid inside a wobbly shape that slowly turns; everything else is a still, quiet shape
 * and the status word on the right says which.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StatusGlyph(task: AgentTask, needsYou: Boolean) {
    val colors = MaterialTheme.colorScheme
    val size = Modifier.size(44.dp)
    when (task.status) {
        AgentTask.Status.WORKING -> {
            val turn = if (LocalInspectionMode.current) 0f else {
                val spin = rememberInfiniteTransition(label = "working")
                spin.animateFloat(0f, 360f, infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "turn").value
            }
            val shape = MaterialShapes.Cookie9Sided.toShape()
            Box(size, contentAlignment = Alignment.Center) {
                // Only the shape turns; the drawing stays upright.
                Box(Modifier.matchParentSize().graphicsLayer { rotationZ = turn }.background(colors.secondary, shape))
                Drawing(solidFor(task.id), Modifier.matchParentSize(), tint = colors.onSecondary)
            }
        }
        else -> {
            val (shape, fill) = when (task.status) {
                // The Sage Cookie means there is something to review; a waiting row without one stays plain.
                AgentTask.Status.DRAFTED, AgentTask.Status.WAITING_FOR_YOU ->
                    if (needsYou) MaterialShapes.Cookie4Sided to colors.secondary else MaterialShapes.Circle to colors.outline
                AgentTask.Status.SCHEDULED -> MaterialShapes.Clover4Leaf to colors.outline
                AgentTask.Status.DONE -> MaterialShapes.Circle to colors.secondary
                AgentTask.Status.FAILED -> MaterialShapes.Burst to colors.error
                else -> MaterialShapes.Circle to colors.outline
            }
            TaskShape(shape, fill)
        }
    }
}

// ---------- Held and parked ----------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeldCard(waiting: Int, onOpenHeld: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // A springy squish on press.
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "held press")
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics(mergeDescendants = true) { contentDescription = "Held for you, ${heldLine(waiting)}" }
            .clickable(interaction, indication = null, role = Role.Button, onClick = onOpenHeld),
        color = colors.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(Modifier.padding(horizontal = Space.Medium, vertical = Space.Medium), horizontalArrangement = Arrangement.spacedBy(Space.Medium), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(if (waiting > 0) colors.tertiaryContainer else colors.surfaceContainerHighest, MaterialShapes.Cookie9Sided.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$waiting",
                    Modifier.clearAndSetSemantics {},
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (waiting > 0) colors.onTertiaryContainer else colors.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f)) {
                Text("Held for you", style = MaterialTheme.clarityText.chat)
                Text(sentenceCase(heldLine(waiting)), style = MaterialTheme.clarityText.chatSmall, color = colors.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant)
        }
    }
}

/** Today's section label: quiet and neutral, so Peach stays for the one action. */
@Composable
internal fun TodayLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = Space.Small).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

private val previewTime = Instant.parse("2026-10-26T14:04:00Z")
private val previewDate = LocalDate(2026, 10, 26)
private const val PREVIEW_KICKER = "Monday, October 26th."

private fun previewTask(id: String, title: String, detail: String, status: AgentTask.Status, held: String? = null, ago: Duration = Duration.ZERO) =
    AgentTask(TaskId(id), title, detail, status, held?.let { HeldActionId(it) }, previewTime - ago)

private val previewState = TodayUiState(
    loading = false,
    kicker = PREVIEW_KICKER,
    today = Today(
        date = previewDate,
        plan = listOf(
            IfThen("it’s 9:30", "spec at my desk until 12, phone face down"),
            IfThen("Dana emails about the contract", "it waits until 12 unless she says it’s urgent"),
            IfThen("it’s 4:00", "walk before the school pickup"),
        ),
        parked = listOf("feedback for Alex", "expense report", "renewing the car registration"),
        called = true,
    ),
    doing = doingRows(
        listOf(
            previewTask("q1", "Follow up with Nick about The Devils", "Mail, waiting for the sent-mail sync fix", AgentTask.Status.WAITING_FOR_YOU, ago = 20.hours),
            previewTask("t3", "Nudge to leave for pickup", "nudge at 3:45", AgentTask.Status.SCHEDULED, ago = 2.hours),
            previewTask("t2", "Find a time with Nick for the Devils game", "Calendar, checking both calendars", AgentTask.Status.WORKING, ago = 5.minutes),
            previewTask("q2", "Find Ulta haircut openings", "Web, no openings this week", AgentTask.Status.WAITING_FOR_YOU, ago = 14.hours),
            previewTask("t1", "Reply to Dana about the revised contract terms", "Mail, waits for your review", AgentTask.Status.DRAFTED, "h1", ago = 20.minutes),
            previewTask("f1", "Check the dentist’s opening hours", "Web, open until 6 today", AgentTask.Status.DONE, ago = 40.minutes),
            previewTask("f2", "Book the car service", "Web, the booking page timed out", AgentTask.Status.FAILED, ago = 1.hours),
        ),
        emptyMap(),
        previewTime,
    ),
    heldWaiting = 2,
)

@Composable
private fun PreviewFrame(state: TodayUiState, inactiveOpen: Boolean = false) = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
        TodayContent(state, onRefresh = {}, onReview = {}, onOpenHeld = {}, inactiveExpanded = inactiveOpen)
    }
}

@Preview(name = "light", widthDp = 390, heightDp = 1000)
@Preview(name = "dark", widthDp = 390, heightDp = 1000, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TodayPreview() = PreviewFrame(previewState)

@Preview(name = "inactive open", widthDp = 390, heightDp = 1300)
@Composable
private fun TodayInactiveOpenPreview() = PreviewFrame(previewState, inactiveOpen = true)

@Preview(name = "failed task in the list", widthDp = 390, heightDp = 1100)
@Composable
private fun TodayFailedPreview() = PreviewFrame(previewState)

@Preview(name = "loading", widthDp = 390, heightDp = 780)
@Composable
private fun TodayLoadingPreview() = PreviewFrame(TodayUiState(kicker = PREVIEW_KICKER))

@Preview(name = "before call", widthDp = 390, heightDp = 780)
@Composable
private fun TodayBeforeCallPreview() = PreviewFrame(
    TodayUiState(loading = false, kicker = "Monday, October 26th.", today = Today(date = previewDate)),
)

@Preview(name = "call unfinished", widthDp = 390, heightDp = 1000)
@Composable
private fun TodayCallAgainPreview() = PreviewFrame(previewState.copy(callOffer = CallOffer.REJOIN))

@Preview(name = "quiet day", widthDp = 390, heightDp = 780)
@Composable
private fun TodayQuietPreview() = PreviewFrame(
    TodayUiState(loading = false, kicker = PREVIEW_KICKER, today = Today(date = previewDate, called = true)),
)

@Preview(name = "every status", widthDp = 390, heightDp = 1200)
@Composable
private fun TodayEveryStatusPreview() = PreviewFrame(
    previewState.copy(
        doing = Doing(
            main = AgentTask.Status.entries.mapIndexed { i, status ->
                val held = status == AgentTask.Status.DRAFTED || status == AgentTask.Status.WAITING_FOR_YOU
                val review = if (held) HeldActionId("h$i") else null
                DoingRow(previewTask("s$i", "A task that is ${status.name.lowercase().replace('_', ' ')}", "Mail, detail line", status, review?.value), review)
            },
        ),
    ),
)

@Preview(name = "offline", widthDp = 390, heightDp = 1000)
@Composable
private fun TodayOfflinePreview() = PreviewFrame(previewState.copy(error = "Can’t reach your server. Check your connection."))

@Preview(name = "unavailable", widthDp = 390, heightDp = 780)
@Composable
private fun TodayUnavailablePreview() = PreviewFrame(TodayUiState(loading = false, unavailable = true, kicker = PREVIEW_KICKER))
