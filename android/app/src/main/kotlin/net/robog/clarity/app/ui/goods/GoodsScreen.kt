package net.robog.clarity.app.ui.goods

import net.robog.clarity.app.ui.theme.Space

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.LoadingSpinner
import net.robog.clarity.app.ui.components.ScreenGutter
import net.robog.clarity.app.ui.components.TaskRow
import net.robog.clarity.app.ui.components.groupShape
import net.robog.clarity.app.ui.components.ClarityListScreen
import net.robog.clarity.app.ui.components.ScreenLevel
import net.robog.clarity.app.ui.theme.clarityText

/** The Goods tab: the documents Clarity made for the person. */
@Composable
fun GoodsScreen(onOpen: (String) -> Unit, modifier: Modifier = Modifier, viewModel: GoodsViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GoodsContent(state, onOpen, viewModel::refresh, modifier)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GoodsContent(state: GoodsUiState, onOpen: (String) -> Unit, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    ClarityListScreen(
        title = "Goods",
        modifier = modifier,
        level = ScreenLevel.Top,
        kicker = "Made for you",
        refreshing = state.refreshing,
        onRefresh = onRefresh,
    ) {
        item(key = "hint") { Hint("Reports and write-ups appear here") }
        when {
            state.loading -> item(key = "loading") { LoadingSpinner() }
            state.rows.isEmpty() && state.error != null -> item(key = "error") {
                Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                    ErrorBox("Can’t load Goods", state.error)
                    TextButton(onClick = onRefresh) { Text("Try again") }
                }
            }
            state.rows.isEmpty() -> item(key = "empty") {
                Hint("Nothing yet. Ask for a write-up.", italic = true)
            }
            else -> {
                state.error?.let { error -> item(key = "stale") { ErrorBox("Can’t refresh", error) } }
                // The rows are one grouped card, so they sit Small apart.
                gap = Space.Small
                itemsIndexed(state.rows, key = { _, row -> row.id }) { i, row ->
                    TaskRow(
                        title = row.title,
                        detail = row.detail,
                        shape = groupShape(i, state.rows.size),
                        glyph = { GoodsGlyph() },
                        trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = { onOpen(row.id) },
                    )
                }
            }
        }
    }
}
