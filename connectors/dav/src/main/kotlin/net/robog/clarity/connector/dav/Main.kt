package net.robog.clarity.connector.dav

import io.github.oshai.kotlinlogging.KotlinLogging
import net.robog.clarity.connector.sdk.ConnectorRuntime
import net.robog.clarity.connector.sdk.ConnectorSettings
import net.robog.clarity.connector.sdk.runConnector
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

suspend fun main() {
    val settings = ConnectorSettings.fromEnv()
    val runtime = ConnectorRuntime(settings)
    runConnector(
        manifest = davManifest(runtime),
        settings = settings,
        actions = DavActions(runtime).handlers(),
        runtime = runtime,
        test = { config -> testDavAccounts(runtime, config) },
    ) { config ->
        val entries = parseDavAccounts(config, runtime)
        if (entries.isEmpty()) {
            log.info { "dav is not configured yet: no account has been added" }
            return@runConnector
        }
        syncAccounts(entries, state, { push(it) }).forEach { report ->
            report.result.fold(
                onSuccess = { log.info { "sync of '${report.label}' done: $it" } },
                onFailure = { log.warn { "calendar account '${report.label}' was not synced: ${it.message}" } },
            )
        }
    }
}
