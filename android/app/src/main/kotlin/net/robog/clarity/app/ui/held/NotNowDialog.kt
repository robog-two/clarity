package net.robog.clarity.app.ui.held

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.shared.governance.HeldAction

/**
 * "Not now" under the slider. Tapping opens a sheet with two choices: deny, or (when offered)
 * approve automatically from now on, which opens the drafted rule to edit before saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotNow(
    held: HeldAction,
    onDeny: () -> Unit,
    onAlways: (text: String, because: String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var sheet by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    TextButton(onClick = { sheet = true }, enabled = enabled, modifier = modifier) {
        Text("Not now", style = MaterialTheme.typography.labelLarge)
    }
    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            NotNowOptions(
                held,
                onDeny = { sheet = false; onDeny() },
                onAlways = { sheet = false; editing = true },
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
    if (editing) RuleDialog(held, onSave = { t, b -> editing = false; onAlways(t, b) }, onBack = { editing = false; sheet = true })
}

/** The sheet's two choices (also used by previews). */
@Composable
fun NotNowOptions(held: HeldAction, onDeny: () -> Unit, onAlways: () -> Unit, modifier: Modifier = Modifier) {
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
    Column(modifier.fillMaxWidth()) {
        Text(held.action.summary, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
        ListItem(
            modifier = Modifier.clickable(onClick = onDeny),
            colors = clear,
            leadingContent = { Icon(Icons.Outlined.Block, contentDescription = null) },
            headlineContent = { Text("Deny", style = MaterialTheme.clarityText.chat) },
            supportingContent = { Text("I won’t do it, and nothing changes in your rules", style = MaterialTheme.clarityText.chatSmall) },
        )
        if (canAlwaysAllow(held)) {
            ListItem(
                modifier = Modifier.clickable(onClick = onAlways),
                colors = clear,
                leadingContent = { Icon(Icons.Outlined.AutoMode, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text("Approve automatically from now on", style = MaterialTheme.clarityText.chat) },
                supportingContent = { Text("Review the rule first; this one is approved when you save it", style = MaterialTheme.clarityText.chatSmall) },
            )
        }
    }
}

/** The drafted rule, editable, before it is saved and this one approved. */
@Composable
fun RuleDialog(held: HeldAction, onSave: (text: String, because: String) -> Unit, onBack: () -> Unit) {
    // The draft may arrive while the dialog is open; it replaces the plain line until the person types.
    var typed by remember { mutableStateOf<String?>(null) }
    val rule = startingRule(held)
    val text = typed ?: rule.text
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("Add this rule?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("It goes in your constitution, and I’ll approve this one now.")
                OutlinedTextField(
                    value = text,
                    onValueChange = { typed = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.clarityText.chat,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    minLines = 2,
                )
                if (rule.because.isNotBlank()) Hint("Because ${rule.because}")
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text, rule.because) }, enabled = text.isNotBlank()) { Text("Add rule and approve") } },
        dismissButton = { TextButton(onClick = onBack) { Text("Back") } },
    )
}
