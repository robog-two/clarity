package net.robog.clarity.app.ui.today

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.graphics.shapes.RoundedPolygon
import net.robog.clarity.app.ui.components.CompactTaskRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
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
import net.robog.clarity.app.ui.components.springIn
import net.robog.clarity.app.ui.components.sentenceCase
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.app.ui.theme.clarityText
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
    viewModel: TodayViewModel = metroViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TodayContent(state, viewModel::refresh, onReview, onOpenHeld, modifier)
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
    /** Whether the quieter tasks start unfolded (previews and screenshots). */
    quietExpanded: Boolean = false,
) {
    val pull = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
        state = pull,
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(state = pull, isRefreshing = state.refreshing, modifier = Modifier.align(Alignment.TopCenter))
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.kicker.isNotEmpty()) Kicker(state.kicker)
            Text("Today", style = MaterialTheme.clarityText.heroTitle)
            state.error?.let {
                ErrorBox(it)
                TextButton(onClick = onRefresh) { Text("Try again") }
            }
            val phase = when {
                state.loading -> Phase.Loading
                state.unavailable -> Phase.Unavailable
                state.today == null -> Phase.Nothing
                else -> Phase.Ready
            }
            // Loading cross-fades into the day instead of popping.
            val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            AnimatedContent(
                targetState = phase,
                transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) },
                label = "today phase",
            ) { p ->
                when (p) {
                    Phase.Loading -> LoadingSpinner(Modifier.padding(top = 32.dp))
                    Phase.Unavailable -> Hint("Your server can’t show today yet. Your plan, what I’m doing and what’s parked will appear here.")
                    Phase.Nothing -> Unit
                    Phase.Ready -> state.today?.let { DayBody(it, state, onReview, onOpenHeld, quietExpanded) }
                }
            }
        }
    }
}

private enum class Phase { Loading, Unavailable, Nothing, Ready }

