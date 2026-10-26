package net.robog.clarity.connector.mail

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.robog.clarity.connector.sdk.ConnectorRuntime
import net.robog.clarity.connector.sdk.ConnectorSettings
import net.robog.clarity.connector.sdk.scoped
import net.robog.clarity.connector.sdk.runConnector
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.noun.Noun
import org.eclipse.angus.mail.imap.IMAPFolder

private val log = KotlinLogging.logger {}

/** How long one sync run keeps the IDLE loop going before it hands back to the SDK (which re-reads the config). */
private val RUN_WINDOW = 10.minutes

/** Longest single IDLE; servers drop idle connections after ~30 minutes, NATs much sooner. */
private val IDLE_SLICE = 4.minutes

fun mailManifest(runtime: ConnectorRuntime) = ConnectorManifest(
    kind = "mail",
    displayName = "Email (IMAP/SMTP)",
    version = "0.1.0",
    produces = setOf(Noun.MESSAGE),
    handles = setOf(ActionKind.SAVE_DRAFT, ActionKind.SEND_EMAIL),
    configSchema = mailConfigSchema,
    sealingKey = runtime.sealingKey,
)

/**
 * Blocks (up to [timeout]) until the server reports a change in [folderName] via IMAP IDLE.
 * Returns true if something changed, false on timeout, null when the server has no IDLE (then the caller polls).
 */
fun idleForChange(access: MailAccess, folderName: String, timeout: Duration): Boolean? {
    val changed = AtomicBoolean(false)
    access.imap().use { store ->
        if (!store.hasCapability("IDLE")) return null
        val folder = store.folder(folderName) as IMAPFolder
        folder.openedUse(READ_ONLY) { f ->
            f.addMessageCountListener(object : jakarta.mail.event.MessageCountAdapter() {
                override fun messagesAdded(e: jakarta.mail.event.MessageCountEvent) { changed.set(true) }
                override fun messagesRemoved(e: jakarta.mail.event.MessageCountEvent) { changed.set(true) }
            })
            val watchdog = Thread {
                try {
                    Thread.sleep(timeout.inWholeMilliseconds)
                    // any command from another thread ends the IDLE
                    f.doCommand { p -> p.simpleCommand("NOOP", null); null }
                } catch (_: InterruptedException) { }
            }.apply { isDaemon = true; start() }
            try {
                f.idle()
            } finally {
                watchdog.interrupt()
            }
        }
    }
    return changed.get()
}

suspend fun main() {
    val settings = ConnectorSettings.fromEnv()
    val runtime = ConnectorRuntime(settings)
    val actions = MailActions(runtime).handlers()
    runConnector(
        manifest = mailManifest(runtime),
        settings = settings,
        actions = actions,
        runtime = runtime,
        test = { config -> testMailAccounts(runtime, config) },
    ) { config ->
        val entries = parseMailAccounts(config, runtime)
        if (entries.isEmpty()) {
            log.info { "mail is not configured yet: no account has been added" }
            return@runConnector
        }
        syncAccounts(entries, state, { push(it) }).forEach { report ->
            report.result.onFailure { log.warn { "mail account '${report.label}' was not synced: ${it.message}" } }
        }
        // Between polls, wait on each server (IDLE) instead of sleeping; an account without IDLE waits for the SDK's interval.
        coroutineScope {
            for (cfg in entries.mapNotNull { it.result.getOrNull() }) launch {
                watchAccount(cfg, MailSync(MailAccess(cfg), state.scoped(cfg.id))) { push(it) }
            }
        }
    }
}

/** Keeps one account in step with its server for up to [RUN_WINDOW]; stops early on a server without IDLE or on an error. */
private suspend fun watchAccount(cfg: MailConfig, sync: MailSync, push: suspend (List<IngestRecord>) -> Unit) {
    val access = MailAccess(cfg)
    val started = TimeSource.Monotonic.markNow()
    try {
        while (started.elapsedNow() < RUN_WINDOW - IDLE_SLICE) {
            val changed = withContext(Dispatchers.IO) { runCatching { idleForChange(access, cfg.folders.first(), IDLE_SLICE) }.getOrElse { log.warn { "IDLE failed for '${cfg.label}': ${it.message}" }; null } }
            when (changed) {
                null -> return
                true -> sync.syncAll(push)
                false -> delay(1.seconds)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "watching mail account '${cfg.label}' failed; the next sync run retries" }
    }
}
