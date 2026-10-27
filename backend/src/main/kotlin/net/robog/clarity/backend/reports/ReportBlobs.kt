package net.robog.clarity.backend.reports

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.robog.clarity.backend.config.AppConfig
import net.robog.clarity.shared.report.ReportFormat

/**
 * Rendered files on the data volume, under `<dataDir>/reports/renditions`. Only the rendition cache
 * lives here: every file can be rendered again from its revision, so losing one is never data loss.
 */
@Inject
@SingleIn(AppScope::class)
class ReportBlobs(config: AppConfig) {
    /** The reports directory; tests point it at a temporary one. */
    val root: Path = config.dataDir.resolve("reports")

    /** Where a rendition goes, relative to [root]: per revision, then format and renderer version. */
    fun relativePath(revisionId: Long, format: ReportFormat, rendererVersion: String): String =
        "renditions/$revisionId/${format.wireName}-${versionSlug(rendererVersion)}.${format.extension}"

    fun resolve(relative: String): Path {
        val path = root.resolve(relative).normalize()
        require(path.startsWith(root.normalize())) { "rendition path escapes the reports directory" }
        return path
    }

    /** Writes [bytes] atomically (a temporary file moved into place); returns the sha-256 hex. */
    suspend fun write(relative: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val target = resolve(relative)
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, ".partial-", ".tmp")
        try {
            Files.write(temp, bytes)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
        sha256Hex(bytes)
    }

    suspend fun exists(relative: String): Boolean = withContext(Dispatchers.IO) { Files.isRegularFile(resolve(relative)) }

    suspend fun read(relative: String): ByteArray? = withContext(Dispatchers.IO) {
        val path = resolve(relative)
        if (Files.isRegularFile(path)) Files.readAllBytes(path) else null
    }

    /** Deletes a rendition's file and its revision folder when that is left empty. */
    suspend fun delete(relative: String) = withContext(Dispatchers.IO) {
        val path = resolve(relative)
        Files.deleteIfExists(path)
        val parent = path.parent
        if (parent != null && parent != root && Files.isDirectory(parent)) {
            runCatching { Files.newDirectoryStream(parent).use { if (!it.iterator().hasNext()) Files.deleteIfExists(parent) } }
        }
    }

    private companion object {
        fun versionSlug(version: String): String {
            val slug = version.lowercase().map { if (it.isLetterOrDigit() || it == '.') it else '_' }.joinToString("").take(40)
            return slug + "-" + sha256Hex(version.encodeToByteArray()).take(8)
        }
    }
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
