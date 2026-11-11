package net.robog.clarity.backend.backup

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import net.robog.clarity.backend.agent.SettingsProvider
import net.robog.clarity.backend.config.AppConfig
import net.robog.clarity.backend.governance.PhoneApprovals
import net.robog.clarity.backend.http.HttpProblem
import net.robog.clarity.backend.vault.VaultStore
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.backup.BackupInfo
import net.robog.clarity.shared.backup.BackupKind
import net.robog.clarity.shared.backup.BackupList
import net.robog.clarity.shared.backup.RollbackAccepted
import net.robog.clarity.shared.backup.RollbackPayload
import net.robog.clarity.shared.backup.RollbackRequest
import net.robog.clarity.shared.backup.RollbackResult
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.time.zoneOrUtc

private val log = KotlinLogging.logger {}

/** Why a backup or rollback request was refused. */
sealed interface BackupError : HttpProblem {
    data class Busy(override val detail: String = "a backup or a rollback is running; try again in a minute") : BackupError {
        override val status = HttpStatusCode.Conflict
        override val title = "Busy"
    }

    data class NotFound(val name: String) : BackupError {
        override val status = HttpStatusCode.NotFound
        override val title = "No such backup"
        override val detail = "there is no backup called '$name'"
    }

    data class Changed(val name: String) : BackupError {
        override val status = HttpStatusCode.Conflict
        override val title = "Backup changed"
        override val detail = "'$name' is not the file that was shown; reload the list"
    }

    data class BadSignature(override val detail: String) : BackupError {
        override val status = HttpStatusCode.Forbidden
        override val title = "Approval not verified"
    }

    data class Replayed(val name: String) : BackupError {
        override val status = HttpStatusCode.Conflict
        override val title = "Signature already used"
        override val detail = "that signature was already used"
    }

    data class Failed(override val detail: String) : BackupError {
        override val status = HttpStatusCode.InternalServerError
        override val title = "Backup failed"
    }

    data object Off : BackupError {
        override val status = HttpStatusCode.NotFound
        override val title = "Backups are off"
        override val detail: String? = "backups are turned off on this server"
    }
}

/** Ends the process. Replaced in tests. */
fun interface ProcessExit {
    fun exit(code: Int)
}

@Inject
@ContributesBinding(AppScope::class)
class SystemExit : ProcessExit {
    override fun exit(code: Int) {
        Runtime.getRuntime().exit(code)
    }
}

/**
 * The whole-database backups and the rollback. One at a time: a backup or rollback holds [lock], and a
 * request that finds it held is told it is busy (the nightly job waits instead).
 */
