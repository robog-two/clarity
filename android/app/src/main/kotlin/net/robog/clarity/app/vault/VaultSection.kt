package net.robog.clarity.app.vault

import net.robog.clarity.app.ui.theme.Space

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.LoadingSpinner
import net.robog.clarity.app.ui.components.SecondaryButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import net.robog.clarity.app.ui.components.sentenceCase
import net.robog.clarity.app.ui.components.CardGroup
import net.robog.clarity.app.ui.components.SegmentCard
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.app.ui.today.SectionTitle

/** Pure: a stored handle as a readable title ("chase_checking" → "Chase checking"). */
fun credentialTitle(name: String): String = sentenceCase(name.replace('_', ' ').trim())

/** The "Vault" section of Settings. */
@Composable
fun VaultSection(showTitle: Boolean = true, viewModel: VaultViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    VaultSectionContent(state, viewModel::delete, viewModel::rekey, showTitle)
}

@Composable
fun VaultSectionContent(
    state: VaultUiState,
    onDelete: (net.robog.clarity.shared.vault.CredentialId) -> Unit,
    onRekey: () -> Unit,
    /** Off when the section is a page of its own and the page already names it. */
    showTitle: Boolean = true,
) {
    var deleting by remember { mutableStateOf<CredentialRow?>(null) }
    if (showTitle) SectionTitle("Vault", Modifier.semantics { heading() })
    Column(verticalArrangement = Arrangement.spacedBy(Space.Medium)) {
        Hint("Logins and cards stay locked on this phone. Each use needs your approval.")
        state.message?.let { ErrorBox(it) }
        state.notice?.let { Hint(it) }
        when (state.keys) {
            VaultKeyStatus.INVALIDATED -> {
                Hint("A new fingerprint or face was added. Saved logins can’t be opened. Reset the keys and add them again.")
                SecondaryButton("reset the vault keys", onRekey, Modifier.fillMaxWidth())
            }
            VaultKeyStatus.MISSING, VaultKeyStatus.READY -> Unit
        }
        if (state.loading && state.rows.isEmpty()) LoadingSpinner()
        else if (state.rows.isEmpty()) Hint("Nothing saved yet.")
        CardGroup(state.rows) { row, shape, _ ->
            SegmentCard(shape) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.Medium), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                        Text(credentialTitle(row.name), style = MaterialTheme.clarityText.chat)
                        Text(
                            sentenceCase(listOf(row.kind, row.site, row.detail).filter { it.isNotBlank() }.joinToString(", ")),
                            style = MaterialTheme.clarityText.chatSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { deleting = row }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete ${credentialTitle(row.name)}")
                    }
                }
            }
        }
    }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${credentialTitle(row.name)}?") },
            text = { Text("It can’t be used until you add it again.") },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(row.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}
