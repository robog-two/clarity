package net.robog.clarity.app.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.feed.UserInput
import net.robog.clarity.shared.session.SessionId

/** What the speech recognizer tells the app. Pure data so the logic around it is testable. */
sealed interface SpeechEvent {
    /**
     * Words so far, replaced by the next partial (the dashed "live" bubble). The first [confirmed]
     * characters of [text] are settled (the second pass has checked them); the rest may still change.
     */
    data class Partial(val text: String, val confirmed: Int = 0) : SpeechEvent
    /** A finished segment; becomes a [UserInput] with `source = VOICE`. */
    data class Final(val text: String) : SpeechEvent
    /** The recognizer started or stopped hearing speech. */
    data class Hearing(val active: Boolean) : SpeechEvent
    data class Error(val kind: SpeechError) : SpeechEvent
}

enum class SpeechError {
    /** Nothing recognized / nobody spoke: harmless, listening just restarts. */
    NoMatch,
    Timeout,
    /** `RECORD_AUDIO` not granted. */
    NoPermission,
    /** No recognizer on this device (or language not available). */
    Unavailable,
    /** The recognizer (or the microphone) is in use: worth a few retries. */
    Busy,
    /** The recognizer rejected a call (`ERROR_CLIENT`), e.g. a start while it was still stopping. */
    Client,
    Other,
}

/** Maps `SpeechRecognizer.ERROR_*` codes. */
fun speechErrorOf(code: Int): SpeechError = when (code) {
    7 -> SpeechError.NoMatch            // ERROR_NO_MATCH
    6 -> SpeechError.Timeout            // ERROR_SPEECH_TIMEOUT
    9 -> SpeechError.NoPermission       // ERROR_INSUFFICIENT_PERMISSIONS
    8, 10 -> SpeechError.Busy           // ERROR_RECOGNIZER_BUSY / ERROR_TOO_MANY_REQUESTS
    5 -> SpeechError.Client             // ERROR_CLIENT
    12, 13 -> SpeechError.Unavailable   // ERROR_LANGUAGE_NOT_SUPPORTED / UNAVAILABLE
    else -> SpeechError.Other
}

/** Whether the recognizer is on, and whether it restarts itself after each final segment. */
enum class MicMode { Off, Listening, Muted }

data class MicState(
    val mode: MicMode = MicMode.Off,
    /** "Mic stays on": keep listening until stopped. */
    val keepOn: Boolean = false,
    /** The recognizer currently hears speech. */
    val hearing: Boolean = false,
    /** Failed starts in a row (busy, client errors); reset by anything heard. */
    val retries: Int = 0,
)

sealed interface MicInput {
    data class Start(val keepOn: Boolean) : MicInput
    data object Stop : MicInput
    data object Mute : MicInput
    data object Unmute : MicInput
    data class Recognizer(val event: SpeechEvent) : MicInput
}

enum class MicCommand {
    None,
    /** Make sure the recognizer listens. Idempotent: when it already listens, nothing happens. */
    StartRecognizer,
    StopRecognizer,
    /** The recognizer failed: reset it and start again after a short back-off ([retryDelayMs]). */
    RetryRecognizer,
}

/** Failed starts in a row before the mic gives up and turns off. */
const val MAX_MIC_RETRIES = 3

/** Back-off before retry [attempt] (1-based): 300 ms, 600 ms, 1.2 s. */
fun retryDelayMs(attempt: Int): Long = 300L shl (attempt - 1).coerceIn(0, 4)

/** The pure state machine behind the microphone. */
fun micStep(state: MicState, input: MicInput): Pair<MicState, MicCommand> = when (input) {
    is MicInput.Start -> MicState(MicMode.Listening, input.keepOn) to MicCommand.StartRecognizer
    MicInput.Stop -> MicState() to MicCommand.StopRecognizer
    MicInput.Mute ->
        if (state.mode == MicMode.Listening) state.copy(mode = MicMode.Muted, hearing = false) to MicCommand.StopRecognizer
        else state to MicCommand.None
    MicInput.Unmute ->
        if (state.mode == MicMode.Muted) state.copy(mode = MicMode.Listening, retries = 0) to MicCommand.StartRecognizer
        else state to MicCommand.None
    is MicInput.Recognizer -> recognizerStep(state, input.event)
}

private fun recognizerStep(state: MicState, event: SpeechEvent): Pair<MicState, MicCommand> {
    if (state.mode != MicMode.Listening) return state to MicCommand.None
    return when (event) {
        is SpeechEvent.Partial -> state.copy(retries = 0) to MicCommand.None
        is SpeechEvent.Hearing -> state.copy(hearing = event.active, retries = if (event.active) 0 else state.retries) to MicCommand.None
        is SpeechEvent.Final ->
            if (state.keepOn) state.copy(hearing = false, retries = 0) to MicCommand.StartRecognizer
            else MicState() to MicCommand.None
        is SpeechEvent.Error -> when (event.kind) {
            SpeechError.NoMatch, SpeechError.Timeout ->
                if (state.keepOn) state.copy(hearing = false) to MicCommand.StartRecognizer
                else MicState() to MicCommand.None
            // Nothing to retry: no permission, or no recognizer at all.
            SpeechError.NoPermission, SpeechError.Unavailable -> MicState() to MicCommand.StopRecognizer
            // Busy, client errors and unknown failures: a few spaced retries, then off instead of looping.
            SpeechError.Busy, SpeechError.Client, SpeechError.Other ->
                if (state.retries < MAX_MIC_RETRIES) state.copy(hearing = false, retries = state.retries + 1) to MicCommand.RetryRecognizer
                else MicState() to MicCommand.StopRecognizer
        }
    }
}

/** What the mic chip shows. */
enum class MicChipState { Listening, MicOn, Muted, Off }

fun micChipState(state: MicState): MicChipState = when (state.mode) {
    MicMode.Off -> MicChipState.Off
    MicMode.Muted -> MicChipState.Muted
    MicMode.Listening -> if (state.hearing) MicChipState.Listening else MicChipState.MicOn
}

/** Final segments become voice input; blank segments are dropped. */
fun Flow<SpeechEvent>.toUserInputs(
    sessionId: () -> SessionId?,
    newId: () -> String,
): Flow<UserInput> = filterIsInstance<SpeechEvent.Final>()
    .filter { it.text.isNotBlank() }
    .map { UserInput(ClientInputId(newId()), it.text.trim(), InputSource.VOICE, sessionId()) }

/** Pure: two pieces of speech as one, separated by a space. */
fun joinSpeech(first: String, second: String): String =
    listOf(first.trim(), second.trim()).filter { it.isNotEmpty() }.joinToString(" ")

/**
 * Holds the segments the recognizer ends at each short pause, so a sentence, a pause to think and
 * the next sentence become one message. The caller runs the timer and calls [flush] when the pause lasts.
 */
class PauseJoiner {
    private var pending = ""

    /** A segment is held, waiting to see if more follows. */
    val holding: Boolean get() = pending.isNotEmpty()

    /** The recognizer ended a segment. Blank ones change nothing. */
    fun segment(text: String) {
        pending = joinSpeech(pending, text)
    }

    /** What to show while [partial] is being heard: the held text followed by it. */
    fun shown(partial: String): String = joinSpeech(pending, partial)

    /** Everything held, now cleared; null when nothing was held. */
    fun flush(): String? = pending.takeIf { it.isNotEmpty() }.also { pending = "" }
}

/** Silence that ends a message: shorter pauses join the phrases around them into one. */
const val PAUSE_GRACE_MS = 3_000L
