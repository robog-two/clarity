package net.robog.clarity.app.ui.path

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import net.robog.clarity.shared.path.Outcome
import net.robog.clarity.shared.path.PathItem

/** What a node looks like: its shape, fill, the glyph on it, and whether it turns (work going on). */
data class NodeLook(val shape: RoundedPolygon, val fill: Color, val onFill: Color, val icon: ImageVector?, val spinning: Boolean = false)

/**
 * The node of an item, in the status language Today uses: Sage cookies for work done, the Sage
 * four-sided cookie for something that needs the person, the error burst for a failure, a
 * turning cookie for work going on; plus a shield for a step the code guard refused and a ghost
 * for an element that was not found.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun nodeLook(item: PathItem, running: Boolean = false): NodeLook {
    val c = MaterialTheme.colorScheme
    val quiet = NodeLook(MaterialShapes.Circle, c.surfaceContainerHighest, c.onSurfaceVariant, null)
    return when (item) {
        is PathItem.Heard -> quiet.copy(
            icon = when (item.from) {
                PathItem.Heard.Source.PERSON -> Icons.Outlined.Person
                PathItem.Heard.Source.HELPER -> Icons.Outlined.AccountTree
                PathItem.Heard.Source.EVENT -> Icons.Outlined.Notifications
            },
        )
        is PathItem.Said -> NodeLook(MaterialShapes.Puffy, c.surfaceContainerHigh, c.onSurface, Icons.Outlined.ChatBubbleOutline)
        is PathItem.Action -> when (item.outcome) {
            Outcome.OK -> NodeLook(MaterialShapes.Cookie6Sided, c.secondaryContainer, c.onSecondaryContainer, actionIcon(item.tool))
            Outcome.HELD -> NodeLook(MaterialShapes.Cookie4Sided, c.secondary, c.onSecondary, Icons.Outlined.PanTool)
            Outcome.ERROR -> NodeLook(MaterialShapes.Burst, c.error, c.onError, Icons.Outlined.Close)
            Outcome.RUNNING -> NodeLook(MaterialShapes.Cookie9Sided, c.secondaryContainer, c.onSecondaryContainer, actionIcon(item.tool), spinning = true)
        }
        is PathItem.Browser -> browserLook(item, running)
        is PathItem.Helper -> NodeLook(
            MaterialShapes.Flower, c.tertiaryContainer, c.onTertiaryContainer, Icons.Outlined.AccountTree,
            spinning = item.status == "running" || item.status == "queued",
        )
        is PathItem.Finished ->
            if (item.ok) NodeLook(MaterialShapes.Circle, c.secondaryContainer, c.onSecondaryContainer, Icons.Outlined.Check)
            else NodeLook(MaterialShapes.Burst, c.error, c.onError, Icons.Outlined.Close)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun browserLook(item: PathItem.Browser, running: Boolean): NodeLook {
    val c = MaterialTheme.colorScheme
    val step = item.step
    return when (stepOutcome(step.gate)) {
        StepOutcome.PASSED -> NodeLook(
            MaterialShapes.Cookie9Sided, c.secondaryContainer, c.onSecondaryContainer,
            when (step.tool) {
                "sign_in" -> Icons.AutoMirrored.Outlined.Login
                "go", "back" -> Icons.Outlined.Language
                else -> Icons.Outlined.TouchApp
            },
            spinning = running,
        )
        StepOutcome.HELD -> NodeLook(MaterialShapes.Cookie4Sided, c.secondary, c.onSecondary, Icons.Outlined.PanTool)
        StepOutcome.REFUSED -> NodeLook(MaterialShapes.Diamond, c.primaryContainer, c.onPrimaryContainer, Icons.Outlined.Shield)
        StepOutcome.NOT_FOUND -> NodeLook(MaterialShapes.Ghostish, c.surfaceContainerHighest, c.onSurfaceVariant, Icons.Outlined.SearchOff)
        StepOutcome.FAILED -> NodeLook(MaterialShapes.Burst, c.error, c.onError, Icons.Outlined.Close)
    }
}

private fun actionIcon(tool: String): ImageVector = when {
    tool.startsWith("browser__") -> Icons.Outlined.Language
    tool == "send_to_user" -> Icons.AutoMirrored.Outlined.Send
    tool in setOf("list_messages", "save_draft", "send_email") -> Icons.Outlined.Mail
    tool in setOf("list_calendar_events", "create_event", "move_event", "respond_to_invite") -> Icons.Outlined.Event
    tool == "create_reminder" -> Icons.Outlined.Alarm
    tool in setOf("ask_code", "request_credential", "list_credentials") -> Icons.Outlined.Password
    tool == "update_task" -> Icons.Outlined.Checklist
    tool.contains("memory") || tool.contains("journal") || tool.contains("profile") -> Icons.Outlined.Psychology
    else -> Icons.Outlined.Bolt
}

/** A [Morph] drawn at [progress], scaled to the node (Material shapes are normalized to the unit square). */
private class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress).asComposePath()
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}

/**
 * A timeline node. When its look changes (a step still running settles, a helper finishes) the
 * shape morphs into the new one and the colour follows, both on the expressive spatial spring;
 * a node for work going on slowly turns, like Today's running tasks.
 */
@Composable
fun PathNode(look: NodeLook, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    var from by remember { mutableStateOf(look.shape) }
    var to by remember { mutableStateOf(look.shape) }
    val progress = remember { Animatable(1f) }
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(look.shape) {
        if (look.shape == to) return@LaunchedEffect
        from = to
        to = look.shape
        progress.snapTo(0f)
        progress.animateTo(1f, spatial)
    }
    val morph = remember(from, to) { Morph(from, to) }
    val fill by animateColorAsState(look.fill, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "node fill")
    val turn = if (!look.spinning || LocalInspectionMode.current) 0f else {
        val spin = rememberInfiniteTransition(label = "node working")
        spin.animateFloat(0f, 360f, infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "node turn").value
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // Only the shape turns; the glyph stays upright.
        Box(Modifier.matchParentSize().graphicsLayer { rotationZ = turn }.background(fill, MorphShape(morph, progress.value)))
        look.icon?.let { Icon(it, contentDescription = null, tint = look.onFill, modifier = Modifier.size(size * 0.5f)) }
    }
}
