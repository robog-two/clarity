package net.robog.clarity.app.voice

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PhraseGuardsTest {
    /** A 200 Hz tone at [db] dBFS RMS: stands in for voice. */
    private fun tone(db: Double, seconds: Double): FloatArray {
        val amp = Math.pow(10.0, db / 20) * Math.sqrt(2.0)
        return FloatArray((seconds * SAMPLE_RATE).toInt()) { (amp * sin(2 * PI * 200 * it / SAMPLE_RATE)).toFloat() }
    }

    private fun lsb(seconds: Double) = FloatArray((seconds * SAMPLE_RATE).toInt()) { if (it % 7 == 0) 1f / 32768 else 0f }

    private fun feed(front: DetectorFront, audio: FloatArray, chunk: Int = SAMPLE_RATE / 10): FloatArray {
        val out = ArrayList<Float>()
        var i = 0
        while (i < audio.size) {
            val end = minOf(i + chunk, audio.size)
            front.process(audio.copyOfRange(i, end)).forEach(out::add)
            i = end
        }
        return out.toFloatArray()
    }

    // --- the detector's front end ---

    @Test fun `samples of a nearly muted mic become exact silence`() {
        val out = feed(DetectorFront(lift = false), lsb(2.0))
        assertTrue(out.all { it == 0f })
    }

    @Test fun `speech passes the gate untouched when it is loud enough`() {
        val speech = tone(-30.0, 1.0)
        assertContentEquals(speech, feed(DetectorFront(lift = false), speech))
    }

    @Test fun `the gaps between words are not chopped`() {
        val front = DetectorFront(lift = false)
        val word = tone(-40.0, 0.3)
        val gap = FloatArray(SAMPLE_RATE / 10) { 1e-6f } // 0.1 s, far under the floor
        val out = feed(front, word + gap + word)
        // The hang-over keeps the gate open for the short gap: the gap's samples are not zeroed.
        assertTrue(out.slice(word.size until word.size + gap.size).all { it == 1e-6f })
    }

    @Test fun `a long quiet stretch is silenced after the hang-over`() {
        val front = DetectorFront()
        val out = feed(front, tone(-40.0, 0.5) + FloatArray(SAMPLE_RATE * 2) { 1e-6f })
        assertTrue(out.takeLast(SAMPLE_RATE).all { it == 0f })
    }

    @Test fun `output has the length of the input for any chunk size`() {
        for (chunk in listOf(1, 7, 319, 320, 321, 1000, 1777, 1600)) {
            val front = DetectorFront()
            val audio = tone(-35.0, 1.0)
            assertEquals(audio.size, feed(front, audio, chunk).size, "chunk $chunk")
        }
    }

    @Test fun `the same audio gives the same result in any chunk size`() {
        val audio = tone(-50.0, 2.0) + lsb(1.0) + tone(-45.0, 1.0)
        val whole = feed(DetectorFront(), audio, audio.size)
        val chunked = feed(DetectorFront(), audio, 320)
        assertContentEquals(whole, chunked)
    }

    @Test fun `faint speech is lifted towards the target`() {
        val front = DetectorFront()
        val out = feed(front, tone(-50.0, 6.0))
        val last = out.takeLast(SAMPLE_RATE)
        val level = levelDb(last.toFloatArray())
        assertTrue(level > -50 + 10, "lifted by at least 10 dB, was $level")
        assertTrue(front.liftDb <= 20.0 + 1e-9, "never more than 20 dB")
    }

    @Test fun `speech that is already loud is not lifted`() {
        val front = DetectorFront()
        feed(front, tone(-12.0, 3.0))
        assertEquals(0.0, front.liftDb)
    }

    @Test fun `silence is never lifted`() {
        val front = DetectorFront()
        feed(front, tone(-55.0, 4.0)) // the gain rises
        val out = feed(front, FloatArray(SAMPLE_RATE * 3)) // then true silence
        assertTrue(out.all { it == 0f })
    }

    @Test fun `a loud click drops the lift at once`() {
        val front = DetectorFront()
        feed(front, tone(-55.0, 6.0))
        assertTrue(front.liftDb > 5.0)
        feed(front, tone(-5.0, 0.2))
        assertTrue(front.liftDb < 5.0 - 1.0 || front.liftDb < 8.0, "gain falls quickly, was ${front.liftDb}")
        feed(front, tone(-5.0, 0.6))
        assertEquals(0.0, front.liftDb)
    }

    @Test fun `lifted audio never leaves full scale`() {
        val out = feed(DetectorFront(), tone(-40.0, 3.0))
        assertTrue(out.all { it in -1f..1f })
    }

    @Test fun `reset forgets the level and the gate`() {
        val front = DetectorFront()
        feed(front, tone(-55.0, 4.0))
        front.reset()
        assertEquals(0.0, front.liftDb)
    }

    // --- making a faint phrase louder for the second pass ---

    @Test fun `a faint phrase is brought up to the target level`() {
        val faint = tone(-55.0, 1.0)
        val lifted = normalizeForDecode(faint)
        assertEquals(-30, levelDb(lifted))
    }

    @Test fun `the gain is capped`() {
        val faint = tone(-70.0, 1.0)
        assertEquals(-40, levelDb(normalizeForDecode(faint)))
    }

    @Test fun `loud phrases and silence come back as they are`() {
        val loud = tone(-25.0, 1.0)
        assertSame(loud, normalizeForDecode(loud))
        val silence = FloatArray(SAMPLE_RATE)
        assertSame(silence, normalizeForDecode(silence))
        val empty = FloatArray(0)
        assertSame(empty, normalizeForDecode(empty))
    }

    @Test fun `normalising never clips`() {
        val spiky = FloatArray(SAMPLE_RATE) { if (it % 1000 == 0) 0.2f else 0.0005f }
        assertTrue(normalizeForDecode(spiky).all { it in -1f..1f })
    }

    // --- phrases that are silence ---

    @Test fun `near silence is left out whatever the live pass said`() {
        assertEquals("silence", silentPhrase(-95, ""))
        assertEquals("silence", silentPhrase(-76, "okay"))
        assertNull(silentPhrase(-74, "okay"))
    }

    @Test fun `a quiet phrase the live pass heard nothing in is left out`() {
        assertEquals("quiet and no live words", silentPhrase(-63, ""))
        assertNull(silentPhrase(-63, "yes"))
        assertNull(silentPhrase(-40, ""))
    }

    // --- a second pass that repeats itself ---

    @Test fun `a word repeated three times in a row is a loop`() {
        assertTrue(hasLoop("Devils Cloud Cloud Cloud Cloud"))
        assertTrue(hasLoop("cloud, Cloud. cloud"))
        assertFalse(hasLoop("Cloud Cloud and Canvas"))
        assertFalse(hasLoop("the cloud and the canvas and the time"))
    }

    @Test fun `a short pattern repeated three times in a row is a loop`() {
        assertTrue(hasLoop("time to time to time to"))
        assertTrue(hasLoop("a b c a b c a b c"))
        assertFalse(hasLoop("time to go and time to eat"))
    }

    @Test fun `the second pass is distrusted only when the live words do not loop too`() {
        assertTrue(looksLikeLoop("Paul Cloud Cloud Cloud Cloud", "paul"))
        assertTrue(looksLikeLoop("Cloud Cloud Cloud", ""))
        assertFalse(looksLikeLoop("No. No. No.", "no no no"), "a person who says it three times is heard so by both")
        assertFalse(looksLikeLoop("Can you find that email?", "can you find that email"))
    }

    @Test fun `long runs are squeezed to one and a stutter is kept`() {
        assertEquals("Devils Cloud Canvas", collapseRuns("Devils Cloud Cloud Cloud Cloud Cloud Canvas"))
        assertEquals("no no no", collapseRuns("no no no"))
        assertEquals("go go", collapseRuns("go go"))
        assertEquals("", collapseRuns(""))
    }

    @Test fun `a repeated pattern is squeezed to one`() {
        assertEquals("Time to Paul", collapseRuns("Time to Time to Time to Time to Paul"))
    }

    @Test fun `the log quotes a runaway pass in a few words`() {
        val q = quoted("Cloud ".repeat(100))
        assertTrue(q.length <= MAX_QUOTED + 3, q.length.toString())
        assertTrue(q.endsWith("…”"))
        assertEquals("“yes”", quoted("yes"))
    }

    // --- fillers and lone words ---

    @Test fun `only fillers is not a message`() {
        for (t in listOf("Um", "um.", "Uh,", "Mm-hmm.", "mm", "Hmm", "Um uh", "Ah.", "Er", "Umm...", "Mhm")) assertTrue(isFillerOnly(t), t)
    }

    @Test fun `words are not fillers`() {
        for (t in listOf("", "  ", "Yes.", "Um yes", "Mm-hmm okay", "Hello", "Ahead")) assertFalse(isFillerOnly(t), t)
    }

    @Test fun `words are counted without punctuation`() {
        assertEquals(1, wordCount("Yes."))
        assertEquals(2, wordCount("Yes, please."))
        assertEquals(0, wordCount(" ... "))
    }
}
