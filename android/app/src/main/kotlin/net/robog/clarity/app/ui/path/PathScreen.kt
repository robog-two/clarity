package net.robog.clarity.app.ui.path

import net.robog.clarity.app.ui.theme.Space
import net.robog.clarity.app.ui.components.ClarityListScreen

import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.heading
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import net.robog.clarity.app.data.frameUrl
import net.robog.clarity.app.ui.call.formatClock
import net.robog.clarity.app.ui.components.BackLink
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.LoadingSpinner
import net.robog.clarity.app.ui.components.ReviewButton
import net.robog.clarity.app.ui.components.ScreenGutter
import net.robog.clarity.app.ui.components.sentenceCase
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.app.ui.today.StatusGlyph
import net.robog.clarity.shared.browser.BrowserStepView
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.path.AgentPath
import net.robog.clarity.shared.path.Outcome
import net.robog.clarity.shared.path.PathItem
import net.robog.clarity.shared.path.PathSubject

/** A task as a thread: its title and status, Review when something waits on the person, and its Agent's Path. */
@Composable
fun TaskThreadScreen(
    taskId: TaskId,
    onBack: () -> Unit,
    onReview: (HeldActionId) -> Unit,
    onOpenRun: (String) -> Unit,
    viewModel: PathViewModel = metroViewModel(),
) {
    LaunchedEffect(taskId) { viewModel.open(PathTarget.Task(taskId)) }
    PathScreenContent(viewModel, onBack, "Today", onReview, onOpenRun)
}

/** One helper run's Agent's Path, stepped into from its task. */
@Composable
fun RunPathScreen(runId: String, onBack: () -> Unit, onOpenRun: (String) -> Unit, viewModel: PathViewModel = metroViewModel()) {
    LaunchedEffect(runId) { viewModel.open(PathTarget.Run(runId)) }
    PathScreenContent(viewModel, onBack, "Back", onReview = {}, onOpenRun = onOpenRun)
}

@Composable
private fun PathScreenContent(viewModel: PathViewModel, onBack: () -> Unit, backLabel: String, onReview: (HeldActionId) -> Unit, onOpenRun: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler { onBack() }
    // A running path refreshes while it is on screen, never in the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val live = state.path?.live == true
    LaunchedEffect(live) { if (live) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.poll() } }
    PathContent(state, onBack, backLabel, viewModel::refresh, viewModel::loadMore, onOpenRun, onReview)
}

/** A screenshot opened full screen. */
internal data class OpenFrame(val url: String, val box: JevBox?, val boxColor: Color, val description: String)

