package net.robog.clarity.app.voice

/*
 * Which speech models the phone downloads, how it picks between the two second-pass sizes, and what
 * Settings → Voice shows about them. Pure: no Android, no network.
 *
 * Every file is pinned: an exact Hugging Face commit (or the sherpa-onnx GitHub release), its size and
 * its SHA-256, checked before the file is used. Nothing is bundled in the APK or kept in git.
 */

/** One file of a model, downloaded to `<pack folder>/<name>`. */
data class ModelFile(val name: String, val url: String, val bytes: Long, val sha256: String)

/** A set of files that are used together (a model and its token list). */
data class ModelPack(val id: String, val title: String, val license: String, val files: List<ModelFile>) {
    val bytes: Long get() = files.sumOf { it.bytes }
}

/** The second-pass size: the larger, more accurate Parakeet where the phone can run it. */
enum class VoiceTier { Accurate, Light }

/** What the person chose in Settings; [Auto] lets the phone decide from its memory and a measured decode. */
enum class TierChoice { Auto, Accurate, Light }

private const val HF = "https://huggingface.co"

object VoiceCatalog {
    /**
     * Always needed: the voice activity detector (Silero VAD, MIT) and the first pass, NVIDIA's
     * streaming FastConformer transducer (114M parameters, 480 ms look-ahead, trained on NeMo ASRSET,
     * CC-BY-4.0) as converted by sherpa-onnx. Chosen over the 2023 streaming Zipformer (Apache-2.0, ~70 MB
     * int8, LibriSpeech only) because it was trained on far more varied English, so the live words are
     * closer to what the second pass settles on; it is 137 MB instead of 73 MB.
     */
    val Base = ModelPack(
        id = "base-fastconformer-480ms-v1",
        title = "Live words and speech detection",
        license = "CC-BY-4.0 (NVIDIA FastConformer), MIT (Silero VAD)",
        files = listOf(
            ModelFile(
                "silero_vad.onnx",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                643_854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
            ),
            fc("encoder.int8.onnx", 131_507_603, "100c5616929a131b5b3c8c8ab0d83aaba2cdcae163acd8d190b4e5ffa5f7d051"),
            fc("decoder.int8.onnx", 3_955_864, "4f04431988472f8c7b815f942aa6901976929c9e22353029681fcdb262da0164"),
            fc("joiner.int8.onnx", 1_408_182, "d2d8e7290a6a8245a83ed211aa0dd8cdf8effb8a7c64fe392399561111b52f30"),
            fc("tokens.txt", 11_896, "618dc110fc2213886b52e063ff42329bbdf37a266ca7705184090fa5f39f3131"),
        ),
    )

