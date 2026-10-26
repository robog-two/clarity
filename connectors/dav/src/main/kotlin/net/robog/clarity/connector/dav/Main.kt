package net.robog.clarity.connector.dav

import io.github.oshai.kotlinlogging.KotlinLogging
import net.robog.clarity.connector.sdk.ConnectorRuntime
import net.robog.clarity.connector.sdk.ConnectorSettings
import net.robog.clarity.connector.sdk.ConnectorTestResult
import net.robog.clarity.connector.sdk.runConnector
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.noun.Noun

private val log = KotlinLogging.logger {}

fun davManifest(runtime: ConnectorRuntime) = ConnectorManifest(
    kind = "dav",
    displayName = "Calendar, contacts and files (CalDAV, CardDAV, WebDAV)",
    version = "0.1.0",
    produces = setOf(Noun.CALENDAR_EVENT, Noun.CONTACT, Noun.DOCUMENT),
    handles = setOf(
        ActionKind.CREATE_EVENT, ActionKind.MOVE_EVENT, ActionKind.RESPOND_TO_INVITE,
        ActionKind.CREATE_REMINDER, ActionKind.READ_DOCUMENT,
    ),
    configSchema = davConfigSchema,
    sealingKey = runtime.sealingKey,
)

/** What the app's "test connection" button runs: sign in, discover, count what is there. */
suspend fun testDavConnection(runtime: ConnectorRuntime, config: ConnectorConfig): ConnectorTestResult {
    val cfg = try { parseDavConfig(config, runtime) } catch (e: ConfigIncomplete) {
        return ConnectorTestResult(false, e.message ?: "The settings are incomplete.")
    }
    return try {
        DavServer(cfg).use { server ->
            val d = server.discover()
            val folders = cfg.webdavFolders.size
            if (d.calendars.isEmpty() && d.addressBooks.isEmpty() && folders == 0) {
                ConnectorTestResult(false, "Signed in, but no calendars or address books were found. Check the server URL.")
            } else {
                ConnectorTestResult(
                    true,
                    "Found ${d.calendars.size} calendars and ${d.addressBooks.size} address books" + if (folders > 0) "; $folders folders to index." else ".",
                    mapOf("calendars" to d.calendars.size, "addressBooks" to d.addressBooks.size, "folders" to folders),
                )
            }
        }
    } catch (e: Exception) {
        ConnectorTestResult(false, "Could not connect: ${e.message ?: e::class.simpleName}")
    }
}

suspend fun main() {
    val settings = ConnectorSettings.fromEnv()
    val runtime = ConnectorRuntime(settings)
    runConnector(
        manifest = davManifest(runtime),
        settings = settings,
        actions = DavActions(runtime).handlers(),
        runtime = runtime,
        test = { config -> testDavConnection(runtime, config) },
    ) { config ->
        val cfg = try { parseDavConfig(config, runtime) } catch (e: ConfigIncomplete) {
            log.info { "dav is not configured yet: ${e.message}" }
            return@runConnector
        }
        DavServer(cfg).use { server ->
            val counts = DavSync(server, cfg, state).syncAll { push(it) }
            log.info { "sync done: $counts" }
        }
    }
}
