package net.robog.clarity.connector.dav

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
import net.robog.clarity.shared.connector.ConnectorConfig

/** Name of the config collection that holds the calendar and files accounts. */
const val ACCOUNTS = "accounts"

/** Id of the account a legacy flat config is read as. */
const val DEFAULT_ACCOUNT = "default"

/** One CalDAV/CardDAV/WebDAV account. */
data class DavConfig(
    val caldavUrl: String?,
    val carddavUrl: String?,
    val webdavUrl: String?,
    val username: String,
    val password: String,
    /** The person's own address, used to find them among attendees; discovered when not set. */
    val email: String?,
    /** Calendars (display name or path) to sync; empty = all. */
    val calendars: List<String>,
    /** Address books to sync; empty = all. */
    val addressBooks: List<String>,
    /** WebDAV folders (paths below the WebDAV URL) to index; empty = none. */
    val webdavFolders: List<String>,
    /** Stable id of the account ([DEFAULT_ACCOUNT] for a legacy flat config); part of every source id. */
    val id: String = DEFAULT_ACCOUNT,
    /** What the person called the account ("iCloud"). */
    val label: String = id,
) {
    override fun toString() = "DavConfig($label[$id], caldav=$caldavUrl, carddav=$carddavUrl, webdav=$webdavUrl, username=$username, password=<redacted>)"
}

class ConfigIncomplete(message: String) : RuntimeException(message)

/** An account of the saved config: its settings, or why they cannot be used yet. */
data class DavAccountEntry(val id: String, val label: String, val result: Result<DavConfig>)

/**
 * Reads every account of [config] (passwords opened with [runtime]); an unusable account is
 * reported in its entry and does not hide the others. Without an `accounts` collection the old
 * flat fields are read as the single account [DEFAULT_ACCOUNT].
 */
fun parseDavAccounts(config: ConnectorConfig, runtime: ConnectorRuntime): List<DavAccountEntry> =
    config.items(ACCOUNTS).map { item ->
        val result = runCatching { parseDavAccount(item, config, runtime) }.recoverCatching {
            throw if (it is ConfigIncomplete && !item.legacy) ConfigIncomplete("${item.label}: ${it.message}") else it
        }
        DavAccountEntry(item.id, item.label, result)
    }

/** The usable accounts of [config]; fails with the first problem when there is none. */
fun usableDavAccounts(config: ConnectorConfig, runtime: ConnectorRuntime): List<DavConfig> {
    val entries = parseDavAccounts(config, runtime)
    if (entries.isEmpty()) throw ConfigIncomplete("No calendar account has been added yet")
    val usable = entries.mapNotNull { it.result.getOrNull() }
    return usable.ifEmpty { throw entries.first().result.exceptionOrNull()!! }
}

/**
 * Reads one account [item] (password opened with [runtime]). `baseUrl` is the fallback for the
 * calendar, contacts and files URLs, so it is not needed when those are set.
 *
 * @throws ConfigIncomplete when a required field is missing.
 */
fun parseDavAccount(item: ConfigItem, config: ConnectorConfig, runtime: ConnectorRuntime): DavConfig {
    val base = item.text("baseUrl")
    val caldav = item.text("caldavUrl") ?: base
    val carddav = item.text("carddavUrl") ?: base
    val folders = item.list("webdavFolders")
    val webdav = item.text("webdavUrl") ?: base.takeIf { folders.isNotEmpty() }
    if (caldav == null && carddav == null && webdav == null) throw ConfigIncomplete("Server URL is not set")
    if (folders.isNotEmpty() && webdav == null) throw ConfigIncomplete("Files URL is not set (needed for the folders to index)")
    listOf(caldav, carddav, webdav).filterNotNull().forEach {
        if (!(it.startsWith("https://") || it.startsWith("http://"))) throw ConfigIncomplete("'$it' is not an http(s) URL")
    }
    return DavConfig(
        caldavUrl = caldav,
        carddavUrl = carddav,
        webdavUrl = webdav,
        username = item.text("username") ?: throw ConfigIncomplete("Username is not set"),
        password = runtime.secret(config, item, "password") ?: throw ConfigIncomplete("Password is not set"),
        email = item.text("email"),
        calendars = item.list("calendars"),
        addressBooks = item.list("addressBooks"),
        webdavFolders = item.list("webdavFolders"),
        id = item.id,
        label = item.label,
    )
}

