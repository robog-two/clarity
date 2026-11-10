package net.robog.clarity.backend.backup

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import net.robog.clarity.shared.backup.BackupInfo
import net.robog.clarity.shared.backup.BackupKind

/** What a backup file's name says. */
data class ParsedBackupName(val kind: BackupKind, val date: LocalDate, val sortKey: String)

/**
 * The names of backup files: `clarity-YYYY-MM-DD.dump` (nightly) and
 * `clarity-YYYY-MM-DDTHHMM-before-rollback.dump` and `clarity-YYYY-MM-DDTHHMM-manual.dump`. Pure. Anything else in the folder is not a backup,
 * and a name from a request is only ever used if it matches here (no path can sneak in).
 */
object BackupNames {
    private val nightly = Regex("""^clarity-(\d{4}-\d{2}-\d{2})\.dump$""")
    private val manual = Regex("""^clarity-(\d{4}-\d{2}-\d{2})T(\d{4})-manual\.dump$""")
    private val safety = Regex("""^clarity-(\d{4}-\d{2}-\d{2})T(\d{4})-before-rollback\.dump$""")

    fun nightly(date: LocalDate) = "clarity-$date.dump"

    fun beforeRollback(at: LocalDateTime): String {
        val hhmm = "%02d%02d".format(at.hour, at.minute)
        return "clarity-${at.date}T$hhmm-before-rollback.dump"
    }

    fun manual(at: LocalDateTime): String {
        val hhmm = "%02d%02d".format(at.hour, at.minute)
        return "clarity-${at.date}T$hhmm-manual.dump"
    }

    fun parse(name: String): ParsedBackupName? {
        nightly.matchEntire(name)?.let { m ->
            val date = runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull() ?: return null
            return ParsedBackupName(BackupKind.NIGHTLY, date, "${m.groupValues[1]}T0000")
        }
        manual.matchEntire(name)?.let { m ->
            val date = runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull() ?: return null
            return ParsedBackupName(BackupKind.MANUAL, date, "${m.groupValues[1]}T${m.groupValues[2]}")
        }
        safety.matchEntire(name)?.let { m ->
            val date = runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull() ?: return null
            return ParsedBackupName(BackupKind.BEFORE_ROLLBACK, date, "${m.groupValues[1]}T${m.groupValues[2]}")
        }
        return null
    }
}

/**
 * Which files go: keeps the [keepNightly] newest nightly ones the [keepManual] newest by-hand ones and the [keepBeforeRollback] newest
 * before-rollback ones (newest by the date or time in the name), returns the names of the rest.
 * Files that are not backups are never in the answer. Pure.
 */
fun filesToDelete(names: Collection<String>, keepNightly: Int, keepBeforeRollback: Int, keepManual: Int = 3): List<String> {
    val parsed = names.mapNotNull { n -> BackupNames.parse(n)?.let { n to it } }
    fun surplus(kind: BackupKind, keep: Int) =
        parsed.filter { it.second.kind == kind }.sortedByDescending { it.second.sortKey }.drop(keep.coerceAtLeast(0)).map { it.first }
    return surplus(BackupKind.NIGHTLY, keepNightly) + surplus(BackupKind.MANUAL, keepManual) + surplus(BackupKind.BEFORE_ROLLBACK, keepBeforeRollback)
}

/**
 * The backup folder (`<dataDir>/backups`, mode 0700, files 0600). A file appears only when it is
 * complete: it is written under a temporary name and renamed. SHA-256 sums are remembered per file
 * (size and modification time) so a listing does not read every dump again.
 */
class BackupFolder(val dir: Path) {
    private val sums = ConcurrentHashMap<String, Triple<Long, Long, String>>()

    fun ensure() {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(DIR_MODE))
        }
        runCatching { Files.setPosixFilePermissions(dir, DIR_MODE) }
        // Leftovers of a dump that was cut short.
        Files.newDirectoryStream(dir, "$TEMP_PREFIX*").use { s -> s.forEach { runCatching { Files.deleteIfExists(it) } } }
    }

    fun path(name: String): Path = dir.resolve(name)

    /** A new empty private temporary file next to the final one. */
    fun newTemp(): Path {
        ensure()
        val temp = dir.resolve("$TEMP_PREFIX${java.util.UUID.randomUUID()}")
        Files.createFile(temp, PosixFilePermissions.asFileAttribute(FILE_MODE))
        return temp
    }

    /** Puts a complete [temp] file in place under [name] (replacing an older one of that name). */
    fun publish(temp: Path, name: String): Path {
        val target = path(name)
        runCatching { Files.setPosixFilePermissions(temp, FILE_MODE) }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        sums.remove(name)
        return target
    }

    fun discard(temp: Path) {
        runCatching { Files.deleteIfExists(temp) }
    }

    fun delete(name: String) {
        if (BackupNames.parse(name) == null) return
        runCatching { Files.deleteIfExists(path(name)) }
        sums.remove(name)
    }

    fun names(): List<String> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.newDirectoryStream(dir).use { s -> s.map { it.fileName.toString() }.filter { BackupNames.parse(it) != null } }
    }

    /** The backup called [name], or null when there is none. Reads the file once to sum it, then remembers. */
    fun info(name: String): BackupInfo? {
        val parsed = BackupNames.parse(name) ?: return null
        val file = path(name)
        val attrs = try {
            Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java)
        } catch (_: IOException) {
            return null
        }
        if (!attrs.isRegularFile) return null
        val size = attrs.size()
        val modified = attrs.lastModifiedTime().toMillis()
        val cached = sums[name]
        val sha = if (cached != null && cached.first == size && cached.second == modified) cached.third
        else sha256(file).also { sums[name] = Triple(size, modified, it) }
        return BackupInfo(
            name = name,
            kind = parsed.kind,
            date = parsed.date.toString(),
            createdAt = Instant.fromEpochMilliseconds(modified),
            sizeBytes = size,
            sha256 = sha,
        )
    }

    /** Every backup, newest first (by the date or time in the name). */
    fun list(): List<BackupInfo> =
        names().sortedByDescending { BackupNames.parse(it)!!.sortKey }.mapNotNull { info(it) }

    companion object {
        const val TEMP_PREFIX = ".tmp-"
        private val DIR_MODE = PosixFilePermissions.fromString("rwx------")
        private val FILE_MODE = PosixFilePermissions.fromString("rw-------")

        fun sha256(file: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
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
