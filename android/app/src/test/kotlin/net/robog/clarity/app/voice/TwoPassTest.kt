package net.robog.clarity.app.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TwoPassTest {
    @Test fun preRollPutsTheAudioJustBeforeAPhraseBackInFront() {
        val p = PreRoll(keepSeconds = 2.0, preSeconds = 0.25, sampleRate = 100) // 100 Hz: one sample per 10 ms
        p.add(FloatArray(100) { it.toFloat() }) // samples 0..99
        // The detector says the phrase starts at sample 60 and gives 20 samples of it.
        val out = p.extend(60, FloatArray(20) { 60f + it })
        assertEquals((35 until 80).map { it.toFloat() }, out.toList(), "25 samples (0.25 s) of the onset put back")
    }

    @Test fun preRollNeverReachesIntoThePhraseBefore() {
        val p = PreRoll(keepSeconds = 2.0, preSeconds = 0.25, sampleRate = 100)
        p.add(FloatArray(100) { it.toFloat() })
        p.extend(10, FloatArray(40) { 10f + it }) // the first phrase: 10..49
        val second = p.extend(60, FloatArray(10) { 60f + it })
        assertEquals((50 until 70).map { it.toFloat() }, second.toList())
    }

    @Test fun preRollGivesThePhraseAsIsWhenItsOnsetIsNoLongerKept() {
        val p = PreRoll(keepSeconds = 0.5, preSeconds = 0.25, sampleRate = 100)
        p.add(FloatArray(300))
        val phrase = FloatArray(10) { 1f }
        assertEquals(phrase.toList(), p.extend(100, phrase).toList())
        p.reset()
        p.add(FloatArray(20) { 2f })
        assertEquals(30, p.extend(20, FloatArray(10)).size, "after a reset, positions count from 0 again")
    }

    @Test fun theVoiceLogKeepsTheLastLinesInMemory() {
        var now = 0L
        val log = VoiceLog(capacity = 3, clock = { now++ })
        (1..5).forEach { log.add("line $it") }
        assertEquals(listOf("line 3", "line 4", "line 5"), log.entries.value.map { it.text })
        log.clear()
        assertTrue(log.entries.value.isEmpty())
    }

    @Test fun sameWordsIgnoresCaseAndPunctuation() {
        assertTrue(sameWords("At some point.", "at  some point"))
        assertFalse(sameWords("at some point", "at some pint"))
        assertFalse(sameWords("", ""))
        assertFalse(sameWords("at some point", null))
    }

    @Test fun levelsAreInDecibelsBelowFullScale() {
        assertEquals(-120, levelDb(FloatArray(10)))
        assertEquals(0, levelDb(FloatArray(10) { 1f }))
        assertEquals(-20, levelDb(FloatArray(10) { 0.1f }))
    }
    @Test fun settledPhrasesLeadAndTheRestStaysLive() {
        val t = Transcript()
        assertTrue(t.isEmpty())
        t.live("and sales start")
        val a = t.close()
        t.live("at nine")
        assertEquals(LiveWords("", "and sales start at nine"), t.shown())
        t.confirm(a, " And class starts ")
        val shown = t.shown()
        assertEquals("And class starts at nine", shown.text)
        assertEquals("And class starts".length, shown.confirmedChars)
        val b = t.close()
        assertFalse(t.settled)
        t.confirm(b, "at 9:30.")
        assertTrue(t.settled)
        assertEquals("And class starts at 9:30.", t.joined())
    }

    @Test fun aLaterPhraseSettledFirstStillShowsAsPending() {
        val t = Transcript()
        t.live("one"); val a = t.close()
        t.live("two"); val b = t.close()
        t.confirm(b, "Two")
        assertEquals(LiveWords("", "one Two"), t.shown())
        t.confirm(a, "One")
        assertEquals(LiveWords("One Two", ""), t.shown())
        assertFalse(t.confirm(99, "stray"), "an unknown phrase changes nothing")
    }

    @Test fun liveWordsMapToAPartialWithItsSettledLength() {
        assertEquals(SpeechEvent.Partial("A b", 1), LiveWords("A", "b").toEvent())
        assertEquals(0, LiveWords("", "b").confirmedChars)
        assertTrue(LiveWords(" ", "").isEmpty())
    }

    @Test fun theWholeMessageIsRedecodedOnlyWhenItFitsTheBudget() {
        assertEquals(EndPass.JoinPhrases, endPass(listOf(2.0), 0.01), "one phrase was already decoded whole")
        assertEquals(EndPass.JoinPhrases, endPass(listOf(2.0, 2.0), null), "unknown speed")
        assertEquals(EndPass.Redecode, endPass(listOf(2.0, 3.0), 0.1)) // 5.25 s × 0.1 + 40 ms ≈ 565 ms
        assertEquals(EndPass.JoinPhrases, endPass(listOf(4.0, 4.0), 0.1)) // ≈ 865 ms
        assertEquals(EndPass.JoinPhrases, endPass(listOf(20.0, 15.0), 0.001), "too long to keep in memory")
        assertEquals(EndPass.Redecode, endPass(listOf(4.0, 4.0), 0.1, budgetMs = 900))
    }

    @Test fun phrasesAreJoinedWithAShortSilence() {
        val joined = joinAudio(listOf(floatArrayOf(1f, 1f), floatArrayOf(2f)), sampleRate = 8)
        assertEquals(listOf(1f, 1f, 0f, 0f, 2f), joined.toList())
        assertEquals(0, joinAudio(emptyList()).size)
    }

    @Test fun decodeSpeedIsSmoothed() {
        assertEquals(0.2, updatedSpeed(null, 5.0, 1_000))
        assertEquals(0.2 + 0.3 * (0.4 - 0.2), updatedSpeed(0.2, 5.0, 2_000)!!, 1e-9)
        assertEquals(0.2, updatedSpeed(0.2, 0.1, 5_000), "a blip is too short to measure")
    }

    @Test fun hotwordsComeFromCapitalisedWordsInsideTitles() {
        val words = hotwordsFrom(
            listOf(
                "Email Professor Klaus about the Calculus exam",
                "Book a room at Ballard Library for Thursday",
                "reply to Klaus",
                "Call Mom",
            ),
        )
        assertEquals(listOf("Professor", "Klaus", "Calculus", "Ballard", "Library", "Mom"), words)
        assertEquals(2, hotwordsFrom(listOf("x Alpha Beta Gamma"), max = 2).size)
        assertEquals(emptyList(), hotwordsFrom(emptyList()))
    }

    @Test fun hotwordsArgumentDropsTheLibrarysSyntax() {
        assertEquals("Klaus/Ballard Library/AC DC/5", hotwordsArg(listOf("Klaus", " Ballard  Library ", "AC/DC", "Klaus", ":5", "")))
        assertEquals("", hotwordsArg(emptyList()))
    }

    @Test fun bpeVocabularyIsTheTokenListInOrderWithoutBlank() {
        val tokens = "<unk> 0\n▁t 1\n▁th 2\nad 99\n<blk> 1024\n"
        assertEquals("<unk>\t0.0\n▁t\t-0.0\n▁th\t-1.0\nad\t-98.0\n", bpeVocabFromTokens(tokens))
    }
}