private fun example(text: String) = JsonArray(listOf(JsonPrimitive(text)))

private fun preset(title: String, description: String, vararg values: Pair<String, String>) = buildJsonObject {
    put("title", title)
    put("description", description)
    putJsonObject("values") {
        put("label", title)
        values.forEach { (k, v) -> put(k, v) }
    }
}

private val davPresets = JsonArray(listOf(
    preset(
        "iCloud", "Use an app-specific password from account.apple.com.",
        "caldavUrl" to "https://caldav.icloud.com", "carddavUrl" to "https://contacts.icloud.com",
    ),
    preset(
        "Fastmail", "Use an app password from Fastmail's settings.",
        "caldavUrl" to "https://caldav.fastmail.com/dav/", "carddavUrl" to "https://carddav.fastmail.com/dav/",
    ),
    preset("Nextcloud", "Enter your server URL, which looks like https://cloud.example.com/remote.php/dav."),
))

private fun stringList(title: String) = buildJsonObject {
    put("type", "array"); put("title", title)
    putJsonObject("items") { put("type", "string") }
}

private val davAccountItem = buildJsonObject {
    put("type", "object")
    putJsonArray("required") { listOf("label", "username", "password").forEach { add(JsonPrimitive(it)) } }
    putJsonObject("properties") {
        putJsonObject("id") { put("type", "string"); put("readOnly", true) }
        putJsonObject("label") { put("type", "string"); put("title", "Name"); put("description", "How you recognise this account."); put("examples", example("Personal")) }
        putJsonObject("baseUrl") {
            put("type", "string"); put("format", "uri"); put("title", "Server URL")
            put("description", "Used for calendars, contacts and files unless they have their own URL below.")
            put("examples", example("https://cloud.example.com/remote.php/dav"))
        }
        putJsonObject("caldavUrl") { put("type", "string"); put("format", "uri"); put("title", "Calendar URL (if different)") }
        putJsonObject("carddavUrl") { put("type", "string"); put("format", "uri"); put("title", "Contacts URL (if different)") }
        putJsonObject("webdavUrl") {
            put("type", "string"); put("format", "uri"); put("title", "Files URL (if different)")
            put("examples", example("https://cloud.example.com/remote.php/dav/files/you"))
        }
        putJsonObject("username") { put("type", "string"); put("title", "Username") }
        putJsonObject("password") { put("type", "string"); put("title", "Password"); put("format", "password"); put("writeOnly", true); put("x-secret", true) }
        putJsonObject("email") { put("type", "string"); put("format", "email"); put("title", "Your email address"); put("description", "Used to find you among event attendees.") }
        put("calendars", stringList("Calendars to sync (empty: all)"))
        put("addressBooks", stringList("Address books to sync (empty: all)"))
        put("webdavFolders", stringList("Folders to index (empty: none)"))
    }
}

/**
 * JSON Schema the app renders as the settings form: a collection of accounts. `password` is a
 * secret the app seals per item (`accounts/<id>/password`).
 */
val davConfigSchema: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonArray("required") { add(JsonPrimitive(ACCOUNTS)) }
    putJsonObject("properties") {
        putJsonObject(ACCOUNTS) {
            put("type", "array")
            put("title", "Calendar and files accounts")
            put("minItems", 1)
            put("x-item-title", "label")
            put("x-item-subtitle", "username")
            put("x-presets", davPresets)
            put("items", davAccountItem)
        }
    }
}
