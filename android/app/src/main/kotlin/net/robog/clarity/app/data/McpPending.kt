package net.robog.clarity.app.data

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.robog.clarity.shared.mcp.McpServerState
import net.robog.clarity.shared.mcp.McpServerStatus

/** The `kind` and id of the connector that runs third-party MCP servers. */
const val MCP_HOST_KIND = "mcp-host"

/** How long the servers screen checks quickly for a saved server before it settles for what it has. */
val MCP_PENDING_WINDOW: Duration = 30.seconds

/**
 * A server the person just saved (new, or edited) that the backend's listing may not show yet. The
 * servers screen shows it as "Pending" under [label] until the listing reflects it.
 */
data class PendingServer(
    val id: String,
    val label: String,
    val isNew: Boolean,
    val savedAt: Instant,
    /** The edit changes how the server runs, so the host restarts it and lists it again. */
    val relists: Boolean = true,
)

/** Item fields the host does not run with: changing only these never restarts or re-lists a server. */
private val DISPLAY_ONLY_FIELDS = setOf("label", "description", "preset")

/**
 * Pure: the servers of a saved `mcp-host` config ([saved]) that are new or differ from the [stored]
 * config they replace, as pending. A secret that was not retyped is stored unchanged, so only a real
 * edit differs.
 */
fun changedServers(stored: JsonObject?, saved: JsonObject, now: Instant): List<PendingServer> {
    fun items(values: JsonObject?): Map<String, JsonObject> =
        (values?.get("servers") as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to o
        }.toMap()
    val before = items(stored)
    return items(saved).mapNotNull { (id, item) ->
        if (before[id] == item) return@mapNotNull null
        val label = (item["label"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        val old = before[id]
        val relists = old == null || old.filterKeys { it !in DISPLAY_ONLY_FIELDS } != item.filterKeys { it !in DISPLAY_ONLY_FIELDS }
        PendingServer(id, label.ifEmpty { id }, isNew = old == null, savedAt = now, relists = relists)
    }
}

/**
 * Pure: whether [listed] (the backend's row for [PendingServer.id], or null) shows what was saved. A
 * new server needs a listed row with its label, not installing, and either its tools or a final
 * state. An edit that changes how it runs also needs a listing made after the save (a name or
 * description change does not restart it, so its label is enough). After [window] a row that is listed
 * (and not installing) counts either way: the person keeps what the backend says rather than a
 * "Pending" that never ends. A new server that is still not listed stays pending.
 */
fun PendingServer.resolvedBy(listed: McpServerStatus?, now: Instant, window: Duration = MCP_PENDING_WINDOW): Boolean {
    if (listed == null) return false
    if (listed.state == McpServerState.INSTALLING) return false
    if (now - savedAt >= window) return true
    val started = listed.state == McpServerState.FAILED || listed.state == McpServerState.DISABLED || listed.tools.isNotEmpty()
    if (listed.label != label || !started) return false
    if (isNew || !relists) return true
    val listedAt = listed.listedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    return listedAt != null && listedAt >= savedAt
}

/** The servers saved from this phone that the listing does not show yet; shared by the editor and the servers screen. */
class PendingMcpServers {
    private val _servers = MutableStateFlow<List<PendingServer>>(emptyList())
    val servers: StateFlow<List<PendingServer>> = _servers.asStateFlow()

    fun add(saved: List<PendingServer>) {
        if (saved.isEmpty()) return
        _servers.update { old -> old.filter { o -> saved.none { it.id == o.id } } + saved }
    }

    /** Drops what [listing] now shows; true when something is still pending. */
    fun settle(listing: List<McpServerStatus>, now: Instant): Boolean {
        _servers.update { old -> old.filterNot { p -> p.resolvedBy(listing.firstOrNull { it.id == p.id }, now) } }
        return _servers.value.isNotEmpty()
    }
}
