package net.robog.clarity.app.ui.main

import androidx.compose.foundation.layout.padding
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
    /** A Good opened, from the Goods tab or a card in the thread; the label is where back goes. */
    onOpenReport: (reportId: String, backLabel: String) -> Unit = { _, _ -> },
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
        val content = Modifier.padding(padding)
        when (tab) {
            MainTab.Today -> TodayScreen(onReview = onReview, onOpenHeld = { onTab(MainTab.Held) }, modifier = content, onOpenTask = onOpenTask)
            MainTab.Ask -> AskScreen(content, onReview = onReview, onOpenReport = { onOpenReport(it, "Ask") })
            MainTab.Goods -> GoodsScreen(onOpen = { onOpenReport(it, "Goods") }, modifier = content)
            MainTab.Held -> HeldScreen(onOpen = onOpenHeldItem, modifier = content, onReview = onReview)
            MainTab.Settings -> SettingsTab(onOpenConstitution, onOpenConnector, content)
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
                icon = {
                    if (t == MainTab.Held && heldWaiting > 0) {
                        androidx.compose.material3.BadgedBox(badge = { androidx.compose.material3.Badge { Text("$heldWaiting") } }) {
                            Icon(t.icon(), contentDescription = heldTabLabel(heldWaiting))
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
