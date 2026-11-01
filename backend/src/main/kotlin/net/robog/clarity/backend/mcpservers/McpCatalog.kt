package net.robog.clarity.backend.mcpservers

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.backend.agent.CollectionStore
import net.robog.clarity.backend.agent.McpIndex
import net.robog.clarity.backend.agent.collectionSlug
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.mcp.McpServerList
import net.robog.clarity.shared.mcp.McpServerState
import net.robog.clarity.shared.mcp.McpServerStatus
import net.robog.clarity.shared.mcp.McpToolMode
import net.robog.clarity.shared.mcp.McpToolStatus

private val log = KotlinLogging.logger {}

/** A tool of a server with the mode in effect. */
data class McpToolView(val tool: HostTool, val mode: McpToolMode, val overridden: Boolean)

/** A server from the host's listing, with its tools' modes and its memory collection. */
data class McpServer(val host: HostServer, val collection: String?, val tools: List<McpToolView>) {
    val id: String get() = host.id
    val label: String get() = host.label

    /** Switched on in its config (a failed server is retried on its next call). */
    val enabled: Boolean get() = host.state != McpServerState.DISABLED

    /** Tools a run may be given: everything not switched off. */
    val usable: List<McpToolView> get() = tools.filter { it.mode != McpToolMode.OFF }
}

/** Pure: the mode a tool has unless the person chose one: only tools the server marks read-only run without asking. */
fun defaultMode(tool: HostTool): McpToolMode = if (tool.readOnly) McpToolMode.AUTO else McpToolMode.ASK

/** Pure: [label] as a tool-name slug: lowercase `[a-z0-9_]`, at most 24 characters. */
fun serverSlug(label: String): String =
    label.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_').take(24).trimEnd('_').ifEmpty { "server" }

/** Pure: the prefix of a server's tools, `mcp_<slug>__` (contract 2). */
fun toolPrefix(label: String): String = "mcp_${serverSlug(label)}__"

/** Pure: the name a run sees for [tool] of the server labelled [label]: prefixed, at most 64 characters. */
fun mcpToolName(label: String, tool: String): String = (toolPrefix(label) + tool).replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)

/** Pure: the first sentence of [text] (or its first line), at most [max] characters. */
fun firstSentence(text: String, max: Int = 200): String {
    val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    val sentence = Regex("^.*?[.!?](?=\\s|$)").find(line)?.value ?: line
    return if (sentence.length <= max) sentence else sentence.take(max - 1).trimEnd() + "…"
}

/** Pure: a server's collection name, from its label. */
fun serverCollectionName(server: HostServer): String = collectionSlug(server.label) ?: "mcp-${serverSlug(server.id)}"

/** Pure: the description of a server's memory collection. */
fun serverCollectionDescription(label: String): String =
    "Notes for using $label: names, IDs, formatting rules, how to find what is coming up"

/** Pure: [tools] with the modes in effect, the person's [overrides] (tool name to mode) first. */
fun withModes(tools: List<HostTool>, overrides: Map<String, McpToolMode>): List<McpToolView> =
    tools.map { t -> overrides[t.name]?.let { McpToolView(t, it, true) } ?: McpToolView(t, defaultMode(t), false) }

/** Pure: a server as the app's settings screen shows it. */
fun statusOf(server: McpServer): McpServerStatus = McpServerStatus(
    id = server.id,
    label = server.label,
    description = server.host.description,
    state = server.host.state,
    error = server.host.error,
    source = server.host.source,
    allowHosts = server.host.allowHosts,
    tools = server.tools.map(::toolStatusOf),
    collection = server.collection,
    listedAt = server.host.listedAt,
)

fun toolStatusOf(view: McpToolView): McpToolStatus = McpToolStatus(
    name = view.tool.name,
    title = view.tool.title,
    description = firstSentence(view.tool.description),
    readOnly = view.tool.readOnly,
    destructive = view.tool.destructive,
    mode = view.mode,
    overridden = view.overridden,
)

/** How the server index starts in the lead's thread (it is re-sent only when it changes). */
const val MCP_INDEX_PREFIX = "[mcp servers]"

/** What the lead is told when the servers it saw are all gone. */
const val NO_MCP_SERVERS = "$MCP_INDEX_PREFIX None are set up now."

/**
 * Pure: the lead's index of the person's servers: each enabled server that has a tool not switched
 * off, by label, with its one-line description. Null when there are none. Nothing that changes
 * often (state, tool counts) is in it, so it is rarely re-sent.
 */
fun renderMcpIndex(servers: List<McpServer>, maxDescription: Int = 120): String? {
    val usable = servers.filter { it.enabled && it.usable.isNotEmpty() }.sortedBy { it.label.lowercase() }
    if (usable.isEmpty()) return null
    return buildString {
        append(MCP_INDEX_PREFIX)
        append(" The person's MCP servers. You cannot use them yourself: give a helper the ones its task needs with delegate(servers=[\"<label>\"]). ")
        append("Calls that change something wait for the person's approval. Each server has a memory collection of the same name with notes for using it.")
        usable.forEach { s ->
            val d = s.host.description.replace(Regex("\\s+"), " ").trim()
            append("\n- ").append(s.label)
            if (d.isNotEmpty()) append(": ").append(if (d.length > maxDescription) d.take(maxDescription - 1).trimEnd() + "…" else d)
        }
    }
}

