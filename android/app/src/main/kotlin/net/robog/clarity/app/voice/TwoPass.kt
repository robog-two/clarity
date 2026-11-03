package net.robog.clarity.app.voice

/*
 * The pure bookkeeping of two-pass recognition (no Android, no native code):
 *  - a fast streaming model gives live words as the person talks (the first pass);
 *  - the voice activity detector closes a phrase at each short pause, and a more accurate offline
 *    model (Parakeet) decodes that phrase in the background (the second pass);
 *  - its text replaces the phrase's live words, so most of a message is settled before it ends.
 */

/** What the live bubble shows: settled words first, then words that may still change. */
data class LiveWords(val confirmed: String, val pending: String) {
    val text: String get() = joinSpeech(confirmed, pending)

    /** How many leading characters of [text] are settled. */
    val confirmedChars: Int get() = confirmed.trim().length

    fun isEmpty(): Boolean = text.isEmpty()

    fun toEvent(): SpeechEvent.Partial = SpeechEvent.Partial(text, confirmedChars)
}

/** A phrase the detector closed. [confirmed] stays null until the second pass returns. */
data class Phrase(val id: Int, val live: String, val confirmed: String? = null) {
    /** The best text known: the second pass's when it is back. */
    val best: String get() = confirmed ?: live
}

/**
 * The phrases of one message, in order, and the live words of the phrase still being spoken.
 * Second-pass results may arrive in any order; only a run of settled phrases at the start shows as settled.
 */
class Transcript {
    private val phrases = mutableListOf<Phrase>()
    private var nextId = 0

    /** The first pass's words for the phrase being spoken now. */
    var tail: String = ""
        private set

    val size: Int get() = phrases.size
    val all: List<Phrase> get() = phrases.toList()

    /** Nothing said yet: no phrases and no live words. */
    fun isEmpty(): Boolean = phrases.isEmpty() && tail.isBlank()

    /** Every phrase is back from the second pass. */
    val settled: Boolean get() = phrases.all { it.confirmed != null }

    fun live(text: String) { tail = text.trim() }

    /** The detector closed a phrase whose live words are [live] (or the current [tail]); returns its id. */
    fun close(live: String = tail, confirmed: String? = null): Int {
        val id = nextId++
        phrases += Phrase(id, live.trim(), confirmed?.trim())
        tail = ""
        return id
    }

    /** The second pass's text for phrase [id]; unknown ids (a message already sent) are ignored. */
    fun confirm(id: Int, text: String): Boolean {
        val i = phrases.indexOfFirst { it.id == id }
        if (i < 0) return false
        phrases[i] = phrases[i].copy(confirmed = text.trim())
        return true
    }

    fun shown(): LiveWords {
        val lead = phrases.takeWhile { it.confirmed != null }
        val rest = phrases.drop(lead.size)
        return LiveWords(
            confirmed = lead.fold("") { acc, p -> joinSpeech(acc, p.best) },
            pending = joinSpeech(rest.fold("") { acc, p -> joinSpeech(acc, p.best) }, tail),
        )
    }

    /** The message as sent: every phrase's best text, joined. */
    fun joined(): String = phrases.fold("") { acc, p -> joinSpeech(acc, p.best) }
}

/** How a finished message gets its final text. */
enum class EndPass {
    /** Join the phrases' second-pass texts (already decoded during speech). */
    JoinPhrases,
    /** Decode the whole message once more, for context across phrases (fits the latency budget). */
    Redecode,
}

/** The longest message kept in memory for a whole-message pass (≈ 1.9 MB of 16 kHz float audio). */
const val MAX_REDECODE_SECONDS = 30.0

/** How long the whole-message pass may add at the end of a message. */
const val REDECODE_BUDGET_MS = 700L

/**
 * Whether to re-decode the whole message at the end. One phrase was already decoded whole; for more,
 * only when the measured decode speed says the pass fits [budgetMs]. Unknown speed: no.
 *
 * @param phraseSeconds the length of each phrase's audio
 * @param secondsPerSecond measured decode time per second of audio on this phone (real-time factor)
 */
fun endPass(
    phraseSeconds: List<Double>,
    secondsPerSecond: Double?,
    budgetMs: Long = REDECODE_BUDGET_MS,
    maxSeconds: Double = MAX_REDECODE_SECONDS,
): EndPass {
    if (phraseSeconds.size < 2 || secondsPerSecond == null || secondsPerSecond <= 0.0) return EndPass.JoinPhrases
    val total = phraseSeconds.sum() + PHRASE_GAP_SECONDS * (phraseSeconds.size - 1)
    if (total > maxSeconds) return EndPass.JoinPhrases
    val estimateMs = total * secondsPerSecond * 1000 + DECODE_OVERHEAD_MS
    return if (estimateMs <= budgetMs) EndPass.Redecode else EndPass.JoinPhrases
}

