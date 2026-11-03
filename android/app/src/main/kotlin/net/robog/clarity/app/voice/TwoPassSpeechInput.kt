package net.robog.clarity.app.voice

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The decode speed this phone measured, kept across launches (see [updatedSpeed]). */
interface SpeedStore {
    fun speed(tier: VoiceTier): Double?
    fun record(tier: VoiceTier, speed: Double)
}

class MemorySpeedStore : SpeedStore {
    private val speeds = java.util.concurrent.ConcurrentHashMap<VoiceTier, Double>()
    override fun speed(tier: VoiceTier): Double? = speeds[tier]
    override fun record(tier: VoiceTier, speed: Double) { speeds[tier] = speed }
}

/**
 * Two-pass speech to text on this phone with the app's own models: one `AudioRecord` stream for as
 * long as the mic is on, so there is no recogniser restart (and no start chime) between messages.
 *
 * Threads: the mic reads on "voice-mic"; the first pass, the detector and [TwoPassCore] run on
 * "voice-asr"; Parakeet runs on "voice-decode", so a long decode never holds up listening. Control
 * calls (start, stop, mute) hop onto "voice-asr", which serialises everything that touches the core.
 *
 * [onUnavailable] is called when the engines cannot be opened (no models, or the native library cannot
 * load on this phone); the caller then switches to the phone's own recogniser.
 */
