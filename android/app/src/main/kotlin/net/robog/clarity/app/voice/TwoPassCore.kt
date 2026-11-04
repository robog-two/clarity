package net.robog.clarity.app.voice

/*
 * The engines the two-pass core drives, as interfaces: the sherpa-onnx versions (SherpaEngines.kt)
 * load native code; unit tests use fakes, so JVM tests never touch it.
 */

/** The first pass: a streaming model that decodes audio as it arrives. */
interface StreamingRecognizer : AutoCloseable {
    /** Feeds [samples] (16 kHz mono, -1..1), decodes what is ready and returns the current phrase's words. */
    fun accept(samples: FloatArray): String

    /**
     * The phrase is over (the detector closed it, or the message or the mic ended): decodes what the
     * model still holds and returns all of the phrase's words, then starts afresh, so nothing of this
     * phrase can come out later as words of the next one.
     */
    fun endPhrase(): String
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

/** What the second pass made of a [DecodeJob]; [text] is null when the decode failed. [ms]: how long it took. */
class DecodeResult(val job: DecodeJob, val text: String?, val ms: Long? = null)

/**
 * Two-pass recognition, single-threaded and free of Android: the caller feeds audio, hands decode jobs
 * to a background thread and returns their results here, all on one thread.
 *
 * Time is the audio's own clock (samples fed), so a pause is measured in audio, not in how fast the
 * phone kept up. A message is [graceMs] of silence after its last phrase (the old [PauseJoiner] rule):
 * it is then sealed, waits for its last phrase's second pass (and, when [endPass] says so, a pass
 * over the whole message), and goes out as one [SpeechEvent.Final]. Speech after the seal starts the
 * next message straight away; the microphone never stops in between.
 *
 * [log] gets a line for the voice log at each step (phrases, both passes' words, messages); it stays on the phone.
 */
class TwoPassCore(
    private val first: StreamingRecognizer,
    private val vad: VoiceActivity,
    private val submit: (DecodeJob) -> Unit,
    private val emit: (SpeechEvent) -> Unit,
    /** Measured decode time per second of audio, for [endPass]; null when unknown. */
    private val speed: () -> Double? = { null },
    private val graceMs: Long = PAUSE_GRACE_MS,
    private val log: (String) -> Unit = {},
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

    /** For the voice log: how much audio the detector took for speech, and how many phrases it closed. */
    var speechSamples = 0L
        private set
    var phrasesClosed = 0
        private set

    /**
     * The first-pass words the detector never heard as speech that ended the last message. A message of
     * only the same words again, with no speech heard in between, is an engine repeating itself, not the
     * person: it is dropped instead of sent a second time.
     */
    private var lastUnheard: String? = null

    /** Nothing held: no words in progress and no message waiting to be sent. */
    val idle: Boolean get() = open.transcript.isEmpty() && waiting.isEmpty()

    fun audio(samples: FloatArray) {
        samplesSeen += samples.size
        val words = first.accept(samples)
        vad.accept(samples)
        val speaking = vad.speaking
        if (speaking) speechSamples += samples.size
        if (speaking != hearing) {
            hearing = speaking
            emit(SpeechEvent.Hearing(speaking))
        }
        val closed = vad.closed()
        if (closed.isNotEmpty()) {
            // The phrase's last words may still be inside the first pass: take them all, and start it afresh.
            open.transcript.live(first.endPhrase().ifBlank { words })
            closed.forEach(::closePhrase)
        } else {
            if (words != open.transcript.tail) lastActivityMs = nowMs // the first pass still finds words
            open.transcript.live(words)
        }
        if (speaking) lastActivityMs = nowMs
        if (!speaking && !open.transcript.isEmpty() && nowMs - lastActivityMs >= graceMs) seal(firstEnded = false)
        show()
    }

    /** A second-pass result, back on this thread. */
    fun decoded(result: DecodeResult) {
        val job = result.job
        val message = (waiting.asSequence() + open).firstOrNull { it.id == job.message } ?: return
        val took = result.ms?.let { " in $it ms" }.orEmpty()
        when (job) {
            is DecodeJob.OnePhrase -> {
                val live = message.transcript.all.firstOrNull { it.id == job.phrase }?.live.orEmpty()
                val text = result.text?.takeIf { it.isNotBlank() }
                // An empty or failed decode keeps the live words: the detector heard speech there.
                message.transcript.confirm(job.phrase, text ?: live)
                log(
                    "Phrase ${message.id}.${job.phrase} second pass ${quoted(result.text)}$took" +
                        if (text == null && live.isNotBlank()) ". Kept the live words." else "",
                )
            }
            is DecodeJob.Whole -> {
                message.final = result.text?.takeIf { it.isNotBlank() } ?: message.transcript.joined()
                log("Message ${message.id} whole-message pass ${quoted(result.text)}$took")
            }
        }
        finishReady()
        show()
    }

    /** The mic is stopping (stop or mute): close what is being said and send it once decoded. */
    fun flush() {
        vad.flush()
        val closed = vad.closed()
        // Everything the first pass still holds, and a fresh start for the next time the mic opens.
        open.transcript.live(first.endPhrase())
        closed.forEach(::closePhrase)
        seal(firstEnded = true)
        vad.reset()
        if (hearing) {
            hearing = false
            emit(SpeechEvent.Hearing(false))
        }
        show()
    }

    private fun closePhrase(samples: FloatArray) {
        val message = open
        val live = message.transcript.tail
        val id = message.transcript.close()
        phrasesClosed++
        if (message.keepAudio) {
            val kept = message.audio.values.sumOf { it.size } + samples.size
            if (kept <= MAX_REDECODE_SECONDS * SAMPLE_RATE) message.audio[id] = samples
            else { message.keepAudio = false; message.audio.clear() }
        }
        log("Phrase ${message.id}.$id closed. ${secondsOf(samples.size)}. ${dbText(levelDb(samples))}. Live ${quoted(live)}")
        submit(DecodeJob.OnePhrase(message.id, id, samples))
        lastUnheard = null
        lastActivityMs = nowMs
    }

    /** The pause lasted: this message is complete; new speech starts the next one. */
    private fun seal(firstEnded: Boolean) {
        val message = open
        val tail = message.transcript.tail
        if (tail.isNotBlank()) {
            // Words the first pass heard that the detector never took for speech (very quiet speech). They
            // are kept as they are, and the first pass starts afresh: left in it, the same words would be
            // the next message's too, and the one after, every few seconds.
            val heard = (if (firstEnded) "" else first.endPhrase()).ifBlank { tail }
            val unheardOnly = message.transcript.size == 0
            if (unheardOnly && sameWords(heard, lastUnheard)) {
                message.transcript.live("")
                log("Dropped ${quoted(heard)}. Same words again with no speech between.")
                if (waiting.isEmpty()) {
                    lastShown = LiveWords("", "")
                    emit(SpeechEvent.Error(SpeechError.NoMatch))
                }
                return
            }
            message.transcript.close(live = heard, confirmed = heard)
            log("Message ${message.id} kept first-pass words with no speech detected. ${quoted(heard)}")
            lastUnheard = heard
        }
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
            log(if (text.isBlank()) "Message ${head.id} empty. Nothing sent." else "Message ${head.id} sent ${quoted(text)}")
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

/** The same words, ignoring case, punctuation and spacing. Pure. */
fun sameWords(a: String?, b: String?): Boolean {
    if (a == null || b == null) return false
    fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]"), " ").trim().replace(Regex("\\s+"), " ")
    val x = norm(a)
    return x.isNotEmpty() && x == norm(b)
}
