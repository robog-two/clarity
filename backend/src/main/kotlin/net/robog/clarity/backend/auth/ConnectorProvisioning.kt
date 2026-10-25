package net.robog.clarity.backend.auth

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import net.robog.clarity.backend.config.AppConfig
import net.robog.clarity.backend.connectors.ConnectorRegistry
import net.robog.clarity.backend.crypto.sha256
import net.robog.clarity.backend.lifecycle.StartupTask
import net.robog.clarity.shared.ConnectorId

private val log = KotlinLogging.logger {}

private val SECRET_FILE = Regex("^connector_(.+)_key$")

/**
 * The connector id encoded in a secret file name (`connector_imap_personal_key` -> `imap-personal`),
 * or null if the name is not a provisioned-connector secret or yields an invalid id.
 */
fun connectorIdFromSecretFile(fileName: String): ConnectorId? {
    val raw = SECRET_FILE.matchEntire(fileName)?.groupValues?.get(1)?.replace('_', '-') ?: return null
    return if (ConnectorId.PATTERN.matches(raw)) ConnectorId(raw) else null
}

/** Registers every `${secretsDir}/connector_<id>_key` file as a provisioned connector (idempotent). */
@Inject
@ContributesIntoSet(AppScope::class)
class ConnectorProvisioning(private val config: AppConfig, private val registry: ConnectorRegistry) : StartupTask {
    override val name = "connector-provisioning"

    override suspend fun run() {
        val dir: Path = config.connectors.secretsDir
        if (!dir.exists() || !dir.isDirectory()) {
            log.info { "no secrets directory at $dir; no connectors provisioned" }
            return
        }
        dir.listDirectoryEntries("connector_*_key").sortedBy { it.name }.forEach { file ->
            val id = connectorIdFromSecretFile(file.name)
            val key = runCatching { file.readText().trim() }.getOrNull()
            when {
                id == null -> log.warn { "ignoring secret ${file.name}: not a valid connector id" }
                key.isNullOrEmpty() -> log.warn { "ignoring secret ${file.name}: empty or unreadable" }
                else -> {
                    registry.provision(id, sha256(key))
                    log.info { "provisioned connector $id" }
                }
            }
        }
    }
}
