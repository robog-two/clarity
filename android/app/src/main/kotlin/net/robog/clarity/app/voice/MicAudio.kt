package net.robog.clarity.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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
        val effects = listOfNotNull(
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(record.audioSessionId) else null,
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId) else null,
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId) else null,
        ).onEach { it.enabled = true }
        return AndroidAudioStream(record, effects)
    }
}

private class AndroidAudioStream(private val record: AudioRecord, private val effects: List<AudioEffect>) : AudioStream {
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

/**
 * Reads [source] on its own thread (audio priority) and hands each chunk to [onChunk]; [onError] gets
 * the reason it stopped early. [stop] waits for the thread and closes the recorder.
 */
class MicReader(
    private val source: AudioSource,
    private val onChunk: (FloatArray) -> Unit,
    private val onError: (SpeechError) -> Unit,
) {
    @Volatile private var running = true
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
                if (running && chunk.isNotEmpty()) onChunk(chunk)
            }
        }
    }

    fun stop() {
        running = false
        if (thread.isAlive && Thread.currentThread() != thread) thread.join(1_000)
    }
}
