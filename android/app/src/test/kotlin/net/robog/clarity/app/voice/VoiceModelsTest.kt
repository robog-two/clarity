package net.robog.clarity.app.voice

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class VoiceTierTest {
    private val eightGb = DeviceFacts(totalRamBytes = 7_400_000_000L, lowRamDevice = false)
    private val sixGb = DeviceFacts(totalRamBytes = 5_600_000_000L, lowRamDevice = false)

    @Test fun memoryProposesATier() {
        assertEquals(VoiceTier.Accurate, proposedTier(eightGb))
        assertEquals(VoiceTier.Light, proposedTier(sixGb))
        assertEquals(VoiceTier.Light, proposedTier(eightGb.copy(lowRamDevice = true)))
    }

    @Test fun aSlowMeasuredDecodeStepsDown() {
        assertEquals(VoiceTier.Accurate, tierAfterBenchmark(VoiceTier.Accurate, 0.12))
        assertEquals(VoiceTier.Light, tierAfterBenchmark(VoiceTier.Accurate, 0.4))
        assertEquals(VoiceTier.Light, tierAfterBenchmark(VoiceTier.Light, 0.9), "nothing lighter to go to")
    }

    @Test fun theChoiceInSettingsWinsAndAutoRemembersTheBenchmark() {
        assertEquals(VoiceTier.Accurate, resolveTier(TierChoice.Auto, eightGb, benchmarkedDown = false))
        assertEquals(VoiceTier.Light, resolveTier(TierChoice.Auto, eightGb, benchmarkedDown = true))
        assertEquals(VoiceTier.Accurate, resolveTier(TierChoice.Accurate, sixGb, benchmarkedDown = true))
        assertEquals(VoiceTier.Light, resolveTier(TierChoice.Light, eightGb, benchmarkedDown = false))
    }

    @Test fun aTierChangeDeletesTheOtherModel() {
        val all = setOf(VoiceCatalog.Base.id, VoiceCatalog.Accurate.id, VoiceCatalog.Light.id, "old-model")
        assertEquals(setOf(VoiceCatalog.Accurate.id, "old-model"), packsToDelete(all, VoiceTier.Light))
        assertEquals(setOf(VoiceCatalog.Light.id, "old-model"), packsToDelete(all, VoiceTier.Accurate))
    }

    @Test fun statusFollowsTheFilesAndTheDownload() {
        val bytes = VoiceCatalog.downloadBytes(VoiceTier.Light)
        assertEquals(ModelStatus.Ready, modelStatus(installed = true, work = null, bytes))
        assertEquals(ModelStatus.NotDownloaded(bytes), modelStatus(false, null, bytes))
        assertEquals(ModelStatus.Downloading(10, 100), modelStatus(false, DownloadWork(DownloadWork.State.Running, 10, 100), bytes))
        assertEquals(ModelStatus.Downloading(0, bytes), modelStatus(false, DownloadWork(DownloadWork.State.Running), bytes))
        assertEquals(ModelStatus.WaitingForNetwork(5, bytes), modelStatus(false, DownloadWork(DownloadWork.State.WaitingForNetwork, 5), bytes))
        assertEquals(ModelStatus.Failed("disk"), modelStatus(false, DownloadWork(DownloadWork.State.Failed, problem = "disk"), bytes))
        assertEquals(ModelStatus.NotDownloaded(bytes), modelStatus(false, DownloadWork(DownloadWork.State.Done), bytes), "files gone since")
        assertEquals(0.5f, ModelStatus.Downloading(50, 100).fraction)
        assertEquals(0f, ModelStatus.Downloading(50, 0).fraction)
    }

    @Test fun sizesReadLikeAPhoneShowsThem() {
        assertEquals("138 MB", formatBytes(VoiceCatalog.Base.bytes))
        assertEquals("799 MB", formatBytes(VoiceCatalog.downloadBytes(VoiceTier.Accurate)))
        assertEquals("274 MB", formatBytes(VoiceCatalog.downloadBytes(VoiceTier.Light)))
        assertEquals("1.2 GB", formatBytes(1_234_000_000))
        assertEquals("12 kB", formatBytes(12_300))
        assertTrue(enoughSpace(2_000_000_000, 800_000_000))
        assertFalse(enoughSpace(1_000_000_000, 800_000_000))
    }

    @Test fun everyModelFileIsPinned() {
        val packs = listOf(VoiceCatalog.Base, VoiceCatalog.Accurate, VoiceCatalog.Light)
        for (pack in packs) {
            assertEquals(pack.files.size, pack.files.map { it.name }.toSet().size, "${pack.id}: unique names")
            for (f in pack.files) {
                assertTrue(Regex("[0-9a-f]{64}").matches(f.sha256), "${f.name}: a SHA-256")
                assertTrue(f.url.startsWith("https://"), f.url)
                assertTrue(f.url.endsWith("/${f.name}"), f.url)
                // A Hugging Face file is pinned to a commit, never a branch.
                if ("huggingface.co" in f.url) assertTrue(Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(f.url), f.url)
                assertTrue(f.bytes > 0)
            }
            assertTrue(pack.files.any { it.name == "tokens.txt" })
        }
    }
}

class ModelDownloaderTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Serves files from memory; `cutAfter` ends the next response early (a dropped connection). */
    private class Server(val files: Map<String, ByteArray>) : ModelFetcher {
        val requests = mutableListOf<Pair<String, Long>>()
        var cutAfter: Int? = null
        var ignoreRange = false
        override fun open(url: String, from: Long): Fetched {
            requests += url to from
            val body = files[url] ?: return Fetched(404, ByteArrayInputStream(ByteArray(0)))
            if (ignoreRange || from == 0L) return Fetched(200, limited(body))
            if (from >= body.size) return Fetched(416, ByteArrayInputStream(ByteArray(0)))
            return Fetched(206, limited(body.copyOfRange(from.toInt(), body.size)))
        }
        private fun limited(b: ByteArray): InputStream {
            val n = cutAfter?.also { cutAfter = null } ?: b.size
            return ByteArrayInputStream(b.copyOf(minOf(n, b.size)))
        }
    }

    private val modelBytes = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val tokenBytes = "<unk> 0\n▁a 1\n<blk> 2\n".toByteArray()
    private val pack = ModelPack(
        "test-pack", "test", "MIT",
        listOf(
            ModelFile("encoder.int8.onnx", "https://example.test/encoder.int8.onnx", modelBytes.size.toLong(), sha(modelBytes)),
            ModelFile("tokens.txt", "https://example.test/tokens.txt", tokenBytes.size.toLong(), sha(tokenBytes)),
        ),
    )
    private val server = Server(mapOf(pack.files[0].url to modelBytes, pack.files[1].url to tokenBytes))
    private val root: File = Files.createTempDirectory("models").toFile()
    private val store = ModelStore(root)

    @Test fun downloadsChecksAndMarksThePackReady() = runTest {
        val progress = mutableListOf<Pair<Long, Long>>()
        ModelDownloader(server, store).download(listOf(pack)) { d, t -> progress += d to t }
        assertTrue(store.installed(pack))
        assertTrue(modelBytes.contentEquals(File(store.dir(pack), "encoder.int8.onnx").readBytes()))
        assertEquals("<unk>\t0.0\n▁a\t-0.0\n", File(store.dir(pack), ModelStore.BPE_VOCAB).readText())
        assertEquals(pack.bytes to pack.bytes, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b.first >= a.first }, "progress only grows")
        // Again: nothing is fetched.
        server.requests.clear()
        ModelDownloader(server, store).download(listOf(pack))
        assertEquals(emptyList(), server.requests)
        root.deleteRecursively()
    }

    @Test fun anInterruptedDownloadResumesWhereItStopped() = runTest {
        server.cutAfter = 100_000
        assertFailsWith<IOException> { ModelDownloader(server, store).download(listOf(pack)) }
        assertFalse(store.installed(pack))
        assertEquals(100_000L, store.bytesPresent(listOf(pack)))
        ModelDownloader(server, store).download(listOf(pack))
        assertTrue(store.installed(pack))
        assertEquals(pack.files[0].url to 100_000L, server.requests.first { it.second > 0 })
        root.deleteRecursively()
    }

    @Test fun aServerThatIgnoresTheRangeStartsTheFileOver() = runTest {
        server.cutAfter = 50_000
        runCatching { ModelDownloader(server, store).download(listOf(pack)) }
        server.ignoreRange = true
        ModelDownloader(server, store).download(listOf(pack))
        assertTrue(store.installed(pack))
        assertTrue(modelBytes.contentEquals(File(store.dir(pack), "encoder.int8.onnx").readBytes()))
        root.deleteRecursively()
    }

    @Test fun aDamagedFileIsDeletedAndNeverUsed() = runTest {
        val bad = Server(mapOf(pack.files[0].url to modelBytes.copyOf().also { it[5] = 0 }, pack.files[1].url to tokenBytes))
        assertFailsWith<ChecksumMismatch> { ModelDownloader(bad, store).download(listOf(pack)) }
        assertFalse(File(store.dir(pack), "encoder.int8.onnx").exists())
        assertFalse(File(store.dir(pack), "encoder.int8.onnx.part").exists())
        assertFalse(store.installed(pack))
        root.deleteRecursively()
    }

    @Test fun httpErrorsAreRetriedLater() = runTest {
        val missing = Server(emptyMap())
        assertFailsWith<IOException> { ModelDownloader(missing, store).download(listOf(pack)) }
        root.deleteRecursively()
    }

    @Test fun deletingAPackRemovesItsFolderOnly() = runTest {
        ModelDownloader(server, store).download(listOf(pack))
        File(root, "other").mkdirs()
        store.delete(pack.id)
        assertEquals(setOf("other"), store.packIds())
        assertFailsWith<IllegalArgumentException> { store.delete("../x") }
        root.deleteRecursively()
    }
}
