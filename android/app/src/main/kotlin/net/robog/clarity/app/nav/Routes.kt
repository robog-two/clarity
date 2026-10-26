package net.robog.clarity.app.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import net.robog.clarity.shared.governance.HeldActionId

@Serializable data object Onboarding : NavKey
@Serializable data object Main : NavKey

/** Review of one held action (usually an email draft). */
@Serializable data class DraftReview(val heldId: String) : NavKey

/** Talk through a held action ([heldId]) or amend the constitution by talking (null). */
@Serializable data class Amend(val heldId: String?) : NavKey

@Serializable data object ConstitutionView : NavKey

/** The screen of one server connector (accounts, passwords), opened from settings. */
@Serializable data class ConnectorView(val connectorId: String) : NavKey

/** The four bottom tabs. */
enum class MainTab(val label: String) {
    Today("today"),
    Ask("ask"),
    Held("held"),
    Settings("settings"),
}

/** Pure rule for what the back stack must look like given whether setup is finished. */
fun reconcileBackStack(current: List<NavKey>, ready: Boolean): List<NavKey> = when {
    ready && current.any { it == Onboarding } -> listOf(Main)
    !ready && current.any { it != Onboarding } -> listOf(Onboarding)
    current.isEmpty() -> listOf(if (ready) Main else Onboarding)
    else -> current
}

/** Where a deep link lands: a tab, and optionally a screen on top of it. */
data class NavTarget(val tab: MainTab, val push: NavKey? = null)

/** Null for links the main activity does not handle (the call has its own activity). */
fun targetFor(link: DeepLink): NavTarget? = when (link) {
    DeepLink.Today -> NavTarget(MainTab.Today)
    DeepLink.Ask -> NavTarget(MainTab.Ask)
    DeepLink.Held -> NavTarget(MainTab.Held)
    is DeepLink.HeldItem -> NavTarget(MainTab.Held, DraftReview(link.id.value))
    is DeepLink.Call -> null
}

/** The stack after following [target]: the main screen, with the linked screen on top if any. */
fun stackFor(target: NavTarget): List<NavKey> = listOfNotNull(Main, target.push)

fun HeldActionId.toDraftReview(): DraftReview = DraftReview(value)
