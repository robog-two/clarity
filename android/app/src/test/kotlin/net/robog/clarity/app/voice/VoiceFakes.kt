package net.robog.clarity.app.voice

/*
 * Fake engines for the two-pass tests: no native code. Audio is 100 ms chunks whose sample value says
 * what was said: 0 is silence, k ≥ 1 is the word "w<k>". The first pass hears "w<k>" (lower case);
 * the second pass "decodes" the same words capitalised ("W<k>"), so tests can see which pass a
 * word came from.
 */

const val CHUNK = SAMPLE_RATE / 10

fun speech(word: Int, chunks: Int = 3): List<FloatArray> = List(chunks) { FloatArray(CHUNK) { word.toFloat() } }
fun silence(ms: Long): List<FloatArray> = List((ms / 100).toInt()) { FloatArray(CHUNK) }

private fun wordsIn(samples: FloatArray): List<Int> {
    val out = mutableListOf<Int>()
    var i = 0
    while (i < samples.size) {
        val w = samples[i].toInt()
        if (w > 0 && out.lastOrNull() != w) out += w
        i += CHUNK / 4
    }
    return out
}

class FakeFirstPass : StreamingRecognizer {
    private val heard = mutableListOf<Int>()
    var resets = 0
    override fun accept(samples: FloatArray): String {
        if (samples.isNotEmpty()) {
            val w = samples[0].toInt()
            if (w > 0 && heard.lastOrNull() != w) heard += w
        }
        return heard.joinToString(" ") { "w$it" }
    }
    override fun reset() { heard.clear(); resets++ }
    override fun close() = Unit
}

/** Closes a phrase after [silenceChunks] silent chunks, like Silero with min_silence_duration. */
class FakeVad(private val silenceChunks: Int = 5) : VoiceActivity {
    private var current = mutableListOf<FloatArray>()
    private var quiet = 0
    private val done = mutableListOf<FloatArray>()
    override var speaking = false
        private set

    override fun accept(samples: FloatArray) {
        if (samples.isEmpty()) return
        val voiced = samples[0] > 0f
        if (voiced) {
            speaking = true
            quiet = 0
            current += samples
        } else if (speaking) {
            quiet++
            if (quiet >= silenceChunks) endPhrase()
        }
    }

    private fun endPhrase() {
        if (current.isNotEmpty()) done += current.reduce { a, b -> a + b }
        current = mutableListOf()
        speaking = false
        quiet = 0
    }

    override fun closed(): List<FloatArray> = done.toList().also { done.clear() }
    override fun flush() { if (speaking) endPhrase() }
    override fun reset() { current = mutableListOf(); speaking = false; quiet = 0 }
    override fun close() = Unit
}

class FakeSecondPass : PhraseRecognizer {
    val decoded = mutableListOf<Int>() // phrase lengths in samples
    override fun decode(samples: FloatArray, hotwords: String): String {
        decoded += samples.size
        return wordsIn(samples).joinToString(" ") { "W$it" }
    }
    override fun close() = Unit
}
