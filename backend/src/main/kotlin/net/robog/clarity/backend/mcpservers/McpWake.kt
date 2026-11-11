package net.robog.clarity.backend.mcpservers

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Duration
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.robog.clarity.shared.mcp.McpServerState

/** Why the refresher is woken: the servers just saved (id to label) it should wait to see listed; null: no particular ones. */
class McpKick(val expected: Map<String, String?>? = null)

/**
 * Wakes [McpRefresher] now instead of at its next tick. Conflated: a burst of kicks is one wake-up
 * carrying the newest [McpKick].
 */
@Inject
@SingleIn(AppScope::class)
class McpWake {
    private val channel = Channel<McpKick>(Channel.CONFLATED)

    fun kick(expected: Map<String, String?>? = null) {
        channel.trySend(McpKick(expected))
    }

    /** The next kick within [timeout], or null when none came. */
    suspend fun next(timeout: Duration): McpKick? = withTimeoutOrNull(timeout) { channel.receive() }

    /** A kick that is already waiting, or null. Never suspends. */
    fun poll(): McpKick? = channel.tryReceive().getOrNull()
}

/**
 * Pure: the servers a saved `mcp-host` config holds (config item id to label), or null when the
 * values have no `servers` list (nothing particular to wait for).
 */
fun expectedServers(values: JsonObject): Map<String, String?>? {
    val array = values["servers"] as? JsonArray ?: return null
    return array.mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        id to (o["label"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    }.toMap()
}

/**
 * Pure: whether [listing] shows what was saved ([expected], id to label): exactly those servers, each
 * with its saved label, none still being installed, and each running or idle one with its tools
 * (a failed or switched-off one is as far as it will get). Null [expected] is settled at once.
 */
fun listingSettled(expected: Map<String, String?>?, listing: List<HostServer>?): Boolean {
    if (expected == null) return true
    if (listing == null) return false
    if (listing.map { it.id }.toSet() != expected.keys) return false
    return listing.all { s ->
        val label = expected[s.id]
        (label == null || s.label == label) && when (s.state) {
            McpServerState.INSTALLING -> false
            McpServerState.FAILED, McpServerState.DISABLED -> true
            McpServerState.IDLE, McpServerState.RUNNING -> s.tools.isNotEmpty()
        }
    }
}
