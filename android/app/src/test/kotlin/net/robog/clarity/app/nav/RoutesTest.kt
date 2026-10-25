package net.robog.clarity.app.nav

import kotlin.test.Test
import kotlin.test.assertEquals

class RoutesTest {
    @Test fun finishingOnboardingReplacesItWithTheApp() =
        assertEquals(listOf(Main), reconcileBackStack(listOf(Onboarding), ready = true))

    @Test fun forgettingResetsToOnboarding() =
        assertEquals(listOf(Onboarding), reconcileBackStack(listOf(Main), ready = false))

    @Test fun consistentStackUntouched() {
        assertEquals(listOf(Main), reconcileBackStack(listOf(Main), ready = true))
        assertEquals(listOf(Onboarding), reconcileBackStack(listOf(Onboarding), ready = false))
    }

    @Test fun emptyStackGetsTheRightRoot() {
        assertEquals(listOf(Main), reconcileBackStack(emptyList(), ready = true))
        assertEquals(listOf(Onboarding), reconcileBackStack(emptyList(), ready = false))
    }
}
