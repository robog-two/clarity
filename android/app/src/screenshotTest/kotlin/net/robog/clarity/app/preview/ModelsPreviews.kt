package net.robog.clarity.app.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import net.robog.clarity.app.ui.components.ScreenGutter
import net.robog.clarity.app.ui.settings.ModelsActions
import net.robog.clarity.app.ui.settings.ModelsSectionContent
import net.robog.clarity.app.ui.settings.ModelsUiState
import net.robog.clarity.app.ui.settings.modelRows
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.shared.session.ModelSettingsView
import net.robog.clarity.shared.session.RoleModelSettings
import net.robog.clarity.shared.session.RoleModelView
import net.robog.clarity.shared.session.SettingSource

private fun settings(provider: String, model: String, effort: String) =
    RoleModelSettings(provider, model, effort, 32_000, "5m", false, 150_000)

private fun role(name: String, default: RoleModelSettings, effective: RoleModelSettings = default): RoleModelView {
    val fields = listOf("provider", "model", "effort", "maxTokens", "cacheTtl", "compaction", "compactAtTokens")
    val changed = setOfNotNull(
        "provider".takeIf { effective.provider != default.provider },
        "model".takeIf { effective.model != default.model },
        "effort".takeIf { effective.effort != default.effort },
    )
    return RoleModelView(name, effective, default, fields.associateWith { if (it in changed) SettingSource.OVERRIDE else SettingSource.DEFAULT })
}

private fun view(openRouterKey: Boolean) = ModelSettingsView(
    roles = listOf(
        role("lead", settings("anthropic", "claude-opus-5-5", "medium")),
        role("helper", settings("anthropic", "claude-sonnet-5-5", "medium"), settings("anthropic", "claude-sonnet-5-5", "high")),
        role(
            "guard", settings("anthropic", "claude-haiku-5-5", "low"),
            if (openRouterKey) settings("openrouter", "anthropic/claude-haiku-5.5", "low") else settings("anthropic", "claude-haiku-5-5", "low"),
        ),
        role("triage", settings("anthropic", "claude-haiku-5-5", "low")),
    ),
    providersWithKeys = if (openRouterKey) listOf("anthropic", "openrouter") else listOf("anthropic"),
)

@Composable
private fun ModelsFrame(state: ModelsUiState) = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = ScreenGutter, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelsSectionContent(state, ModelsActions())
        }
    }
}

// The section is taller than one render, so each preview shows two of the four roles.

@PreviewTest @Part @Composable
fun SettingsModels() = ModelsFrame(
    ModelsUiState(loading = false, rows = modelRows(view(openRouterKey = true)).take(2), openRouterKey = true),
)

@PreviewTest @Part @Composable
fun SettingsModelsGuardOnOpenRouter() = ModelsFrame(
    ModelsUiState(loading = false, rows = modelRows(view(openRouterKey = true)).drop(2), openRouterKey = true),
)

@PreviewTest @Part @Composable
fun SettingsModelsNoOpenRouterKey() = ModelsFrame(
    ModelsUiState(
        loading = false,
        rows = modelRows(view(openRouterKey = false), errors = mapOf("triage" to "Effort is one of low, medium, high, xhigh, max.")).drop(2),
        openRouterKey = false,
    ),
)
