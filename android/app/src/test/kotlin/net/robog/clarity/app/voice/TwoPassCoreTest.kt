package net.robog.clarity.app.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TwoPassCoreTest {
    /** A core with fake engines; [immediate] decodes each phrase at once, otherwise jobs wait in [jobs]. */
    private class Rig(val immediate: Boolean = true, speed: Double? = null) {
        val events = mutableListOf<SpeechEvent>()
        val jobs = ArrayDeque<DecodeJob>()
        val second = FakeSecondPass()
        val first = FakeFirstPass()
        var mic = MicState()
        val commands = mutableListOf<MicCommand>()
        lateinit var core: TwoPassCore

        init {
            core = TwoPassCore(first, FakeVad(), submit = { job -> jobs += job; if (immediate) decodeAll() }, emit = ::onEvent, speed = { speed })
        }

        fun start(keepOn: Boolean) { mic = micStep(mic, MicInput.Start(keepOn)).first }

        private fun onEvent(e: SpeechEvent) {
            events += e
            val (next, command) = micStep(mic, MicInput.Recognizer(e))
            mic = next
            commands += command
        }

        fun feed(chunks: List<FloatArray>) = chunks.forEach(core::audio)

        fun decodeAll() {
            while (jobs.isNotEmpty()) {
                val job = jobs.removeFirst()
                core.decoded(DecodeResult(job, second.decode(job.samples, "")))
            }
        }

        val finals get() = events.filterIsInstance<SpeechEvent.Final>().map { it.text }
        val partials get() = events.filterIsInstance<SpeechEvent.Partial>()
    }

    @Test fun phrasesSeparatedByShortPausesBecomeOneMessageWithSecondPassText() {
        val r = Rig()
        r.start(keepOn = true)
        r.feed(speech(1) + speech(2) + silence(1_000) + speech(3) + silence(800))
        assertEquals(emptyList(), r.finals, "a pause under 3 s keeps the message open")
        r.feed(silence(3_000))
        assertEquals(listOf("W1 W2 W3"), r.finals)
        assertEquals(MicMode.Listening, r.mic.mode)
    }

    @Test fun confirmedWordsReplaceLiveWordsAndAreMarkedSettled() {
        val r = Rig(immediate = false)
        r.start(keepOn = true)
        r.feed(speech(1) + speech(2))
        assertEquals("w1 w2", r.partials.last().text)
        assertEquals(0, r.partials.last().confirmed)
        r.feed(silence(600)) // the detector closes the phrase
        assertEquals(1, r.jobs.size)
        r.feed(speech(3))
        assertEquals(SpeechEvent.Partial("w1 w2 w3", 0), r.partials.last(), "the closed phrase shows its live words until decoded")
        r.decodeAll()
        assertEquals(SpeechEvent.Partial("W1 W2 w3", "W1 W2".length), r.partials.last())
    }

    @Test fun theMicStaysOnAcrossManyMessagesWithoutEverStopping() {
        val r = Rig()
        r.start(keepOn = true)
        repeat(25) { i -> r.feed(speech(i + 1, chunks = 4) + silence(3_600)) }
        assertEquals((1..25).map { "W$it" }, r.finals)
        assertEquals(MicMode.Listening, r.mic.mode)
        assertTrue(MicCommand.StopRecognizer !in r.commands && MicCommand.RetryRecognizer !in r.commands)
    }

    @Test fun speechRightAfterTheSealStartsTheNextMessage() {
        val r = Rig(immediate = false)
        r.start(keepOn = true)
        r.feed(speech(1) + silence(3_600)) // sealed, its decode still running
        r.feed(speech(2))
        r.decodeAll()
        assertEquals(listOf("W1"), r.finals, "the first message goes out alone")
        assertEquals("w2", r.partials.last().text, "the next one is already showing")
        r.feed(silence(3_600))
        r.decodeAll()
        assertEquals(listOf("W1", "W2"), r.finals)
    }

    @Test fun messagesGoOutInOrderEvenIfTheirDecodesFinishOutOfOrder() {
        val r = Rig(immediate = false)
        r.start(keepOn = true)
        r.feed(speech(1) + silence(3_600) + speech(2) + silence(3_600))
        val (one, two) = r.jobs.toList()
        r.jobs.clear()
        r.core.decoded(DecodeResult(two, "W2"))
        assertEquals(emptyList(), r.finals)
        r.core.decoded(DecodeResult(one, "W1"))
        assertEquals(listOf("W1", "W2"), r.finals)
    }

    @Test fun aFailedDecodeKeepsTheLiveWords() {
        val r = Rig(immediate = false)
        r.start(keepOn = true)
        r.feed(speech(4) + silence(3_600))
        r.core.decoded(DecodeResult(r.jobs.removeFirst(), null))
        assertEquals(listOf("w4"), r.finals)
    }

    @Test fun pushToTalkTurnsTheMicOffAfterTheMessage() {
        val r = Rig()
        r.start(keepOn = false)
        r.feed(speech(1) + silence(3_600))
        assertEquals(listOf("W1"), r.finals)
        assertEquals(MicMode.Off, r.mic.mode)
    }

    @Test fun stoppingSendsWhatWasBeingSaid() {
        val r = Rig()
        r.start(keepOn = true)
        r.feed(speech(1) + silence(600) + speech(2))
        r.core.flush()
        assertEquals(listOf("W1 W2"), r.finals)
        assertTrue(r.core.idle)
    }

    @Test fun onlyNoiseSendsNothingAndListeningGoesOn() {
        val r = Rig(immediate = false)
        r.start(keepOn = true)
        r.feed(speech(1) + silence(3_600))
        r.core.decoded(DecodeResult(r.jobs.removeFirst(), ""))
        assertEquals(emptyList(), r.finals)
        assertEquals(SpeechEvent.Error(SpeechError.NoMatch), r.events.last())
        assertEquals(MicMode.Listening, r.mic.mode)
    }

    @Test fun aShortMessageOnAFastPhoneIsDecodedWholeAtTheEnd() {
        val r = Rig(immediate = true, speed = 0.05)
        r.start(keepOn = true)
        r.feed(speech(1) + silence(600) + speech(2) + silence(3_600))
        val whole = r.second.decoded.last()
        assertEquals(3, r.second.decoded.size, "two phrases, then the whole message")
        assertTrue(whole > r.second.decoded[0] + r.second.decoded[1], "phrases joined with a gap between")
        assertEquals(listOf("W1 W2"), r.finals)
    }

    @Test fun aSlowPhoneJoinsThePhrasesInstead() {
        val r = Rig(immediate = true, speed = 1.0) // ≈ 0.9 s for 0.85 s of audio: over the budget
        r.start(keepOn = true)
        r.feed(speech(1) + silence(600) + speech(2) + silence(3_600))
        assertEquals(2, r.second.decoded.size)
        assertEquals(listOf("W1 W2"), r.finals)
    }

    @Test fun hearingFollowsTheDetector() {
        val r = Rig()
        r.start(keepOn = true)
        r.feed(speech(1) + silence(600))
        val hearing = r.events.filterIsInstance<SpeechEvent.Hearing>().map { it.active }
        assertEquals(listOf(true, false), hearing)
    }
}