class TwoPassSpeechInput(
    private val bus: SpeechBus,
    private val paths: () -> EnginePaths?,
    private val factory: SpeechEngineFactory,
    private val audio: AudioSource,
    private val hotwords: suspend () -> List<String> = { emptyList() },
    private val speeds: SpeedStore = MemorySpeedStore(),
    private val onUnavailable: (keepOn: Boolean, Throwable?) -> Unit = { _, _ -> },
    private val idleReleaseMs: Long = 60_000,
    asrDispatcher: CoroutineDispatcher? = null,
    decodeDispatcher: CoroutineDispatcher? = null,
) : SpeechInput {
    override val events: Flow<SpeechEvent> = bus.events
    override val mic: StateFlow<MicState> = bus.mic

    private val asr = asrDispatcher ?: Executors.newSingleThreadExecutor { Thread(it, "voice-asr").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val decoder = decodeDispatcher ?: Executors.newSingleThreadExecutor { Thread(it, "voice-decode").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + asr)

    // Owned by the asr thread.
    private var engines: EnginePaths? = null
    private var first: StreamingRecognizer? = null
    private var vad: VoiceActivity? = null
    private var core: TwoPassCore? = null
    private var reader: MicReader? = null
    private var loop: Job? = null
    private var release: Job? = null
    private var retrying: Job? = null
    private var hotwordArg = ""
    private val chunks = Channel<FloatArray>(capacity = 300) // 30 s of audio
    /** Commands the state machine asked for while the core was mid-call; run once it returns. */
    private val pending = ArrayDeque<MicCommand>()

    // Owned by the decode thread.
    private var second: PhraseRecognizer? = null
    private var secondFailed = false

    override fun start(keepOn: Boolean) = post { dispatch(MicInput.Start(keepOn)) }
    override fun stop() = post { dispatch(MicInput.Stop) }
    override fun mute() = post { dispatch(MicInput.Mute) }
    override fun unmute() = post { dispatch(MicInput.Unmute) }

    override fun release() = post {
        dispatch(MicInput.Stop)
        closeEngines()
    }

    private fun post(block: suspend () -> Unit) { scope.launch { block() } }

    private suspend fun dispatch(input: MicInput) {
        pending += bus.step(input)
        runPending()
    }

    /** From the core (asr thread): show the event, and queue what the state machine says to do about it. */
    private fun emit(event: SpeechEvent) {
        bus.emit(event)
        pending += bus.step(MicInput.Recognizer(event))
        // "Mic stays on" is off and a message just went out: the mic turns off after it.
        if (event is SpeechEvent.Final && bus.mic.value.mode != MicMode.Listening) pending += MicCommand.StopRecognizer
    }

    private suspend fun runPending() {
        while (pending.isNotEmpty()) {
            when (pending.removeFirst()) {
                MicCommand.None -> Unit
                MicCommand.StartRecognizer -> listen()
                MicCommand.StopRecognizer -> quiet()
                MicCommand.RetryRecognizer -> retry()
            }
        }
    }

    /** Starts the mic unless it is already on. Never a second recorder, never a restart between messages. */
    private suspend fun listen() {
        if (bus.mic.value.mode != MicMode.Listening || reader != null) return
        release?.cancel()
        val core = core ?: open() ?: return
        reader = MicReader(audio, onChunk = { chunks.trySend(it) }, onError = { kind -> post { failed(kind) } }).also { it.start() }
        if (loop?.isActive != true) {
            loop = scope.launch {
                for (chunk in chunks) {
                    core.audio(chunk)
                    runPending()
                }
            }
        }
    }

    /** Opens the first pass and the detector (Parakeet opens on its own thread at the first phrase). */
    private suspend fun open(): TwoPassCore? {
        val keepOn = bus.mic.value.keepOn
        val found = paths()
        val opened = try {
            if (found == null) error("speech models are not downloaded")
            hotwordArg = runCatching { hotwordsArg(hotwords()) }.getOrDefault("")
            val f = factory.firstPass(found)
            val v = try { factory.vad(found) } catch (t: Throwable) { f.close(); throw t }
            Triple(found, f, v)
        } catch (t: Throwable) { // LinkageError included: no native library for this phone
            bus.reset()
            onUnavailable(keepOn, t)
            return null
        }
        engines = opened.first
        first = opened.second
        vad = opened.third
        return TwoPassCore(opened.second, opened.third, ::submit, ::emit, { speeds.speed(opened.first.tier) }).also { core = it }
    }

    private fun submit(job: DecodeJob) {
        val paths = engines ?: return
        scope.launch(decoder) {
            val started = System.nanoTime()
            val text = runCatching { recognizer(paths)?.decode(job.samples, hotwordArg) }.getOrNull()
            val ms = (System.nanoTime() - started) / 1_000_000
            if (text != null) updatedSpeed(speeds.speed(paths.tier), job.seconds, ms)?.let { speeds.record(paths.tier, it) }
            withContext(asr) {
                core?.decoded(DecodeResult(job, text))
                runPending()
            }
        }
    }

    /** Parakeet, opened on first use (decode thread). If it cannot open, phrases keep their live words. */
    private fun recognizer(paths: EnginePaths): PhraseRecognizer? {
        if (secondFailed) return null
        return second ?: try {
            factory.secondPass(paths).also { second = it }
        } catch (_: Throwable) {
            secondFailed = true
            null
        }
    }

    /** Stop or mute: the mic goes off at once; what was being said is still decoded and sent. */
    private suspend fun quiet() {
        retrying?.cancel()
        val r = reader ?: return
        reader = null
        withContext(kotlinx.coroutines.Dispatchers.IO) { r.stop() }
        val core = core ?: return
        while (true) {
            val chunk = chunks.tryReceive().getOrNull() ?: break
            core.audio(chunk)
        }
        core.flush()
        runPending()
        scheduleRelease()
    }

    /** The recorder failed (busy mic, no permission): report it; the state machine decides what next. */
    private suspend fun failed(kind: SpeechError) {
        reader?.stop()
        reader = null
        bus.emit(SpeechEvent.Error(kind))
        pending += bus.step(MicInput.Recognizer(SpeechEvent.Error(kind)))
        runPending()
        if (bus.mic.value.mode == MicMode.Off) scheduleRelease()
    }

    private fun retry() {
        reader?.stop()
        reader = null
        retrying?.cancel()
        retrying = scope.launch {
            delay(retryDelayMs(bus.mic.value.retries))
            listen()
        }
    }

    /** Frees the models' memory (Parakeet 0.6B holds about 0.9 GB) once the mic has been off for a while. */
    private fun scheduleRelease() {
        release?.cancel()
        release = scope.launch {
            delay(idleReleaseMs)
            if (reader == null && core?.idle != false) closeEngines()
        }
    }

    private suspend fun closeEngines() {
        reader?.stop()
        reader = null
        loop?.cancel()
        loop = null
        core = null
        first?.close(); first = null
        vad?.close(); vad = null
        engines = null
        withContext(decoder) {
            second?.close()
            second = null
            secondFailed = false
        }
    }
}
