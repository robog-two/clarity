package net.robog.clarity.app.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One line of the voice log; [atMs] is wall-clock time. */
data class VoiceLogEntry(val atMs: Long, val text: String)

/**
 * What on-phone recognition did lately (engines opened, each phrase and what both passes made of it,
 * each message sent), for Settings → Voice, so a problem can be read off the phone without a cable.
 *
 * Memory only: the last [capacity] lines, gone when the app closes. It holds words that were said, so
 * it is never written to disk, logcat or the network. Thread-safe.
 */
class VoiceLog(private val capacity: Int = 40, private val clock: () -> Long = System::currentTimeMillis) {
    private val _entries = MutableStateFlow<List<VoiceLogEntry>>(emptyList())
    val entries: StateFlow<List<VoiceLogEntry>> = _entries.asStateFlow()

    fun add(text: String) {
        val entry = VoiceLogEntry(clock(), text)
        _entries.update { (it + entry).takeLast(capacity) }
    }

    fun clear() { _entries.value = emptyList() }
}

/** The most of a pass's words the log quotes: a pass that ran away on noise would fill it with one word. */
const val MAX_QUOTED = 160

/** "“words”", or "nothing" for empty text: how the log quotes what a pass heard. Pure. */
fun quoted(text: String?): String = when {
    text == null -> "failed"
    text.isBlank() -> "nothing"
    text.trim().length > MAX_QUOTED -> "“${text.trim().take(MAX_QUOTED).trimEnd()}…”"
    else -> "“${text.trim()}”"
}

/** "1.4 s" from samples at 16 kHz. Pure. */
fun secondsOf(samples: Int): String = "%.1f s".format(java.util.Locale.ROOT, samples.toDouble() / SAMPLE_RATE)

/** "minus 36 dB": a level for the log, with the sign in words so a screen reader does not say "dash". Pure. */
fun dbText(db: Int): String = if (db < 0) "minus ${-db} dB" else "$db dB"
