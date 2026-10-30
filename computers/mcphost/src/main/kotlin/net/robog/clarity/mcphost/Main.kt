package net.robog.clarity.mcphost

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.robog.clarity.connector.sdk.ConnectorRuntime
import net.robog.clarity.connector.sdk.ConnectorSettings
import net.robog.clarity.connector.sdk.ConnectorTestResult
import net.robog.clarity.connector.sdk.items
import net.robog.clarity.connector.sdk.runConnector
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.connector.ConnectorManifest

private val log = KotlinLogging.logger {}

/**
 * The host's own settings (the connector's come from [ConnectorSettings]):
 *
 *   MCPHOST_PORT (8950), MCPHOST_KEY_FILE (/run/secrets/mcphost_key: the backend's bearer key),
 *   MCPHOST_SERVERS_DIR (/data/servers), MCPHOST_PROXY (egress-mcp:3128; unset = no internet for servers),
 *   MCPHOST_EGRESS_DIR (/egress: the proxy's login and allow-list files), MCPHOST_EGRESS_GID (10001: the proxy's group),
 *   MCPHOST_ISOLATION (uid; `none` runs servers as the supervisor's user, for development only),
 *   MCPHOST_LOCAL_DIR (unset; when set, kind `local` may run a command under it: tests and smoke tests).
 */
data class HostSettings(
    val port: Int = 8950,
    val keyFile: String = "/run/secrets/mcphost_key",
    val serversDir: Path = Path.of("/data/servers"),
    val proxyHost: String? = null,
    val proxyPort: Int = 3128,
    val egressDir: Path? = null,
    val egressGid: Int? = null,
    val isolation: Isolation = Isolation.PerUid(),
    val localDir: String? = null,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): HostSettings {
            fun v(name: String) = env[name]?.trim()?.takeIf(String::isNotEmpty)
            val proxy = v("MCPHOST_PROXY")
            return HostSettings(
                port = v("MCPHOST_PORT")?.toInt() ?: 8950,
                keyFile = v("MCPHOST_KEY_FILE") ?: "/run/secrets/mcphost_key",
                serversDir = Path.of(v("MCPHOST_SERVERS_DIR") ?: "/data/servers"),
                proxyHost = proxy?.substringBefore(':'),
                proxyPort = proxy?.substringAfter(':', "3128")?.toInt() ?: 3128,
                egressDir = v("MCPHOST_EGRESS_DIR")?.let(Path::of),
                egressGid = v("MCPHOST_EGRESS_GID")?.toInt(),
                isolation = if (v("MCPHOST_ISOLATION") == "none") Isolation.SameUser else Isolation.PerUid(),
                localDir = v("MCPHOST_LOCAL_DIR"),
            )
        }
    }
}

fun hostManifest(runtime: ConnectorRuntime) = ConnectorManifest(
    kind = "mcp-host",
    displayName = "MCP servers",
    version = "0.1.0",
    produces = emptySet(),
    handles = emptySet(),
    configSchema = hostConfigSchema,
    sealingKey = runtime.sealingKey,
)

/** Every server item of [config], validated, secrets opened with [runtime]. */
fun parseServers(config: ConnectorConfig, runtime: ConnectorRuntime, localDir: String?): List<ItemResult> =
    parseItems(config.items(SERVERS).filter { !it.legacy }, { item, property -> runtime.secret(config, item, property) }, localDir)

/** Refuses a secret file that any other user could read: servers run as other uids in this container. */
private fun checkPrivate(file: String) {
    val path = Path.of(file)
    if (!path.exists()) return
    val perms = runCatching { Files.getPosixFilePermissions(path) }.getOrNull() ?: return
    if (PosixFilePermission.OTHERS_READ in perms) {
        log.error { "$file is readable by every user; mount it with mode 0400 (see deploy/compose.yaml) so servers cannot read it" }
        exitProcess(1)
    }
}

private fun ownUid(): Int = Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Int

fun main() {
    // The JDK refuses Basic proxy auth for CONNECT unless this is cleared (also set in the start script).
    System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "")
    System.setProperty("jdk.http.auth.proxying.disabledSchemes", "")
    val settings = ConnectorSettings.fromEnv()
    val host = HostSettings.fromEnv()
    val key = runCatching { Files.readString(Path.of(host.keyFile)).trim() }.getOrNull()?.ifEmpty { null }
    if (key == null) {
        log.error { "No bearer key in ${host.keyFile}. Create the mcphost_key secret (deploy/init-secrets.sh)." }
        exitProcess(1)
    }
    if (host.isolation is Isolation.PerUid) {
        if (ownUid() != 0) {
            log.error { "per-server uids need the supervisor to run as root inside its (rootless) container; set MCPHOST_ISOLATION=none only for development" }
            exitProcess(1)
        }
        checkPrivate(host.keyFile)
        checkPrivate(System.getenv("CLARITY_CONNECTOR_KEY_FILE") ?: ConnectorSettings.DEFAULT_KEY_FILE)
    }
    // /data and the homes' parent: others may pass through to their own home, not list them.
    Files.createDirectories(host.serversDir)
    for (dir in listOfNotNull(host.serversDir.parent, host.serversDir)) {
        runCatching { Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx--x--x")) }
    }
    Files.createDirectories(settings.stateDir)
    Files.setPosixFilePermissions(settings.stateDir, PosixFilePermissions.fromString("rwx------"))

    val runtime = ConnectorRuntime(settings)
    val state = HostState(settings.stateDir)
    val runner = ProcessRunner(host.isolation, if (host.isolation is Isolation.SameUser) System.getenv("PATH") ?: SYSTEM_PATH else SYSTEM_PATH)
    val egress = EgressAccess(host.egressDir, host.proxyHost, host.proxyPort, host.egressGid)
    val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val supervisor = Supervisor(
        state = state,
        homes = Homes(host.serversDir, runner, state),
        runner = runner,
        installer = RegistryInstaller(runner, egress),
        egress = egress,
        scope = scope,
        childPath = if (host.isolation is Isolation.SameUser) System.getenv("PATH") ?: SYSTEM_PATH else SYSTEM_PATH,
    )
    supervisor.init()
    log.info {
        "mcp-host on :${host.port}, isolation ${if (host.isolation is Isolation.PerUid) "per-server uid" else "NONE (development)"}, " +
            "egress ${host.proxyHost?.let { "$it:${host.proxyPort}" } ?: "none (servers have no internet)"}"
    }
    val server = embeddedServer(CIO, port = host.port, host = "0.0.0.0") { hostModule(supervisor, key) }
    server.start(wait = false)
    Runtime.getRuntime().addShutdownHook(Thread({ runBlocking { withContext(NonCancellable) { supervisor.stopAll() } } }, "mcphost-stop"))

    runBlocking {
        coroutineScope {
            launch { supervisor.sweepIdle() }
            runConnector(
                manifest = hostManifest(runtime),
                settings = settings,
                runtime = runtime,
                test = { config ->
                    supervisor.reconcile(parseServers(config, runtime, host.localDir))
                    val (ok, message) = supervisor.summary()
                    ConnectorTestResult(ok, message)
                },
            ) { config ->
                supervisor.reconcile(parseServers(config, runtime, host.localDir))
            }
        }
    }
}
