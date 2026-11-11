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

    @Test fun joinersOfRealScriptsAndEmojiStay() {
        val persian = "\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645" // ZWNJ inside a Persian word
        assertEquals(persian, oneLine(persian, 80))
        val family = "\uD83D\uDC69\u200D\uD83D\uDC69\u200D\uD83D\uDC67" // woman, ZWJ, woman, ZWJ, girl
        assertEquals(family, oneLine(family, 80))
    }

    @Test fun reorderingAndHiddenCharactersGo() {
        for (hidden in listOf("\u00AD", "\u061C", "\u202E", "\u2067", "\u2063", "\uDB40\uDC41")) {
            assertEquals("ab", oneLine("a" + hidden + "b", 80), hidden)
        }
    }

    @Test fun blankLookingFillersGoButEmojiSelectorsStay() {
        for (filler in listOf("\u034F", "\u115F", "\u1160", "\u3164", "\uFFA0", "\u180E")) {
            assertEquals("ab", oneLine("a" + filler + "b", 80), filler)
        }
        val heart = "\u2764\uFE0F"
        assertEquals(heart, oneLine(heart, 80))
    }

    @Test fun noRoomMeansNothing() {
        assertEquals("", oneLine("abc", 0))
        assertEquals("", oneLine("abc", -3))
        assertEquals("\u2026", oneLine("abc", 1))
    }

    @Test fun cuttingNeverLeavesALoneSurrogate() {
        val text = "ab" + "\uD83D\uDE00".repeat(10)
        for (max in 2..12) {
            val kept = oneLine(text, max).dropLast(1)
            assertEquals(kept.count { it.isHighSurrogate() }, kept.count { it.isLowSurrogate() }, "max $max")
        }
    }
}
