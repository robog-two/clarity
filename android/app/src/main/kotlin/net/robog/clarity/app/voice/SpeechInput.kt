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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Speech to text on the phone. Only final text ever leaves the device. */
interface SpeechInput {
    val events: Flow<SpeechEvent>
    val mic: StateFlow<MicState>

    /** Start listening; with [keepOn] it restarts after each final segment until [stop]. */
    fun start(keepOn: Boolean)
    fun stop()
    fun mute()
    fun unmute()
    fun release()
}

/**
 * `SpeechRecognizer.createOnDeviceSpeechRecognizer` when the device has an on-device model,
 * otherwise the default recogniser. All recogniser calls happen on the main thread.
 */
class AndroidSpeechInput(private val context: Context) : SpeechInput {
    private val main = Handler(Looper.getMainLooper())
    private val _events = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<SpeechEvent> = _events.asSharedFlow()
    private val _mic = MutableStateFlow(MicState())
    override val mic: StateFlow<MicState> = _mic.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    /**
     * The recogniser ends a segment at the first short pause. A segment is held for [PAUSE_GRACE_MS]
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
            val kind = speechErrorOf(error)
            // While holding a segment, "heard nothing" just means the pause went on: the timer sends it.
            if (joiner.holding && (kind == SpeechError.NoMatch || kind == SpeechError.Timeout)) {
                if (!main.hasCallbacks(graceOver)) finishUtterance()
                return
            }
            if (joiner.holding) finishUtterance()
            emit(SpeechEvent.Error(kind))
        }
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (_mic.value.mode != MicMode.Listening) return
            if (text.isBlank() && joiner.holding) {
                // Nothing new: listen on, and let the running pause timer decide.
                if (main.hasCallbacks(graceOver)) startRecognizer() else finishUtterance()
                return
            }
            joiner.segment(text)
            if (!joiner.holding) return emit(SpeechEvent.Final(text)) // nothing heard: as before
            _events.tryEmit(SpeechEvent.Partial(joiner.shown("")))
            main.removeCallbacks(graceOver)
            main.postDelayed(graceOver, PAUSE_GRACE_MS)
            startRecognizer() // keep listening through the pause
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
        // Without "mic stays on" the mic is now off, but the recogniser was listening through the pause.
        if (_mic.value.mode != MicMode.Listening) recognizer?.cancel()
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
        _events.tryEmit(event)
        // Recogniser callbacks arrive on the main thread already.
        dispatch(MicInput.Recognizer(event))
    }

    private fun dispatch(input: MicInput) {
        val (next, command) = micStep(_mic.value, input)
        _mic.value = next
        when (command) {
            MicCommand.None -> Unit
            MicCommand.StartRecognizer -> startRecognizer()
            MicCommand.StopRecognizer -> recognizer?.cancel()
        }
    }

    private fun startRecognizer() {
        val r = recognizer ?: create() ?: run {
            _events.tryEmit(SpeechEvent.Error(SpeechError.Unavailable))
            _mic.value = MicState()
            return
        }
        recognizer = r
        // A short pause keeps the recogniser from reporting "busy" when restarting right after a result.
        main.postDelayed({ if (_mic.value.mode == MicMode.Listening) r.startListening(intent()) }, RESTART_DELAY_MS)
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
        // Hints only (many recognisers ignore them); the pause grace above is what makes pauses safe.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, PAUSE_GRACE_MS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, PAUSE_GRACE_MS)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object {
        const val RESTART_DELAY_MS = 150L
        const val PAUSE_GRACE_MS = 3_000L
    }
}