/**
 * The Agent's Path: a header with the totals, then one node per step on a rail, newest last. A
 * helper's steps follow its node, indented on a rail of their own; browser steps carry their
 * screenshots with Jev's box, grouped by tab.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PathContent(
    state: PathUiState,
    onBack: () -> Unit,
    backLabel: String,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenRun: (String) -> Unit,
    onReview: (HeldActionId) -> Unit,
    modifier: Modifier = Modifier,
    zone: TimeZone = TimeZone.currentSystemDefault(),
    /** Previews: which frame of each two-frame step shows (0 before, 1 after). */
    initialFramePage: Int = 0,
) {
    var open by remember { mutableStateOf<OpenFrame?>(null) }
    val path = state.path
    ClarityListScreen(
        title = null,
        modifier = modifier,
        backLabel = backLabel,
        onBack = onBack,
        refreshing = state.refreshing,
        onRefresh = onRefresh.takeIf { path != null },
        // The rail runs unbroken from row to row.
        itemSpacing = 0.dp,
    ) {
        if (path == null) {
            item(key = "state") {
                Column(verticalArrangement = Arrangement.spacedBy(Space.Medium)) {
                    if (state.loading) LoadingSpinner(Modifier.padding(top = Space.Large).align(Alignment.CenterHorizontally))
                    else {
                        ErrorBox("Can’t show this path", state.error)
                        TextButton(onClick = onRefresh) { Text("Try again") }
                    }
                }
            }
            return@ClarityListScreen
        }
        item(key = "header") { PathHeader(path, onReview, Modifier.padding(bottom = Space.Medium)) }
        if (state.rows.isEmpty() && !path.live) item(key = "empty") { Hint("Nothing recorded yet.") }
        val lastIndex = state.rows.lastIndex
        items(state.rows.size, key = { state.rows[it].key }) { i ->
            val row = state.rows[i]
            val nextDepth = state.rows.getOrNull(i + 1)?.depth
            TimelineRow(
                row = row,
                first = i == 0,
                last = i == lastIndex && !path.live,
                continuesBelow = nextDepth != null && nextDepth >= row.depth,
                zone = zone,
                frameBase = state.frameBase,
                onOpenRun = onOpenRun,
                onOpenFrame = { open = it },
                initialFramePage = initialFramePage,
                live = path.live && i == lastIndex,
                helperRun = path.subject.kind == PathSubject.Kind.RUN,
            )
        }
        if (path.live) item(key = "working") { WorkingRow(state.rows.lastOrNull()?.depth ?: 0) }
        if (path.next != null) item(key = "more") {
            TextButton(onClick = onLoadMore, enabled = !state.loadingMore, modifier = Modifier.padding(top = Space.Small)) {
                Text(if (state.loadingMore) "Loading" else "Show more")
            }
        }
    }
    open?.let { FullScreenFrame(it) { open = null } }
}

// ---------- Header ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PathHeader(path: AgentPath, onReview: (HeldActionId) -> Unit, modifier: Modifier = Modifier) {
    val subject = path.subject
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.Medium)) {
        Kicker(if (subject.kind == PathSubject.Kind.TASK) "task path" else "helper path")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.Medium), verticalAlignment = Alignment.CenterVertically) {
            val task = taskOf(subject)
            if (task != null) StatusGlyph(task, needsYou = subject.heldActionId != null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                Text(subject.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val line = listOfNotNull(sentenceCase(statusLabel(subject)), subject.detail?.takeIf { it.isNotBlank() }).joinToString(", ")
                Text(line, style = MaterialTheme.clarityText.chatSmall, color = colors.onSurfaceVariant)
            }
        }
        val held = subject.heldActionId?.let(::HeldActionId)
        val waiting = taskOf(subject)?.status.let { it == AgentTask.Status.DRAFTED || it == AgentTask.Status.WAITING_FOR_YOU }
        if (held != null && waiting) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.Medium)) {
                Text("Needs your OK.", Modifier.weight(1f), style = MaterialTheme.clarityText.chatSmall)
                ReviewButton({ onReview(held) }, subject.title)
            }
        }
        val totals = path.totals
        val stats = listOfNotNull(
            stepsText(totals.steps).substringBefore(' ') to (if (totals.steps == 1) "step" else "steps"),
            durationText(totals.durationMillis) to "taken",
            costText(totals.modelCostMicros)?.let { it to "thinking" },
            costText(totals.decisionCostMicros)?.let { it to "checks" },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.Small)) {
            stats.forEachIndexed { i, (value, label) ->
                StatTile(value, label, horizontalGroupShape(i, stats.size), Modifier.weight(1f))
            }
        }
    }
}

/** Segmented corners for a row of tiles: round outer ends, tight inner joins (like [net.robog.clarity.app.ui.components.groupShape] sideways). */
private fun horizontalGroupShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 20.dp
    val inner = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        bottomStart = if (index == 0) outer else inner,
        topEnd = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
}

