package net.robog.clarity.app.voice

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.robog.clarity.app.data.feed.FeedStore

/** The person's voice choices and what this phone measured, kept on the phone. */
data class VoicePrefsState(
    val choice: TierChoice = TierChoice.Auto,
    /** The measured decode showed the 0.6B model is too slow here: Auto stays on the lighter one. */
    val benchmarkedDown: Boolean = false,
    /** Use the phone's own recognizer even when the models are there. */
    val useBuiltIn: Boolean = false,
    /** Download over mobile data too (default: Wi-Fi or another unmetered network only). */
    val mobileData: Boolean = false,
    /** The person asked for the download (it is never started without that). */
    val requested: Boolean = false,
    val speeds: Map<VoiceTier, Double> = emptyMap(),
)

interface VoicePrefs {
    val state: Flow<VoicePrefsState>
    suspend fun update(change: (VoicePrefsState) -> VoicePrefsState)
}

class DataStoreVoicePrefs(private val store: DataStore<Preferences>) : VoicePrefs {
    override val state: Flow<VoicePrefsState> = store.data.map { p ->
        VoicePrefsState(
            choice = p[CHOICE]?.let { runCatching { TierChoice.valueOf(it) }.getOrNull() } ?: TierChoice.Auto,
            benchmarkedDown = p[DOWN] == true,
            useBuiltIn = p[BUILT_IN] == true,
            mobileData = p[MOBILE] == true,
            requested = p[REQUESTED] == true,
            speeds = VoiceTier.entries.mapNotNull { t -> p[speedKey(t)]?.let { t to it } }.toMap(),
        )
    }

    override suspend fun update(change: (VoicePrefsState) -> VoicePrefsState) {
        store.edit { p ->
            val now = change(
                VoicePrefsState(
                    choice = p[CHOICE]?.let { runCatching { TierChoice.valueOf(it) }.getOrNull() } ?: TierChoice.Auto,
                    benchmarkedDown = p[DOWN] == true,
                    useBuiltIn = p[BUILT_IN] == true,
                    mobileData = p[MOBILE] == true,
                    requested = p[REQUESTED] == true,
                    speeds = VoiceTier.entries.mapNotNull { t -> p[speedKey(t)]?.let { t to it } }.toMap(),
                ),
            )
            p[CHOICE] = now.choice.name
            p[DOWN] = now.benchmarkedDown
            p[BUILT_IN] = now.useBuiltIn
            p[MOBILE] = now.mobileData
            p[REQUESTED] = now.requested
            VoiceTier.entries.forEach { t -> now.speeds[t]?.let { p[speedKey(t)] = it } ?: p.remove(speedKey(t)) }
        }
    }

    private companion object {
        val CHOICE = stringPreferencesKey("voice_tier_choice")
        val DOWN = booleanPreferencesKey("voice_benchmarked_down")
        val BUILT_IN = booleanPreferencesKey("voice_use_built_in")
        val MOBILE = booleanPreferencesKey("voice_mobile_data")
        val REQUESTED = booleanPreferencesKey("voice_download_requested")
        fun speedKey(t: VoiceTier) = doublePreferencesKey("voice_speed_${t.name.lowercase()}")
    }
}

/** Settings → Voice. */
data class VoiceUiState(
    val tier: VoiceTier = VoiceTier.Light,
    val choice: TierChoice = TierChoice.Auto,
    /** What Auto would pick from this phone's memory. */
    val proposed: VoiceTier = VoiceTier.Light,
    val status: ModelStatus = ModelStatus.NotDownloaded(VoiceCatalog.downloadBytes(VoiceTier.Light)),
    val useBuiltIn: Boolean = false,
    val mobileData: Boolean = false,
    /** The models are there but could not run on this phone. */
    val engineFailed: Boolean = false,
    val benchmarkedDown: Boolean = false,
) {
    /** Which recognizer listens now. */
    val usingOwn: Boolean get() = status == ModelStatus.Ready && !useBuiltIn && !engineFailed
}

/**
 * The speech models on this phone: what is installed, the download (WorkManager, unmetered network by
 * default, resumable), the tier, and the switch to the built-in recognizer. Only model files are ever
 * downloaded; nothing is uploaded.
 */
