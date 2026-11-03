package net.robog.clarity.app.voice

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/*
 * The sherpa-onnx (v1.13.8, Apache-2.0) engines behind the interfaces in TwoPassCore.kt. This is the
 * only file that touches the library: its classes load libsherpa-onnx-jni.so on first use, which
 * throws (UnsatisfiedLinkError) on a phone without arm64; callers catch that and use the fallback.
 *
 * The library ends the process on some malformed inputs instead of throwing, so it is only ever given
 * files whose SHA-256 was checked (ModelDownloader) and hotwords cleaned by [hotwordsArg].
 */

/** Where the verified model files are. */
data class EnginePaths(val base: File, val secondPass: File, val tier: VoiceTier, val bpeVocab: File?)

/** NVIDIA streaming FastConformer transducer (the first pass). */
class SherpaStreaming(base: File, threads: Int = 1) : StreamingRecognizer {
    private val recognizer = OnlineRecognizer(
        config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(base, "encoder.int8.onnx").path,
                    decoder = File(base, "decoder.int8.onnx").path,
                    joiner = File(base, "joiner.int8.onnx").path,
                ),
                tokens = File(base, "tokens.txt").path,
                numThreads = threads,
                provider = "cpu",
                // Empty: the library reads the model's own metadata and picks its NeMo transducer path.
                modelType = "",
            ),
            // Phrases are cut by the voice activity detector instead.
            enableEndpoint = false,
            decodingMethod = "greedy_search",
        ),
    )
    private var stream = freshStream()

    /**
     * A new stream, primed with a moment of silence: a stream whose first audio is already speech drops
     * its first word ("well i don't" came out as "i don't").
     */
    private fun freshStream(): OnlineStream = recognizer.createStream().also { it.acceptWaveform(FloatArray(PAD), SAMPLE_RATE) }

    override fun accept(samples: FloatArray): String {
        if (samples.isNotEmpty()) stream.acceptWaveform(samples, SAMPLE_RATE)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        return recognizer.getResult(stream).text.trim()
    }

    /**
     * Not `recognizer.reset(stream)`: that keeps the audio the model has not decoded yet, so the end of
     * the closed phrase came out afterwards as words of the next message. Instead the stream is padded
     * so its last words decode, finished, and replaced.
     */
    override fun endPhrase(): String {
        stream.acceptWaveform(FloatArray(PAD), SAMPLE_RATE)
        stream.inputFinished()
        val words = accept(FloatArray(0))
        stream.release()
        stream = freshStream()
        return words
    }

    private companion object {
        /** 0.5 s of silence: enough for the 480 ms look-ahead (measured with the real model). */
        const val PAD = SAMPLE_RATE / 2
    }

    override fun close() {
        stream.release()
        recognizer.release()
    }
}

/** Silero VAD. A pause of [minSilenceSeconds] closes a phrase; a phrase is cut at [maxSpeechSeconds]. */
class SherpaVad(
    base: File,
    minSilenceSeconds: Float = 0.5f,
    maxSpeechSeconds: Float = 15f,
) : VoiceActivity {
    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = File(base, "silero_vad.onnx").path,
                threshold = 0.5f,
                minSilenceDuration = minSilenceSeconds,
                minSpeechDuration = 0.25f,
                windowSize = WINDOW,
                maxSpeechDuration = maxSpeechSeconds,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
        ),
    )
    private val preRoll = PreRoll(keepSeconds = maxSpeechSeconds + minSilenceSeconds + 1.0)

    // The library keeps leftover samples between calls itself, so any chunk size works.
    override fun accept(samples: FloatArray) {
        preRoll.add(samples)
        vad.acceptWaveform(samples)
    }

    override val speaking: Boolean get() = vad.isSpeechDetected()

    override fun closed(): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        while (!vad.empty()) {
            val segment = vad.front()
            out += preRoll.extend(segment.start.toLong(), segment.samples)
            vad.pop()
        }
        return out
    }

    override fun flush() = vad.flush()

    override fun reset() {
        vad.reset()
        preRoll.reset()
    }

    override fun close() = vad.release()

    private companion object { const val WINDOW = 512 }
}

/**
 * NVIDIA Parakeet TDT (the second pass). With [bpeVocab] (written next to the model at download) the
 * decoder uses modified beam search, which is what lets hotwords bias it; without, greedy search.
 */
class SherpaParakeet(dir: File, bpeVocab: File?, threads: Int) : PhraseRecognizer {
    private val biasing = bpeVocab != null && bpeVocab.isFile
    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80), // the model's own dims win
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").path,
                    decoder = File(dir, "decoder.int8.onnx").path,
                    joiner = File(dir, "joiner.int8.onnx").path,
                ),
                tokens = File(dir, "tokens.txt").path,
                numThreads = threads,
                provider = "cpu",
                modelType = "nemo_transducer",
                modelingUnit = if (biasing) "bpe" else "",
                bpeVocab = if (biasing) bpeVocab!!.path else "",
            ),
            decodingMethod = if (biasing) "modified_beam_search" else "greedy_search",
            maxActivePaths = 4,
            hotwordsScore = 1.5f,
        ),
    )

    override fun decode(samples: FloatArray, hotwords: String): String {
        // Hotwords need the BPE vocabulary: without it the library would have nothing to encode them with.
        val stream = if (biasing && hotwords.isNotEmpty()) recognizer.createStream(hotwords) else recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}

/** Opens the engines for a set of verified files. Throws (including [LinkageError]) when they cannot run. */
interface SpeechEngineFactory {
    fun firstPass(paths: EnginePaths): StreamingRecognizer
    fun vad(paths: EnginePaths): VoiceActivity
    fun secondPass(paths: EnginePaths): PhraseRecognizer
}

class SherpaEngineFactory(private val cores: Int = Runtime.getRuntime().availableProcessors()) : SpeechEngineFactory {
    override fun firstPass(paths: EnginePaths): StreamingRecognizer = SherpaStreaming(paths.base, threads = 1)
    override fun vad(paths: EnginePaths): VoiceActivity = SherpaVad(paths.base)
    // Half the cores, two to four: the first pass and the rest of the phone keep the others.
    override fun secondPass(paths: EnginePaths): PhraseRecognizer =
        SherpaParakeet(paths.secondPass, paths.bpeVocab, threads = (cores / 2).coerceIn(2, 4))
}
