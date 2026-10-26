package net.robog.clarity.connector.mail

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.robog.clarity.connector.sdk.ConnectorRuntime
import net.robog.clarity.shared.connector.ConnectorConfig

/** Transport security of an IMAP or SMTP connection. */
enum class Security(val wire: String) {
    SSL("ssl"), STARTTLS("starttls"), NONE("none");

    companion object {
        fun parse(raw: String?, default: Security): Security = entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: default
    }
}

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
    val initialWindowDays: Int,
) {
    override fun toString(): String = "MailConfig(imap=$imapHost:$imapPort, smtp=$smtpHost:$smtpPort, username=$username, password=<redacted>, folders=$folders)"
}

/** Why the saved configuration cannot be used yet; the message is shown in the app. */
class ConfigIncomplete(message: String) : RuntimeException(message)

/**
 * Reads [config] (password opened with [runtime]). The password field is `password` and must be
 * sealed; everything else is plain.
 *
 * @throws ConfigIncomplete when a required field is missing.
 */
fun parseMailConfig(config: ConnectorConfig, runtime: ConnectorRuntime): MailConfig {
    val v = config.values
    fun text(name: String): String? = (v[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf(String::isNotEmpty)
    fun required(name: String, label: String): String = text(name) ?: throw ConfigIncomplete("$label is not set")
    fun port(name: String, security: Security, ssl: Int, plain: Int): Int =
        ((v[name] as? JsonPrimitive)?.intOrNull ?: text(name)?.toIntOrNull()) ?: if (security == Security.SSL) ssl else plain

    val username = required("username", "Username")
    val password = runtime.secret(config, "password") ?: throw ConfigIncomplete("Password is not set")
    val imapSecurity = Security.parse(text("imapSecurity"), Security.SSL)
    val smtpSecurity = Security.parse(text("smtpSecurity"), Security.STARTTLS)
    val fromAddress = text("fromAddress") ?: username.takeIf { '@' in it } ?: throw ConfigIncomplete("From address is not set (the username is not an email address)")
    val folders = (v["folders"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }
        ?: text("folders")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
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
        fromName = text("fromName"),
        folders = folders?.takeIf { it.isNotEmpty() } ?: listOf("INBOX"),
        initialWindowDays = ((v["initialWindowDays"] as? JsonPrimitive)?.intOrNull ?: 30).coerceIn(1, 3650),
    )
}

/** JSON Schema the app renders as the settings form; `password` is marked secret so the app seals it. */
val mailConfigSchema: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonArray("required") { add(JsonPrimitive("imapHost")); add(JsonPrimitive("smtpHost")); add(JsonPrimitive("username")); add(JsonPrimitive("password")) }
    putJsonObject("properties") {
        putJsonObject("imapHost") { put("type", "string"); put("title", "IMAP server"); put("examples", JsonArray(listOf(JsonPrimitive("imap.example.com")))) }
        putJsonObject("imapPort") { put("type", "integer"); put("title", "IMAP port"); put("default", 993) }
        putJsonObject("imapSecurity") { put("type", "string"); put("title", "IMAP security"); putJsonArray("enum") { Security.entries.forEach { add(JsonPrimitive(it.wire)) } }; put("default", "ssl") }
        putJsonObject("smtpHost") { put("type", "string"); put("title", "SMTP server"); put("examples", JsonArray(listOf(JsonPrimitive("smtp.example.com")))) }
        putJsonObject("smtpPort") { put("type", "integer"); put("title", "SMTP port"); put("default", 587) }
        putJsonObject("smtpSecurity") { put("type", "string"); put("title", "SMTP security"); putJsonArray("enum") { Security.entries.forEach { add(JsonPrimitive(it.wire)) } }; put("default", "starttls") }
        putJsonObject("username") { put("type", "string"); put("title", "Username") }
        putJsonObject("password") { put("type", "string"); put("title", "Password"); put("format", "password"); put("x-secret", true) }
        putJsonObject("fromAddress") { put("type", "string"); put("title", "Send from (if the username is not an address)") }
        putJsonObject("fromName") { put("type", "string"); put("title", "Your name on outgoing mail") }
        putJsonObject("folders") {
            put("type", "array"); put("title", "Folders to sync")
            putJsonObject("items") { put("type", "string") }
            put("default", JsonArray(listOf(JsonPrimitive("INBOX"))))
        }
        putJsonObject("initialWindowDays") { put("type", "integer"); put("title", "Days of history on first sync"); put("default", 30); put("minimum", 1) }
    }
}
