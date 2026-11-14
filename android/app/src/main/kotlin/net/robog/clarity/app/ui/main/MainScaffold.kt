package net.robog.clarity.app.ui.main

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import net.robog.clarity.app.ui.components.PhaseSwap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.nav.MainTab
import net.robog.clarity.app.ui.ask.AskScreen
import net.robog.clarity.app.ui.goods.GoodsScreen
import net.robog.clarity.app.ui.held.HeldScreen
import net.robog.clarity.app.ui.settings.SettingsTab
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.app.ui.today.TodayScreen
import net.robog.clarity.shared.governance.HeldActionId

private fun MainTab.icon(): ImageVector = when (this) {
    MainTab.Today -> Icons.Outlined.Today
    MainTab.Ask -> Icons.Outlined.ChatBubbleOutline
    MainTab.Goods -> Icons.Outlined.Inventory2
    MainTab.Held -> Icons.Outlined.PauseCircleOutline
    MainTab.Settings -> Icons.Outlined.Settings
}

/** The signed-in app: the bottom tabs (the prototype's four and Goods), with "held · N" and confirmation toasts. */
@Composable
fun MainScaffold(
    tab: MainTab,
    onTab: (MainTab) -> Unit,
    onReview: (HeldActionId) -> Unit,
    /** A task on Today tapped: its thread. */
    onOpenTask: (net.robog.clarity.shared.feed.TaskId) -> Unit = {},
    onOpenHeldItem: (HeldActionId) -> Unit,
    onOpenConstitution: () -> Unit,
    onOpenConnector: (String) -> Unit,
    /** Settings → MCP servers. */
    onOpenMcp: () -> Unit = {},
    /** A Good opened, from the Goods tab or a card in the thread; the label is where back goes. */
    onOpenReport: (reportId: String, backLabel: String) -> Unit = { _, _ -> },
    /** A record a sources card cites, opened from the thread. */
    onOpenSource: (entityId: String, backLabel: String) -> Unit = { _, _ -> },
    viewModel: MainViewModel = metroViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.keepInSync() }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    // What the morning call needs and the phone does not allow yet (notifications, full screen, exact
    // alarms): explained, then the system prompt or settings page; "not now" holds until the next open.
    CallPermissionGate()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        // The screens paint their background under the status bar and pad for it themselves
        // (ClarityScreen, ChatLayout), so the scaffold only makes room for the tab bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    data,
                    actionColor = MaterialTheme.colorScheme.inversePrimary,
                    shape = MaterialTheme.shapes.extraSmall,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        },
        bottomBar = { TabBar(tab, state.heldWaiting, onTab) },
    ) { padding ->
        // padding is the tab bar; it already covers the nav bar, so the screens' own nav bar padding is consumed.
        val content = Modifier.padding(padding).consumeWindowInsets(padding)
        PhaseSwap(tab, label = "tab") { shown ->
            when (shown) {
                MainTab.Today -> TodayScreen(onReview = onReview, onOpenHeld = { onTab(MainTab.Held) }, modifier = content, onOpenTask = onOpenTask)
                MainTab.Ask -> AskScreen(content, onReview = onReview, onOpenReport = { onOpenReport(it, "Ask") }, onOpenSource = { onOpenSource(it, "Ask") })
                MainTab.Goods -> GoodsScreen(onOpen = { onOpenReport(it, "Goods") }, modifier = content)
                MainTab.Held -> HeldScreen(onOpen = onOpenHeldItem, modifier = content, onReview = onReview)
                MainTab.Settings -> SettingsTab(onOpenConstitution, onOpenConnector, content, onOpenMcp = onOpenMcp)
            }
        }
    }
}

@Composable
fun TabBar(selected: MainTab, heldWaiting: Int, onSelect: (MainTab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        MainTab.entries.forEach { t ->
            NavigationBarItem(
                selected = t == selected,
                onClick = { onSelect(t) },
                modifier = if (t == MainTab.Held && heldWaiting > 0) Modifier.semantics { stateDescription = heldTabLabel(heldWaiting) } else Modifier,
                icon = {
                    if (t == MainTab.Held && heldWaiting > 0) {
                        androidx.compose.material3.BadgedBox(badge = { androidx.compose.material3.Badge(Modifier.clearAndSetSemantics {}) { Text("$heldWaiting") } }) {
                            Icon(t.icon(), contentDescription = null)
                        }
                    } else {
                        Icon(t.icon(), contentDescription = null)
                    }
                },
                label = { Text(net.robog.clarity.app.ui.components.sentenceCase(t.label), style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun TabBarPreview() = ClarityTheme {
    TabBar(MainTab.Held, 2) {}
}
