package net.robog.clarity.backend.auth

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Clock
import kotlin.uuid.Uuid
import net.robog.clarity.backend.config.AppConfig
import net.robog.clarity.backend.crypto.sha256
import net.robog.clarity.backend.lifecycle.StartupTask
import net.robog.clarity.backend.persistence.EnrollmentTokens
import net.robog.clarity.backend.persistence.tx
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

private val log = KotlinLogging.logger {}

/** Stores only SHA-256(enrollment token) and answers "is this the token?". */
@Inject
@SingleIn(AppScope::class)
class EnrollmentTokenStore(private val database: Database, private val clock: Clock) {

    suspend fun matches(token: String): Boolean = database.tx {
        !EnrollmentTokens.selectAll().where { EnrollmentTokens.tokenHash eq sha256(token) }.empty()
    }

    /** Makes [token] the one active token (no-op when it already is). */
    suspend fun activate(token: String) {
        val hash = sha256(token)
        database.tx {
            val active = !EnrollmentTokens.selectAll().where { EnrollmentTokens.tokenHash eq hash }.empty()
            if (!active) {
                EnrollmentTokens.deleteAll()
                EnrollmentTokens.insert {
                    it[id] = Uuid.random()
                    it[tokenHash] = hash
                    it[createdAt] = clock.now()
                }
            }
        }
    }
}

/**
 * Ensures an enrollment token exists at boot: the operator-provided one if configured, else the
 * one persisted in `${dataDir}/enrollment-token`, else a freshly generated one (written 0600 and
 * logged exactly once, when created).
 */
@Inject
@ContributesIntoSet(AppScope::class)
class EnrollmentTokenBootstrap(private val config: AppConfig, private val store: EnrollmentTokenStore) : StartupTask {
    override val name = "enrollment-token"

    override suspend fun run() {
        config.enrollment.token?.let { store.activate(it.value); return }

        val file = config.dataDir.resolve("enrollment-token")
        if (file.exists()) {
            store.activate(file.readText().trim())
            return
        }
        val token = generateToken()
        writePrivate(file, token)
        store.activate(token)
        log.info { "generated enrollment token (also written to $file): $token" }
    }

    private fun generateToken(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

    private fun writePrivate(file: Path, content: String) {
        Files.createDirectories(file.parent)
        val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        try {
            Files.createFile(file, permissions)
        } catch (_: UnsupportedOperationException) {
            Files.createFile(file) // non-POSIX filesystem
        }
        Files.writeString(file, content + "\n")
    }
}
