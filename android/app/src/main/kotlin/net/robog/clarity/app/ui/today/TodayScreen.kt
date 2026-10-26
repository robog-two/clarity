package net.robog.clarity.app.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import net.robog.clarity.app.ui.components.sentenceCase
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.ui.cards.taskStatusLabel
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.InfoBox
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.PrimaryButton
import net.robog.clarity.app.ui.components.ScreenTitle
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.shared.governance.HeldActionId

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

@Composable
fun TodayContent(
    state: TodayUiState,
    onRefresh: () -> Unit,
    onReview: (HeldActionId) -> Unit,
    onOpenHeld: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.kicker.isNotEmpty()) Kicker(state.kicker)
            ScreenTitle("Today")
            state.error?.let {
                ErrorBox(it)
                androidx.compose.material3.TextButton(onClick = onRefresh) { Text("Try again") }
            }
            val today = state.today
            when {
                state.loading -> Hint("Loading…")
                state.unavailable -> Hint("Your server can’t show today yet. Your plan, what I’m doing and what’s parked will appear here.")
                today == null -> Unit
                else -> {
                    if (today.plan.isNotEmpty()) {
                        InfoBox(
                            container = MaterialTheme.colorScheme.primaryContainer,
                            content = MaterialTheme.colorScheme.onPrimaryContainer,
                            border = null,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Kicker("your plan", color = MaterialTheme.colorScheme.onPrimaryContainer)
                            today.plan.forEach { Text(it.sentence(), style = MaterialTheme.clarityText.reading.copy(fontSize = 15.sp)) }
                        }
                    } else if (!today.called) {
                        Hint("No plan yet. It appears here after your morning call.")
                    }
                    if (state.doing.isNotEmpty()) {
                        SectionTitle("I’m doing")
                        Column {
                            state.doing.forEach { DoingRowView(it, onReview) }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    HeldRow(state.heldWaiting, onOpenHeld)
                    today.parked.let { parkedLine(it) }?.let { line ->
                        SectionTitle("Parked")
                        Hint(line)
                    }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun DoingRowView(row: DoingRow, onReview: (HeldActionId) -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(row.task.title, style = MaterialTheme.clarityText.chat) },
        supportingContent = { Text(sentenceCase(row.task.detail), style = MaterialTheme.clarityText.chatSmall) },
        trailingContent = {
            if (row.review != null) {
                FilledTonalButton(onClick = { onReview(row.review) }) { Text("Review") }
            } else {
                Text(sentenceCase(taskStatusLabel(row.task.status)), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}

@Composable
private fun HeldRow(waiting: Int, onOpenHeld: () -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    ListItem(
        modifier = Modifier.clickable(onClick = onOpenHeld),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text("Held for you", style = MaterialTheme.clarityText.chat) },
        supportingContent = { Text(sentenceCase(heldLine(waiting)), style = MaterialTheme.clarityText.chatSmall) },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
