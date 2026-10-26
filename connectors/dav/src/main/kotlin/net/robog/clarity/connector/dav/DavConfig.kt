package net.robog.clarity.connector.dav

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.robog.clarity.connector.sdk.ConnectorRuntime
import net.robog.clarity.shared.connector.ConnectorConfig

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
) {
    override fun toString() = "DavConfig(caldav=$caldavUrl, carddav=$carddavUrl, webdav=$webdavUrl, username=$username, password=<redacted>)"
}

class ConfigIncomplete(message: String) : RuntimeException(message)

fun parseDavConfig(config: ConnectorConfig, runtime: ConnectorRuntime): DavConfig {
    val v = config.values
    fun text(name: String): String? = (v[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf(String::isNotEmpty)
    fun list(name: String): List<String> = (v[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }
        ?: text(name)?.split(',', '\n')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

    val base = text("baseUrl")
    val caldav = text("caldavUrl") ?: base
    val carddav = text("carddavUrl") ?: base
    val webdav = text("webdavUrl") ?: base.takeIf { list("webdavFolders").isNotEmpty() }
    if (caldav == null && carddav == null && webdav == null) throw ConfigIncomplete("Server URL is not set")
    listOf(caldav, carddav, webdav).filterNotNull().forEach {
        if (!(it.startsWith("https://") || it.startsWith("http://"))) throw ConfigIncomplete("'$it' is not an http(s) URL")
    }
    return DavConfig(
        caldavUrl = caldav,
        carddavUrl = carddav,
        webdavUrl = webdav,
        username = text("username") ?: throw ConfigIncomplete("Username is not set"),
        password = runtime.secret(config, "password") ?: throw ConfigIncomplete("Password is not set"),
        email = text("email"),
        calendars = list("calendars"),
        addressBooks = list("addressBooks"),
        webdavFolders = list("webdavFolders"),
    )
}

val davConfigSchema: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonArray("required") { add(JsonPrimitive("baseUrl")); add(JsonPrimitive("username")); add(JsonPrimitive("password")) }
    putJsonObject("properties") {
        putJsonObject("baseUrl") { put("type", "string"); put("title", "Server URL"); put("description", "e.g. https://cloud.example.com/remote.php/dav") }
        putJsonObject("caldavUrl") { put("type", "string"); put("title", "Calendar URL (if different)") }
        putJsonObject("carddavUrl") { put("type", "string"); put("title", "Contacts URL (if different)") }
        putJsonObject("webdavUrl") { put("type", "string"); put("title", "Files URL (if different)"); put("description", "e.g. https://cloud.example.com/remote.php/dav/files/you") }
        putJsonObject("username") { put("type", "string"); put("title", "Username") }
        putJsonObject("password") { put("type", "string"); put("title", "Password"); put("format", "password"); put("x-secret", true) }
        putJsonObject("email") { put("type", "string"); put("title", "Your email address (to find yourself among attendees)") }
        putJsonObject("calendars") { put("type", "array"); put("title", "Calendars to sync (empty: all)"); putJsonObject("items") { put("type", "string") } }
        putJsonObject("addressBooks") { put("type", "array"); put("title", "Address books to sync (empty: all)"); putJsonObject("items") { put("type", "string") } }
        putJsonObject("webdavFolders") { put("type", "array"); put("title", "Folders to index (empty: none)"); putJsonObject("items") { put("type", "string") } }
    }
}
