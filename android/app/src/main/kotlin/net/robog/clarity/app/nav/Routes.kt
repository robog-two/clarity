package net.robog.clarity.app.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Onboarding : NavKey
@Serializable data object Home : NavKey
@Serializable data object Connectors : NavKey
@Serializable data object Settings : NavKey

/** Pure rule for what the back stack must look like given whether the device is enrolled. */
fun reconcileBackStack(current: List<NavKey>, signedIn: Boolean): List<NavKey> = when {
    signedIn && current.any { it == Onboarding } -> listOf(Home)
    !signedIn && current.any { it != Onboarding } -> listOf(Onboarding)
    current.isEmpty() -> listOf(if (signedIn) Home else Onboarding)
    else -> current
}