class VoiceModels(
    private val context: Context,
    private val prefs: VoicePrefs,
    private val feed: FeedStore,
) {
    val store = ModelStore(File(context.noBackupFilesDir, "voice-models"))
    /** What on-phone recognition did lately, for Settings → Voice. Memory only. */
    val log = VoiceLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineFailed = MutableStateFlow(false)
    private val installedTick = MutableStateFlow(0)

    val facts: DeviceFacts by lazy {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        DeviceFacts(totalRamBytes = info.totalMem, lowRamDevice = am.isLowRamDevice)
    }

    private val current: StateFlow<VoicePrefsState> = prefs.state.stateIn(scope, SharingStarted.Eagerly, VoicePrefsState())

    fun tier(p: VoicePrefsState = current.value): VoiceTier = resolveTier(p.choice, facts, p.benchmarkedDown)

    val state: Flow<VoiceUiState> = combine(
        prefs.state,
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK).map { it.firstOrNull()?.let(::downloadWork) },
        engineFailed,
        installedTick,
    ) { p, work, failed, _ ->
        val tier = tier(p)
        VoiceUiState(
            tier = tier,
            choice = p.choice,
            proposed = proposedTier(facts),
            status = modelStatus(store.installed(tier), work?.takeIf { p.requested }, VoiceCatalog.downloadBytes(tier)),
            useBuiltIn = p.useBuiltIn,
            mobileData = p.mobileData,
            engineFailed = failed,
            benchmarkedDown = p.benchmarkedDown,
        )
    }

    /** For the speech input, at each start (cheap: file sizes only). */
    fun readyPaths(): EnginePaths? = store.paths(tier())

    fun useOwnModels(): Boolean = !current.value.useBuiltIn && !engineFailed.value && readyPaths() != null

    fun nativeFailed() { engineFailed.value = true }

    /** Names, courses and places from the task titles already cached on this phone. */
    suspend fun hotwords(): List<String> = runCatching { hotwordsFrom(feed.tasks.first().map { it.title }) }.getOrDefault(emptyList())

    val speeds: SpeedStore = object : SpeedStore {
        override fun speed(tier: VoiceTier): Double? = current.value.speeds[tier]
        override fun record(tier: VoiceTier, speed: Double) {
            scope.launch { prefs.update { it.copy(speeds = it.speeds + (tier to speed)) } }
        }
    }

    /** The person confirmed the download. */
    fun download() = scope.launch {
        prefs.update { it.copy(requested = true) }
        enqueue(ExistingWorkPolicy.REPLACE)
    }

    fun cancelDownload() = scope.launch {
        prefs.update { it.copy(requested = false) }
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    fun setChoice(choice: TierChoice) = scope.launch {
        prefs.update { it.copy(choice = choice) }
        val p = current.value.copy(choice = choice)
        cleanUp(tier(p))
        if (p.requested && !store.installed(tier(p))) enqueue(ExistingWorkPolicy.REPLACE)
    }

    fun setUseBuiltIn(on: Boolean) = scope.launch { prefs.update { it.copy(useBuiltIn = on) } }

    fun setMobileData(on: Boolean) = scope.launch {
        prefs.update { it.copy(mobileData = on) }
        if (current.value.requested && !store.installed(tier())) enqueue(ExistingWorkPolicy.REPLACE)
    }

    fun deleteModels() = scope.launch {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        prefs.update { it.copy(requested = false) }
        withContext(Dispatchers.IO) { store.packIds().forEach(store::delete) }
        installedTick.value++
    }

    /** Deletes what the tier no longer needs (a tier change frees the other model's space). */
    suspend fun cleanUp(tier: VoiceTier) = withContext(Dispatchers.IO) {
        packsToDelete(store.packIds(), tier).forEach(store::delete)
        installedTick.value++
    }

    private suspend fun enqueue(policy: ExistingWorkPolicy) {
        val p = prefs.state.first()
        val request = OneTimeWorkRequestBuilder<VoiceModelWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (p.mobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, policy, request)
    }

    /** Run by [VoiceModelWorker]: download, check, and on Auto measure whether the 0.6B model keeps up. */
    internal suspend fun runDownload(progress: suspend (Long, Long) -> Unit): String? {
        val tier = tier(prefs.state.first())
        val packs = VoiceCatalog.packs(tier)
        val needed = VoiceCatalog.downloadBytes(tier) - store.bytesPresent(packs)
        store.root.mkdirs()
        if (!enoughSpace(StatFs(store.root.path).availableBytes, needed)) {
            return "Not enough free space: the models need ${formatBytes(needed)}"
        }
        var last = 0L
        ModelDownloader(HttpModelFetcher(), store).download(packs) { done, total ->
            if (done - last >= total / 200 || done == total) {
                last = done
                progress(done, total)
            }
        }
        cleanUp(tier)
        if (prefs.state.first().choice == TierChoice.Auto && tier == VoiceTier.Accurate) benchmark(tier)
        return null
    }

    /** Decodes a few seconds of quiet noise with the second pass and keeps the speed; too slow steps down. */
    private suspend fun benchmark(tier: VoiceTier) {
        val paths = store.paths(tier) ?: return
        val speed = withContext(Dispatchers.Default) {
            runCatching {
                SherpaEngineFactory().secondPass(paths).use { model ->
                    val random = Random(7)
                    val samples = FloatArray((BENCHMARK_SECONDS * SAMPLE_RATE).toInt()) { (random.nextFloat() - 0.5f) * 0.02f }
                    model.decode(samples.copyOf(SAMPLE_RATE / 2)) // warm up
                    val started = System.nanoTime()
                    model.decode(samples)
                    (System.nanoTime() - started) / 1e9 / BENCHMARK_SECONDS
                }
            }.getOrNull()
        } ?: run {
            log.add("Speed test: the $tier model could not run on this phone")
            return nativeFailed()
        }
        prefs.update { it.copy(speeds = it.speeds + (tier to speed)) }
        val next = tierAfterBenchmark(tier, speed)
        log.add(
            "Speed test: the $tier model takes %.2f s per second of audio".format(java.util.Locale.ROOT, speed) +
                if (next != tier) "; too slow, stepping down to $next" else "",
        )
        if (next != tier) {
            prefs.update { it.copy(benchmarkedDown = true) }
            cleanUp(next)
            enqueue(ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    private fun downloadWork(info: WorkInfo): DownloadWork? {
        val done = info.progress.getLong(DONE, 0)
        val total = info.progress.getLong(TOTAL, 0)
        return when (info.state) {
            WorkInfo.State.ENQUEUED -> DownloadWork(DownloadWork.State.WaitingForNetwork, done, total)
            WorkInfo.State.BLOCKED -> DownloadWork(DownloadWork.State.Queued, done, total)
            WorkInfo.State.RUNNING -> DownloadWork(DownloadWork.State.Running, done, total)
            WorkInfo.State.FAILED -> DownloadWork(DownloadWork.State.Failed, problem = info.outputData.getString(PROBLEM))
            WorkInfo.State.SUCCEEDED -> DownloadWork(DownloadWork.State.Done)
            WorkInfo.State.CANCELLED -> null
        }
    }

    companion object {
        const val WORK = "voice-models"
        const val DONE = "done"
        const val TOTAL = "total"
        const val PROBLEM = "problem"
    }
}

/**
 * Downloads the speech models. An interrupted download (network lost, the system stopped the worker)
 * is retried later and continues where it stopped.
 */
class VoiceModelWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val voice = (applicationContext as net.robog.clarity.app.ClarityApp).graph.voiceModels
        return try {
            val problem = voice.runDownload { done, total -> setProgress(workDataOf(VoiceModels.DONE to done, VoiceModels.TOTAL to total)) }
            if (problem == null) Result.success() else Result.failure(workDataOf(VoiceModels.PROBLEM to problem))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: ChecksumMismatch) {
            if (runAttemptCount >= 3) Result.failure(workDataOf(VoiceModels.PROBLEM to "A model file arrived damaged; try again later")) else Result.retry()
        } catch (_: IOException) {
            if (runAttemptCount >= 8) Result.failure(workDataOf(VoiceModels.PROBLEM to "The download kept failing; try again later")) else Result.retry()
        }
    }
}
