package net.robog.clarity.connector.mail

import jakarta.mail.Folder
import jakarta.mail.Session
import java.util.Properties
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

private const val TIMEOUT_MS = "30000"

/** Opens connections for one [MailConfig]. Connections are short-lived; callers close them. */
class MailAccess(val config: MailConfig) {

    private fun imapSession(): Session {
        val protocol = if (config.imapSecurity == Security.SSL) "imaps" else "imap"
        val p = Properties().apply {
            put("mail.store.protocol", protocol)
            put("mail.$protocol.host", config.imapHost)
            put("mail.$protocol.port", config.imapPort.toString())
            put("mail.$protocol.connectiontimeout", TIMEOUT_MS)
            put("mail.$protocol.timeout", TIMEOUT_MS)
            put("mail.$protocol.writetimeout", TIMEOUT_MS)
            put("mail.$protocol.peek", "true") // reading must not set \Seen
            put("mail.$protocol.ssl.checkserveridentity", "true")
            if (config.imapSecurity == Security.STARTTLS) {
                put("mail.imap.starttls.enable", "true")
                put("mail.imap.starttls.required", "true")
            }
            if (config.imapSecurity == Security.NONE) put("mail.imap.starttls.enable", "false")
        }
        return Session.getInstance(p)
    }

    /** A connected IMAP store. */
    fun imap(): IMAPStore {
        val session = imapSession()
        val store = session.getStore(session.getProperty("mail.store.protocol")) as IMAPStore
        store.connect(config.imapHost, config.imapPort, config.username, config.password)
        return store
    }

    /** Session for building messages and for the SMTP transport. */
    fun smtpSession(): Session {
        val protocol = if (config.smtpSecurity == Security.SSL) "smtps" else "smtp"
        val p = Properties().apply {
            put("mail.transport.protocol", protocol)
            put("mail.$protocol.host", config.smtpHost)
            put("mail.$protocol.port", config.smtpPort.toString())
            put("mail.$protocol.connectiontimeout", TIMEOUT_MS)
            put("mail.$protocol.timeout", TIMEOUT_MS)
            put("mail.$protocol.writetimeout", TIMEOUT_MS)
            put("mail.$protocol.auth", "true")
            put("mail.$protocol.ssl.checkserveridentity", "true")
            if (config.smtpSecurity == Security.STARTTLS) {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
            }
        }
        return Session.getInstance(p)
    }

    /** Authenticates against both servers; counts the folders and the messages in the configured ones. Throws the server's error otherwise. */
    fun check(): MailCheck {
        val check = imap().use { store ->
            val messages = store.syncFolders(config.folders).sumOf { it.messageCount.coerceAtLeast(0) }
            MailCheck(store.defaultFolder.list("*").size, messages)
        }
        val session = smtpSession()
        session.getTransport().use { transport -> transport.connect(config.smtpHost, config.smtpPort, config.username, config.password) }
        return check
    }
}

/** What [MailAccess.check] found. */
data class MailCheck(val folders: Int, val messages: Int)

inline fun <T> IMAPStore.use(block: (IMAPStore) -> T): T = try { block(this) } finally { runCatching { close() } }

inline fun <T> jakarta.mail.Transport.use(block: (jakarta.mail.Transport) -> T): T = try { block(this) } finally { runCatching { close() } }

inline fun <T> IMAPFolder.openedUse(mode: Int, block: (IMAPFolder) -> T): T {
    open(mode)
    return try { block(this) } finally { runCatching { if (isOpen) close(false) } }
}

/** The folder with the SPECIAL-USE [attribute] (`\Drafts`, `\Sent`), else the first existing of [names]. */
fun IMAPStore.specialFolder(attribute: String, vararg names: String): IMAPFolder? {
    val all = defaultFolder.list("*").map { it as IMAPFolder }
    all.firstOrNull { f -> f.attributes.any { it.equals(attribute, ignoreCase = true) } }?.let { return it }
    for (name in names) all.firstOrNull { it.fullName.equals(name, ignoreCase = true) }?.let { return it }
    return null
}

/**
 * The folders to sync: the [configured] ones that exist, or, when none are configured, every
 * folder that can hold messages (Sent, Junk, Trash and Gmail's All Mail included: a message seen
 * in several folders is stored once, and the agent can ask for a folder).
 */
fun IMAPStore.syncFolders(configured: List<String>): List<IMAPFolder> =
    if (configured.isEmpty()) {
        defaultFolder.list("*").map { it as IMAPFolder }.filter { it.canHoldMessages() }
    } else {
        configured.mapNotNull { name ->
            folder(name).takeIf { it.exists() } ?: null.also { log.warn { "folder '$name' does not exist; skipping" } }
        }
    }

/** Selectable and able to hold messages (not a `\Noselect` parent such as Gmail's `[Gmail]`). */
private fun IMAPFolder.canHoldMessages(): Boolean =
    (type and Folder.HOLDS_MESSAGES) != 0 &&
        attributes.none { it.equals("\\Noselect", ignoreCase = true) || it.equals("\\NonExistent", ignoreCase = true) }

/** Opens [name] read-only. */
fun IMAPStore.folder(name: String): IMAPFolder = getFolder(name) as IMAPFolder

val READ_ONLY = Folder.READ_ONLY
val READ_WRITE = Folder.READ_WRITE
