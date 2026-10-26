package net.robog.clarity.app.ui.held

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.ScreenTitle
import net.robog.clarity.app.ui.components.SecondaryButton
import net.robog.clarity.shared.governance.HeldActionId

@Composable
fun HeldScreen(
    onOpen: (HeldActionId) -> Unit,
    modifier: Modifier = Modifier,
    onReview: (HeldActionId) -> Unit = onOpen,
    viewModel: HeldViewModel = metroViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HeldContent(state, onOpen, viewModel::toggleAllowed, modifier, onReview)
}

@Composable
fun HeldContent(
    state: HeldUiState,
    onOpen: (HeldActionId) -> Unit,
    onToggleAllowed: () -> Unit,
    modifier: Modifier = Modifier,
    onReview: (HeldActionId) -> Unit = onOpen,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Kicker(state.kicker)
        ScreenTitle("Held for You")
        Hint("Things I tried that hit one of your lines. Tap one to review it.")
        Column {
            if (state.rows.isEmpty()) Hint("Nothing is held right now.")
            state.rows.forEach { row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                HeldRowView(row, onClick = { if (row.review) onReview(row.id) else onOpen(row.id) })
            }
            if (state.rows.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Kicker("low-urgency holds wait for an evening batch", Modifier.padding(top = 8.dp))
        SecondaryButton(if (state.showAllowed) "hide allowed actions" else "see allowed actions", onToggleAllowed, Modifier.fillMaxWidth())
        if (state.showAllowed) {
            Column {
                if (state.allowed.isEmpty()) Hint("Nothing yet.")
                state.allowed.forEach { a ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(a.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Kicker(if (a.ok) a.whenText else "${a.whenText} · failed")
                    }
                }
            }
        }
    }
}