@Composable
private fun DayBody(today: Today, state: TodayUiState, onReview: (HeldActionId) -> Unit, onOpenHeld: () -> Unit, quietExpanded: Boolean) {
    Column(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        var order = 0
        when {
            today.plan.isNotEmpty() -> PlanHero(today.plan, Modifier.springIn(order++))
            !today.called -> NoPlanYet(Modifier.springIn(order++))
        }
        val doing = state.doing
        if (!doing.isEmpty) {
            TodayLabel("I’m doing", Modifier.padding(top = 10.dp).springIn(order++))
            if (doing.main.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    doing.main.forEachIndexed { i, row ->
                        DoingRowView(row, groupShape(i, doing.main.size), onReview, Modifier.springIn(order++))
                    }
                }
            }
            if (doing.quiet.isNotEmpty()) QuietTasks(doing.quiet, quietExpanded, Modifier.springIn(order++))
        }
        HeldCard(state.heldWaiting, onOpenHeld, Modifier.padding(top = if (doing.isEmpty) 10.dp else 4.dp).springIn(order++))
        if (today.parked.isNotEmpty()) {
            TodayLabel("Parked", Modifier.padding(top = 10.dp).springIn(order++))
            ParkedChips(today.parked, Modifier.springIn(order++))
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
        Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Your plan", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
                Text(if (plan.size == 1) "1 step" else "${plan.size} steps", style = MaterialTheme.typography.labelLarge)
            }
            plan.forEachIndexed { i, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    StepBadge(i + 1, stepShapes[i % stepShapes.size].toShape())
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(sentenceCase("if ${step.cue}"), style = MaterialTheme.typography.labelLarge, color = colors.onSecondaryContainer.copy(alpha = 0.72f))
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
 * where the plan will land.
 */
@Composable
private fun NoPlanYet(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), color = colors.surfaceContainer, shape = RoundedCornerShape(28.dp)) {
        Column(
            Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Drawing(R.drawable.drawing_cheese, Modifier.size(120.dp), tint = colors.onSurface)
            Text(
                "“I gather words. For the winter days are long and many, and we’ll run out of things to say.”",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.clarityText.reading.copy(fontFamily = ClarityFonts.reading, fontStyle = FontStyle.Italic),
                textAlign = TextAlign.Center,
            )
            Kicker("Frederick, Leo Lionni")
            Text(
                "No plan yet",
                Modifier.padding(top = 14.dp),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Hint("It appears here after your morning call.")
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
) {
    TaskRow(
        title = row.task.title,
        detail = row.task.detail,
        shape = shape,
        glyph = { StatusGlyph(row.task, needsYou) },
        // The one Peach element on the screen: the action that needs the person.
        trailing = { if (row.review != null) ReviewButton { onReview(row.review) } else TaskStatus(taskStatusLabel(row.task.status)) },
        modifier = modifier,
    )
}

/**
 * The quieter tasks: waiting with nothing to review, or untouched for a long while. Folded by
 * default under one small line; unfolded they are compact, still rows with nothing to press.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QuietTasks(rows: List<DoingRow>, expandedInitially: Boolean, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(expandedInitially) }
    val colors = MaterialTheme.colorScheme
    val turn by animateFloatAsState(if (expanded) 180f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "quiet chevron")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide quieter tasks" else "Show quieter tasks") { expanded = !expanded }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(sentenceCase(quietLine(rows.size)), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = turn },
            )
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                rows.forEachIndexed { i, row ->
                    val (mark, fill) = quietMark(row.task.status)
                    CompactTaskRow(row.task.title, row.task.detail, quietStatusLabel(row.task.status), groupShape(i, rows.size), mark, fill)
                }
            }
        }
    }
}

/** A quiet row's status word: a waiting row here has nothing to review, so it says only "waiting". */
private fun quietStatusLabel(status: AgentTask.Status): String =
    if (status == AgentTask.Status.WAITING_FOR_YOU) "waiting" else taskStatusLabel(status)

/** A quiet row's small still mark: never the "needs you" Cookie in Sage. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun quietMark(status: AgentTask.Status): Pair<RoundedPolygon, Color> {
    val colors = MaterialTheme.colorScheme
    val muted = colors.onSurfaceVariant.copy(alpha = 0.35f)
    return when (status) {
        AgentTask.Status.WORKING -> MaterialShapes.Cookie9Sided to colors.secondaryContainer
        AgentTask.Status.SCHEDULED -> MaterialShapes.Clover4Leaf to muted
        AgentTask.Status.DONE -> MaterialShapes.Circle to colors.secondaryContainer
        AgentTask.Status.FAILED -> MaterialShapes.Burst to colors.error
        else -> MaterialShapes.Circle to muted
    }
}

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
private fun StatusGlyph(task: AgentTask, needsYou: Boolean) {
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
                Box(Modifier.matchParentSize().graphicsLayer { rotationZ = turn }.background(colors.secondaryContainer, shape))
                Drawing(solidFor(task.id), Modifier.matchParentSize(), tint = colors.onSecondaryContainer)
            }
        }
        else -> {
            val (shape, fill) = when (task.status) {
                // The Sage Cookie means there is something to review; a waiting row without one stays plain.
                AgentTask.Status.DRAFTED, AgentTask.Status.WAITING_FOR_YOU ->
                    if (needsYou) MaterialShapes.Cookie4Sided to colors.secondary else MaterialShapes.Circle to colors.outlineVariant
                AgentTask.Status.SCHEDULED -> MaterialShapes.Clover4Leaf to colors.onSurfaceVariant.copy(alpha = 0.35f)
                AgentTask.Status.DONE -> MaterialShapes.Circle to colors.secondaryContainer
                AgentTask.Status.FAILED -> MaterialShapes.Burst to colors.error
                else -> MaterialShapes.Circle to colors.onSurfaceVariant.copy(alpha = 0.35f)
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
            .clickable(interaction, indication = null, role = Role.Button, onClick = onOpenHeld),
        color = colors.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(if (waiting > 0) colors.tertiaryContainer else colors.surfaceContainerHighest, MaterialShapes.Cookie9Sided.toShape()),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$waiting",
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParkedChips(parked: List<String>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            parked.forEach { item ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Text(sentenceCase(item), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Hint("Back in tomorrow’s call.")
    }
}

/** Today's section label: quiet and neutral, so Peach stays for the one action. */
@Composable
internal fun TodayLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

private val previewTime = Instant.parse("2026-10-26T14:04:00Z")
private val previewDate = LocalDate(2026, 10, 26)
private const val PREVIEW_KICKER = "monday, October 26 · 2:04 pm"

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
            previewTask("q1", "Follow up with Nick about The Devils", "mail · waiting for the sent-mail sync fix", AgentTask.Status.WAITING_FOR_YOU, ago = 20.hours),
            previewTask("t3", "Nudge to leave for pickup", "nudge at 3:45", AgentTask.Status.SCHEDULED, ago = 2.hours),
            previewTask("t2", "Find a time with Nick for the Devils game", "calendar · checking both calendars", AgentTask.Status.WORKING, ago = 5.minutes),
            previewTask("q2", "Find Ulta haircut openings", "web · no openings this week", AgentTask.Status.WAITING_FOR_YOU, ago = 14.hours),
            previewTask("t1", "Reply to Dana about the revised contract terms", "mail · waits for your review", AgentTask.Status.DRAFTED, "h1", ago = 20.minutes),
        ),
        emptyMap(),
        previewTime,
    ),
    heldWaiting = 2,
)

@Composable
private fun PreviewFrame(state: TodayUiState, quietOpen: Boolean = false) = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
        TodayContent(state, onRefresh = {}, onReview = {}, onOpenHeld = {}, quietExpanded = quietOpen)
    }
}

@Preview(name = "light", widthDp = 390, heightDp = 1000)
@Preview(name = "dark", widthDp = 390, heightDp = 1000, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TodayPreview() = PreviewFrame(previewState)

@Preview(name = "quieter tasks open", widthDp = 390, heightDp = 1100)
@Composable
private fun TodayQuietOpenPreview() = PreviewFrame(previewState, quietOpen = true)

@Preview(name = "loading", widthDp = 390, heightDp = 780)
@Composable
private fun TodayLoadingPreview() = PreviewFrame(TodayUiState(kicker = PREVIEW_KICKER))

@Preview(name = "before call", widthDp = 390, heightDp = 780)
@Composable
private fun TodayBeforeCallPreview() = PreviewFrame(
    TodayUiState(loading = false, kicker = "monday, October 26 · 7:12 am", today = Today(date = previewDate)),
)

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
                DoingRow(previewTask("s$i", "A task that is ${status.name.lowercase().replace('_', ' ')}", "mail · detail line", status, review?.value), review)
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
