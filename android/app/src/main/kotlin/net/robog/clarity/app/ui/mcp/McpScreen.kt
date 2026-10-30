package net.robog.clarity.app.ui.mcp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import net.robog.clarity.R
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.LoadingSpinner
import net.robog.clarity.app.ui.components.PrimaryButton
import net.robog.clarity.app.ui.components.SecondaryButton
import net.robog.clarity.app.ui.components.sentenceCase
import net.robog.clarity.app.ui.onboarding.ConnectorSetupContent
import net.robog.clarity.app.ui.onboarding.SetupActions
import net.robog.clarity.app.ui.onboarding.StepScaffold
import net.robog.clarity.app.ui.onboarding.notRunningState
import net.robog.clarity.app.ui.today.SectionTitle
import net.robog.clarity.shared.mcp.McpServerState
import net.robog.clarity.shared.mcp.McpServerStatus
import net.robog.clarity.shared.mcp.McpSuggestion
import net.robog.clarity.shared.mcp.McpToolMode
import net.robog.clarity.shared.mcp.McpToolStatus

/** Everything the MCP screens can do, grouped so previews and tests can pass no-ops. */
data class McpActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    /** "add server", or "set up" on a suggestion. */
    val onAdd: (McpSuggestion?) -> Unit = {},
    val onOpen: (serverId: String) -> Unit = {},
    val onClose: () -> Unit = {},
    val onEdit: (serverId: String) -> Unit = {},
    val onMode: (serverId: String, tool: String, mode: McpToolMode) -> Unit = { _, _, _ -> },
    val onReset: (serverId: String, tool: String) -> Unit = { _, _ -> },
    val onConfirm: () -> Unit = {},
    val onCancelConfirm: () -> Unit = {},
    val onDismiss: (suggestionId: String) -> Unit = {},
)

/**
 * Settings → MCP servers. [onAdd] and [onEdit] open the `mcp-host` connector's editor for one
 * server: adding (on a suggestion's preset) or editing the item with that id.
 */
@Composable
fun McpScreen(
    viewModel: McpViewModel,
    onBack: () -> Unit,
    onAdd: (McpSuggestion?) -> Unit,
    onEdit: (serverId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Each time the screen comes back (from the editor too), read the servers again.
    LaunchedEffect(viewModel) { viewModel.refresh() }
    // A server being installed changes on its own; check again until it is done.
    LaunchedEffect(state.anyInstalling) {
        while (state.anyInstalling) {
            delay(5.seconds)
            viewModel.refresh()
        }
    }
    BackHandler(enabled = state.selected != null) { viewModel.close() }
    McpContent(
        state,
        McpActions(
            onBack = onBack,
            onRetry = viewModel::refresh,
            onAdd = onAdd,
            onOpen = viewModel::open,
            onClose = viewModel::close,
            onEdit = onEdit,
            onMode = viewModel::chooseMode,
            onReset = viewModel::reset,
            onConfirm = viewModel::confirm,
            onCancelConfirm = viewModel::cancelConfirm,
            onDismiss = viewModel::dismiss,
        ),
    )
}

@Composable
fun McpContent(state: McpUiState, actions: McpActions) {
    val server = state.selectedServer
    when {
        state.hostNotRunning -> ConnectorSetupContent(
            notRunningState(MCP_HOST, stringResource(R.string.mcp_title)).copy(blurb = stringResource(R.string.mcp_not_running_blurb)),
            SetupActions(onBack = actions.onBack, onRecheck = actions.onRetry),
            step = null,
            backLabel = stringResource(R.string.mcp_back_settings),
        )
        server != null -> ServerDetail(server, state, actions)
        else -> ServerList(state, actions)
    }
    state.confirming?.let { choice ->
        val s = state.servers.firstOrNull { it.id == choice.serverId }
        val tool = s?.tools?.firstOrNull { it.name == choice.tool }
        if (s != null && tool != null) ConfirmAuto(s, tool, actions)
    }
}

// --- the list ---

@Composable
private fun ServerList(state: McpUiState, actions: McpActions) {
    StepScaffold(
        step = null,
        backLabel = stringResource(R.string.mcp_back_settings),
        onBack = actions.onBack,
        title = stringResource(R.string.mcp_title),
        footer = {
            if (state.error != null && state.servers.isEmpty()) {
                PrimaryButton(stringResource(R.string.mcp_retry), actions.onRetry, Modifier.fillMaxWidth())
            } else {
                PrimaryButton(stringResource(R.string.mcp_add), { actions.onAdd(null) }, Modifier.fillMaxWidth(), enabled = !state.loading)
            }
        },
    ) {
        Hint(stringResource(R.string.mcp_intro))
        state.changeError?.let { ErrorBox(it) }
        state.suggestions.forEach { SuggestionCard(it, actions) }
        when {
            state.loading && state.servers.isEmpty() -> LoadingSpinner()
            state.error != null -> ErrorBox(state.error)
            state.servers.isEmpty() -> Hint(stringResource(R.string.mcp_empty))
        }
        state.servers.forEach { ServerCard(it) { actions.onOpen(it.id) } }
    }
}

@Composable
private fun SuggestionCard(suggestion: McpSuggestion, actions: McpActions) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.mcp_suggests, suggestion.label), style = MaterialTheme.typography.titleMedium)
            Text(suggestion.why, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { actions.onDismiss(suggestion.id) }) { Text(stringResource(R.string.mcp_dismiss)) }
                FilledTonalButton(onClick = { actions.onAdd(suggestion) }) { Text(sentenceCase(stringResource(R.string.mcp_set_up))) }
            }
        }
    }
}

