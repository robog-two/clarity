package net.robog.clarity.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.ui.components.ClarityDropdown
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.LoadingSpinner
import net.robog.clarity.app.ui.components.sentenceCase
import net.robog.clarity.app.ui.today.SectionTitle

/** Callbacks of the "Models" section, so previews and tests can pass no-ops. */
data class ModelsActions(
    val onModel: (role: String, model: String) -> Unit = { _, _ -> },
    val onEffort: (role: String, effort: String) -> Unit = { _, _ -> },
    val onReset: (role: String) -> Unit = {},
)

/** The "Models" section of Settings. */
@Composable
fun ModelsSection(showTitle: Boolean = true, viewModel: ModelsViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ModelsSectionContent(state, ModelsActions(viewModel::setModel, viewModel::setEffort, viewModel::reset), showTitle)
}

/** [showTitle] is off when the section is a page of its own and the page already names it. */
@Composable
fun ModelsSectionContent(state: ModelsUiState, actions: ModelsActions, showTitle: Boolean = true) {
    if (!state.available) {
        if (!showTitle) Hint("Your server doesn’t let you choose models yet.")
        return
    }
    if (state.loading && state.rows.isEmpty()) {
        if (!showTitle) LoadingSpinner()
        return
    }
    if (showTitle) SectionTitle("Models")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Hint("Which model does each part of the work, by its OpenRouter name. Changes are saved on your server.")
        state.message?.let { ErrorBox(it) }
        state.rows.forEach { row -> RoleModelItem(row, state.efforts, actions) }
    }
}

@Composable
private fun RoleModelItem(row: RoleModelRow, efforts: List<String>, actions: ModelsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(sentenceCase(row.title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal))
                Kicker(row.caption)
            }
            if (!row.isDefault) TextButton(onClick = { actions.onReset(row.role) }) { Text("Reset to default") }
        }
        ModelField(row, onModel = { actions.onModel(row.role, it) })
        ClarityDropdown(
            label = "Effort",
            selected = row.effort,
            options = efforts.map { it to effortLabel(it) },
            onSelect = { actions.onEffort(row.role, it) },
        )
        row.note?.let { Hint(it) }
        row.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** The model name: typed freely, or picked from a few suggestions. Saved on done, on a pick, or when the field is left. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelField(row: RoleModelRow, onModel: (String) -> Unit) {
    var draft by rememberSaveable(row.role, row.model) { mutableStateOf(row.model) }
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    fun commit() {
        if (draft.isNotBlank() && draft.trim() != row.model) onModel(draft.trim())
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .onFocusChanged { f ->
                    if (focused && !f.isFocused) commit()
                    focused = f.isFocused
                },
            label = { Text("Model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = {
                expanded = false
                commit()
            }),
            textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
            shape = MaterialTheme.shapes.extraSmall,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            row.suggestions.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        draft = name
                        if (name != row.model) onModel(name)
                    },
                )
            }
        }
    }
}
