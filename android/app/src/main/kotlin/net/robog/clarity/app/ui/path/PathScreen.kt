package net.robog.clarity.app.ui.path

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
    PathScreenFrame(viewModel, onBack, "Today", onReview, onOpenRun)
}

/** One helper run's Agent's Path, stepped into from its task. */
@Composable
fun RunPathScreen(runId: String, onBack: () -> Unit, onOpenRun: (String) -> Unit, viewModel: PathViewModel = metroViewModel()) {
    LaunchedEffect(runId) { viewModel.open(PathTarget.Run(runId)) }
    PathScreenFrame(viewModel, onBack, "Back", onReview = {}, onOpenRun = onOpenRun)
}

@Composable
private fun PathScreenFrame(viewModel: PathViewModel, onBack: () -> Unit, backLabel: String, onReview: (HeldActionId) -> Unit, onOpenRun: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler { onBack() }
    // A running path refreshes while it is on screen, never in the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val live = state.path?.live == true
    LaunchedEffect(live) { if (live) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.poll() } }
    PathContent(state, onBack, backLabel, viewModel::refresh, viewModel::loadMore, onOpenRun, onReview)
}

/** A screenshot opened full screen. */
private data class OpenFrame(val url: String, val box: JevBox?, val boxColor: Color, val description: String)

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
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        BackLink(backLabel, onBack, Modifier.padding(horizontal = ScreenGutter))
        val path = state.path
        if (path == null) {
            Column(Modifier.padding(horizontal = ScreenGutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.loading) LoadingSpinner(Modifier.padding(top = 48.dp).align(Alignment.CenterHorizontally))
                else {
                    ErrorBox("Can’t show this path", state.error)
                    TextButton(onClick = onRefresh) { Text("Try again") }
                }
            }
            return@Column
        }
        val pull = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            state = pull,
            modifier = Modifier.fillMaxSize(),
            indicator = { PullToRefreshDefaults.LoadingIndicator(state = pull, isRefreshing = state.refreshing, modifier = Modifier.align(Alignment.TopCenter)) },
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = ScreenGutter, end = ScreenGutter, bottom = 32.dp),
            ) {
                item(key = "header") { PathHeader(path, onReview, Modifier.padding(bottom = 18.dp)) }
                if (state.rows.isEmpty() && !path.live) item(key = "empty") { Hint("Nothing recorded for this one yet.") }
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
                        modifier = Modifier.animateItem(),
                    )
                }
                if (path.live) item(key = "working") { WorkingRow(state.rows.lastOrNull()?.depth ?: 0, Modifier.animateItem()) }
                if (path.next != null) item(key = "more") {
                    TextButton(onClick = onLoadMore, enabled = !state.loadingMore, modifier = Modifier.padding(top = 8.dp)) {
                        Text(if (state.loadingMore) "Loading…" else "Show more")
                    }
                }
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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Kicker(if (subject.kind == PathSubject.Kind.TASK) "agent’s path" else "helper’s path")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val task = taskOf(subject)
            if (task != null) StatusGlyph(task, needsYou = subject.heldActionId != null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(subject.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val line = listOfNotNull(sentenceCase(statusLabel(subject)), subject.detail?.takeIf { it.isNotBlank() }).joinToString(" · ")
                Text(line, style = MaterialTheme.clarityText.chatSmall, color = colors.onSurfaceVariant)
            }
        }
        val held = subject.heldActionId?.let(::HeldActionId)
        val waiting = taskOf(subject)?.status.let { it == AgentTask.Status.DRAFTED || it == AgentTask.Status.WAITING_FOR_YOU }
        if (held != null && waiting) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Something here waits for your OK.", Modifier.weight(1f), style = MaterialTheme.clarityText.chatSmall)
                ReviewButton { onReview(held) }
            }
        }
        val totals = path.totals
        val stats = listOfNotNull(
            stepsText(totals.steps).substringBefore(' ') to (if (totals.steps == 1) "step" else "steps"),
            durationText(totals.durationMillis) to "time",
            costText(totals.modelCostMicros)?.let { it to "thinking" },
            costText(totals.decisionCostMicros)?.let { it to "decisions" },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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
private val NESTED_INDENT = 22.dp

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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(RAIL_X * 2).padding(top = 4.dp), contentAlignment = Alignment.TopCenter) {
            when (row) {
                is PathRow.Node -> PathNode(nodeLook(row.item, running = live))
                is PathRow.Tab -> Box(Modifier.padding(top = 6.dp).size(10.dp).background(rail, MaterialShapes.Circle.toShape()))
            }
        }
        Box(Modifier.weight(1f).padding(top = 4.dp, bottom = 16.dp)) {
            when (row) {
                is PathRow.Tab -> TabLine(row.label)
                is PathRow.Node -> ItemContent(row.item, zone, frameBase, onOpenRun, onOpenFrame, initialFramePage)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WorkingRow(depth: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = if (depth > 0) NESTED_INDENT else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(RAIL_X * 2), contentAlignment = Alignment.Center) { LoadingIndicator(Modifier.size(32.dp)) }
        Hint("Still working…")
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
) {
    val colors = MaterialTheme.colorScheme
    val time = formatClock(item.at, zone)
    when (item) {
        is PathItem.Heard -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Kicker(
                when (item.from) {
                    PathItem.Heard.Source.PERSON -> "you · $time"
                    PathItem.Heard.Source.HELPER -> "a helper reported · $time"
                    PathItem.Heard.Source.EVENT -> "came in · $time"
                },
            )
            Text(item.text, style = MaterialTheme.clarityText.chat)
        }
        is PathItem.Said -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Kicker("${if (item.depth > 0) "helper" else "clarity"} · $time")
            Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)) {
                Text(item.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.clarityText.chatSmall)
            }
        }
        is PathItem.Action -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.line, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall)
                when (item.outcome) {
                    Outcome.HELD -> Pill("held for you", colors.secondary, colors.onSecondary)
                    Outcome.ERROR -> Pill("didn’t work", colors.errorContainer, colors.onErrorContainer)
                    Outcome.RUNNING -> Pill("working", colors.secondaryContainer, colors.onSecondaryContainer)
                    Outcome.OK -> Unit
                }
            }
            Text(listOfNotNull(time, item.detail).joinToString(" · "), style = MaterialTheme.clarityText.chatSmall, color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        is PathItem.Helper -> HelperCard(item, time, onOpenRun)
        is PathItem.Finished -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Kicker(if (item.ok) "helper finished · $time" else "helper stopped · $time")
            Text(item.text, style = MaterialTheme.clarityText.chatSmall)
        }
        is PathItem.Browser -> BrowserStep(item.step, time, frameBase, onOpenFrame, initialFramePage)
    }
}

