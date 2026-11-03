package net.robog.clarity.app.voice

/*
 * The engines the two-pass core drives, as interfaces: the sherpa-onnx versions (SherpaEngines.kt)
 * load native code; unit tests use fakes, so JVM tests never touch it.
 */

/** The first pass: a streaming model that decodes audio as it arrives. */
interface StreamingRecognizer : AutoCloseable {
    /** Feeds [samples] (16 kHz mono, -1..1), decodes what is ready and returns the current phrase's words. */
    fun accept(samples: FloatArray): String

    /** Starts a new phrase (the detector closed the last one). */
    fun reset()
}

/** Voice activity detection: which audio is speech, cut into phrases at short pauses. */
interface VoiceActivity : AutoCloseable {
    fun accept(samples: FloatArray)

    /** Speech is going on now. */
    val speaking: Boolean

    /** The audio of each phrase closed since the last call, oldest first. */
    fun closed(): List<FloatArray>

    /** Closes the phrase in progress, if any (the mic is stopping); it shows up in [closed]. */
    fun flush()

    fun reset()
}

/** The second pass: an offline model that decodes one phrase (or a whole message) at a time. */
interface PhraseRecognizer : AutoCloseable {
    /** Blocking; runs on the decode thread. [hotwords] is sherpa-onnx's "/"-separated list, or empty. */
    fun decode(samples: FloatArray, hotwords: String = ""): String
}

/** Work for the second pass. */
sealed interface DecodeJob {
    val message: Int
    val samples: FloatArray

    class OnePhrase(override val message: Int, val phrase: Int, override val samples: FloatArray) : DecodeJob
    class Whole(override val message: Int, override val samples: FloatArray) : DecodeJob

    val seconds: Double get() = samples.size.toDouble() / SAMPLE_RATE
}

/** What the second pass made of a [DecodeJob]; [text] is null when the decode failed. */
class DecodeResult(val job: DecodeJob, val text: String?)

/**
 * Two-pass recognition, single-threaded and free of Android: the caller feeds audio, hands decode jobs
 * to a background thread and returns their results here, all on one thread.
 *
 * Time is the audio's own clock (samples fed), so a pause is measured in audio, not in how fast the
 * phone kept up. A message is [graceMs] of silence after its last phrase (the old [PauseJoiner] rule):
 * it is then sealed, waits for its last phrase's second pass (and, when [endPass] says so, a pass
 * over the whole message), and goes out as one [SpeechEvent.Final]. Speech after the seal starts the
 * next message straight away; the microphone never stops in between.
 */