@Inject
@SingleIn(AppScope::class)
class BackupService(
    private val config: AppConfig,
    private val pg: PgTools,
    private val settings: SettingsProvider,
    private val clock: Clock,
    private val vault: VaultStore,
    private val maintenance: Maintenance,
    private val sql: RollbackSql,
    private val scope: CoroutineScope,
    private val exit: ProcessExit,
) {
    val folder = BackupFolder(config.dataDir.resolve("backups"))
    private val lock = Mutex()
    private val cfg get() = config.backups

    val enabled: Boolean get() = cfg.enabled

    val busy: Boolean get() = lock.isLocked || maintenance.active

    suspend fun list(): BackupList = withContext(Dispatchers.IO) {
        BackupList(backups = folder.list(), busy = busy, lastRollback = readResult())
    }

    /** The 2 am dump for [date] (or "back up now"): holds the lock, waits for it when [wait]. */
    suspend fun backupNightly(date: LocalDate, wait: Boolean): Either<BackupError, BackupInfo> {
        if (!enabled) return BackupError.Off.left()
        if (wait) lock.lock() else if (!lock.tryLock()) return BackupError.Busy().left()
        try {
            val info = dumpTo(BackupNames.nightly(date))
            prune()
            return info.right()
        } catch (e: BackupFailure) {
            return BackupError.Failed(e.message.orEmpty()).left()
        } finally {
            lock.unlock()
        }
    }

    /** "Back up now": today's file on the home clock, replaced by a fresh dump. */
    suspend fun backupNow(): Either<BackupError, BackupInfo> {
        if (!enabled) return BackupError.Off.left()
        if (!lock.tryLock()) return BackupError.Busy().left()
        try {
            val zone = zoneOrUtc(settings.current().homeZone)
            val info = dumpTo(freshName(zone, BackupNames::manual))
            prune()
            return info.right()
        } catch (e: BackupFailure) {
            return BackupError.Failed(e.message.orEmpty()).left()
        } finally {
            lock.unlock()
        }
    }

    suspend fun today(): LocalDate {
        val zone = zoneOrUtc(settings.current().homeZone)
        return clock.now().toLocalDateTime(zone).date
    }

    /** The home-clock days that have a nightly file. */
    fun nightlyDates(): Set<LocalDate> =
        folder.names().mapNotNull { BackupNames.parse(it) }.filter { it.kind == BackupKind.NIGHTLY }.map { it.date }.toSet()

    /** Writes a dump under [name]: complete or not there at all. */
    private suspend fun dumpTo(name: String): BackupInfo = withContext(Dispatchers.IO) {
        val temp = folder.newTemp()
        try {
            pg.dump(temp)
            if (Files.size(temp) == 0L) throw BackupFailure("the backup came out empty")
            folder.publish(temp, name)
        } catch (e: Throwable) {
            folder.discard(temp)
            throw e
        }
        checkNotNull(folder.info(name)) { "the backup $name vanished" }
    }

    private suspend fun prune() {
        filesToDelete(folder.names(), cfg.keepNightly, cfg.keepBeforeRollback, cfg.keepManual, today(), cfg.beforeRollbackDays)
            .forEach { folder.delete(it) }
    }

    /**
     * The signed rollback. Checks the file and the signature, takes a copy of the database as it is now
     * (if that fails nothing changes), then answers and goes on in the background: stops the agent, the
     * scheduler and the outbox, refuses API calls, restores all or nothing, and ends the process so the
     * container runtime starts a fresh backend on the restored data.
     */
    suspend fun rollback(device: DeviceId, request: RollbackRequest): Either<BackupError, RollbackAccepted> {
        if (!enabled) return BackupError.Off.left()
        if (BackupNames.parse(request.name) == null) return BackupError.NotFound(request.name).left()
        if (!lock.tryLock()) return BackupError.Busy().left()
        var handedOver = false
        try {
            val info = withContext(Dispatchers.IO) { folder.info(request.name) } ?: return BackupError.NotFound(request.name).left()
            if (!info.sha256.equals(request.sha256, ignoreCase = true)) return BackupError.Changed(request.name).left()
            val key = vault.approvalKey(device) ?: return BackupError.BadSignature("this phone has no approval key; set up the vault on it first").left()
            if (!PhoneApprovals.verify(key, RollbackPayload.payload(info.name, info.sha256), request.signature)) {
                return BackupError.BadSignature("the signature does not match this backup").left()
            }
            if (!vault.markSignatureUsed(PhoneApprovals.signatureHash(request.signature), signatureSubject(info), device)) {
                return BackupError.Replayed(info.name).left()
            }
            // 1) a copy of now, so the rollback can be undone. If it fails, nothing has changed.
            val zone = zoneOrUtc(settings.current().homeZone)
            val safetyName = freshName(zone, BackupNames::beforeRollback)
            // From here nothing else may write: requests get 503 until the rollback is over (or fails to start).
            maintenance.begin()
            val safety = try {
                dumpTo(safetyName)
            } catch (e: Throwable) {
                maintenance.end()
                if (e !is BackupFailure) throw e
                return BackupError.Failed("a copy of the current data could not be made, so nothing was changed. ${e.message}").left()
            }
            // 2..5) in the background: the lock stays held until the process ends.
            handedOver = true
            scope0.launch { restoreAndExit(info) }
            return RollbackAccepted(safety).right()
        } finally {
            if (!handedOver) lock.unlock()
        }
    }

    // Not the application scope: stopping that one is part of the job.
    private val scope0 = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private fun freshName(zone: TimeZone, make: (kotlinx.datetime.LocalDateTime) -> String): String {
        var at = clock.now()
        while (true) {
            val name = make(at.toLocalDateTime(zone))
            if (!Files.exists(folder.path(name))) return name
            at += 60.seconds
        }
    }

    private suspend fun restoreAndExit(info: BackupInfo) {
        var result: RollbackResult
        try {
            maintenance.begin()
            // The agent, the scheduler, the outbox and every other background service.
            withTimeoutOrNull(STOP_WAIT) { scope.coroutineContext[Job]?.cancelAndJoin() }
            delay(SETTLE)
            sql.capture()
            sql.killOtherConnections()
            pg.restore(folder.path(info.name))
            result = RollbackResult(info.name, clock.now(), ok = true)
            log.info { "rolled back to ${info.name}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.error(e) { "rollback to ${info.name} failed; the data was not changed" }
            val why = (e as? BackupFailure)?.message ?: "the restore did not work"
            result = RollbackResult(info.name, clock.now(), ok = false, message = "$why. Nothing was changed.")
            // A restore that did not finish was undone by Postgres; drop what was copied for it.
            runCatching { sql.dropCarry() }
        }
        writeResult(result)
        // A fresh process on the data as it now is. Non-zero, so `restart: unless-stopped` starts it.
        delay(EXIT_DELAY)
        exit.exit(EXIT_CODE)
    }

    private fun resultFile() = folder.path(RESULT_FILE)

    private fun writeResult(result: RollbackResult) {
        runCatching {
            val temp = folder.newTemp()
            Files.writeString(temp, ClarityJson.encodeToString(result))
            Files.move(temp, resultFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { log.warn(it) { "could not write the rollback result" } }
    }

    private fun readResult(): RollbackResult? = runCatching {
        ClarityJson.decodeFromString<RollbackResult>(Files.readString(resultFile()))
    }.getOrNull()

    companion object {
        const val RESULT_FILE = "rollback-result.json"
        const val EXIT_CODE = 75
        private val STOP_WAIT = 20.seconds
        private val SETTLE = 1.seconds
        private val EXIT_DELAY = 2.seconds

        /** A name-based id for the signature record, so the same file and sum always map to the same subject. */
        fun signatureSubject(info: BackupInfo): String = UUID.nameUUIDFromBytes(
            MessageDigest.getInstance("SHA-256").digest(RollbackPayload.payload(info.name, info.sha256).toByteArray()),
        ).toString()
    }
}
