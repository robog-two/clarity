package net.robog.clarity.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.ScreenTitle
import net.robog.clarity.app.ui.components.SecondaryButton

/** Placeholder settings tab: the enrolled instance and "forget". The real settings are `app-screens`. */
@Composable
fun SettingsTab(modifier: Modifier = Modifier, viewModel: SettingsViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(state, viewModel::forgetInstance, modifier)
}

@Composable
fun SettingsContent(state: SettingsUiState, onForget: () -> Unit, modifier: Modifier = Modifier) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("Settings")
        LabeledValue("server", state.instanceUrl)
        LabeledValue("this device", state.deviceId)
        SecondaryButton("forget this server", { confirming = true }, Modifier.fillMaxWidth())
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Forget this server?") },
            text = { Text("This removes the device key and the saved feed from this phone. You will need to set up again.") },
            confirmButton = {
                TextButton(onClick = { confirming = false; onForget() }) { Text("forget") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("cancel") } },
        )
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Kicker(label)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