@Composable
private fun StatTile(value: String, label: String, shape: RoundedCornerShape, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer, shape = shape) {
        Column(Modifier.padding(horizontal = Space.Medium, vertical = Space.Medium).semantics(mergeDescendants = true) {}) {
            Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

/** The subject as a task, for Today's status glyph; null for a run. */
private fun taskOf(subject: PathSubject): AgentTask? {
    if (subject.kind != PathSubject.Kind.TASK) return null
    val status = taskStatusOf(subject.status) ?: return null
    return AgentTask(TaskId(subject.id), subject.title, subject.detail.orEmpty(), status, subject.heldActionId?.let(::HeldActionId), subject.startedAt ?: kotlin.time.Instant.DISTANT_PAST)
}

// ---------- The rail ----------

private val RAIL_X = 16.dp
private val NESTED_INDENT = Space.Large

/**
 * One row on the rail: the node on the left, the line through it to the next row, the content on
 * the right. Rows inside a helper sit one step in, on a rail of their own.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TimelineRow(
    row: PathRow,
    first: Boolean,
    last: Boolean,
    continuesBelow: Boolean,
    zone: TimeZone,
    frameBase: String?,
    onOpenRun: (String) -> Unit,
    onOpenFrame: (OpenFrame) -> Unit,
    initialFramePage: Int,
    live: Boolean,
    /** The whole path is one helper's run: everything said on it is the helper's. */
    helperRun: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val nested = row.depth > 0
    val rail = if (nested) colors.tertiary.copy(alpha = 0.45f) else colors.outlineVariant
    val outerRail = colors.outlineVariant
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val x = RAIL_X.toPx()
                val w = 2.dp.toPx()
                // The lead's rail runs on past a helper's rows; a helper's own rail is drawn one step in.
                if (nested) drawLine(outerRail, Offset(x, 0f), Offset(x, size.height), w)
                val ownX = if (nested) x + NESTED_INDENT.toPx() else x
                val top = if (first) 20.dp.toPx() else 0f
                val bottom = if (last || (nested && !continuesBelow)) 20.dp.toPx() else size.height
                if (bottom > top) drawLine(rail, Offset(ownX, top), Offset(ownX, bottom), w)
            }
            .padding(start = if (nested) NESTED_INDENT else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.Medium),
    ) {
        Box(Modifier.width(RAIL_X * 2).padding(top = Space.Small), contentAlignment = Alignment.TopCenter) {
            when (row) {
                is PathRow.Node -> PathNode(nodeLook(row.item, running = live))
                is PathRow.Tab -> Box(Modifier.padding(top = Space.Small).size(10.dp).background(rail, MaterialShapes.Circle.toShape()))
            }
        }
        Box(Modifier.weight(1f).padding(top = Space.Small, bottom = Space.Medium)) {
            when (row) {
                is PathRow.Tab -> TabLine(row.label)
                is PathRow.Node -> ItemContent(row.item, zone, frameBase, onOpenRun, onOpenFrame, initialFramePage, helperRun)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WorkingRow(depth: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = if (depth > 0) NESTED_INDENT else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(RAIL_X * 2), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(32.dp)) }
        Hint("Still working")
    }
}

@Composable
private fun TabLine(label: String) {
    Text(sentenceCase(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
}

// ---------- The items ----------

@Composable
private fun ItemContent(
    item: PathItem,
    zone: TimeZone,
    frameBase: String?,
    onOpenRun: (String) -> Unit,
    onOpenFrame: (OpenFrame) -> Unit,
    initialFramePage: Int,
    helperRun: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val time = formatClock(item.at, zone)
    when (item) {
        is PathItem.Heard -> Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
            Kicker(
                when (item.from) {
                    PathItem.Heard.Source.PERSON -> "You at $time"
                    PathItem.Heard.Source.HELPER -> "Helper at $time"
                    PathItem.Heard.Source.EVENT -> "Came in at $time"
                },
            )
            Text(item.text, style = MaterialTheme.clarityText.chat)
        }
        is PathItem.Said -> Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
            Kicker("${if (item.depth > 0 || helperRun) "helper" else "clarity"}, $time")
            Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)) {
                Text(item.text, Modifier.padding(horizontal = Space.Medium, vertical = Space.Medium), style = MaterialTheme.clarityText.chatSmall)
            }
        }
        is PathItem.Action -> Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.Small)) {
                Text(item.line, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall)
                when (item.outcome) {
                    Outcome.HELD -> Pill("held for you", colors.secondary, colors.onSecondary)
                    Outcome.ERROR -> Pill("didn’t work", colors.errorContainer, colors.onErrorContainer)
                    Outcome.RUNNING -> Pill("working", colors.secondaryContainer, colors.onSecondaryContainer)
                    Outcome.OK -> Unit
                }
            }
            Text(listOfNotNull(time, item.detail).joinToString(", "), style = MaterialTheme.clarityText.chatSmall, color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        is PathItem.Helper -> HelperCard(item, time, onOpenRun)
        is PathItem.Finished -> Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
            Kicker(if (item.ok) "Helper finished at $time" else "Helper stopped at $time")
            Text(item.text, style = MaterialTheme.clarityText.chatSmall)
        }
        is PathItem.Browser -> BrowserStep(item.step, time, frameBase, onOpenFrame, initialFramePage)
    }
}

