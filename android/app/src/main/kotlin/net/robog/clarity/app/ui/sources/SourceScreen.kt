package net.robog.clarity.app.ui.sources

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.datetime.TimeZone
import net.robog.clarity.app.ui.components.CardGroup
import net.robog.clarity.app.ui.components.ClarityListScreen
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.LoadingSpinner
import net.robog.clarity.app.ui.components.SegmentCard
import net.robog.clarity.app.ui.theme.Space
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.shared.source.SourceAttachment

/** One record the agent cited, read-only: opened from a sources card in the thread. */
@Composable
fun SourceScreen(entityId: String, onBack: () -> Unit, backLabel: String = "Ask", viewModel: SourceViewModel = metroViewModel()) {
    LaunchedEffect(entityId) { viewModel.open(entityId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler { onBack() }
    val context = LocalContext.current
    // A downloaded attachment goes to an app that can read it.
    LaunchedEffect(state.open) {
        val request = state.open ?: return@LaunchedEffect
        viewModel.opened(handOverFile(context, request.file.bytes, request.name, request.file.mimeType))
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.note) {
        val note = state.note ?: return@LaunchedEffect
        viewModel.noteShown()
        snackbar.showSnackbar(note)
    }
    SourceContent(state, onBack, backLabel, viewModel::retry, viewModel::download, snackbar = snackbar)
}

/**
 * The viewer: what the record is, its header lines, its text as it is stored (selectable), and for a
 * message its attachments, each opened on tap.
 */
@Composable
fun SourceContent(
    state: SourceUiState,
    onBack: () -> Unit,
    backLabel: String,
    onRetry: () -> Unit,
    onDownload: (String) -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    zone: TimeZone = remember { TimeZone.currentSystemDefault() },
) {
    val view = state.view
    ClarityListScreen(
        title = view?.title,
        modifier = modifier,
        kicker = view?.let(::sourceKind),
        backLabel = backLabel,
        onBack = onBack,
        overlay = {
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(Space.Medium))
        },
    ) {
        when {
            state.loading -> item(key = "loading") { LoadingSpinner() }
            state.error != null || view == null -> item(key = "error") {
                Column(verticalArrangement = Arrangement.spacedBy(Space.Medium)) {
                    ErrorBox("Can’t open this", state.error)
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
            }
            else -> {
                val facts = sourceFacts(view, zone)
                if (facts.isNotEmpty()) item(key = "facts") {
                    CardGroup(facts) { fact, shape, _ ->
                        SegmentCard(shape) {
                            Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                                Kicker(fact.label)
                                SelectionContainer { Text(fact.value, style = MaterialTheme.clarityText.chat) }
                            }
                        }
                    }
                }
                sourceText(view)?.let { body ->
                    item(key = "text") {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                            Kicker(body.label, heading = true)
                            SelectionContainer { Text(body.text, style = MaterialTheme.clarityText.reading) }
                        }
                    }
                }
                val files = sourceAttachments(view)
                if (files.isNotEmpty()) item(key = "files") {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                        Kicker(if (files.size == 1) "1 file" else "${files.size} files", heading = true)
                        CardGroup(files) { file, shape, _ ->
                            FileRow(file, state.files[file.part], shape) { onDownload(file.part) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(file: SourceAttachment, state: FileState?, shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit) {
    val name = file.filename ?: "Attachment"
    SegmentCard(
        shape,
        Modifier.semantics { contentDescription = "Open $name" },
        onClick = onClick.takeIf { state != FileState.Loading },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.Medium), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                Text(name, style = MaterialTheme.clarityText.chat)
                Text(attachmentLine(file), style = MaterialTheme.clarityText.chatSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state is FileState.Failed) Text(state.message, style = MaterialTheme.clarityText.chatSmall, color = MaterialTheme.colorScheme.error)
            }
            when (state) {
                FileState.Loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else -> Icon(Icons.Outlined.FileDownload, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
