package net.robog.clarity.app.ui.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.ConnectorsRepository
import net.robog.clarity.app.data.McpRepository
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.shared.mcp.McpServerState
import net.robog.clarity.shared.mcp.McpServerStatus
import net.robog.clarity.shared.mcp.McpSuggestion
import net.robog.clarity.shared.mcp.McpToolMode

/** A tool's mode the person picked; waiting for their OK when it lets a tool that changes things run alone. */
data class ToolChoice(val serverId: String, val tool: String, val mode: McpToolMode)

data class McpUiState(
    val loading: Boolean = true,
    /** The `mcp-host` connector is not running on the server (or not set up there at all). */
    val hostNotRunning: Boolean = false,
    val servers: List<McpServerStatus> = emptyList(),
    val suggestions: List<McpSuggestion> = emptyList(),
    /** Why the servers could not be loaded. */
    val error: String? = null,
    /** The server whose detail is open. */
    val selected: String? = null,
    val confirming: ToolChoice? = null,
    /** Why the last change to a tool or a suggestion did not go through. */
    val changeError: String? = null,
) {
    val selectedServer: McpServerStatus? get() = selected?.let { id -> servers.firstOrNull { it.id == id } }

    /** Some server is still installing, so its state and tools are worth checking again soon. */
    val anyInstalling: Boolean get() = servers.any { it.state == McpServerState.INSTALLING }
}

/** Settings → MCP servers: the list, one server's tools and how each may be used, and Clarity's suggestions. */
@Inject
@ViewModelKey(McpViewModel::class)
@ContributesIntoMap(AppScope::class)
class McpViewModel(
    private val mcp: McpRepository,
    private val connectors: ConnectorsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(McpUiState())
    val state: StateFlow<McpUiState> = _state.asStateFlow()

    /** Loads everything again; the screen calls it whenever it comes back into view. */
    fun refresh() {
        viewModelScope.launch {
            val list = connectors.list()
            val host = (list as? ApiResult.Success)?.value?.firstOrNull(::isMcpHost)
            // A list that failed says nothing about the host: try the servers anyway.
            if (list is ApiResult.Success && host?.manifest == null) {
                _state.update { it.copy(loading = false, hostNotRunning = true, servers = emptyList(), error = null) }
                return@launch
            }
            val servers = mcp.servers()
            val suggestions = mcp.suggestions()
            _state.update { s ->
                s.copy(
                    loading = false,
                    hostNotRunning = false,
                    servers = (servers as? ApiResult.Success)?.value ?: s.servers,
                    error = (servers as? ApiResult.Failure)?.error?.sentence(),
                    // Suggestions are extra: a server without them just shows none.
                    suggestions = (suggestions as? ApiResult.Success)?.value ?: s.suggestions,
                )
            }
        }
    }

    fun open(serverId: String) = _state.update { it.copy(selected = serverId, changeError = null) }

    fun close() = _state.update { it.copy(selected = null, confirming = null, changeError = null) }

    /** A segment of a tool's Auto / Ask / Off control was tapped. */
    fun chooseMode(serverId: String, tool: String, mode: McpToolMode) {
        val current = _state.value.servers.firstOrNull { it.id == serverId }?.tools?.firstOrNull { it.name == tool } ?: return
        if (current.mode == mode && current.overridden) return
        if (needsConfirmation(current, mode)) {
            _state.update { it.copy(confirming = ToolChoice(serverId, tool, mode)) }
        } else {
            apply(serverId, tool, mode)
        }
    }

    /** "Allow" in the confirmation. */
    fun confirm() {
        val choice = _state.value.confirming ?: return
        _state.update { it.copy(confirming = null) }
        apply(choice.serverId, choice.tool, choice.mode)
    }

    fun cancelConfirm() = _state.update { it.copy(confirming = null) }

    /** Back to the default for this tool. */
    fun reset(serverId: String, tool: String) = apply(serverId, tool, null)

    /** Shown at once; put back as it was if the server refuses. */
    private fun apply(serverId: String, tool: String, mode: McpToolMode?) {
        val before = _state.value.servers.firstOrNull { it.id == serverId }?.tools?.firstOrNull { it.name == tool } ?: return
        _state.update { s -> s.copy(servers = s.servers.withTool(serverId, tool) { it.withMode(mode) }, changeError = null) }
        viewModelScope.launch {
            val r = mcp.setToolMode(serverId, tool, mode)
            if (r is ApiResult.Failure) {
                _state.update { s -> s.copy(servers = s.servers.withTool(serverId, tool) { before }, changeError = r.error.sentence()) }
            }
        }
    }

    fun dismiss(suggestionId: String) {
        val before = _state.value.suggestions
        _state.update { s -> s.copy(suggestions = s.suggestions.filterNot { it.id == suggestionId }, changeError = null) }
        viewModelScope.launch {
            val r = mcp.dismiss(suggestionId)
            if (r is ApiResult.Failure) _state.update { it.copy(suggestions = before, changeError = r.error.sentence()) }
        }
    }
}
