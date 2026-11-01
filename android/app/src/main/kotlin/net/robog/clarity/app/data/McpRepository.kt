package net.robog.clarity.app.data

import net.robog.clarity.app.net.McpApi
import net.robog.clarity.shared.mcp.McpServerStatus
import net.robog.clarity.shared.mcp.McpSuggestion
import net.robog.clarity.shared.mcp.McpToolMode
import net.robog.clarity.shared.mcp.McpToolPolicyUpdate

/** The person's MCP servers as the backend sees them, how each tool may be used, and Clarity's suggestions. */
interface McpRepository {
    suspend fun servers(): ApiResult<List<McpServerStatus>>

    /** Sets how [tool] of [serverId] may be used; null returns it to the default. */
    suspend fun setToolMode(serverId: String, tool: String, mode: McpToolMode?): ApiResult<Unit>
    suspend fun suggestions(): ApiResult<List<McpSuggestion>>
    suspend fun dismiss(suggestionId: String): ApiResult<Unit>
}

class DefaultMcpRepository(private val api: McpApi) : McpRepository {
    override suspend fun servers() = api.mcpServers().map { it.servers }
    override suspend fun setToolMode(serverId: String, tool: String, mode: McpToolMode?) =
        api.putMcpToolPolicy(serverId, tool, McpToolPolicyUpdate(mode))
    override suspend fun suggestions() = api.mcpSuggestions().map { it.suggestions }
    override suspend fun dismiss(suggestionId: String) = api.dismissMcpSuggestion(suggestionId)
}