/**
 * The person's third-party servers: the host's listing (kept fresh by [McpRefresher]; readers only
 * see the cache, and the last good listing stays while the host is unreachable), the tool modes
 * from Postgres, and a memory collection per server, created the first time it is listed.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class McpCatalog(
    private val host: McpHost,
    private val store: McpStore,
    private val collections: CollectionStore,
    private val clock: Clock,
    /** Tests only: every read lists the host first. In production [McpRefresher] keeps the listing fresh. */
    private val refreshOnRead: Boolean = false,
) : McpIndex {
    @Inject
    constructor(config: AgentConfig, store: McpStore, collections: CollectionStore, clock: Clock) :
        this(McpHost.of(config.mcpHost), store, collections, clock)

    private val refreshing = Mutex()

    @Volatile private var listing: List<HostServer>? = null
    private var failing = false

    val configured: Boolean get() = host.configured

    /**
     * Lists the host once and keeps the result (the last good listing stays while the host is
     * unreachable); true when it answered. Only [McpRefresher] calls it in production.
     */
    suspend fun refresh(): Boolean {
        if (!host.configured) return true
        return refreshing.withLock {
            try {
                val fresh = host.servers()
                ensureCollections(fresh, clock.now())
                listing = fresh
                if (failing) log.info { "MCP host reachable again: ${fresh.size} servers" }
                failing = false
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!failing) log.warn { "MCP host unreachable, keeping the last listing: ${e.message}" }
                failing = true
                false
            }
        }
    }

    /** The last listing, or null when the host has not answered yet. Never waits on the host. */
    suspend fun hostServers(): List<HostServer>? {
        if (!host.configured) return emptyList()
        if (refreshOnRead) refresh()
        return listing
    }

    /** Every server with its tools' modes; empty when there are none or the host was never reachable. */
    suspend fun servers(): List<McpServer> {
        val hosts = hostServers().orEmpty()
        if (hosts.isEmpty()) return emptyList()
        val overrides = store.policies()
        val seen = store.seenCollections()
        return hosts.map { h ->
            McpServer(h, seen[h.id], withModes(h.tools, overrides[h.id].orEmpty()))
        }
    }

    /** The app's view; null when the host is set up but has never answered. */
    suspend fun list(): McpServerList? {
        if (host.configured && hostServers() == null) return null
        return McpServerList(servers().map(::statusOf))
    }

    /** Servers named by [labels] (case-insensitive), or the reason one cannot be used. */
    suspend fun resolve(labels: List<String>): Pair<List<McpServer>, String?> {
        val all = servers()
        val found = mutableListOf<McpServer>()
        for (raw in labels.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }) {
            val server = all.firstOrNull { it.label.equals(raw, ignoreCase = true) || it.id == raw }
            val usable = all.filter { it.enabled && it.usable.isNotEmpty() }.map { it.label }
            val names = if (usable.isEmpty()) "none are set up" else "available: ${usable.joinToString()}"
            when {
                server == null -> return emptyList<McpServer>() to "There is no MCP server \"$raw\" ($names)."
                !server.enabled -> return emptyList<McpServer>() to "The MCP server ${server.label} is switched off ($names)."
                server.usable.isEmpty() -> return emptyList<McpServer>() to "The MCP server ${server.label} has no tools Clarity may use yet ($names)."
                else -> found += server
            }
        }
        return found to null
    }

    /** Calls [tool] on server [serverId] through the host. */
    suspend fun call(serverId: String, tool: String, input: JsonObject): ToolResult = host.call(serverId, tool, input)

    /** Names of the collections made for servers (the memory tidy keeps them as they are). */
    suspend fun collectionNames(): List<String> = store.seenCollections().values.distinct().sorted()

    override suspend fun leadIndex(): String? = renderMcpIndex(servers())

    /** Sets or (null) clears the person's choice for one tool. */
    suspend fun setMode(serverId: String, tool: String, mode: McpToolMode?) = store.setPolicy(serverId, tool, mode, clock.now())

    suspend fun close() = host.close()

    /** A server listed for the first time gets its memory collection (an existing one of that name is used). */
    private suspend fun ensureCollections(servers: List<HostServer>, now: Instant) {
        val seen = store.seenCollections()
        servers.filter { it.id !in seen }.forEach { server ->
            val name = serverCollectionName(server)
            collections.find(name) ?: collections.create(name, serverCollectionDescription(server.label))
            if (store.markSeen(server.id, name, now)) log.info { "MCP server ${server.label}: memory collection \"$name\"" }
        }
    }
}
