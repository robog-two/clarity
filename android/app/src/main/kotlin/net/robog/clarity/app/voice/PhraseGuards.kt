package net.robog.clarity.app.voice

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * What keeps near-silence and garbage out of the two-pass pipeline. All pure (no Android, no native code).
 * The numbers were measured with the real Silero VAD and the real Parakeet models (see the commit message).
 *
 *  - [DetectorFront]: what the detector and the live words get to hear. Blocks under a floor become exact
 *    silence (Silero has no level floor of its own: a few ±1 LSB samples of a muted mic read as speech and
 *    were held for seconds), and faint speech is lifted so the detector can hear it.
 *  - [normalizeForDecode]: a faint phrase is made louder before the second pass.
 *  - [looksLikeLoop], [collapseRuns]: a second pass that repeats itself is not trusted.
 *  - [isFillerOnly], [wordCount]: messages that are only "um" or one word the live pass never heard.
 */

/** A phrase this quiet (dBFS, as [levelDb]) is silence to the speech engines: it is dropped. */
const val SILENCE_DB = -75

/** A phrase this quiet that the live pass heard nothing in is dropped too. */
const val QUIET_WITHOUT_LIVE_DB = -62

/** Blocks quieter than this (RMS, dBFS, 20 ms) are replaced by silence before the detector. */
const val GATE_FLOOR_DB = -80.0

/** The loudness a faint phrase is lifted to for the second pass, and the most it is ever lifted. */
const val DECODE_TARGET_DB = -30.0
const val DECODE_MAX_GAIN_DB = 30.0

/**
 * Gate and lift for the audio the detector and the live pass hear. Same length out as in, any chunk size.
 *
 * Gate: a 20 ms block whose RMS is under [floorDb] (and the [holdBlocks] after the last louder block, so
 * the gaps between words are not chopped) is set to exact zeros.
 * Lift: the loudest recent block is tracked (it falls by [decayDbPerBlock] a block); the gain brings it
 * up to [targetDb], by at most [maxLiftDb], and moves slowly up and quickly down. Speech that is already
 * loud is not touched. Silence is never lifted: a gated block stays zero.
 */
class DetectorFront(
    private val floorDb: Double = GATE_FLOOR_DB,
    private val holdBlocks: Int = 15,
    private val lift: Boolean = true,
    private val targetDb: Double = -16.0,
    private val maxLiftDb: Double = 20.0,
    private val decayDbPerBlock: Double = 0.04,
    private val minPeakDb: Double = -72.0,
) {
    private var fill = 0
    private var sumSquares = 0.0
    private var hold = 0
    private var open = false
    private var peakDb = -120.0
    private var gainDb = 0.0

    /** The gain in effect now, in dB (for tests and the voice log). */
    val liftDb: Double get() = gainDb

    fun process(samples: FloatArray): FloatArray {
        val out = FloatArray(samples.size)
        var i = 0
        while (i < samples.size) {
            val n = min(BLOCK - fill, samples.size - i)
            for (k in i until i + n) sumSquares += samples[k].toDouble() * samples[k]
            val db = dbOfMeanSquare(sumSquares / (fill + n))
            // A block still under way is decided on what it has so far; it is committed once complete.
            val passes = open || db >= floorDb
            if (passes) {
                val g = 10.0.pow(gainDb / 20).toFloat()
                for (k in i until i + n) out[k] = if (g == 1f) samples[k] else (samples[k] * g).coerceIn(-1f, 1f)
            }
            fill += n
            i += n
            if (fill == BLOCK) {
                commit(db)
                fill = 0
                sumSquares = 0.0
            }
        }
        return out
    }

    private fun commit(db: Double) {
        if (db >= floorDb) { hold = holdBlocks; open = true }
        else if (hold > 0) { hold--; open = true }
        else open = false
        peakDb = maxOf(peakDb - decayDbPerBlock, if (db >= minPeakDb) db else -120.0)
        val want = if (lift && peakDb > -120.0) (targetDb - peakDb).coerceIn(0.0, maxLiftDb) else 0.0
        gainDb += if (want > gainDb) min(UP_DB, want - gainDb) else maxOf(-DOWN_DB, want - gainDb)
    }

    fun reset() {
        fill = 0; sumSquares = 0.0; hold = 0; open = false; peakDb = -120.0; gainDb = 0.0
    }

    private companion object {
        const val BLOCK = SAMPLE_RATE / 50 // 20 ms
        const val UP_DB = 0.5
        const val DOWN_DB = 2.0
    }
}

private fun dbOfMeanSquare(ms: Double): Double = if (ms <= 1e-12) -120.0 else 10 * log10(ms)

