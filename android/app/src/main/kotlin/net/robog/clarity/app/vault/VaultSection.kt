package net.robog.clarity.app.vault

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
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
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.app.ui.today.SectionTitle

/** Pure: a stored handle as a readable title ("chase_checking" → "Chase checking"). */
fun credentialTitle(name: String): String = sentenceCase(name.replace('_', ' ').trim())

/** The "Vault" section of Settings. */
@Composable
fun VaultSection(viewModel: VaultViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    VaultSectionContent(state, viewModel::delete, viewModel::rekey)
}

@Composable
fun VaultSectionContent(
    state: VaultUiState,
    onDelete: (net.robog.clarity.shared.vault.CredentialId) -> Unit,
    onRekey: () -> Unit,
) {
    var deleting by remember { mutableStateOf<CredentialRow?>(null) }
    SectionTitle("Vault")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hint("Logins and cards I can use for you, each locked on your phone. I can’t read them; I ask you every time.")
        state.message?.let { ErrorBox(it) }
        state.notice?.let { Hint(it) }
        when (state.keys) {
            VaultKeyStatus.INVALIDATED -> {
                Hint("A new fingerprint or face was added, so the saved logins can’t be opened any more.")
                SecondaryButton("reset the vault keys", onRekey, Modifier.fillMaxWidth())
            }
            VaultKeyStatus.MISSING, VaultKeyStatus.READY -> Unit
        }
        Column {
            if (!state.loading && state.rows.isEmpty()) Hint("Nothing saved yet. When I need a login or card, I’ll ask you here.")
            state.rows.forEach { row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(credentialTitle(row.name), style = MaterialTheme.clarityText.chat) },
                    supportingContent = {
                        Text(sentenceCase(listOf(row.kind, row.site, row.detail).filter { it.isNotBlank() }.joinToString(" · ")), style = MaterialTheme.clarityText.chatSmall)
                    },
                    trailingContent = {
                        IconButton(onClick = { deleting = row }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete ${credentialTitle(row.name)}")
                        }
                    },
                )
            }
        }
    }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${credentialTitle(row.name)}?") },
            text = { Text("I won’t be able to use it again until you add it again.") },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(row.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}
