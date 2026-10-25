package net.robog.clarity.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.data.UrlError

@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel = metroViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnboardingContent(
        state = state,
        onUrlChanged = viewModel::onUrlChanged,
        onTokenChanged = viewModel::onTokenChanged,
        onConnect = viewModel::connect,
    )
}

@Composable
fun OnboardingContent(
    state: OnboardingState,
    onUrlChanged: (String) -> Unit,
    onTokenChanged: (String) -> Unit,
    onConnect: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text("Welcome to Clarity", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Paste the link to your Clarity instance to connect this phone.",
                style = MaterialTheme.typography.bodyLarge,
            )
            val urlError = (state.error as? OnboardingError.InvalidUrl)?.reason
            OutlinedTextField(
                value = state.url,
                onValueChange = onUrlChanged,
                label = { Text("Instance URL") },
                placeholder = { Text("https://clarity.example.com") },
                singleLine = true,
                isError = urlError != null,
                supportingText = urlError?.let { { Text(it.message()) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            val tokenError = state.error is OnboardingError.TokenRequired
            OutlinedTextField(
                value = state.token,
                onValueChange = onTokenChanged,
                label = { Text("Enrollment token (optional)") },
                singleLine = true,
                isError = tokenError,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.takeIf { it !is OnboardingError.InvalidUrl }?.let {
                Text(it.message(), color = MaterialTheme.colorScheme.error)
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Button(onClick = onConnect, enabled = state.canSubmit) { Text("Connect") }
                if (state.connecting) {
                    CircularProgressIndicator(Modifier.padding(start = 16.dp).size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

private fun UrlError.message(): String = when (this) {
    UrlError.Empty -> "Enter the address of your instance."
    UrlError.Malformed -> "That does not look like a valid URL."
    UrlError.InsecureScheme -> "The instance URL must start with https://."
    UrlError.UnsupportedScheme -> "The instance URL must start with https://."
}

private fun OnboardingError.message(): String = when (this) {
    is OnboardingError.InvalidUrl -> reason.message()
    OnboardingError.Unreachable -> "Could not reach the instance. Check the address and your connection."
    OnboardingError.TokenRequired -> "The instance requires a valid enrollment token."
    is OnboardingError.Server -> "The instance returned an error (HTTP $status)."
    OnboardingError.Unexpected -> "Unexpected response. Is this a Clarity instance?"
}
