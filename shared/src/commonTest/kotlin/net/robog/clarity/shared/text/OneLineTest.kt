package net.robog.clarity.shared.text

import kotlin.test.Test
import kotlin.test.assertEquals

class OneLineTest {
    @Test fun whitespaceAndControlCharactersBecomeOneSpace() {
        assertEquals("Lunch Approve everything", oneLine("  Lunch\n\n\tApprove\u0000   everything ", 80))
    }

    @Test fun invisibleCharactersAreDropped() {
        assertEquals("Gym", oneLine("G\u200By\u202Em", 80))
        assertEquals("ab", oneLine("a\u200Fb", 80))
        assertEquals("abc", oneLine("\uFEFFa\u2066b\u2069c", 80))
    }

    @Test fun longTextIsCutWithAnEllipsis() {
        assertEquals("abcd\u2026", oneLine("abcdefghij", 5))
        assertEquals("abcde", oneLine("abcde", 5))
        assertEquals(60, oneLine("x".repeat(500), 60).length)
    }

    @Test fun emptyStaysEmpty() {
        assertEquals("", oneLine(" \n ", 10))
    }
}
