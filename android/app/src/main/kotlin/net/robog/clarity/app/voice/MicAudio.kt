package net.robog.clarity.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import androidx.core.content.ContextCompat

/** The microphone as 16 kHz mono float chunks. Audio stays in memory: nothing here writes it anywhere. */
interface AudioSource {
    /** Throws [SecurityException] without the permission, [IllegalStateException] when the mic cannot open. */
    fun open(): AudioStream
}

interface AudioStream : AutoCloseable {
    /** Blocks for the next chunk (~100 ms); null when the recorder failed. */
    fun read(): FloatArray?

    /** How the mic was opened (source, where the phone routed it, which effects run), for the voice log. */
    val info: String get() = ""
}

class AndroidAudioSource(private val context: Context) : AudioSource {
    @SuppressLint("MissingPermission") // checked just below; a refusal is reported as NoPermission
    override fun open(): AudioStream {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO not granted")
        }
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // Two seconds of room, so a slow moment on the decoding side never drops audio.
        val record = AudioRecord(
            // The system's voice mode: automatic gain, noise suppression and echo cancellation. VOICE_RECOGNITION
            // leaves them off on many phones, which made quiet speech too faint for the detector.
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, SAMPLE_RATE * 2 * 2),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("the microphone is in use")
        }
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            throw IllegalStateException("the microphone did not start")
        }
        // Anything that goes wrong after the recorder started must not leave it running.
        val effects = mutableListOf<AudioEffect>()
        try {
            // Optional extras: one that fails costs only itself, never the recording.
            fun enable(name: String, available: Boolean, create: () -> AudioEffect?): String {
                val effect = if (available) runCatching { create()?.also { it.enabled = true } }.getOrNull() else null
                effect?.let(effects::add)
                return "$name " + if (effect != null) "on" else if (available) "failed" else "not offered"
            }
            val session = record.audioSessionId
            val states = listOf(
                enable("gain", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(session) },
                enable("noise suppression", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(session) },
                enable("echo cancellation", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(session) },
            ).joinToString(", ")
            return AndroidAudioStream(record, effects, states)
        } catch (t: Throwable) {
            effects.forEach { runCatching { it.release() } }
            runCatching { record.stop() }
            record.release()
            throw IllegalStateException("the microphone could not be set up", t)
        }
    }
}

private class AndroidAudioStream(
    private val record: AudioRecord,
    private val effects: List<AudioEffect>,
    private val effectStates: String,
) : AudioStream {
    // Asked after the first audio has come in: the route is settled by then.
    override val info: String
        get() = "voice-call mode, ${record.routedDevice?.let(::deviceName) ?: "input unknown"}, ${record.sampleRate} Hz; $effectStates"

    private val shorts = ShortArray(SAMPLE_RATE / 10)

    override fun read(): FloatArray? {
        val n = record.read(shorts, 0, shorts.size)
        if (n < 0) return null
        return FloatArray(n) { shorts[it] / 32768f }
    }

    override fun close() {
        runCatching { record.stop() }
        effects.forEach { runCatching { it.release() } }
        record.release()
    }
}

private fun deviceName(device: AudioDeviceInfo): String {
    val kind = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in mic"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth call audio"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        else -> "input type ${device.type}"
    }
    val product = device.productName?.toString()?.takeIf { it.isNotBlank() }
    return if (product != null && device.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) "$kind ($product)" else kind
}

/**
 * Reads [source] on its own thread (audio priority) and hands each chunk to [onChunk]; [onError] gets
 * the reason it stopped early. [stop] waits for the thread and closes the recorder.
 */
class MicReader(
    private val source: AudioSource,
    private val onChunk: (FloatArray) -> Unit,
    private val onError: (SpeechError) -> Unit,
    /** Gets [AudioStream.info] once, when the first audio has arrived. */
    private val onOpened: (String) -> Unit = {},
) {
    @Volatile private var running = true
    private var announced = false
    private val thread = Thread({ run() }, "voice-mic").apply { isDaemon = true }

    fun start() = thread.start()

    private fun run() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val stream = try {
            source.open()
        } catch (_: SecurityException) {
            return onError(SpeechError.NoPermission)
        } catch (_: Exception) {
            return onError(SpeechError.Busy)
        }
        stream.use {
            while (running) {
                val chunk = it.read() ?: run {
                    if (running) onError(SpeechError.Busy)
                    return
                }
                if (running && chunk.isNotEmpty()) {
                    if (!announced) { announced = true; onOpened(it.info) }
                    onChunk(chunk)
                }
            }
        }
    }

    fun stop() {
        running = false
        if (thread.isAlive && Thread.currentThread() != thread) thread.join(1_000)
    }
}
