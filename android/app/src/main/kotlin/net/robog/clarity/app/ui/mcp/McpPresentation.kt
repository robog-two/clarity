package net.robog.clarity.app.ui.mcp

import net.robog.clarity.app.data.MCP_HOST_KIND
import net.robog.clarity.app.data.PendingServer
import net.robog.clarity.shared.connector.ConnectorSummary
import net.robog.clarity.shared.mcp.McpServerState
import net.robog.clarity.shared.mcp.McpServerStatus
import net.robog.clarity.shared.mcp.McpToolMode
import net.robog.clarity.shared.mcp.McpToolStatus

/** The connector that runs third-party MCP servers; its config holds one item per server. */
const val MCP_HOST = MCP_HOST_KIND

/** A tool's name as a person reads it: its title, or the id with underscores as spaces ("create_issue" becomes "Create issue"). */
fun toolDisplayName(tool: McpToolStatus): String =
    tool.title?.takeIf { it.isNotBlank() }
        ?: tool.name.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.titlecase() }

/** The `mcp-host` connector, found by id or (once it has sent a manifest) by kind. */
fun isMcpHost(summary: ConnectorSummary): Boolean =
    summary.id.value == MCP_HOST || summary.manifest?.kind == MCP_HOST

/** How a server's state chip looks: each state has its own tone. */
enum class ChipTone { Progress, Quiet, Active, Problem, Off }

fun McpServerState.tone(): ChipTone = when (this) {
    McpServerState.INSTALLING -> ChipTone.Progress
    McpServerState.IDLE -> ChipTone.Quiet
    McpServerState.RUNNING -> ChipTone.Active
    McpServerState.FAILED -> ChipTone.Problem
    McpServerState.DISABLED -> ChipTone.Off
}

/** "12 tools: 9 read, 3 can change things": how many of a server's tools only read. */
data class ToolCounts(val total: Int, val reads: Int) {
    val changes: Int get() = total - reads
}

fun McpServerStatus.toolCounts(): ToolCounts = ToolCounts(tools.size, tools.count { it.readOnly })

/** What it does with a tool the person has not chosen a mode for: Auto, the safety check decides. */
@Suppress("UNUSED_PARAMETER")
fun defaultMode(tool: McpToolStatus): McpToolMode = McpToolMode.AUTO

/** The modes in the order of the control. */
val MODE_ORDER = listOf(McpToolMode.ASK, McpToolMode.AUTO, McpToolMode.ALWAYS_OK)

/** A server's tools in the two groups of its detail screen, each sorted by name. */
data class ToolGroups(val reads: List<McpToolStatus>, val changes: List<McpToolStatus>)

fun McpServerStatus.toolGroups(): ToolGroups {
    val (reads, changes) = tools.sortedBy { it.name }.partition { it.readOnly }
    return ToolGroups(reads, changes)
}

/**
 * Always OK lets a tool run without the safety check asking, so on a tool the server does not
 * mark read-only it is confirmed first. Read-only tools and the other modes are set at once.
 */
fun needsConfirmation(tool: McpToolStatus, mode: McpToolMode): Boolean =
    mode == McpToolMode.ALWAYS_OK && !tool.readOnly && tool.mode != McpToolMode.ALWAYS_OK

/** A server as a row of the list: the backend's view, and whether it is saved but not shown by the backend yet. */
data class McpRow(val server: McpServerStatus, val pending: Boolean)

/**
 * The list the person sees: the backend's [servers] (a pending one marked), then the [pending] ones
 * it does not list yet as rows under their saved label, so a save never leaves the list blank.
 */
fun mergeRows(servers: List<McpServerStatus>, pending: List<PendingServer>): List<McpRow> {
    val ids = pending.map { it.id }.toSet()
    val listed = servers.map { McpRow(it, it.id in ids) }
    val missing = pending.filter { p -> servers.none { it.id == p.id } }
        .map { McpRow(McpServerStatus(id = it.id, label = it.label, state = McpServerState.INSTALLING), pending = true) }
    return listed + missing
}

/** [tool] after the person sets [mode], or resets it with null, as the server will report it. */
fun McpToolStatus.withMode(mode: McpToolMode?): McpToolStatus =
    if (mode == null) copy(mode = defaultMode(this), overridden = false) else copy(mode = mode, overridden = true)

/** The servers with one tool changed. */
fun List<McpServerStatus>.withTool(serverId: String, tool: String, change: (McpToolStatus) -> McpToolStatus): List<McpServerStatus> =
    map { s -> if (s.id != serverId) s else s.copy(tools = s.tools.map { t -> if (t.name == tool) change(t) else t }) }

private val HTTPS_HOST = Regex("https://([A-Za-z0-9.-]+)")

/**
 * Hosts of the https addresses in a server's `KEY=value` settings that [allowHosts] does not cover
 * (an entry `.example.com` covers example.com and every subdomain, as the egress proxy reads it).
 */
fun unreachableHosts(env: List<String>, allowHosts: List<String>): List<String> {
    val allowed = allowHosts.map { it.trim().lowercase().removeSuffix(".") }.filter { it.isNotEmpty() }
    fun covered(host: String) = allowed.any { a -> host == a || (a.startsWith(".") && (host == a.drop(1) || host.endsWith(a))) }
    return env.flatMap { line -> HTTPS_HOST.findAll(line.substringAfter('=', "")).map { it.groupValues[1].lowercase().removeSuffix(".") } }
        .filter { it.contains('.') && !covered(it) }
        .distinct()
}