class TwoPassCore(
    private val first: StreamingRecognizer,
    private val vad: VoiceActivity,
    private val submit: (DecodeJob) -> Unit,
    private val emit: (SpeechEvent) -> Unit,
    /** Measured decode time per second of audio, for [endPass]; null when unknown. */
    private val speed: () -> Double? = { null },
    private val graceMs: Long = PAUSE_GRACE_MS,
) {
    private class Message(val id: Int) {
        val transcript = Transcript()
        /** Phrase audio by phrase id, kept for the whole-message pass while short enough. */
        val audio = LinkedHashMap<Int, FloatArray>()
        var keepAudio = true
        var redecoding = false
        var final: String? = null
    }

    private var nextMessage = 0
    private var open = Message(nextMessage++)
    private val waiting = ArrayDeque<Message>()

    private var samplesSeen = 0L
    private val nowMs: Long get() = samplesSeen * 1000 / SAMPLE_RATE
    private var lastActivityMs = 0L
    private var hearing = false
    private var lastShown = LiveWords("", "")

    /** Nothing held: no words in progress and no message waiting to be sent. */
    val idle: Boolean get() = open.transcript.isEmpty() && waiting.isEmpty()

    fun audio(samples: FloatArray) {
        samplesSeen += samples.size
        val words = first.accept(samples)
        vad.accept(samples)
        val speaking = vad.speaking
        if (speaking != hearing) {
            hearing = speaking
            emit(SpeechEvent.Hearing(speaking))
        }
        val closed = vad.closed()
        if (closed.isNotEmpty()) {
            open.transcript.live(words)
            closed.forEach(::closePhrase)
        } else {
            if (words != open.transcript.tail) lastActivityMs = nowMs // the first pass still finds words
            open.transcript.live(words)
        }
        if (speaking) lastActivityMs = nowMs
        if (!speaking && !open.transcript.isEmpty() && nowMs - lastActivityMs >= graceMs) seal()
        show()
    }

    /** A second-pass result, back on this thread. */
    fun decoded(result: DecodeResult) {
        val job = result.job
        val message = (waiting.asSequence() + open).firstOrNull { it.id == job.message } ?: return
        when (job) {
            is DecodeJob.OnePhrase -> {
                val live = message.transcript.all.firstOrNull { it.id == job.phrase }?.live.orEmpty()
                message.transcript.confirm(job.phrase, result.text ?: live)
            }
            is DecodeJob.Whole -> message.final = result.text?.takeIf { it.isNotBlank() } ?: message.transcript.joined()
        }
        finishReady()
        show()
    }

    /** The mic is stopping (stop or mute): close what is being said and send it once decoded. */
    fun flush() {
        vad.flush()
        val closed = vad.closed()
        val words = first.accept(FloatArray(0))
        open.transcript.live(words)
        closed.forEach(::closePhrase)
        seal()
        first.reset()
        vad.reset()
        if (hearing) {
            hearing = false
            emit(SpeechEvent.Hearing(false))
        }
        show()
    }

    private fun closePhrase(samples: FloatArray) {
        val message = open
        val id = message.transcript.close()
        if (message.keepAudio) {
            val kept = message.audio.values.sumOf { it.size } + samples.size
            if (kept <= MAX_REDECODE_SECONDS * SAMPLE_RATE) message.audio[id] = samples
            else { message.keepAudio = false; message.audio.clear() }
        }
        submit(DecodeJob.OnePhrase(message.id, id, samples))
        first.reset()
        lastActivityMs = nowMs
    }

    /** The pause lasted: this message is complete; new speech starts the next one. */
    private fun seal() {
        val message = open
        // Words the first pass heard that the detector never took for speech (very quiet): keep them as they are.
        if (message.transcript.tail.isNotBlank()) message.transcript.close(confirmed = message.transcript.tail)
        if (message.transcript.size == 0) return
        waiting.addLast(message)
        open = Message(nextMessage++)
        finishReady()
    }

    /** Sends every waiting message, in order, whose text is complete. */
    private fun finishReady() {
        while (true) {
            val head = waiting.firstOrNull() ?: return
            if (!head.transcript.settled) return
            if (head.final == null) {
                if (head.redecoding) return
                val seconds = head.audio.values.map { it.size.toDouble() / SAMPLE_RATE }
                val pass = if (head.keepAudio && head.audio.size == head.transcript.size) endPass(seconds, speed()) else EndPass.JoinPhrases
                if (pass == EndPass.Redecode) {
                    head.redecoding = true
                    submit(DecodeJob.Whole(head.id, joinAudio(head.audio.values.toList())))
                    return
                }
                head.final = head.transcript.joined()
            }
            waiting.removeFirst()
            val text = head.final.orEmpty()
            lastShown = LiveWords("", "") // the bubble empties with the message
            // Only noise (a cough the second pass found no words in): nothing to send, listening goes on.
            emit(if (text.isBlank()) SpeechEvent.Error(SpeechError.NoMatch) else SpeechEvent.Final(text))
        }
    }

    /** The live bubble: everything heard and not yet sent, settled words first. */
    private fun show() {
        var confirmed = ""
        var pending = ""
        var settledSoFar = true
        for (t in waiting.map { it.transcript } + open.transcript) {
            val s = t.shown()
            if (settledSoFar) {
                confirmed = joinSpeech(confirmed, s.confirmed)
                if (s.pending.isNotEmpty()) { settledSoFar = false; pending = s.pending }
            } else {
                pending = joinSpeech(pending, s.text)
            }
        }
        val words = LiveWords(confirmed, pending)
        if (words == lastShown) return
        lastShown = words
        if (!words.isEmpty()) emit(words.toEvent())
    }
}
