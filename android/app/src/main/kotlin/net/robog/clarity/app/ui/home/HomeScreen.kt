package net.robog.clarity.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun HomeScreen(
    onOpenConnectors: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = metroViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeContent(state, viewModel::refresh, onOpenConnectors, onOpenSettings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: HomeUiState,
    onRetry: () -> Unit,
    onOpenConnectors: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clarity") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state) {
                HomeUiState.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }
                is HomeUiState.Content -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(state.instance.name, style = MaterialTheme.typography.titleLarge)
                        Text("Version ${state.instance.version}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is HomeUiState.Error -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.kind.message(), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onRetry) { Text("Retry") }
                    }
                }
            }
            Button(onClick = onOpenConnectors, modifier = Modifier.fillMaxWidth()) { Text("Connectors") }
        }
    }
}

private fun HomeUiState.Kind.message(): String = when (this) {
    HomeUiState.Kind.Unreachable -> "Could not reach your instance."
    HomeUiState.Kind.Unauthorized -> "This device is no longer authorized. Forget the instance in Settings and connect again."
    HomeUiState.Kind.Other -> "Something went wrong talking to your instance."
}
