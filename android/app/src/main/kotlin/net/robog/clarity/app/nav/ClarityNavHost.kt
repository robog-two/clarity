package net.robog.clarity.app.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.push.LocalFrontScreen
import net.robog.clarity.app.push.placeFor
import net.robog.clarity.app.ui.amend.AmendScreen
import net.robog.clarity.app.ui.constitution.ConstitutionScreen
import net.robog.clarity.app.ui.held.DraftReviewScreen
import net.robog.clarity.app.ui.main.MainScaffold
import net.robog.clarity.app.ui.onboarding.OnboardingFlow
import net.robog.clarity.shared.governance.HeldActionId

/**
 * Root navigation. [deepLink] is a link from a notification that the activity has parsed; it is
 * followed once the app is set up, then reported through [onDeepLinkHandled].
 */
@Composable
fun ClarityNavHost(
    deepLink: DeepLink? = null,
    onDeepLinkHandled: () -> Unit = {},
    rootViewModel: RootViewModel = metroViewModel(),
) {
    val root by rootViewModel.state.collectAsStateWithLifecycle()
    if (root == RootState.Loading) return // DataStore read is fast; avoid flashing the wrong screen
    ClarityNavDisplay(ready = root == RootState.Ready, deepLink = deepLink, onDeepLinkHandled = onDeepLinkHandled)
}

@Composable
private fun ClarityNavDisplay(ready: Boolean, deepLink: DeepLink?, onDeepLinkHandled: () -> Unit) {
    val backStack = rememberNavBackStack(if (ready) Main else Onboarding)
    var tab by rememberSaveable { mutableStateOf(MainTab.Today) }

    // Finishing onboarding / forgetting the instance changes the root state; keep the stack consistent.
    LaunchedEffect(ready) {
        val target = reconcileBackStack(backStack.toList(), ready)
        if (target != backStack.toList()) {
            backStack.clear()
            backStack.addAll(target)
        }
    }

    // Links from notifications: select the tab and open the linked screen on top of it.
    LaunchedEffect(deepLink, ready) {
        if (!ready || deepLink == null) return@LaunchedEffect
        targetFor(deepLink)?.let { target ->
            tab = target.tab
            backStack.clear()
            backStack.addAll(stackFor(target))
        }
        onDeepLinkHandled()
    }

    // Tell the push service what is on screen, so it does not notify about what the person is reading.
    val front = LocalFrontScreen.current
    val top = backStack.lastOrNull()
    LaunchedEffect(tab, top) { front?.showing(placeFor(top, tab)) }

    fun push(key: NavKey) {
        backStack.add(key)
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
            entry<Main> {
                MainScaffold(
                    tab = tab,
                    onTab = { tab = it },
                    onReview = { push(DraftReview(it.value)) },
                    onOpenHeldItem = { push(Amend(it.value)) },
                    onOpenConstitution = { push(ConstitutionView) },
                )
            }
            entry<DraftReview> { key ->
                DraftReviewScreen(
                    HeldActionId(key.heldId),
                    onBack = { backStack.removeLastOrNull() },
                    onAmend = { push(Amend(key.heldId)) },
                )
            }
            entry<Amend> { key ->
                AmendScreen(
                    heldId = key.heldId?.let(::HeldActionId),
                    backLabel = if (key.heldId != null) "← held" else "← constitution",
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<ConstitutionView> {
                ConstitutionScreen(
                    onBack = { backStack.removeLastOrNull() },
                    onAmend = { push(Amend(null)) },
                )
            }
        },
    )
}