/**
 * A faint phrase made louder for the second pass: its RMS is brought to [DECODE_TARGET_DB] by at most
 * [DECODE_MAX_GAIN_DB], never past full scale. Phrases already at or above the target, and silence, come
 * back unchanged (the same array). Pure.
 */
fun normalizeForDecode(samples: FloatArray): FloatArray {
    if (samples.isEmpty()) return samples
    var sum = 0.0
    var peak = 0f
    for (s in samples) { sum += s.toDouble() * s; if (abs(s) > peak) peak = abs(s) }
    val rms = sqrt(sum / samples.size)
    if (rms <= 1e-6 || peak <= 0f) return samples
    val level = 20 * log10(rms)
    var gain = 10.0.pow(min(DECODE_MAX_GAIN_DB, DECODE_TARGET_DB - level) / 20)
    if (gain <= 1.0) return samples
    gain = min(gain, 0.97 / peak)
    if (gain <= 1.0) return samples
    val g = gain.toFloat()
    return FloatArray(samples.size) { samples[it] * g }
}

/** Why a closed phrase is not worth a second pass, or null when it is. [liveWords] are the first pass's words for it. */
fun silentPhrase(levelDb: Int, liveWords: String): String? = when {
    levelDb < SILENCE_DB -> "silence"
    liveWords.isBlank() && levelDb < QUIET_WITHOUT_LIVE_DB -> "quiet and no live words"
    else -> null
}

// --- repeating itself ---

private fun wordKey(word: String): String = word.lowercase().filter { it.isLetterOrDigit() || it == '\'' }

private fun keys(text: String): List<String> = text.split(Regex("\\s+")).map(::wordKey).filter { it.isNotEmpty() }

/** How many times the pattern of [len] words at [at] repeats back to back (1 = just once). */
private fun repeats(words: List<String>, at: Int, len: Int): Int {
    if (at + len > words.size) return 0
    var n = 1
    while (at + (n + 1) * len <= words.size && (0 until len).all { words[at + n * len + it] == words[at + it] }) n++
    return n
}

/** Runs of this many repeats are a loop; the pattern is up to [MAX_PATTERN] words. */
const val LOOP_REPEATS = 3
private const val MAX_PATTERN = 4

/** A word, or a pattern of up to four words, repeated three or more times in a row. */
fun hasLoop(text: String): Boolean {
    val words = keys(text)
    for (at in words.indices) for (len in 1..MAX_PATTERN) if (repeats(words, at, len) >= LOOP_REPEATS) return true
    return false
}

/**
 * The second pass's [text] cannot be trusted when it loops ("Cloud Cloud Cloud ...") and the first pass's
 * [live] words do not: the beam search ran away on noise. A person who really says "no no no" is heard so by
 * both passes. Pure.
 */
fun looksLikeLoop(text: String, live: String): Boolean = hasLoop(text) && !hasLoop(live)

/**
 * Long runs squeezed to one: a word, or a pattern of up to four words, repeated four or more times in a row
 * is kept once (three, as in "no no no", is a person speaking). Pure.
 */
fun collapseRuns(text: String): String {
    val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val words = tokens.map(::wordKey)
    val out = mutableListOf<String>()
    var i = 0
    while (i < tokens.size) {
        var bestLen = 0
        var bestRepeats = 0
        for (len in 1..MAX_PATTERN) {
            val r = if (words[i].isEmpty()) 0 else repeats(words, i, len)
            if (r >= LOOP_REPEATS + 1 && r * len > bestRepeats * bestLen) { bestLen = len; bestRepeats = r }
        }
        if (bestLen == 0) { out += tokens[i]; i++ } else {
            for (k in 0 until bestLen) out += tokens[i + k]
            i += bestLen * bestRepeats
        }
    }
    return out.joinToString(" ")
}

// --- fillers and lone words ---

private val FILLERS = setOf(
    "um", "umm", "uhm", "uh", "uhh", "er", "err", "erm", "ah", "ahh", "eh", "hm", "hmm", "hmmm", "mm", "mmm", "mhm", "mmhmm", "mmhm", "mm-hmm", "mm-hm",
)

/** Only fillers (um, uh, mm, mm-hmm, hmm, ah, er and their spellings): not a message. Blank text is not "only fillers". */
fun isFillerOnly(text: String): Boolean {
    val words = text.lowercase().split(Regex("[\\s,.;!?…]+")).map { it.trim('-', '\'', '"') }.filter { it.isNotEmpty() }
    return words.isNotEmpty() && words.all { it in FILLERS }
}

fun wordCount(text: String): Int = keys(text).size
