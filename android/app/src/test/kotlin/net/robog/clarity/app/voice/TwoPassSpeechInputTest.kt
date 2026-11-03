package net.robog.clarity.app.voice

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The threaded shell around [TwoPassCore], with fake audio and engines (real threads, no native code). */
class TwoPassSpeechInputTest {
    private class ScriptedMic(chunks: List<FloatArray>) : AudioSource {
        val script = ConcurrentLinkedQueue(chunks)
        val opened = AtomicInteger()
        override fun open(): AudioStream {
            opened.incrementAndGet()
            return object : AudioStream {
                override fun read(): FloatArray {
                    // After the script: silence, a little faster than real time.
                    Thread.sleep(1) // paced like a real recorder, only faster
                    return script.poll() ?: FloatArray(CHUNK)
                }
                override fun close() = Unit
            }
        }
    }

    private class Engines(val failWith: Throwable? = null) : SpeechEngineFactory {
        override fun firstPass(paths: EnginePaths): StreamingRecognizer = failWith?.let { throw it } ?: FakeFirstPass()
        override fun vad(paths: EnginePaths): VoiceActivity = FakeVad()
        override fun secondPass(paths: EnginePaths): PhraseRecognizer = FakeSecondPass()
    }

    private val paths = EnginePaths(File("base"), File("second"), VoiceTier.Light, null)

    @Test fun aMorningCallHearsManyMessagesWithOneMicrophoneStream() = runBlocking {
        val messages = 12
        val mic = ScriptedMic((1..messages).flatMap { speech(it, chunks = 4) + silence(3_600) })
        val bus = SpeechBus()
        val input = TwoPassSpeechInput(bus, { paths }, Engines(), mic, idleReleaseMs = 50)
        val finals = mutableListOf<String>()
        val stops = AtomicInteger()
        val collector = launch(Dispatchers.Default) {
            bus.events.collect { e ->
                if (e is SpeechEvent.Final) synchronized(finals) { finals += e.text }
            }
        }
        val watcher = launch(Dispatchers.Default) { bus.mic.collect { if (it.mode == MicMode.Off) stops.incrementAndGet() } }
        Thread.sleep(50) // subscribers in place
        stops.set(0)
        input.start(keepOn = true)
        withTimeout(20_000) { while (synchronized(finals) { finals.size } < messages) kotlinx.coroutines.delay(20) }
        assertEquals((1..messages).map { "W$it" }, synchronized(finals) { finals.toList() })
        assertEquals(MicMode.Listening, bus.mic.value.mode)
        assertEquals(1, mic.opened.get(), "one recorder for the whole call: no restarts, no chime")
        assertEquals(0, stops.get(), "the mic never went off in between")
        input.stop()
        withTimeout(5_000) { bus.mic.first { it.mode == MicMode.Off } }
        collector.cancel()
        watcher.cancel()
        input.release()
    }

    @Test fun stoppingSendsTheWordsInProgressAndTurnsTheMicOff() = runBlocking {
        val mic = ScriptedMic(speech(5, chunks = 4))
        val bus = SpeechBus()
        val input = TwoPassSpeechInput(bus, { paths }, Engines(), mic)
        val finals = mutableListOf<String>()
        val collector = launch(Dispatchers.Default) { bus.events.collect { e -> if (e is SpeechEvent.Final) synchronized(finals) { finals += e.text } } }
        Thread.sleep(50)
        input.start(keepOn = false)
        withTimeout(5_000) { while (mic.script.isNotEmpty()) kotlinx.coroutines.delay(10) }
        input.stop()
        withTimeout(5_000) { while (synchronized(finals) { finals.isEmpty() }) kotlinx.coroutines.delay(10) }
        assertEquals(listOf("W5"), synchronized(finals) { finals.toList() })
        assertEquals(MicMode.Off, bus.mic.value.mode)
        collector.cancel()
        input.release()
    }

    @Test fun enginesThatCannotLoadHandOverToTheBuiltInRecognizer() = runBlocking {
        val bus = SpeechBus()
        var handedOver: Boolean? = null
        val input = TwoPassSpeechInput(
            bus, { paths }, Engines(failWith = UnsatisfiedLinkError("no arm64")), ScriptedMic(emptyList()),
            onUnavailable = { keepOn, t -> handedOver = keepOn; assertTrue(t is UnsatisfiedLinkError) },
        )
        input.start(keepOn = true)
        withTimeout(5_000) { while (handedOver == null) kotlinx.coroutines.delay(10) }
        assertEquals(true, handedOver)
        assertEquals(MicState(), bus.mic.value, "the built-in recognizer starts from a clean state")
        input.release()
    }
}
