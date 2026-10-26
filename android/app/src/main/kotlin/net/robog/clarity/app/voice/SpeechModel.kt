package net.robog.clarity.app.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.feed.UserInput
import net.robog.clarity.shared.session.SessionId

/** What the speech recogniser tells the app. Pure data so the logic around it is testable. */
sealed interface SpeechEvent {
    /** Words so far, replaced by the next partial (the dashed "live" bubble). */
    data class Partial(val text: String) : SpeechEvent
    /** A finished segment; becomes a [UserInput] with `source = VOICE`. */
    data class Final(val text: String) : SpeechEvent
    /** The recogniser started or stopped hearing speech. */
    data class Hearing(val active: Boolean) : SpeechEvent
    data class Error(val kind: SpeechError) : SpeechEvent
}

enum class SpeechError {
    /** Nothing recognised / nobody spoke: harmless, listening just restarts. */
    NoMatch,
    Timeout,
    /** `RECORD_AUDIO` not granted. */
    NoPermission,
    /** No recogniser on this device (or language not available). */
    Unavailable,
    Busy,
    Other,
}

/** Maps `SpeechRecognizer.ERROR_*` codes. */
fun speechErrorOf(code: Int): SpeechError = when (code) {
    7 -> SpeechError.NoMatch            // ERROR_NO_MATCH
    6 -> SpeechError.Timeout            // ERROR_SPEECH_TIMEOUT
    9 -> SpeechError.NoPermission       // ERROR_INSUFFICIENT_PERMISSIONS
    8 -> SpeechError.Busy               // ERROR_RECOGNIZER_BUSY
    12, 13 -> SpeechError.Unavailable   // ERROR_LANGUAGE_NOT_SUPPORTED / UNAVAILABLE
    else -> SpeechError.Other
}

/** Whether the recogniser is on, and whether it restarts itself after each final segment. */
enum class MicMode { Off, Listening, Muted }

data class MicState(
    val mode: MicMode = MicMode.Off,
    /** "Mic stays on": keep listening until stopped. */
    val keepOn: Boolean = false,
    /** The recogniser currently hears speech. */
    val hearing: Boolean = false,
)

sealed interface MicInput {
    data class Start(val keepOn: Boolean) : MicInput
    data object Stop : MicInput
    data object Mute : MicInput
    data object Unmute : MicInput
    data class Recognizer(val event: SpeechEvent) : MicInput
}

enum class MicCommand { None, StartRecognizer, StopRecognizer }

/** The pure state machine behind the microphone. */
fun micStep(state: MicState, input: MicInput): Pair<MicState, MicCommand> = when (input) {
    is MicInput.Start -> MicState(MicMode.Listening, input.keepOn) to MicCommand.StartRecognizer
    MicInput.Stop -> MicState() to MicCommand.StopRecognizer
    MicInput.Mute ->
        if (state.mode == MicMode.Listening) state.copy(mode = MicMode.Muted, hearing = false) to MicCommand.StopRecognizer
        else state to MicCommand.None
    MicInput.Unmute ->
        if (state.mode == MicMode.Muted) state.copy(mode = MicMode.Listening) to MicCommand.StartRecognizer
        else state to MicCommand.None
    is MicInput.Recognizer -> recognizerStep(state, input.event)
}

private fun recognizerStep(state: MicState, event: SpeechEvent): Pair<MicState, MicCommand> {
    if (state.mode != MicMode.Listening) return state to MicCommand.None
    return when (event) {
        is SpeechEvent.Partial -> state to MicCommand.None
        is SpeechEvent.Hearing -> state.copy(hearing = event.active) to MicCommand.None
        is SpeechEvent.Final ->
            if (state.keepOn) state.copy(hearing = false) to MicCommand.StartRecognizer
            else MicState() to MicCommand.None
        is SpeechEvent.Error -> when (event.kind) {
            SpeechError.NoMatch, SpeechError.Timeout ->
                if (state.keepOn) state.copy(hearing = false) to MicCommand.StartRecognizer
                else MicState() to MicCommand.None
            // Busy, missing permission, no recogniser or unknown failures: stop instead of looping.
            else -> MicState() to MicCommand.StopRecognizer
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
