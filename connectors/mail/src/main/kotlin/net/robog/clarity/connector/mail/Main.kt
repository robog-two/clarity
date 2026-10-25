package net.robog.clarity.connector.mail

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.robog.clarity.connector.sdk.ConnectorRuntime
import net.robog.clarity.connector.sdk.ConnectorSettings
import net.robog.clarity.connector.sdk.ConnectorTestResult
import net.robog.clarity.connector.sdk.runConnector
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.connector.ConnectorConfig
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

/** What the app's "test connection" button runs: log in to IMAP and SMTP and count the folders. */
suspend fun testMailConnection(runtime: ConnectorRuntime, config: ConnectorConfig): ConnectorTestResult {
    val cfg = try { parseMailConfig(config, runtime) } catch (e: ConfigIncomplete) {
        return ConnectorTestResult(false, e.message ?: "The mail settings are incomplete.")
    }
    return withContext(Dispatchers.IO) {
        try {
            val folders = MailAccess(cfg).check()
            ConnectorTestResult(true, "Signed in to ${cfg.imapHost} and ${cfg.smtpHost} as ${cfg.username}; $folders folders.", mapOf("folders" to folders))
        } catch (e: Exception) {
            ConnectorTestResult(false, "Could not connect: ${e.message ?: e::class.simpleName}")
        }
    }
}

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
        test = { config -> testMailConnection(runtime, config) },
    ) { config ->
        val cfg = try { parseMailConfig(config, runtime) } catch (e: ConfigIncomplete) {
            log.info { "mail is not configured yet: ${e.message}" }
            return@runConnector
        }
        val access = MailAccess(cfg)
        val sync = MailSync(access, state)
        suspend fun runOnce() { sync.syncAll { push(it) } }

        runOnce()
        // Between polls, wait on the server (IDLE) instead of sleeping; fall back to the SDK's interval without it.
        val started = TimeSource.Monotonic.markNow()
        while (started.elapsedNow() < RUN_WINDOW - IDLE_SLICE) {
            val changed = withContext(Dispatchers.IO) { runCatching { idleForChange(access, cfg.folders.first(), IDLE_SLICE) }.getOrElse { log.warn { "IDLE failed: ${it.message}" }; null } }
            when (changed) {
                null -> return@runConnector // server without IDLE (or a connection problem): the SDK interval takes over
                true -> runOnce()
                false -> delay(1.seconds)
            }
        }
    }
}
