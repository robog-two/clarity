package net.robog.clarity.app.ui.today

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import net.robog.clarity.app.ui.cards.taskStatusLabel
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.LoadingSpinner
import net.robog.clarity.app.ui.components.ScreenGutter
import net.robog.clarity.app.ui.components.sentenceCase
import net.robog.clarity.app.ui.theme.ClarityPalette
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
                    Phase.Ready -> state.today?.let { DayBody(it, state, onReview, onOpenHeld) }
                }
            }
        }
    }
}

private enum class Phase { Loading, Unavailable, Nothing, Ready }

@Composable
private fun DayBody(today: Today, state: TodayUiState, onReview: (HeldActionId) -> Unit, onOpenHeld: () -> Unit) {
    Column(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        var order = 0
        when {
            today.plan.isNotEmpty() -> PlanHero(today.plan, Modifier.springIn(order++))
            !today.called -> NoPlanYet(Modifier.springIn(order++))
        }
        if (state.doing.isNotEmpty()) {
            TodayLabel("I’m doing", Modifier.padding(top = 10.dp).springIn(order++))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                state.doing.forEachIndexed { i, row ->
                    DoingRowView(row, groupShape(i, state.doing.size), onReview, Modifier.springIn(order++))
                }
            }
        }
        HeldCard(state.heldWaiting, onOpenHeld, Modifier.padding(top = if (state.doing.isEmpty()) 10.dp else 4.dp).springIn(order++))
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

/** Segmented-list corners: round outer ends, tight inner joins. */
internal fun groupShape(index: Int, count: Int): Shape {
    val outer = 24.dp
    val inner = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DoingRowView(row: DoingRow, shape: Shape, onReview: (HeldActionId) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = shape) {
        Row(
            Modifier.heightIn(min = 72.dp).padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusGlyph(row.task)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.task.title, style = MaterialTheme.clarityText.chat)
                Text(sentenceCase(row.task.detail), style = MaterialTheme.clarityText.chatSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (row.review != null) {
                // The one Peach element on the screen: the action that needs the person.
                Button(
                    onClick = { onReview(row.review) },
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(containerColor = ClarityPalette.Peach, contentColor = ClarityPalette.Charcoal),
                ) { Text("Review") }
            } else {
                Text(
                    sentenceCase(taskStatusLabel(row.task.status)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
private fun StatusGlyph(task: AgentTask) {
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
                AgentTask.Status.DRAFTED, AgentTask.Status.WAITING_FOR_YOU -> MaterialShapes.Cookie4Sided to colors.secondary
                AgentTask.Status.SCHEDULED -> MaterialShapes.Clover4Leaf to colors.onSurfaceVariant.copy(alpha = 0.35f)
                AgentTask.Status.DONE -> MaterialShapes.Circle to colors.secondaryContainer
                AgentTask.Status.FAILED -> MaterialShapes.Burst to colors.error
                else -> MaterialShapes.Circle to colors.onSurfaceVariant.copy(alpha = 0.35f)
            }
            Box(Modifier.padding(8.dp).size(28.dp).background(fill, shape.toShape()))
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

// ---------- Motion ----------

/**
 * Staggered arrival: each block rises a little and fades in on an expressive spring, [index] steps
 * after the first. Previews and screenshots show the settled layout.
 */
@Composable
internal fun Modifier.springIn(index: Int): Modifier {
    if (LocalInspectionMode.current) return this
    val progress = remember { Animatable(0f) }
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        delay(45L * index)
        launch { progress.animateTo(1f, spatial) }
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 24.dp.toPx()
    }
}

private val previewTime = Instant.parse("2026-10-26T14:04:00Z")
private val previewDate = LocalDate(2026, 10, 26)
private const val PREVIEW_KICKER = "monday, October 26 · 2:04 pm"

private fun previewTask(id: String, title: String, detail: String, status: AgentTask.Status, held: String? = null) =
    AgentTask(TaskId(id), title, detail, status, held?.let { HeldActionId(it) }, previewTime)

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
    doing = listOf(
        DoingRow(previewTask("t1", "Reply to Dana about the revised contract terms", "mail · waits for your review", AgentTask.Status.DRAFTED, "h1"), HeldActionId("h1")),
        DoingRow(previewTask("t2", "Find a time with Nick for the Devils game", "calendar · checking both calendars", AgentTask.Status.WORKING), null),
        DoingRow(previewTask("t3", "Nudge to leave for pickup", "nudge at 3:45", AgentTask.Status.SCHEDULED), null),
    ),
    heldWaiting = 2,
)

@Composable
private fun PreviewFrame(state: TodayUiState) = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
        TodayContent(state, onRefresh = {}, onReview = {}, onOpenHeld = {})
    }
}

@Preview(name = "light", widthDp = 390, heightDp = 1000)
@Preview(name = "dark", widthDp = 390, heightDp = 1000, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TodayPreview() = PreviewFrame(previewState)

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
        doing = AgentTask.Status.entries.mapIndexed { i, status ->
            val held = status == AgentTask.Status.DRAFTED || status == AgentTask.Status.WAITING_FOR_YOU
            val review = if (held) HeldActionId("h$i") else null
            DoingRow(previewTask("s$i", "A task that is ${status.name.lowercase().replace('_', ' ')}", "mail · detail line", status, review?.value), review)
        },
    ),
)

@Preview(name = "offline", widthDp = 390, heightDp = 1000)
@Composable
private fun TodayOfflinePreview() = PreviewFrame(previewState.copy(error = "Can’t reach your server. Check your connection."))

@Preview(name = "unavailable", widthDp = 390, heightDp = 780)
@Composable
private fun TodayUnavailablePreview() = PreviewFrame(TodayUiState(loading = false, unavailable = true, kicker = PREVIEW_KICKER))