/** Silence put between phrases when they are decoded together. */
const val PHRASE_GAP_SECONDS = 0.25
private const val DECODE_OVERHEAD_MS = 40.0

/** Phrases joined for the whole-message pass, with [PHRASE_GAP_SECONDS] of silence between them. */
fun joinAudio(phrases: List<FloatArray>, sampleRate: Int = SAMPLE_RATE): FloatArray {
    val gap = (PHRASE_GAP_SECONDS * sampleRate).toInt()
    val out = FloatArray(phrases.sumOf { it.size } + gap * (phrases.size - 1).coerceAtLeast(0))
    var at = 0
    phrases.forEachIndexed { i, p ->
        if (i > 0) at += gap
        p.copyInto(out, at)
        at += p.size
    }
    return out
}

const val SAMPLE_RATE = 16_000

/**
 * The decode speed (seconds of work per second of audio), smoothed over real decodes so a single slow
 * one (the phone was busy) does not swing it. The first measurement is taken as is.
 */
fun updatedSpeed(previous: Double?, audioSeconds: Double, decodeMs: Long, weight: Double = 0.3): Double? {
    if (audioSeconds < 0.5) return previous // too short to say anything
    val measured = decodeMs / 1000.0 / audioSeconds
    return if (previous == null) measured else previous + weight * (measured - previous)
}

// --- context priming (hotwords) ---

/** Words worth biasing the second pass towards, at most this many. */
const val MAX_HOTWORDS = 64

/**
 * Names, courses and places from text the app already has on the phone (task titles): capitalised
 * words that are not the first of their title, not common words, three letters or more. Pure.
 */
fun hotwordsFrom(texts: List<String>, max: Int = MAX_HOTWORDS): List<String> {
    val seen = LinkedHashMap<String, String>()
    for (text in texts) {
        val words = text.split(Regex("[^\\p{L}'-]+")).filter { it.isNotEmpty() }
        words.drop(1).forEach { w ->
            val word = w.trim('\'', '-')
            if (word.length >= 3 && word[0].isUpperCase() && word.all { it in 'A'..'Z' || it in 'a'..'z' || it == '\'' || it == '-' } &&
                word.lowercase() !in COMMON_WORDS
            ) {
                seen.putIfAbsent(word.lowercase(), word)
            }
        }
        if (seen.size >= max) break
    }
    return seen.values.take(max)
}

private val COMMON_WORDS = setOf(
    "the", "and", "for", "with", "about", "from", "this", "that", "your", "you", "monday", "tuesday",
    "wednesday", "thursday", "friday", "saturday", "sunday", "today", "tomorrow", "email", "call", "reply",
    "draft", "review", "send", "check", "book", "meeting", "january", "february", "march", "april", "may",
    "june", "july", "august", "september", "october", "november", "december", "re", "fwd",
)

/**
 * The hotwords argument of sherpa-onnx's `createStream(hotwords)`: one phrase per entry, separated by
 * "/" (the library turns "/" into new lines). Characters it would read as syntax are dropped:
 * a leading ':' is a boost score and '/' separates entries.
 */
fun hotwordsArg(words: List<String>): String =
    words.map { w -> w.replace(Regex("[/:\\n\\r#@]"), " ").trim().replace(Regex("\\s+"), " ") }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString("/")

/**
 * sherpa-onnx encodes hotwords for NeMo models with a SentencePiece vocabulary ("piece\tscore" lines).
 * The model folders only ship `tokens.txt` ("piece id" lines, `<blk>` last). For these BPE models the
 * vocabulary is the token list in id order with score -(id - 1) (`<unk>` 0), without `<blk>`: the
 * same file the NeMo export writes as `bpe.vocab`. Pure.
 */
fun bpeVocabFromTokens(tokens: String): String = buildString {
    tokens.lineSequence().filter { it.isNotBlank() }.forEach { line ->
        val sep = line.lastIndexOf(' ')
        if (sep <= 0) return@forEach
        val piece = line.substring(0, sep)
        val id = line.substring(sep + 1).toIntOrNull() ?: return@forEach
        if (piece == "<blk>") return@forEach
        val score = if (id == 0) "0.0" else "-${id - 1}.0"
        append(piece).append('\t').append(score).append('\n')
    }
}
