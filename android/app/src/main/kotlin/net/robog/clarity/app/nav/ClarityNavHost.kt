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
import net.robog.clarity.app.ui.main.MainScaffold
import net.robog.clarity.app.ui.onboarding.OnboardingFlow

@Composable
fun ClarityNavHost(rootViewModel: RootViewModel = metroViewModel()) {
    val root by rootViewModel.state.collectAsStateWithLifecycle()
    if (root == RootState.Loading) return // DataStore read is fast; avoid flashing the wrong screen
    ClarityNavDisplay(ready = root == RootState.Ready)
}

@Composable
private fun ClarityNavDisplay(ready: Boolean) {
    val backStack = rememberNavBackStack(if (ready) Main else Onboarding)

    // Finishing onboarding / forgetting the instance changes the root state; keep the stack consistent.
    LaunchedEffect(ready) {
        val target = reconcileBackStack(backStack.toList(), ready)
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
            entry<Onboarding> { OnboardingFlow() }
            entry<Main> { MainScaffold() }
        },
    )
}
