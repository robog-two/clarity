package net.robog.clarity.app.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Turns [SpeechInput] events into what screens need: the words heard so far (the dashed "live"
 * bubble) and each finished segment, handed to [onFinal]. One per screen that listens.
 */
class VoiceCapture(
    private val speech: SpeechInput,
    private val onFinal: suspend (String) -> Unit,
) {
    private val _live = MutableStateFlow<String?>(null)
    /** Words so far in the current segment, or null when nothing is being said. */
    val live: StateFlow<String?> = _live.asStateFlow()

    private val _error = MutableStateFlow<SpeechError?>(null)
    /** The last problem worth telling the person about (permission, no recogniser). */
    val error: StateFlow<SpeechError?> = _error.asStateFlow()

    val mic: StateFlow<MicState> get() = speech.mic

    /** Starts collecting speech events in [scope]; cancel the returned job (or the scope) to stop. */
    fun attach(scope: CoroutineScope): Job = scope.launch {
        speech.events.collect { event ->
            when (event) {
                is SpeechEvent.Partial -> { _live.value = event.text; _error.value = null }
                is SpeechEvent.Final -> {
                    _live.value = null
                    if (event.text.isNotBlank()) onFinal(event.text.trim())
                }
                is SpeechEvent.Error -> {
                    _live.value = null
                    _error.value = event.kind.takeIf { it in REPORTED }
                }
                is SpeechEvent.Hearing -> Unit
            }
        }
    }

    fun start(keepOn: Boolean) { _error.value = null; speech.start(keepOn) }
    fun stop() { speech.stop(); _live.value = null }
    fun mute() = speech.mute()
    fun unmute() = speech.unmute()

    private companion object {
        val REPORTED = setOf(SpeechError.NoPermission, SpeechError.Unavailable, SpeechError.Other)
    }
}
