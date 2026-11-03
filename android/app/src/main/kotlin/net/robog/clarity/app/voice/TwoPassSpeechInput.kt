package net.robog.clarity.app.voice

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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
 * long as the mic is on, so there is no recognizer restart (and no start chime) between messages.
 *
 * Threads: the mic reads on "voice-mic"; the first pass, the detector and [TwoPassCore] run on
 * "voice-asr"; Parakeet runs on "voice-decode", so a long decode never holds up listening. Control
 * calls (start, stop, mute) hop onto "voice-asr", which serialises everything that touches the core.
 *
 * [onUnavailable] is called when the engines cannot be opened (no models, or the native library cannot
 * load on this phone); the caller then switches to the phone's own recognizer.
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
    /** What happened, for Settings → Voice; memory only. */
    private val log: VoiceLog = VoiceLog(),
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
    /** Chunks the recorder could not hand over (the queue was full); logged with the next start. */
    private val dropped = AtomicInteger()
    /** When the mic was asked to start, and when its first audio arrived (nanoTime; 0 until then). */
    @Volatile private var startedAt = 0L
    @Volatile private var firstAudioAt = 0L
    /** The first second of audio after a start, for its level in the log; null once logged. */
    private var opening: MutableList<FloatArray>? = null
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
        // "Mic stays on" is off and a message just went out, or turned out to be only noise: the mic turns
        // off after it. (After a blank one it used to show as off while it went on recording.)
        if ((event is SpeechEvent.Final || event is SpeechEvent.Error) && bus.mic.value.mode != MicMode.Listening && reader != null) {
            pending += MicCommand.StopRecognizer
        }
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
        // The recorder first: while the models load (a second or so the first time), audio waits in [chunks],
        // so the first words are not lost.
        startedAt = System.nanoTime()
        firstAudioAt = 0L
        opening = mutableListOf()
        reader = MicReader(
            audio,
            onChunk = { chunk ->
                if (firstAudioAt == 0L) firstAudioAt = System.nanoTime()
                if (chunks.trySend(chunk).isFailure) dropped.incrementAndGet()
            },
            onError = { kind -> post { failed(kind) } },
        ).also { it.start() }
        val core = core ?: open() ?: return
        if (loop?.isActive != true) {
            loop = scope.launch {
                for (chunk in chunks) {
                    noteOpening(chunk)
                    core.audio(chunk)
                    runPending()
                }
            }
        }
    }

    /** Logs how the mic started: how soon audio came, and how loud its first second was (silence there: a slow recorder). */
    private fun noteOpening(chunk: FloatArray) {
        val heard = opening ?: return
        heard += chunk
        val size = heard.sumOf { it.size }
        if (size < SAMPLE_RATE) return
        opening = null
        val firstMs = if (firstAudioAt > 0) (firstAudioAt - startedAt) / 1_000_000 else -1
        val all = FloatArray(size)
        var at = 0
        heard.forEach { it.copyInto(all, at); at += it.size }
        val lost = dropped.getAndSet(0)
        log.add("Mic on: first audio $firstMs ms after the start, first second at ${levelDb(all)} dB" + if (lost > 0) ", $lost chunks dropped" else "")
    }

    /** The engines did not open: the recorder goes, and the audio it buffered with it (nobody will decode it). */
    private fun stopReader() {
        reader?.stop()
        reader = null
        while (chunks.tryReceive().isSuccess) Unit
    }

    /** Opens the first pass and the detector (Parakeet opens on its own thread at the first phrase). */
    private suspend fun open(): TwoPassCore? {
        val keepOn = bus.mic.value.keepOn
        val found = paths() ?: run {
            // The models went away since the start was routed here (deleted, tier changed): not a failure.
            stopReader()
            bus.reset()
            onUnavailable(keepOn, null)
            return null
        }
        val loading = System.nanoTime()
        val opened = try {
            hotwordArg = runCatching { hotwordsArg(hotwords()) }.getOrDefault("")
            val f = factory.firstPass(found)
            val v = try { factory.vad(found) } catch (t: Throwable) { f.close(); throw t }
            Triple(found, f, v)
        } catch (t: Throwable) { // LinkageError included: no native library for this phone
            log.add("Live words could not start (${t.javaClass.simpleName}); the phone’s recognizer takes over")
            stopReader()
            bus.reset()
            onUnavailable(keepOn, t)
            return null
        }
        // The audio that came in meanwhile waits in [chunks]: none of it is dropped.
        log.add("Live words and speech detection loaded in ${(System.nanoTime() - loading) / 1_000_000} ms (${opened.first.tier} tier)")
        engines = opened.first
        first = opened.second
        vad = opened.third
        return TwoPassCore(opened.second, opened.third, ::submit, ::emit, { speeds.speed(opened.first.tier) }, log = log::add)
            .also { core = it }
    }

    private fun submit(job: DecodeJob) {
        val paths = engines ?: return
        scope.launch(decoder) {
            val model = recognizer(paths)
            // Timed once the model is open: the first phrase must not count the load as decode speed.
            val started = System.nanoTime()
            val text = model?.let { runCatching { it.decode(job.samples, hotwordArg) }.getOrNull() }
            val ms = (System.nanoTime() - started) / 1_000_000
            if (text != null) updatedSpeed(speeds.speed(paths.tier), job.seconds, ms)?.let { speeds.record(paths.tier, it) }
            withContext(asr) {
                core?.decoded(DecodeResult(job, text, ms.takeIf { model != null }))
                runPending()
            }
        }
    }

    /** Parakeet, opened on first use (decode thread). If it cannot open, phrases keep their live words. */
    private fun recognizer(paths: EnginePaths): PhraseRecognizer? {
        if (secondFailed) return null
        second?.let { return it }
        val loading = System.nanoTime()
        return try {
            factory.secondPass(paths).also {
                second = it
                val words = hotwordArg.split('/').count { w -> w.isNotBlank() }
                val how = if (paths.bpeVocab != null) "beam search, $words hotwords" else "greedy search"
                log.add("Second pass loaded in ${(System.nanoTime() - loading) / 1_000_000} ms (${paths.tier} tier, $how)")
            }
        } catch (t: Throwable) {
            log.add("Second pass could not load (${t.javaClass.simpleName}); phrases keep their live words")
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
        opening = null
        log.add("Mic off")
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
