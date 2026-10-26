package net.robog.clarity.app.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.robog.clarity.shared.governance.HeldActionId

class DeepLinkTest {
    @Test fun parsesTheTabLinks() {
        assertEquals(DeepLink.Today, parseDeepLink("clarity://today"))
        assertEquals(DeepLink.Ask, parseDeepLink("clarity://ask"))
        assertEquals(DeepLink.Held, parseDeepLink("clarity://held"))
        assertEquals(DeepLink.Held, parseDeepLink("clarity://held/"))
    }

    @Test fun parsesAHeldItem() {
        assertEquals(DeepLink.HeldItem(HeldActionId("abc-123")), parseDeepLink("clarity://held/abc-123"))
    }

    @Test fun parsesTheCallWithAndWithoutAnswer() {
        assertEquals(DeepLink.Call(answer = false), parseDeepLink("clarity://call"))
        assertEquals(DeepLink.Call(answer = true), parseDeepLink("clarity://call?answer=1"))
    }

    @Test fun ignoresOtherSchemesAndUnknownPaths() {
        assertNull(parseDeepLink(null))
        assertNull(parseDeepLink("https://example.com/today"))
        assertNull(parseDeepLink("clarity://nowhere"))
        assertNull(parseDeepLink("clarity://"))
    }

    @Test fun uriRoundTrips() {
        listOf(DeepLink.Today, DeepLink.Ask, DeepLink.Held, DeepLink.HeldItem(HeldActionId("h1")), DeepLink.Call(false), DeepLink.Call(true))
            .forEach { assertEquals(it, parseDeepLink(it.toUri())) }
    }

    @Test fun tabLinksSelectTheTabOnTheMainScreen() {
        assertEquals(NavTarget(MainTab.Held), targetFor(DeepLink.Held))
        assertEquals(listOf(Main), stackFor(targetFor(DeepLink.Today)!!))
    }

    @Test fun heldItemOpensTheReviewOnTopOfTheHeldTab() {
        val target = targetFor(DeepLink.HeldItem(HeldActionId("h1")))!!
        assertEquals(MainTab.Held, target.tab)
        assertEquals(listOf(Main, DraftReview("h1")), stackFor(target))
    }

    @Test fun theCallIsNotHandledByTheMainScreen() {
        assertNull(targetFor(DeepLink.Call()))
    }
}
