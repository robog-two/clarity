package net.robog.clarity.app.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.ui.connectors.ConnectorsScreen
import net.robog.clarity.app.ui.home.HomeScreen
import net.robog.clarity.app.ui.onboarding.OnboardingScreen
import net.robog.clarity.app.ui.settings.SettingsScreen

@Composable
fun ClarityNavHost(rootViewModel: RootViewModel = metroViewModel()) {
    val root by rootViewModel.state.collectAsStateWithLifecycle()
    if (root == RootState.Loading) return // DataStore read is fast; avoid flashing the wrong screen
    ClarityNavDisplay(signedIn = root == RootState.SignedIn)
}

@Composable
private fun ClarityNavDisplay(signedIn: Boolean) {
    val backStack = rememberNavBackStack(if (signedIn) Home else Onboarding)

    // Enrollment / forgetting changes the session; keep the back stack consistent with it.
    LaunchedEffect(signedIn) {
        val target = reconcileBackStack(backStack.toList(), signedIn)
        if (target != backStack.toList()) {
            backStack.clear()
            backStack.addAll(target)
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider<NavKey> {
            entry<Onboarding> { OnboardingScreen() }
            entry<Home> {
                HomeScreen(
                    onOpenConnectors = { backStack.add(Connectors) },
                    onOpenSettings = { backStack.add(Settings) },
                )
            }
            entry<Connectors> { ConnectorsScreen(onBack = { backStack.removeLastOrNull() }) }
            entry<Settings> { SettingsScreen(onBack = { backStack.removeLastOrNull() }) }
        },
    )
}
