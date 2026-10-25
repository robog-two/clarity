package net.robog.clarity.app.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.ui.cards.CardEnv
import net.robog.clarity.app.ui.cards.FeedItemView
import net.robog.clarity.app.ui.cards.taskStatusLabel
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.ScreenTitle
import net.robog.clarity.app.ui.settings.SettingsTab
import net.robog.clarity.app.ui.theme.ClarityTheme

private enum class Tab(val label: String, val icon: ImageVector) {
    Today("today", Icons.Outlined.Today),
    Ask("ask", Icons.Outlined.ChatBubbleOutline),
    Held("held", Icons.Outlined.PauseCircleOutline),
    Settings("settings", Icons.Outlined.Settings),
}

/**
 * The signed-in app: four bottom tabs from the prototype. The real screens are the `app-screens`
 * workstream; for now each tab shows its empty state or whatever is already cached.
 */
@Composable
fun MainScaffold(viewModel: MainViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.keepInSync() }
    }
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    MainContent(state, tab.name, { tab = Tab.valueOf(it) })
}

@Composable
private fun MainContent(state: MainUiState, selected: String, onSelect: (String) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t.name == selected,
                        onClick = { onSelect(t.name) },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = {
                            Text(
                                if (t == Tab.Held) heldTabLabel(state.heldWaiting) else t.label,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer),
                    )
                }
            }
        },
    ) { padding ->
        when (Tab.valueOf(selected)) {
            Tab.Today -> TodayPlaceholder(state, padding)
            Tab.Ask -> AskPlaceholder(state, padding)
            Tab.Held -> HeldPlaceholder(state, padding)
            Tab.Settings -> SettingsTab(Modifier.padding(padding))
        }
    }
}

@Composable
private fun TabColumn(padding: PaddingValues, title: String, kicker: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (kicker != null) Kicker(kicker)
        ScreenTitle(title)
        content()
    }
}

@Composable
private fun TodayPlaceholder(state: MainUiState, padding: PaddingValues) {
    TabColumn(padding, "Today") {
        if (state.tasks.isEmpty()) {
            Hint("Nothing yet. Your plan, what I’m doing and what’s parked show up here after your first morning call.")
        } else {
            Kicker("I’m doing")
            state.tasks.forEach { task ->
                Column(Modifier.fillMaxWidth()) {
                    Text(task.title, style = MaterialTheme.typography.bodyLarge)
                    Kicker("${task.detail} · ${taskStatusLabel(task.status)}")
                }
            }
        }
    }
}

@Composable
private fun AskPlaceholder(state: MainUiState, padding: PaddingValues) {
    val env = CardEnv(held = state.held.associateBy { it.id }, tasks = state.tasks.associateBy { it.id })
    TabColumn(padding, "Ask") {
        if (state.items.isEmpty()) {
            Hint("Ask me anything about your day. I’ll check your sources and answer here.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.items, key = { it.seq }) { FeedItemView(it, env = env) }
            }
        }
    }
}

@Composable
private fun HeldPlaceholder(state: MainUiState, padding: PaddingValues) {
    TabColumn(padding, "Held for You", kicker = heldTabLabel(state.heldWaiting)) {
        Hint("Things I tried that hit one of your lines. Tap one to talk it through.")
        if (state.held.isEmpty()) {
            Hint("Nothing is held right now.")
        } else {
            state.held.forEach { h ->
                Column(Modifier.fillMaxWidth()) {
                    Text(h.action.summary, style = MaterialTheme.typography.bodyLarge)
                    Kicker(h.status.name.lowercase().replace('_', ' '))
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 780)
@Composable
private fun MainPreview() = ClarityTheme {
    MainContent(MainUiState(), selected = Tab.Ask.name, onSelect = {})
}

