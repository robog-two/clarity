package net.robog.clarity.app.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Onboarding : NavKey
@Serializable data object Main : NavKey

/** Pure rule for what the back stack must look like given whether setup is finished. */
fun reconcileBackStack(current: List<NavKey>, ready: Boolean): List<NavKey> = when {
    ready && current.any { it == Onboarding } -> listOf(Main)
    !ready && current.any { it != Onboarding } -> listOf(Onboarding)
    current.isEmpty() -> listOf(if (ready) Main else Onboarding)
    else -> current
}