@Composable
private fun ServerCard(server: McpServerStatus, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(server.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                StateChip(server.state)
            }
            if (server.description.isNotBlank()) Text(server.description, style = MaterialTheme.typography.bodyMedium)
            if (server.source.isNotBlank()) Kicker(server.source)
            Text(toolCountLine(server), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            server.failure()?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** The error of a failed server, the only state that has one worth showing. */
private fun McpServerStatus.failure(): String? = error?.takeIf { state == McpServerState.FAILED && it.isNotBlank() }

@Composable
private fun toolCountLine(server: McpServerStatus): String {
    val counts = server.toolCounts()
    if (counts.total == 0) return stringResource(R.string.mcp_tools_none)
    return listOf(
        pluralStringResource(R.plurals.mcp_tools_count, counts.total, counts.total),
        stringResource(R.string.mcp_tools_read, counts.reads),
        pluralStringResource(R.plurals.mcp_tools_change, counts.changes, counts.changes),
    ).joinToString(" · ")
}

@Composable
fun StateChip(state: McpServerState, modifier: Modifier = Modifier) {
    val (container, content) = when (state.tone()) {
        ChipTone.Progress -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ChipTone.Quiet -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ChipTone.Active -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        ChipTone.Problem -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        ChipTone.Off -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(modifier, shape = CircleShape, color = container, contentColor = content) {
        Text(stateLabel(state), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun stateLabel(state: McpServerState): String = stringResource(
    when (state) {
        McpServerState.INSTALLING -> R.string.mcp_state_installing
        McpServerState.IDLE -> R.string.mcp_state_idle
        McpServerState.RUNNING -> R.string.mcp_state_running
        McpServerState.FAILED -> R.string.mcp_state_failed
        McpServerState.DISABLED -> R.string.mcp_state_disabled
    },
)

// --- one server ---

@Composable
private fun ServerDetail(server: McpServerStatus, state: McpUiState, actions: McpActions) {
    StepScaffold(
        step = null,
        backLabel = stringResource(R.string.mcp_back_servers),
        onBack = actions.onClose,
        title = server.label,
        footer = { SecondaryButton(stringResource(R.string.mcp_edit), { actions.onEdit(server.id) }, Modifier.fillMaxWidth()) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StateChip(server.state)
            if (server.description.isNotBlank()) Text(server.description, style = MaterialTheme.typography.bodyLarge)
            if (server.source.isNotBlank()) Kicker(server.source)
            if (server.allowHosts.isNotEmpty()) Kicker(stringResource(R.string.mcp_reaches, server.allowHosts.joinToString(", ")))
            server.collection?.let { Hint(stringResource(R.string.mcp_notes, it)) }
        }
        server.failure()?.let { ErrorBox(stringResource(R.string.mcp_state_failed), it) }
        state.changeError?.let { ErrorBox(it) }
        val groups = server.toolGroups()
        if (server.tools.isEmpty()) Hint(stringResource(R.string.mcp_no_tools))
        if (groups.reads.isNotEmpty()) ToolGroup(R.string.mcp_group_reads, R.string.mcp_reads_hint, server, groups.reads, actions)
        if (groups.changes.isNotEmpty()) ToolGroup(R.string.mcp_group_changes, R.string.mcp_changes_hint, server, groups.changes, actions)
    }
}

@Composable
private fun ToolGroup(title: Int, hint: Int, server: McpServerStatus, tools: List<McpToolStatus>, actions: McpActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SectionTitle(stringResource(title))
            Hint(stringResource(hint))
        }
        tools.forEach { ToolRow(server.id, it, actions) }
    }
}

@Composable
private fun ToolRow(serverId: String, tool: McpToolStatus, actions: McpActions) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tool.destructive) {
                    Icon(
                        Icons.Outlined.WarningAmber,
                        contentDescription = stringResource(R.string.mcp_destructive),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                Text(tool.title ?: tool.name, style = MaterialTheme.typography.titleMedium)
            }
            if (tool.title != null) {
                Text(tool.name, style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tool.description.isNotBlank()) Hint(tool.description)
            ModeControl(tool) { actions.onMode(serverId, tool.name, it) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (tool.overridden) {
                    Kicker(stringResource(R.string.mcp_default_is, modeLabel(defaultMode(tool))), Modifier.weight(1f))
                    TextButton(onClick = { actions.onReset(serverId, tool.name) }) { Text(stringResource(R.string.mcp_reset)) }
                } else {
                    Kicker(stringResource(R.string.mcp_default), Modifier.padding(vertical = 12.dp))
                }
            }
        }
    }
}

private val MODES = listOf(McpToolMode.AUTO, McpToolMode.ASK, McpToolMode.OFF)

@Composable
private fun ModeControl(tool: McpToolStatus, onChoose: (McpToolMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        MODES.forEachIndexed { i, mode ->
            SegmentedButton(
                selected = tool.mode == mode,
                onClick = { onChoose(mode) },
                shape = SegmentedButtonDefaults.itemShape(i, MODES.size),
            ) { Text(modeLabel(mode)) }
        }
    }
}

@Composable
private fun modeLabel(mode: McpToolMode): String = stringResource(
    when (mode) {
        McpToolMode.AUTO -> R.string.mcp_mode_auto
        McpToolMode.ASK -> R.string.mcp_mode_ask
        McpToolMode.OFF -> R.string.mcp_mode_off
    },
)

@Composable
private fun ConfirmAuto(server: McpServerStatus, tool: McpToolStatus, actions: McpActions) {
    AlertDialog(
        onDismissRequest = actions.onCancelConfirm,
        icon = if (tool.destructive) {
            { Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
        } else {
            null
        },
        title = { Text(stringResource(R.string.mcp_confirm_title, tool.title ?: tool.name)) },
        text = {
            val text = stringResource(R.string.mcp_confirm_text, server.label)
            Text(if (tool.destructive) text + " " + stringResource(R.string.mcp_confirm_destructive) else text)
        },
        confirmButton = { TextButton(onClick = actions.onConfirm) { Text(stringResource(R.string.mcp_confirm_yes)) } },
        dismissButton = { TextButton(onClick = actions.onCancelConfirm) { Text(stringResource(R.string.mcp_confirm_no)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        iconContentColor = Color.Unspecified,
    )
}
