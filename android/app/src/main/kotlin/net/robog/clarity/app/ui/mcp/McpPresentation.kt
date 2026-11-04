package net.robog.clarity.app.ui.mcp

import net.robog.clarity.shared.connector.ConnectorSummary
import net.robog.clarity.shared.mcp.McpServerState
import net.robog.clarity.shared.mcp.McpServerStatus
import net.robog.clarity.shared.mcp.McpToolMode
import net.robog.clarity.shared.mcp.McpToolStatus

/** The connector that runs third-party MCP servers; its config holds one item per server. */
const val MCP_HOST = "mcp-host"

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

/** What I do with [tool] when the person has not chosen: only read-only tools run without asking. */
fun defaultMode(tool: McpToolStatus): McpToolMode = if (tool.readOnly) McpToolMode.AUTO else McpToolMode.ASK

/** A server's tools in the two groups of its detail screen, each sorted by name. */
data class ToolGroups(val reads: List<McpToolStatus>, val changes: List<McpToolStatus>)

fun McpServerStatus.toolGroups(): ToolGroups {
    val (reads, changes) = tools.sortedBy { it.name }.partition { it.readOnly }
    return ToolGroups(reads, changes)
}

/**
 * Choosing Auto for a tool that changes things lets me use it without asking, so it is
 * confirmed first. Read-only tools and the other modes are set at once.
 */
fun needsConfirmation(tool: McpToolStatus, mode: McpToolMode): Boolean =
    mode == McpToolMode.AUTO && !tool.readOnly && tool.mode != McpToolMode.AUTO

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