@Composable
private fun Pill(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(sentenceCase(text), Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
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
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Kicker("started a ${item.role} · $time")
            Text(item.task, style = MaterialTheme.clarityText.chat, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOf(sentenceCase(item.status.let { if (it == "running") "working" else it }), helperLine(item)).joinToString(" · "),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
                FilledTonalButton(onClick = { onOpenRun(item.childRunId) }, shapes = ButtonDefaults.shapes()) {
                    Text("Step in")
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, Modifier.padding(start = 6.dp).size(18.dp))
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Frames(step, frameBase, outcome, onOpenFrame, initialFramePage, Modifier.padding(top = 4.dp))
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalPager(pager, pageSpacing = 8.dp) { page -> FrameView(frames[page], onOpenFrame) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Before", "After").forEachIndexed { i, label ->
                val selected = pager.currentPage == i
                Surface(
                    onClick = { scope.launch { pager.animateScrollToPage(i) } },
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(label, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
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
private const val DEFAULT_RATIO = 1280f / 714f

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
                .clickable(role = Role.Image, onClickLabel = "Open full screen", onClick = onClick)
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
private fun JevBoxOverlay(box: JevBox, color: Color, modifier: Modifier = Modifier) {
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
            .semantics { contentDescription = "$description. Hidden while a password or code was on screen." },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)) {
            Box(Modifier.size(44.dp).background(colors.tertiaryContainer, MaterialShapes.Cookie12Sided.toShape()), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.onTertiaryContainer, modifier = Modifier.size(20.dp))
            }
            Text(
                "Hidden while a password or code was on screen",
                style = MaterialTheme.clarityText.chatSmall,
                color = colors.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** A screenshot full screen: pinch to zoom, drag to look around, double-tap to zoom in or back out. */
@Composable
private fun FullScreenFrame(frame: OpenFrame, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var ratio by remember { mutableFloatStateOf(DEFAULT_RATIO) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInputZoom(
                    onZoom = { zoom, pan ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    },
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2.5f
                        if (scale == 1f) offset = Offset.Zero
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            ) {
                AsyncImage(
                    model = frame.url,
                    contentDescription = frame.description,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.matchParentSize(),
                    onSuccess = { s -> s.painter.intrinsicSize.takeIf { it.width > 0f && it.height > 0f }?.let { ratio = it.width / it.height } },
                )
                frame.box?.let { JevBoxOverlay(it, frame.boxColor, Modifier.matchParentSize()) }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

private fun Modifier.pointerInputZoom(onZoom: (Float, Offset) -> Unit, onDoubleTap: () -> Unit): Modifier = this
    .pointerInput("zoom") { detectTransformGestures { _, pan, zoom, _ -> onZoom(zoom, pan) } }
    .pointerInput("tap") { detectTapGestures(onDoubleTap = { onDoubleTap() }) }
