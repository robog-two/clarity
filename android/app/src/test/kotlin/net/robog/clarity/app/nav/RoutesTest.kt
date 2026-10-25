package net.robog.clarity.app.nav

import kotlin.test.Test
import kotlin.test.assertEquals

class RoutesTest {
    @Test fun signingInReplacesOnboardingWithHome() =
        assertEquals(listOf(Home), reconcileBackStack(listOf(Onboarding), signedIn = true))

    @Test fun forgettingResetsToOnboarding() =
        assertEquals(listOf(Onboarding), reconcileBackStack(listOf(Home, Settings), signedIn = false))

    @Test fun consistentStackUntouched() {
        val s = listOf(Home, Connectors)
        assertEquals(s, reconcileBackStack(s, signedIn = true))
    }
}