    /** NVIDIA Parakeet TDT 0.6B v2 (English), int8, CC-BY-4.0. */
    val Accurate = ModelPack(
        id = "parakeet-tdt-0.6b-v2-int8",
        title = "Parakeet 0.6B (most accurate)",
        license = "CC-BY-4.0",
        files = listOf(
            pk06("encoder.int8.onnx", 652_184_296, "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
            pk06("decoder.int8.onnx", 7_257_753, "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
            pk06("joiner.int8.onnx", 1_739_080, "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
            pk06("tokens.txt", 9_384, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"),
        ),
    )

    /**
     * NVIDIA Parakeet TDT-CTC 110M (English), its transducer head, int8, CC-BY-4.0. sherpa-onnx publishes
     * this one only as a .tar.bz2 on its GitHub release; the files here come from a Hugging Face mirror
     * whose four files were checked byte for byte (SHA-256) against that release archive
     * (sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8.tar.bz2, sha256 f628312e…cfe9b).
     */
    val Light = ModelPack(
        id = "parakeet-tdt-110m-int8",
        title = "Parakeet 110M (lighter)",
        license = "CC-BY-4.0",
        files = listOf(
            pk110("encoder.int8.onnx", 131_113_202, "0f35509ddeb9b39002fb077d979a9fe74f06eb0bc4dd5c34f512f82e5111d657"),
            pk110("decoder.int8.onnx", 3_955_863, "f7c331c5504c2e593c76ed22b728e3f554af6c4a383dde862e719ced08b1da19"),
            pk110("joiner.int8.onnx", 1_411_403, "bf7dff69e9f2cdbe9943d70da358f38b361c115ba0105bae7e908e0d6ec782f6"),
            pk110("tokens.txt", 9_953, "450e56bd2f036fe5b6aa821865838cc5aa9d8b0106134ce9a9ba0664abe6cd10"),
        ),
    )

    fun secondPass(tier: VoiceTier): ModelPack = when (tier) {
        VoiceTier.Accurate -> Accurate
        VoiceTier.Light -> Light
    }

    /** Everything a tier needs, in download order (the small base first). */
    fun packs(tier: VoiceTier): List<ModelPack> = listOf(Base, secondPass(tier))

    fun downloadBytes(tier: VoiceTier): Long = packs(tier).sumOf { it.bytes }

    private fun fc(name: String, bytes: Long, sha: String) = ModelFile(
        name, "$HF/csukuangfj/sherpa-onnx-nemo-streaming-fast-conformer-transducer-en-480ms-int8/resolve/df8ed95e44a70924450381e610770f9d656d1e15/$name", bytes, sha,
    )
    private fun pk06(name: String, bytes: Long, sha: String) = ModelFile(
        name, "$HF/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/1ab9323565ddb038682214b292f588070a538ce2/$name", bytes, sha,
    )
    private fun pk110(name: String, bytes: Long, sha: String) = ModelFile(
        name, "$HF/punitd/sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8/resolve/66a4fa70643dc7ce25c9b38b2f87e1b35ddad33d/$name", bytes, sha,
    )
}

// --- choosing the tier ---

/** What the phone says about itself (ActivityManager), never its model name. */
data class DeviceFacts(val totalRamBytes: Long, val lowRamDevice: Boolean)

/**
 * Below this much RAM the 0.6B model (≈ 0.9 GB resident while listening, next to the first pass and
 * the rest of the phone) is not attempted. Phones sold as 8 GB report about 7.3 × 10⁹ bytes.
 */
const val ACCURATE_MIN_RAM_BYTES = 6_500_000_000L

/** The 0.6B model must decode a phrase in under a quarter of its length, or it falls behind speech. */
const val ACCURATE_MAX_SPEED = 0.25

/** Before anything is measured: what the memory allows. */
fun proposedTier(facts: DeviceFacts): VoiceTier =
    if (!facts.lowRamDevice && facts.totalRamBytes >= ACCURATE_MIN_RAM_BYTES) VoiceTier.Accurate else VoiceTier.Light

/** After a measured decode ([speed]: seconds of work per second of audio): too slow steps down. */
fun tierAfterBenchmark(tier: VoiceTier, speed: Double): VoiceTier =
    if (tier == VoiceTier.Accurate && speed > ACCURATE_MAX_SPEED) VoiceTier.Light else tier

/**
 * The tier to use. A choice made in Settings wins; on Auto, a tier the benchmark stepped down from is
 * not tried again ([benchmarkedDown]), and otherwise the memory decides.
 */
fun resolveTier(choice: TierChoice, facts: DeviceFacts, benchmarkedDown: Boolean): VoiceTier = when (choice) {
    TierChoice.Accurate -> VoiceTier.Accurate
    TierChoice.Light -> VoiceTier.Light
    TierChoice.Auto -> if (benchmarkedDown) VoiceTier.Light else proposedTier(facts)
}

/** Seconds of audio the benchmark decodes. */
const val BENCHMARK_SECONDS = 4.0

// --- download and status ---

/** WorkManager's view of the download, reduced to what matters here. */
data class DownloadWork(
    val state: State,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    /** The last attempt's problem, for a failed download. */
    val problem: String? = null,
) {
    enum class State { Queued, Running, WaitingForNetwork, Failed, Done }
}

/** What Settings → Voice shows. */
sealed interface ModelStatus {
    /** Not downloaded; [bytes] is what the download would take. */
    data class NotDownloaded(val bytes: Long) : ModelStatus
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : ModelStatus {
        val fraction: Float get() = if (totalBytes <= 0) 0f else (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
    }
    /** Queued until Wi-Fi (or any network, with mobile data allowed). */
    data class WaitingForNetwork(val doneBytes: Long, val totalBytes: Long) : ModelStatus
    data class Failed(val problem: String) : ModelStatus
    data object Ready : ModelStatus
}

fun modelStatus(installed: Boolean, work: DownloadWork?, tierBytes: Long): ModelStatus = when {
    installed -> ModelStatus.Ready
    work == null -> ModelStatus.NotDownloaded(tierBytes)
    else -> when (work.state) {
        DownloadWork.State.Queued -> ModelStatus.WaitingForNetwork(work.doneBytes, tierBytes)
        DownloadWork.State.WaitingForNetwork -> ModelStatus.WaitingForNetwork(work.doneBytes, tierBytes)
        DownloadWork.State.Running -> ModelStatus.Downloading(work.doneBytes, work.totalBytes.takeIf { it > 0 } ?: tierBytes)
        DownloadWork.State.Failed -> ModelStatus.Failed(work.problem ?: "The download stopped")
        // Finished, but the files are not all there (deleted since): offer it again.
        DownloadWork.State.Done -> ModelStatus.NotDownloaded(tierBytes)
    }
}

/** Free space needed before starting: the files plus a margin for the rest of the phone. */
fun enoughSpace(freeBytes: Long, neededBytes: Long, marginBytes: Long = 500_000_000L): Boolean = freeBytes >= neededBytes + marginBytes

/** "137 MB", "1.2 GB": decimal units, as phones show storage. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(java.util.Locale.ROOT, bytes / 1e9)
    bytes >= 1_000_000L -> "${(bytes + 500_000) / 1_000_000} MB"
    bytes >= 1_000L -> "${(bytes + 500) / 1_000} kB"
    else -> "$bytes B"
}

/** Which installed packs to delete when the tier changes: everything not needed by [tier]. */
fun packsToDelete(installed: Set<String>, tier: VoiceTier): Set<String> =
    installed - VoiceCatalog.packs(tier).map { it.id }.toSet()
