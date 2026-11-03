package net.robog.clarity.app.voice

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One HTTP response body. */
class Fetched(val code: Int, val body: InputStream) : Closeable {
    override fun close() = body.close()
}

/** A GET from byte [from] on (a `Range` request when [from] > 0). Behind an interface so tests never use the network. */
fun interface ModelFetcher {
    fun open(url: String, from: Long): Fetched
}

/**
 * Plain `HttpURLConnection`: not the app's Ktor client, which signs requests for the person's own
 * server. Model hosts get a bare GET with no cookies, tokens or identifiers.
 */
class HttpModelFetcher : ModelFetcher {
    override fun open(url: String, from: Long): Fetched {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true // Hugging Face and GitHub redirect to their CDNs (https → https)
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.useCaches = false
        if (from > 0) connection.setRequestProperty("Range", "bytes=$from-")
        val code = connection.responseCode
        val body = if (code in 200..299) connection.inputStream else (connection.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0)))
        return Fetched(code, body)
    }
}

class ChecksumMismatch(file: String) : IOException("$file did not match its checksum")

/**
 * The model files in app-private storage: `<root>/<pack id>/<file>`. A file gets its final name only
 * once its SHA-256 matched, and a pack is installed once all its files are there (`.ready` marks it).
 */
class ModelStore(val root: File) {
    fun dir(pack: ModelPack): File = File(root, pack.id)

    fun installed(pack: ModelPack): Boolean =
        File(dir(pack), READY).isFile && pack.files.all { File(dir(pack), it.name).length() == it.bytes }

    fun installed(tier: VoiceTier): Boolean = VoiceCatalog.packs(tier).all(::installed)

    /** Ids of the packs on disk, finished or not. */
    fun packIds(): Set<String> = root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet().orEmpty()

    fun delete(packId: String) {
        require(packId.isNotEmpty() && !packId.contains('/') && !packId.contains("..")) { "bad pack id" }
        File(root, packId).deleteRecursively()
    }

    /** Bytes already on disk for [packs] (finished files and partial downloads), for progress. */
    fun bytesPresent(packs: List<ModelPack>): Long = packs.sumOf { pack ->
        pack.files.sumOf { f ->
            val done = File(dir(pack), f.name)
            if (done.isFile) done.length() else File(dir(pack), f.name + PART).length().coerceAtMost(f.bytes)
        }
    }

    fun paths(tier: VoiceTier): EnginePaths? {
        if (!installed(tier)) return null
        val second = dir(VoiceCatalog.secondPass(tier))
        return EnginePaths(dir(VoiceCatalog.Base), second, tier, File(second, BPE_VOCAB).takeIf { it.isFile })
    }

    companion object {
        const val READY = ".ready"
        const val PART = ".part"
        const val BPE_VOCAB = "bpe.vocab"
    }
}

/**
 * Downloads packs into a [ModelStore]: resumable (a `.part` file continues with a `Range` request
 * after an interruption), checked against each file's SHA-256 before it is renamed into place.
 */
class ModelDownloader(private val fetcher: ModelFetcher, private val store: ModelStore) {

    /** [progress] gets (bytes on disk, total bytes) as the download goes. Throws [IOException] to retry later. */
    suspend fun download(packs: List<ModelPack>, progress: suspend (Long, Long) -> Unit = { _, _ -> }) {
        val total = packs.sumOf { it.bytes }
        var done = store.bytesPresent(packs)
        progress(done, total)
        for (pack in packs) {
            if (store.installed(pack)) continue
            val dir = store.dir(pack).apply { mkdirs() }
            for (file in pack.files) {
                fetchFile(file, dir) { delta ->
                    done += delta
                    progress(done, total)
                }
            }
            // The second pass's hotwords need a SentencePiece vocabulary; the model folders only have tokens.txt.
            val tokens = File(dir, "tokens.txt")
            if (pack != VoiceCatalog.Base && tokens.isFile) {
                File(dir, ModelStore.BPE_VOCAB).writeText(bpeVocabFromTokens(tokens.readText()))
            }
            File(dir, ModelStore.READY).writeText(pack.id)
        }
        progress(total, total)
    }

    private suspend fun fetchFile(file: ModelFile, dir: File, advanced: suspend (Long) -> Unit) {
        val target = File(dir, file.name)
        if (target.isFile && target.length() == file.bytes) return // renamed only after its checksum matched
        val part = File(dir, file.name + ModelStore.PART)
        if (part.length() > file.bytes) part.delete()
        if (part.length() < file.bytes) {
            val from = part.length()
            fetcher.open(file.url, from).use { response ->
                val append = when (response.code) {
                    206 -> true
                    200 -> { advanced(-from); false } // the server sent it all again: start over
                    416 -> null // nothing left to send
                    else -> throw IOException("${file.name}: HTTP ${response.code}")
                }
                if (append != null) copy(response.body, part, append, file.bytes, advanced)
            }
        }
        if (part.length() != file.bytes) throw IOException("${file.name}: ${part.length()} of ${file.bytes} bytes")
        if (sha256(part) != file.sha256) {
            advanced(-part.length())
            part.delete()
            throw ChecksumMismatch(file.name)
        }
        if (!part.renameTo(target)) throw IOException("${file.name}: could not move into place")
    }

    private suspend fun copy(body: InputStream, part: File, append: Boolean, limit: Long, advanced: suspend (Long) -> Unit) {
        java.io.FileOutputStream(part, append).use { out ->
            val buffer = ByteArray(64 * 1024)
            var written = if (append) part.length() else 0L
            while (true) {
                currentCoroutineContext().ensureActive() // WorkManager stopped us: keep the part file for next time
                val n = body.read(buffer)
                if (n < 0) break
                val keep = minOf(n.toLong(), limit - written).toInt()
                if (keep <= 0) break
                out.write(buffer, 0, keep)
                written += keep
                advanced(keep.toLong())
            }
        }
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
