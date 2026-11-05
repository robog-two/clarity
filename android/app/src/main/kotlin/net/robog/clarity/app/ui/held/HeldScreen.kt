package net.robog.clarity.app.ui.held

import net.robog.clarity.app.ui.theme.Space

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.app.ui.components.ScreenGutter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.ReviewButton
import net.robog.clarity.app.ui.components.SecondaryButton
import net.robog.clarity.app.ui.components.TaskRow
import net.robog.clarity.app.ui.components.TaskShape
import net.robog.clarity.app.ui.components.TaskStatus
import net.robog.clarity.app.ui.components.groupShape
import net.robog.clarity.app.ui.components.ClarityScreen
import net.robog.clarity.app.ui.components.ScreenLevel
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId

@Composable
fun HeldScreen(
    onOpen: (HeldActionId) -> Unit,
    modifier: Modifier = Modifier,
    onReview: (HeldActionId) -> Unit = onOpen,
    viewModel: HeldViewModel = metroViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HeldContent(state, onOpen, viewModel::toggleAllowed, modifier, onReview)
}

@Composable
fun HeldContent(
    state: HeldUiState,
    onOpen: (HeldActionId) -> Unit,
    onToggleAllowed: () -> Unit,
    modifier: Modifier = Modifier,
    onReview: (HeldActionId) -> Unit = onOpen,
) {
    ClarityScreen(title = "Held for Approval", modifier = modifier, level = ScreenLevel.Top) {
        if (state.rows.isEmpty()) Hint("Nothing waiting.")
        // The same contained group as Today's "I'm doing".
        Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
            state.rows.forEachIndexed { i, row ->
                key(row.id) {
                    val open = { if (row.review) onReview(row.id) else onOpen(row.id) }
                    TaskRow(
                        title = row.title,
                        detail = if (row.waiting) "Needs your OK, ${row.whenText}" else row.whenText,
                        shape = groupShape(i, state.rows.size),
                        glyph = { HeldGlyph(row.state) },
                        trailing = { if (row.waiting) ReviewButton(open, row.title) else TaskStatus(row.status) },
                        onClick = open,
                    )
                }
            }
        }
        Kicker("Low-urgency items wait until evening.", Modifier.padding(top = Space.Small))
        SecondaryButton(if (state.showAllowed) "Hide automatic actions" else "Show automatic actions", onToggleAllowed, Modifier.fillMaxWidth())
        if (state.showAllowed) {
            if (state.allowed.isEmpty()) Hint("None yet.")
            Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                state.allowed.forEachIndexed { i, a ->
                    TaskRow(
                        title = a.title,
                        detail = a.whenText,
                        shape = groupShape(i, state.allowed.size),
                        glyph = { HeldGlyph(if (a.ok) HeldAction.Status.EXECUTED else HeldAction.Status.FAILED) },
                        trailing = { TaskStatus(if (a.ok) "done" else "failed") },
                    )
                }
            }
        }
    }
}

/**
 * A still shape per state, matching Today's tasks: waiting on the person is the same shape as a
 * drafted task, done is a full circle, failed a burst, and anything else settled is a faint circle.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeldGlyph(status: HeldAction.Status) {
    val colors = MaterialTheme.colorScheme
    val faint = colors.outline
    when (status) {
        HeldAction.Status.HELD, HeldAction.Status.BLOCKED -> TaskShape(MaterialShapes.Cookie4Sided, colors.secondary)
        HeldAction.Status.APPROVED -> TaskShape(MaterialShapes.Clover4Leaf, faint)
        HeldAction.Status.EXECUTED -> TaskShape(MaterialShapes.Circle, colors.secondary)
        HeldAction.Status.FAILED -> TaskShape(MaterialShapes.Burst, colors.error)
        else -> TaskShape(MaterialShapes.Circle, faint)
    }
}