@Composable
private fun Pill(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(sentenceCase(text), Modifier.padding(horizontal = Space.Medium, vertical = Space.Small), style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HelperCard(item: PathItem.Helper, time: String, onOpenRun: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClickLabel = "Open the helper’s path") { onOpenRun(item.childRunId) },
        color = colors.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(horizontal = Space.Medium, vertical = Space.Medium), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
            Kicker("started a ${item.role}, $time")
            Text(item.task, style = MaterialTheme.clarityText.chat, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOf(sentenceCase(item.status.let { if (it == "running") "working" else it }), helperLine(item)).joinToString(", "),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
                FilledTonalButton(onClick = { onOpenRun(item.childRunId) }, shapes = ButtonDefaults.shapes()) {
                    Text("Step in")
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, Modifier.padding(start = Space.Small).size(18.dp))
                }
            }
        }
    }
}

// ---------- A browser step ----------

@Composable
private fun BrowserStep(step: BrowserStepView, time: String, frameBase: String?, onOpenFrame: (OpenFrame) -> Unit, initialFramePage: Int) {
    val colors = MaterialTheme.colorScheme
    val outcome = stepOutcome(step.gate)
    Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.Small)) {
            Text(time, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            val (container, content) = when (outcome) {
                StepOutcome.PASSED -> colors.secondaryContainer to colors.onSecondaryContainer
                StepOutcome.HELD -> colors.secondary to colors.onSecondary
                StepOutcome.REFUSED -> colors.primaryContainer to colors.onPrimaryContainer
                StepOutcome.NOT_FOUND -> colors.surfaceContainerHighest to colors.onSurfaceVariant
                StepOutcome.FAILED -> colors.errorContainer to colors.onErrorContainer
            }
            Pill(outcomeLabel(step), container, content)
        }
        Text(stepTitle(step), style = MaterialTheme.typography.titleSmall)
        elementLine(step)?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = colors.tertiary) }
        val report = condenseReport(step.changeReport)
        report?.let { Text(it, style = MaterialTheme.clarityText.chatSmall, color = colors.onSurfaceVariant) }
        // Jev's confidence that the step worked; a refused or failed step never ran, so it has none to show.
        if (report?.contains("Jev") != true && (outcome == StepOutcome.PASSED || outcome == StepOutcome.HELD)) stepConfidence(step)?.let { Text(confidenceText(it), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
        Frames(step, frameBase, outcome, onOpenFrame, initialFramePage, Modifier.padding(top = Space.Small))
    }
}

