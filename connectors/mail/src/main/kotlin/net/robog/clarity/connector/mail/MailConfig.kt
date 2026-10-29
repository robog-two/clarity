package net.robog.clarity.connector.mail

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.robog.clarity.connector.sdk.ConfigItem
import net.robog.clarity.connector.sdk.ConnectorRuntime
import net.robog.clarity.connector.sdk.items
import net.robog.clarity.shared.connector.ConnectorAccount
import net.robog.clarity.shared.connector.ConnectorConfig

/** Transport security of an IMAP or SMTP connection. */
enum class Security(val wire: String) {
    SSL("ssl"), STARTTLS("starttls"), NONE("none");

    companion object {
        fun parse(raw: String?, default: Security): Security = entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: default
    }
}

/** The "Mail history" choices: how far back a folder's mail is imported. [days] null is everything. */
enum class MailHistory(val wire: String, val title: String, val days: Int?) {
    DAYS_30("30d", "30 days", 30),
    DAYS_90("90d", "90 days", 90),
    YEAR("1y", "1 year", 365),
    ALL("all", "All time", null),
    ;

    companion object {
        val DEFAULT = DAYS_30

        fun parse(raw: String?): MailHistory? = entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) }
    }
}

/**
 * Days of mail to import for [item]: its "Mail history" choice (null for all time); without one,
 * the `initialWindowDays` of configs saved before the choice existed, else [MailHistory.DEFAULT].
 */
fun historyDays(item: ConfigItem): Int? {
    MailHistory.parse(item.text("history"))?.let { return it.days }
    return (item.int("initialWindowDays") ?: MailHistory.DEFAULT.days!!).coerceIn(1, 36500)
}

/** Name of the config collection that holds the mail accounts. */
const val ACCOUNTS = "accounts"

/** Id of the account a legacy flat config is read as. */
const val DEFAULT_ACCOUNT = "default"

/** One mail account: its servers, login and the folders to sync. */
data class MailConfig(
    val imapHost: String,
    val imapPort: Int,
    val imapSecurity: Security,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecurity: Security,
    val username: String,
    val password: String,
    /** The address mail is sent from; defaults to the username when that is an address. */
    val fromAddress: String,
    val fromName: String?,
    val folders: List<String>,
    /** Days of mail to import, counted back from now; null imports everything. A longer history later backfills. */
    val historyDays: Int?,
    /** Stable id of the account ([DEFAULT_ACCOUNT] for a legacy flat config); part of every source id. */
    val id: String = DEFAULT_ACCOUNT,
    /** What the person called the account ("Work"). */
    val label: String = id,
) {
    override fun toString(): String = "MailConfig($label[$id], imap=$imapHost:$imapPort, smtp=$smtpHost:$smtpPort, username=$username, password=<redacted>, folders=$folders, historyDays=${historyDays ?: "all"})"
}

/** Why the saved configuration cannot be used yet; the message is shown in the app. */
class ConfigIncomplete(message: String) : RuntimeException(message)

/** An account of the saved config: its settings, or why they cannot be used yet. */
data class MailAccountEntry(val id: String, val label: String, val result: Result<MailConfig>)

/**
 * Reads every account of [config] (passwords opened with [runtime]); an unusable account is
 * reported in its entry and does not hide the others. Without an `accounts` collection the old
 * flat fields are read as the single account [DEFAULT_ACCOUNT].
 */
fun parseMailAccounts(config: ConnectorConfig, runtime: ConnectorRuntime): List<MailAccountEntry> =
    config.items(ACCOUNTS).map { item ->
        val result = runCatching { parseMailAccount(item, config, runtime) }.recoverCatching {
            throw if (it is ConfigIncomplete && !item.legacy) ConfigIncomplete("${item.label}: ${it.message}") else it
        }
        MailAccountEntry(item.id, item.label, result)
    }

/** The usable accounts of [config]; fails with the first problem when there is none. */
fun usableMailAccounts(config: ConnectorConfig, runtime: ConnectorRuntime): List<MailConfig> {
    val entries = parseMailAccounts(config, runtime)
    if (entries.isEmpty()) throw ConfigIncomplete("No mail account has been added yet")
    val usable = entries.mapNotNull { it.result.getOrNull() }
    return usable.ifEmpty { throw entries.first().result.exceptionOrNull()!! }
}

/**
 * The accounts of [config] as the backend may know them: label and send-from address (the
 * `fromAddress`, else a username that is an address). No secret is read; an item without an
 * address is left out.
 */
fun mailAccountList(config: ConnectorConfig): List<ConnectorAccount> =
    config.items(ACCOUNTS).mapNotNull { item ->
        val address = item.text("fromAddress") ?: item.text("username")?.takeIf { '@' in it } ?: return@mapNotNull null
        ConnectorAccount(item.label, address)
    }

/**
 * Reads one account [item] (password opened with [runtime]). The password must be sealed;
 * everything else is plain.
 *
 * @throws ConfigIncomplete when a required field is missing.
 */
