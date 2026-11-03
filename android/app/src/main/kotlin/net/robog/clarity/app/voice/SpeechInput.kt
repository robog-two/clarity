package net.robog.clarity.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Speech to text on the phone. Only final text ever leaves the device. */
interface SpeechInput {
    val events: Flow<SpeechEvent>
    val mic: StateFlow<MicState>

    /** Start listening; with [keepOn] it listens on after each final segment until [stop]. */
    fun start(keepOn: Boolean)
    fun stop()
    fun mute()
    fun unmute()
    fun release()
}

/**
 * The events and mic state screens see. Shared by the recognizers behind [SwitchingSpeechInput], so a
 * screen keeps one flow to collect whichever of them is listening.
 */
class SpeechBus {
    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 64)
    val events: Flow<SpeechEvent> = _events.asSharedFlow()
    private val _mic = MutableStateFlow(MicState())
    val mic: StateFlow<MicState> = _mic.asStateFlow()

    fun emit(event: SpeechEvent) { _events.tryEmit(event) }

    /** Runs [input] through [micStep], stores the new state and returns the command to carry out. */
    @Synchronized
    fun step(input: MicInput): MicCommand {
        val (next, command) = micStep(_mic.value, input)
        _mic.value = next
        return command
    }

    fun reset() { _mic.value = MicState() }
}

/**
 * The phone's own recognizer: `SpeechRecognizer.createOnDeviceSpeechRecognizer` when the device has an
 * on-device model, otherwise the default one. Only the fallback now, while the app's own models are
 * missing or cannot run (see [SwitchingSpeechInput]). All recognizer calls happen on the main thread.
 *
 * The system recognizer plays its start chime on every `startListening`; Android has no supported way
 * to turn that off (short of muting audio streams, which this app does not do), so the fallback keeps
 * restarts to the minimum: one per segment, never two at once.
 */
class AndroidSpeechInput(private val context: Context, private val bus: SpeechBus = SpeechBus()) : SpeechInput {
    private val main = Handler(Looper.getMainLooper())
    override val events: Flow<SpeechEvent> = bus.events
    override val mic: StateFlow<MicState> = bus.mic

    private var recognizer: SpeechRecognizer? = null

    /** `startListening` was called and neither results nor an error have come back yet. */
    private var active = false
    private val pendingStart = Runnable { begin() }

    /**
     * The recognizer ends a segment at the first short pause. A segment is held for [PAUSE_GRACE_MS]
     * while it listens on; more speech joins it, and only silence for that long sends it.
     */
    private val joiner = PauseJoiner()
    private val graceOver = Runnable { finishUtterance() }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() {
            main.removeCallbacks(graceOver) // still talking: wait for this segment too
            emit(SpeechEvent.Hearing(true))
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = emit(SpeechEvent.Hearing(false))
        override fun onError(error: Int) {
            active = false
            val kind = speechErrorOf(error)
            // While holding a segment, "heard nothing" just means the pause went on: listen on, the timer sends it.
            if (joiner.holding && (kind == SpeechError.NoMatch || kind == SpeechError.Timeout)) {
                if (main.hasCallbacks(graceOver)) startRecognizer() else finishUtterance()
                return
            }
            if (joiner.holding) finishUtterance()
            emit(SpeechEvent.Error(kind))
        }
        override fun onResults(results: Bundle?) {
            active = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (bus.mic.value.mode != MicMode.Listening) return
            if (text.isBlank() && joiner.holding) {
                // Nothing new: listen on, and let the running pause timer decide.
                if (main.hasCallbacks(graceOver)) startRecognizer() else finishUtterance()
                return
            }
            joiner.segment(text)
            if (!joiner.holding) return emit(SpeechEvent.Final(text)) // nothing heard: as before
            bus.emit(SpeechEvent.Partial(joiner.shown("")))
            main.removeCallbacks(graceOver)
            main.postDelayed(graceOver, PAUSE_GRACE_MS)
            // Keep listening through the pause. When the pause then ends the message, the state machine
            // asks for a start again; that one is a no-op because this one is already running.
            startRecognizer()
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                main.removeCallbacks(graceOver)
                emit(SpeechEvent.Partial(joiner.shown(text)))
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** The pause lasted: send everything held as one final segment. */
    private fun finishUtterance() {
        main.removeCallbacks(graceOver)
        val text = joiner.flush() ?: return
        emit(SpeechEvent.Final(text))
        // Without "mic stays on" the mic is now off, but the recognizer was listening through the pause.
        if (bus.mic.value.mode != MicMode.Listening) halt()
    }

    override fun start(keepOn: Boolean) = onMain { dispatch(MicInput.Start(keepOn)) }
    override fun stop() = onMain { finishUtterance(); dispatch(MicInput.Stop) }
    override fun mute() = onMain { finishUtterance(); dispatch(MicInput.Mute) }
    override fun unmute() = onMain { dispatch(MicInput.Unmute) }

    override fun release() = onMain {
        main.removeCallbacks(graceOver)
        joiner.flush()
        dispatch(MicInput.Stop)
        recognizer?.destroy()
        recognizer = null
    }

    private fun emit(event: SpeechEvent) {
        bus.emit(event)
        // Recognizer callbacks arrive on the main thread already.
        dispatch(MicInput.Recognizer(event))
    }

    private fun dispatch(input: MicInput) {
        when (bus.step(input)) {
            MicCommand.None -> Unit
            MicCommand.StartRecognizer -> startRecognizer()
            MicCommand.StopRecognizer -> halt()
            MicCommand.RetryRecognizer -> retry()
        }
    }

    /** Listens unless it already does (or is about to): a second `startListening` would fail as busy. */
    private fun startRecognizer() {
        if (active || main.hasCallbacks(pendingStart)) return
        // A short pause keeps the recognizer from reporting "busy" when restarting right after a result.
        main.postDelayed(pendingStart, RESTART_DELAY_MS)
    }

    private fun begin() {
        if (bus.mic.value.mode != MicMode.Listening || active) return
        val r = recognizer ?: create() ?: run {
            bus.emit(SpeechEvent.Error(SpeechError.Unavailable))
            bus.reset()
            return
        }
        recognizer = r
        active = true
        r.startListening(intent())
    }

    private fun halt() {
        main.removeCallbacks(pendingStart)
        if (active) recognizer?.cancel()
        active = false
    }

    /** Busy or client error: a fresh recognizer after a back-off. */
    private fun retry() {
        halt()
        recognizer?.destroy()
        recognizer = null
        main.postDelayed(pendingStart, retryDelayMs(bus.mic.value.retries))
    }

    private fun create(): SpeechRecognizer? {
        val r = when {
            // On-device recognition exists from Android 13; minSdk is 31 (Android 12).
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ->
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            SpeechRecognizer.isRecognitionAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context)
            else -> return null
        }
        r.setRecognitionListener(listener)
        return r
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Hints only (many recognizers ignore them); the pause grace above is what makes pauses safe.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, PAUSE_GRACE_MS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, PAUSE_GRACE_MS)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object {
        const val RESTART_DELAY_MS = 150L
    }
}
