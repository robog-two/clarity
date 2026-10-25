package net.robog.clarity.app.voice

import android.content.Context
import android.content.Intent
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

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = emit(SpeechEvent.Hearing(true))
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = emit(SpeechEvent.Hearing(false))
        override fun onError(error: Int) = emit(SpeechEvent.Error(speechErrorOf(error)))
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            emit(SpeechEvent.Final(text))
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) emit(SpeechEvent.Partial(text))
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    override fun start(keepOn: Boolean) = onMain { dispatch(MicInput.Start(keepOn)) }
    override fun stop() = onMain { dispatch(MicInput.Stop) }
    override fun mute() = onMain { dispatch(MicInput.Mute) }
    override fun unmute() = onMain { dispatch(MicInput.Unmute) }

    override fun release() = onMain {
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
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
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
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object {
        const val RESTART_DELAY_MS = 150L
    }
}
