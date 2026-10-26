package net.robog.clarity.app.ui.held

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.shared.governance.HeldAction

/**
 * The plain "not now" words under the slider. Tapping opens a pop-up: "deny", or (when offered)
 * "approve automatically from now on", which turns the pop-up into the drafted rule: tap to edit
 * the text, then "approve" (saves the rule and approves this one) or "back".
 */
@Composable
fun NotNow(
    held: HeldAction,
    onDeny: () -> Unit,
    onAlways: (text: String, because: String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, enabled = enabled, modifier = modifier) {
        Text("not now", style = MaterialTheme.typography.labelLarge)
    }
    if (open) NotNowDialog(held, onDeny = { open = false; onDeny() }, onAlways = { t, b -> open = false; onAlways(t, b) }, onDismiss = { open = false })
}

@Composable
fun NotNowDialog(
    held: HeldAction,
    onDeny: () -> Unit,
    onAlways: (text: String, because: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    // The draft may arrive while the pop-up is open; it replaces the plain line until the person types.
    var typed by remember { mutableStateOf<String?>(null) }
    val rule = startingRule(held)
    val text = typed ?: rule.text
    if (!editing) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Not now") },
            text = { Text(held.action.summary, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                // "deny" is the filled one: the quick tap to make it go away never loosens a rule; the rule change is outlined.
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = onDeny, modifier = Modifier.fillMaxWidth()) { Text("deny") }
                    if (canAlwaysAllow(held)) OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { Text("approve automatically from now on") }
                    TextButton(onClick = onDismiss) { Text("cancel") }
                }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add this rule?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Hint("It goes in your constitution, and I'll approve this one now.")
                    OutlinedTextField(
                        value = text,
                        onValueChange = { typed = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        minLines = 2,
                    )
                    if (rule.because.isNotBlank()) Hint("because ${rule.because}")
                }
            },
            confirmButton = { TextButton(onClick = { onAlways(text, rule.because) }, enabled = text.isNotBlank()) { Text("approve") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("back") } },
        )
    }
}
