package net.robog.clarity.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.InfoBox
import net.robog.clarity.app.ui.components.LoadingSpinner
import net.robog.clarity.app.ui.components.PrimaryButton
import net.robog.clarity.app.ui.components.SecondaryButton
import net.robog.clarity.app.ui.components.CardGroup
import net.robog.clarity.app.ui.components.SegmentCard
import net.robog.clarity.app.ui.theme.Space

/** Callbacks of the "Backups" page, so previews and tests can pass no-ops. */
data class BackupsActions(
    val onBackUpNow: () -> Unit = {},
    val onRollBack: (name: String) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onDismissResult: () -> Unit = {},
)

/** The "Backups" page of Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupsSection(viewModel: BackupsViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = BackupsActions(viewModel::backUpNow, viewModel::askRollback, viewModel::confirmRollback, viewModel::cancelRollback, viewModel::dismissResult)
    BackupsContent(state, actions)
    (state.stage as? RollbackStage.Confirm)?.let { confirm ->
        ModalBottomSheet(onDismissRequest = actions.onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            RollbackConfirm(confirm.row, actions.onConfirm, actions.onCancel, Modifier.padding(bottom = Space.Large))
        }
    }
}

@Composable
fun BackupsContent(state: BackupsUiState, actions: BackupsActions) {
    if (!state.available) {
        Hint("Your server doesn’t make backups yet.")
        return
    }
    when (val stage = state.stage) {
        is RollbackStage.Waiting -> {
            RollbackWaiting(stage.row)
            return
        }
        is RollbackStage.Finished -> {
            RollbackFinished(stage, actions.onDismissResult)
            return
        }
        else -> Unit
    }
    Hint("Every night at 2 am the whole database is saved. The last 7 are kept.")
    state.message?.let { ErrorBox(it) }
    SecondaryButton(
        "Back up now", actions.onBackUpNow,
        Modifier.fillMaxWidth(),
        enabled = !state.serverBusy && state.stage == RollbackStage.Idle,
        busy = state.backingUp,
    )
    when {
        state.loading && state.rows.isEmpty() -> LoadingSpinner()
        state.rows.isEmpty() -> Hint("No backups yet. The first one is made tonight.")
        else -> CardGroup(state.rows) { row, shape, _ -> BackupItem(row, shape, enabled = !state.serverBusy && state.stage == RollbackStage.Idle, actions.onRollBack) }
    }
}

@Composable
private fun BackupItem(row: BackupRow, shape: Shape, enabled: Boolean, onRollBack: (String) -> Unit) {
    SegmentCard(shape) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.Medium), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal))
                Text(row.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(
                onClick = { onRollBack(row.name) },
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "Roll back to ${row.title}" },
            ) { Text("Roll back") }
        }
    }
}

/** The sheet that says plainly what a rollback costs, before the fingerprint or face prompt. */
@Composable
fun RollbackConfirm(row: BackupRow, onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = Space.Large), verticalArrangement = Arrangement.spacedBy(Space.Medium)) {
        Text("Roll back to ${row.title}?", style = MaterialTheme.typography.titleLarge)
        Text(
            "Everything since then is lost. That includes messages, notes and decisions.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "A copy of now is kept first, so this can be undone. It restarts for a minute.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryButton("Roll back", onConfirm, Modifier.fillMaxWidth())
        SecondaryButton("Cancel", onCancel, Modifier.fillMaxWidth())
    }
}

/** While the server restores and restarts. */
@Composable
fun RollbackWaiting(row: BackupRow) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = Space.Large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.Medium),
    ) {
        LoadingSpinner()
        Text("Rolling back to ${row.title}", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Hint("It restarts and reconnects by itself.", textAlign = TextAlign.Center)
    }
}

@Composable
private fun RollbackFinished(stage: RollbackStage.Finished, onDone: () -> Unit) {
    if (stage.ok) {
        InfoBox {
            Text(stage.title, style = MaterialTheme.typography.titleMedium)
            stage.detail?.let { Hint(it) }
        }
    } else {
        ErrorBox(stage.title, stage.detail)
    }
    SecondaryButton("Done", onDone, Modifier.fillMaxWidth())
}
