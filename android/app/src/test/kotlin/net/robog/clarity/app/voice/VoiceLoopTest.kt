package net.robog.clarity.app.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The v0.1.34 field report: the same few words sent every 3 s ("at some point" five times), and what
 * was said before them lost. The streaming model was still holding a phrase's last words when the
 * detector closed it; `reset()` let them out afterwards as the next message's words, and a message
 * made of such words never reset the model, so they came back as every message after.
 */
class VoiceLoopTest {
    private class Rig(first: StreamingRecognizer, private val decode: (DecodeJob) -> String? = { FakeSecondPass().decode(it.samples, "") }) {
        val events = mutableListOf<SpeechEvent>()
        val log = mutableListOf<String>()
        lateinit var core: TwoPassCore
        init {
            core = TwoPassCore(first, FakeVad(), submit = { job -> core.decoded(DecodeResult(job, decode(job))) }, emit = { events += it }, log = { log += it })
        }
        fun feed(chunks: List<FloatArray>) = chunks.forEach(core::audio)
        val finals get() = events.filterIsInstance<SpeechEvent.Final>().map { it.text }
    }

    @Test fun aPhrasesLastWordsStillInTheModelAreNotSentAgainAndAgain() {
        val r = Rig(LaggingFirstPass())
        r.feed(speech(1) + speech(2) + silence(15_000))
        assertEquals(listOf("W1 W2"), r.finals)
    }

    @Test fun theLiveWordsOfAClosedPhraseIncludeTheWordsTheModelStillHeld() {
        val r = Rig(LaggingFirstPass(), decode = { null }) // no second pass: the live words are what is sent
        r.feed(speech(1) + speech(2) + silence(3_600))
        assertEquals(listOf("w1 w2"), r.finals)
    }

    @Test fun anEngineStuckOnTheSameWordsSendsThemOnceAtMost() {
        val r = Rig(StuckFirstPass("at some point"))
        r.feed(speech(1) + silence(20_000))
        assertEquals(listOf("W1 at some point"), r.finals)
        assertTrue(r.log.any { it.startsWith("Dropped") })
    }

    @Test fun quietWordsTheDetectorMissedAreSentOnceAndTheModelStartsAfresh() {
        val first = FakeFirstPass()
        val r = Rig(first)
        // The first pass hears w7, the detector hears nothing (too quiet): an orphan, sent as heard.
        val quiet = List(3) { FloatArray(CHUNK) { 7f } }
        val deaf = object : VoiceActivity by FakeVad() {
            override fun accept(samples: FloatArray) = Unit
        }
        val core = TwoPassCore(first, deaf, submit = {}, emit = { r.events += it })
        (quiet + silence(15_000)).forEach(core::audio)
        assertEquals(listOf("w7"), r.finals)
        assertEquals(1, first.phrasesEnded)
    }

    @Test fun anEmptySecondPassKeepsTheLiveWords() {
        val r = Rig(FakeFirstPass(), decode = { "" })
        r.feed(speech(4) + silence(3_600))
        assertEquals(listOf("w4"), r.finals)
        assertTrue(r.log.any { "second pass nothing" in it && "Kept the live words" in it }, r.log.toString())
    }

    @Test fun theLogTellsEachPhraseAndWhatBothPassesHeard() {
        val r = Rig(FakeFirstPass())
        r.feed(speech(3) + silence(3_600))
        assertTrue(r.log.any { it.startsWith("Phrase 0.0 closed") && "Live “w3”" in it }, r.log.toString())
        assertTrue(r.log.any { it == "Phrase 0.0 second pass “W3”" }, r.log.toString())
        assertTrue(r.log.any { it == "Message 0 sent “W3”" }, r.log.toString())
    }
}