fun parseMailAccount(item: ConfigItem, config: ConnectorConfig, runtime: ConnectorRuntime): MailConfig {
    fun required(name: String, label: String): String = item.text(name) ?: throw ConfigIncomplete("$label is not set")
    fun port(name: String, security: Security, ssl: Int, plain: Int): Int = item.int(name) ?: if (security == Security.SSL) ssl else plain

    val username = required("username", "Username")
    val password = runtime.secret(config, item, "password") ?: throw ConfigIncomplete("Password is not set")
    val imapSecurity = Security.parse(item.text("imapSecurity"), Security.SSL)
    val smtpSecurity = Security.parse(item.text("smtpSecurity"), Security.STARTTLS)
    val fromAddress = item.text("fromAddress") ?: username.takeIf { '@' in it } ?: throw ConfigIncomplete("From address is not set (the username is not an email address)")
    return MailConfig(
        imapHost = required("imapHost", "IMAP host"),
        imapPort = port("imapPort", imapSecurity, 993, 143),
        imapSecurity = imapSecurity,
        smtpHost = required("smtpHost", "SMTP host"),
        smtpPort = port("smtpPort", smtpSecurity, 465, 587),
        smtpSecurity = smtpSecurity,
        username = username,
        password = password,
        fromAddress = fromAddress,
        fromName = item.text("fromName"),
        folders = item.list("folders"), // empty: every folder
        historyDays = historyDays(item),
        id = item.id,
        label = item.label,
    )
}

private fun securityProperty(title: String, default: String) = buildJsonObject {
    put("type", "string")
    put("title", title)
    putJsonArray("enum") { Security.entries.forEach { add(JsonPrimitive(it.wire)) } }
    putJsonArray("x-enum-titles") { listOf("SSL/TLS", "STARTTLS", "None").forEach { add(JsonPrimitive(it)) } }
    put("default", default)
}

private fun preset(title: String, description: String, imapHost: String, smtpHost: String, smtpPort: Int, smtpSecurity: String) = buildJsonObject {
    put("title", title)
    put("description", description)
    putJsonObject("values") {
        put("label", title)
        put("imapHost", imapHost); put("imapPort", 993); put("imapSecurity", "ssl")
        put("smtpHost", smtpHost); put("smtpPort", smtpPort); put("smtpSecurity", smtpSecurity)
    }
}

private val mailPresets = JsonArray(listOf(
    preset("iCloud", "Use an app-specific password from account.apple.com.", "imap.mail.me.com", "smtp.mail.me.com", 587, "starttls"),
    preset("Fastmail", "Use an app password from Fastmail's settings.", "imap.fastmail.com", "smtp.fastmail.com", 465, "ssl"),
    preset("Gmail", "Use an app password. Google offers them only with 2-step verification turned on.", "imap.gmail.com", "smtp.gmail.com", 465, "ssl"),
))

private fun example(text: String) = JsonArray(listOf(JsonPrimitive(text)))

private val mailAccountItem = buildJsonObject {
    put("type", "object")
    putJsonArray("required") { listOf("label", "imapHost", "smtpHost", "username", "password").forEach { add(JsonPrimitive(it)) } }
    putJsonObject("properties") {
        putJsonObject("id") { put("type", "string"); put("readOnly", true) }
        putJsonObject("label") { put("type", "string"); put("title", "Name"); put("description", "How you recognise this account."); put("examples", example("Work")) }
        putJsonObject("imapHost") { put("type", "string"); put("title", "IMAP server"); put("examples", example("imap.example.com")) }
        putJsonObject("imapPort") { put("type", "integer"); put("title", "IMAP port"); put("default", 993); put("minimum", 1); put("maximum", 65535) }
        put("imapSecurity", securityProperty("IMAP security", "ssl"))
        putJsonObject("smtpHost") { put("type", "string"); put("title", "SMTP server"); put("examples", example("smtp.example.com")) }
        putJsonObject("smtpPort") { put("type", "integer"); put("title", "SMTP port"); put("default", 587); put("minimum", 1); put("maximum", 65535) }
        put("smtpSecurity", securityProperty("SMTP security", "starttls"))
        putJsonObject("username") { put("type", "string"); put("title", "Username"); put("examples", example("you@example.com")) }
        putJsonObject("password") { put("type", "string"); put("title", "Password"); put("format", "password"); put("writeOnly", true); put("x-secret", true) }
        putJsonObject("fromAddress") { put("type", "string"); put("format", "email"); put("title", "Send from"); put("description", "Only needed when the username is not an email address.") }
        putJsonObject("fromName") { put("type", "string"); put("title", "Your name on outgoing mail") }
        putJsonObject("folders") {
            put("type", "array"); put("title", "Folders to sync")
            put("description", "Leave empty to sync every folder, including Sent and Junk.")
            putJsonObject("items") { put("type", "string") }
            put("examples", example("INBOX"))
        }
        putJsonObject("history") {
            put("type", "string"); put("title", "Mail history")
            put("description", "How far back to import mail. A longer history fetches the older mail; a shorter one deletes nothing.")
            putJsonArray("enum") { MailHistory.entries.forEach { add(JsonPrimitive(it.wire)) } }
            putJsonArray("x-enum-titles") { MailHistory.entries.forEach { add(JsonPrimitive(it.title)) } }
            put("default", MailHistory.DEFAULT.wire)
        }
    }
}

/**
 * JSON Schema the app renders as the settings form: a collection of accounts. `password` is a
 * secret the app seals per item (`accounts/<id>/password`).
 */
val mailConfigSchema: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonArray("required") { add(JsonPrimitive(ACCOUNTS)) }
    putJsonObject("properties") {
        putJsonObject(ACCOUNTS) {
            put("type", "array")
            put("title", "Mail accounts")
            put("minItems", 1)
            put("x-item-title", "label")
            put("x-item-subtitle", "username")
            put("x-presets", mailPresets)
            put("items", mailAccountItem)
        }
    }
}