/** A step's screenshots: the one before with Jev's box, the one after a swipe (or a tap on its label) away. */
@Composable
private fun Frames(step: BrowserStepView, base: String?, outcome: StepOutcome, onOpenFrame: (OpenFrame) -> Unit, initialPage: Int, modifier: Modifier = Modifier) {
    val boxColor = if (outcome == StepOutcome.PASSED || outcome == StepOutcome.HELD) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val box = jevBox(step)
    val frames = listOfNotNull(
        frameOf(step, base, after = false, box, boxColor),
        frameOf(step, base, after = true, null, boxColor),
    ).let { both -> if (both.all { it is Frame.Withheld }) both.take(1) else both } // two placeholders say nothing more than one
    if (frames.isEmpty()) return
    if (frames.size == 1) {
        FrameView(frames.single(), onOpenFrame, modifier)
        return
    }
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, 1)) { 2 }
    val scope = rememberCoroutineScope()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.Small)) {
        HorizontalPager(pager, pageSpacing = 8.dp) { page -> FrameView(frames[page], onOpenFrame) }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.Small)) {
            listOf("Before", "After").forEachIndexed { i, label ->
                val selected = pager.currentPage == i
                Surface(
                    onClick = { scope.launch { pager.animateScrollToPage(i) } },
                    modifier = Modifier.minimumInteractiveComponentSize().semantics { role = Role.Tab; this.selected = selected },
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(label, Modifier.padding(horizontal = Space.Medium, vertical = Space.Small), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** One frame: stored, withheld, or none at all. */
private sealed interface Frame {
    val description: String

    data class Shot(val url: String, val box: JevBox?, val boxColor: Color, override val description: String) : Frame
    data class Withheld(override val description: String) : Frame
}

private fun frameOf(step: BrowserStepView, base: String?, after: Boolean, box: JevBox?, boxColor: Color): Frame? {
    val stored = if (after) step.afterShot else step.beforeShot
    val withheld = if (after) step.afterWithheld else step.beforeWithheld
    val description = frameDescription(step, after)
    return when {
        stored && base != null -> Frame.Shot(frameUrl(base, step.id, after), box, boxColor, description)
        withheld -> Frame.Withheld(description)
        else -> null
    }
}

@Composable
private fun FrameView(frame: Frame, onOpenFrame: (OpenFrame) -> Unit, modifier: Modifier = Modifier) {
    when (frame) {
        is Frame.Shot -> ShotImage(frame.url, frame.box, frame.boxColor, frame.description, modifier) {
            onOpenFrame(OpenFrame(frame.url, frame.box, frame.boxColor, frame.description))
        }
        is Frame.Withheld -> WithheldFrame(frame.description, modifier)
    }
}

/** The usual browser window shape, until the image says otherwise. */
internal const val DEFAULT_RATIO = 1280f / 714f

@Composable
private fun ShotImage(url: String, box: JevBox?, boxColor: Color, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var ratio by remember(url) { mutableFloatStateOf(DEFAULT_RATIO) }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .heightIn(max = 320.dp)
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(role = Role.Button, onClickLabel = "Open full screen", onClick = onClick)
                .semantics { contentDescription = description },
        ) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize(),
                onSuccess = { s -> s.painter.intrinsicSize.takeIf { it.width > 0f && it.height > 0f }?.let { ratio = it.width / it.height } },
            )
            box?.let { JevBoxOverlay(it, boxColor, Modifier.matchParentSize()) }
        }
    }
}

@Composable
internal fun JevBoxOverlay(box: JevBox, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val topLeft = Offset(box.left * size.width, box.top * size.height)
        val boxSize = androidx.compose.ui.geometry.Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height)
        val corner = CornerRadius(6.dp.toPx())
        drawRoundRect(color.copy(alpha = 0.14f), topLeft, boxSize, corner)
        drawRoundRect(color, topLeft, boxSize, corner, style = Stroke(width = 2.5.dp.toPx()))
    }
}

/** A frame not kept because a secret may have been on it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WithheldFrame(description: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surfaceContainer)
            .semantics { contentDescription = "$description. Hidden for privacy" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.Medium), modifier = Modifier.padding(horizontal = Space.Medium, vertical = Space.Medium)) {
            Box(Modifier.size(44.dp).background(colors.tertiaryContainer, MaterialShapes.Cookie12Sided.toShape()), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.onTertiaryContainer, modifier = Modifier.size(20.dp))
            }
            Text(
                "Hidden for privacy",
                style = MaterialTheme.clarityText.chatSmall,
                color = colors.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}


