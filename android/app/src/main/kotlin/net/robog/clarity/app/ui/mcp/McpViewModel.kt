package net.robog.clarity.app.ui.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.app.data.ConnectorsRepository
import net.robog.clarity.app.data.MCP_PENDING_WINDOW
import net.robog.clarity.app.data.McpRepository
import net.robog.clarity.app.data.PendingMcpServers
import net.robog.clarity.app.data.PendingServer
import net.robog.clarity.app.data.SystemAppClock
import kotlin.time.Duration.Companion.milliseconds
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.shared.mcp.McpServerState
import net.robog.clarity.shared.mcp.McpServerStatus
import net.robog.clarity.shared.mcp.McpSuggestion
import net.robog.clarity.shared.mcp.McpToolMode

/** How often the list is checked while a saved server is pending. */
private val PENDING_POLL = 1500.milliseconds

data class McpUiState(
    val loading: Boolean = true,
    /** The `mcp-host` connector is not running on the server (or not set up there at all). */
    val hostNotRunning: Boolean = false,
    val servers: List<McpServerStatus> = emptyList(),
    val suggestions: List<McpSuggestion> = emptyList(),
    /** Saved from this phone and not (fully) listed by the backend yet. */
    val pending: List<PendingServer> = emptyList(),
    /** Why the servers could not be loaded. */
    val error: String? = null,
    /** The server whose detail is open. */
    val selected: String? = null,
    /** Why the last change to a tool or a suggestion did not go through. */
    val changeError: String? = null,
) {
    /** The list as shown: listed servers, then saved ones the backend does not list yet. */
    val rows: List<McpRow> get() = mergeRows(servers, pending)

    val selectedServer: McpServerStatus? get() = selected?.let { id -> rows.firstOrNull { it.server.id == id }?.server }

    /** Whether [serverId] shows "Pending". */
    fun isPending(serverId: String): Boolean = pending.any { it.id == serverId }

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
    private val pending: PendingMcpServers = PendingMcpServers(),
    private val clock: AppClock = SystemAppClock(),
) : ViewModel() {
    private val _state = MutableStateFlow(McpUiState())
    val state: StateFlow<McpUiState> = _state.asStateFlow()

    private var watching: Job? = null

    init {
        // A server saved in the editor shows at once as Pending, and the list is checked quickly until it is listed.
        viewModelScope.launch {
            pending.servers.collect { p ->
                _state.update { it.copy(pending = p) }
                if (p.isNotEmpty()) watchPending()
            }
        }
    }

    /** Checks the list every [PENDING_POLL] until nothing is pending or [MCP_PENDING_WINDOW] has passed since the last save. */
    private fun watchPending() {
        if (watching?.isActive == true) return
        watching = viewModelScope.launch {
            while (true) {
                val saved = pending.servers.value
                if (saved.isEmpty() || clock.now() - saved.maxOf { it.savedAt } >= MCP_PENDING_WINDOW) break
                delay(PENDING_POLL)
                load(fresh = false)
            }
        }
    }

    /** Loads everything again from the backend's host; the screen calls it whenever it comes back into view. */
    fun refresh() {
        viewModelScope.launch { load(fresh = true) }
    }

    private suspend fun load(fresh: Boolean) {
        run {
            val list = connectors.list()
            val host = (list as? ApiResult.Success)?.value?.firstOrNull(::isMcpHost)
            // A list that failed says nothing about the host: try the servers anyway.
            if (list is ApiResult.Success && host?.manifest == null) {
                _state.update { it.copy(loading = false, hostNotRunning = true, servers = emptyList(), error = null) }
                return
            }
            val servers = mcp.servers(fresh)
            val suggestions = mcp.suggestions()
            (servers as? ApiResult.Success)?.let { pending.settle(it.value, clock.now()) }
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

    fun close() = _state.update { it.copy(selected = null, changeError = null) }

    /** A segment of a tool's Ask / Auto / Always OK control was tapped. */
    fun chooseMode(serverId: String, tool: String, mode: McpToolMode) {
        val current = _state.value.servers.firstOrNull { it.id == serverId }?.tools?.firstOrNull { it.name == tool } ?: return
        if (current.mode == mode && current.overridden) return
        // Set at once, no confirm: a mistake is one tap to undo.
        apply(serverId, tool, mode)
    }

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
