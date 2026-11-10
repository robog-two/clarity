package net.robog.clarity.backend.backup

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.robog.clarity.backend.config.AppConfig

private val log = KotlinLogging.logger {}

/** A dump or restore that did not work. [message] is short, safe to show, and never has the password. */
class BackupFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The two PostgreSQL client programs. Faked in tests. */
interface PgTools {
    /** Writes the whole database to [target] as a compressed custom-format dump (`pg_dump -Fc`). */
    suspend fun dump(target: Path)

    /** Restores [source] over the database, all or nothing (`pg_restore --clean --if-exists --single-transaction --no-owner`). */
    suspend fun restore(source: Path)
}

/** Where the database is: taken from the JDBC URL the backend itself uses. */
data class PgTarget(val host: String, val port: Int, val database: String, val user: String, val sslMode: String?) {
    companion object {
        private val url = Regex("""^jdbc:postgresql://([^/:?]+)(?::(\d+))?/([^?]+)(?:\?(.*))?$""")

        fun parse(jdbcUrl: String, user: String): PgTarget {
            val m = url.matchEntire(jdbcUrl) ?: throw BackupFailure("the database address is not one the backup can use")
            val params = m.groupValues[4].split('&').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            return PgTarget(m.groupValues[1], m.groupValues[2].toIntOrNull() ?: 5432, m.groupValues[3], user, params["sslmode"])
        }
    }
}

/**
 * Runs `pg_dump` and `pg_restore` as processes. The password goes to them in the environment
 * (`PGPASSWORD`), never on the command line, and nothing they print is passed on beyond its last
 * few lines with the password cut out.
 */
@Inject
@ContributesBinding(AppScope::class)
class ProcessPgTools(config: AppConfig) : PgTools {
    private val backups = config.backups
    private val target = PgTarget.parse(config.database.url, config.database.user)
    private val password = config.database.password

    override suspend fun dump(target: Path) {
        run(
            listOf(
                backups.pgDump, "--format=custom", "--no-password",
                // the rollback's carry-over tables are not part of the data
                "--exclude-schema=$CARRY_SCHEMA",
            ) + connection(),
            stdout = target,
            what = "backup",
        )
    }

    override suspend fun restore(source: Path) {
        run(
            listOf(
                backups.pgRestore, "--clean", "--if-exists", "--single-transaction", "--no-owner", "--no-password",
                "--exit-on-error",
            ) + connection() + source.toString(),
            stdout = null,
            what = "restore",
        )
    }

    private fun connection() = listOf("--host=${target.host}", "--port=${target.port}", "--username=${target.user}", "--dbname=${target.database}")

    private suspend fun run(command: List<String>, stdout: Path?, what: String) = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(command)
        builder.environment().apply {
            remove("PGPASSFILE")
            password?.value?.let { put("PGPASSWORD", it) }
            put("PGCONNECT_TIMEOUT", "15")
            target.sslMode?.let { put("PGSSLMODE", it) }
        }
        if (stdout != null) builder.redirectOutput(ProcessBuilder.Redirect.to(stdout.toFile())) else builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        builder.redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
        val process = try {
            builder.start()
        } catch (e: java.io.IOException) {
            throw BackupFailure("the $what program (${command.first()}) could not be started", e)
        }
        // pg_dump and pg_restore write only short messages to stderr; read them as they come so the pipe never fills.
        val errors = StringBuilder()
        val reader = Thread({
            process.errorStream.bufferedReader().useLines { lines -> lines.forEach { synchronized(errors) { if (errors.length < MAX_ERROR_CHARS) errors.appendLine(it) } } }
        }, "pg-stderr").apply { isDaemon = true; start() }
        val finished = process.waitFor(backups.timeout.inWholeSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw BackupFailure("the $what took too long and was stopped")
        }
        reader.join(2_000)
        if (process.exitValue() != 0) {
            val detail = synchronized(errors) { errors.toString() }.trim().lines().takeLast(3).joinToString(" ").take(300)
                .let { scrub(it) }
            log.warn { "pg $what failed (exit ${process.exitValue()}): $detail" }
            throw BackupFailure("the $what failed" + if (detail.isNotEmpty()) ": $detail" else "")
        }
    }

    private fun scrub(text: String): String = password?.value?.takeIf { it.length >= 4 }?.let { text.replace(it, "***") } ?: text

    private companion object {
        const val MAX_ERROR_CHARS = 8_000
    }
}
