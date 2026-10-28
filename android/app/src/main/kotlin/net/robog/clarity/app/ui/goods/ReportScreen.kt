package net.robog.clarity.app.ui.goods

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zacsweers.metrox.viewmodel.metroViewModel
import net.robog.clarity.app.ui.components.BackLink
import net.robog.clarity.app.ui.components.ErrorBox
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.components.LoadingSpinner
import net.robog.clarity.app.ui.components.ScreenGutter
import net.robog.clarity.app.ui.components.groupShape
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.shared.report.ReportCitation

/** One Good, opened from the Goods tab or a card in the thread. */
@Composable
fun ReportScreen(reportId: String, onBack: () -> Unit, backLabel: String = "Goods", viewModel: ReportViewModel = metroViewModel()) {
    LaunchedEffect(reportId) { viewModel.open(reportId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler { onBack() }
    val context = LocalContext.current
    var noApp by remember { mutableStateOf<String?>(null) }
    // A finished download goes to the app that opens it, or to the share sheet.
    LaunchedEffect(state.open) {
        val open = state.open ?: return@LaunchedEffect
        noApp = if (handOver(context, open.file, open.title, open.share)) null else "No app on this phone takes this kind of file."
        viewModel.opened()
    }
    ReportContent(state, onBack, backLabel, viewModel::download, viewModel::retry, notice = noApp)
}

/**
 * The viewer: what it is and when it changed, the downloads, the text as it reads, and the web
 * sources it cites. The text is drawn here from the Markdown; the downloads are the server's files.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReportContent(
    state: ReportUiState,
    onBack: () -> Unit,
    backLabel: String,
    onDownload: (format: String, share: Boolean) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    notice: String? = null,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        BackLink(backLabel, onBack, Modifier.padding(horizontal = ScreenGutter))
        when {
            state.loading -> LoadingSpinner(Modifier.padding(top = 48.dp))
            state.error != null -> Column(Modifier.padding(horizontal = ScreenGutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ErrorBox("Can’t open this one", state.error)
                TextButton(onClick = onRetry) { Text("Try again") }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 40.dp),
            ) {
                item(key = "header") { ReportHeader(state, Modifier.padding(bottom = 16.dp)) }
                item(key = "downloads") { Downloads(state, onDownload, notice, Modifier.padding(bottom = 20.dp)) }
                item(key = "rule") { HorizontalDivider(Modifier.padding(bottom = 22.dp), color = MaterialTheme.colorScheme.outlineVariant) }
                val document = state.document
                when {
                    document == null -> item(key = "no-text") {
                        ErrorBox("Can’t show the text here", (state.sourceError ?: "It didn’t come through.") + " The downloads still work.")
                    }
                    document.blocks.isEmpty() -> item(key = "empty") { Hint("Nothing written in this one yet.") }
                    else -> markdownItems(document.blocks)
                }
                if (state.sources.isNotEmpty()) {
                    item(key = "sources-title") {
                        Kicker("sources · ${state.sources.size}", Modifier.padding(top = 32.dp, bottom = 8.dp).semantics { heading() })
                    }
                    itemsIndexed(state.sources, key = { _, s -> "source-" + s.url }) { i, source ->
                        SourceRow(source, groupShape(i, state.sources.size), Modifier.padding(bottom = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportHeader(state: ReportUiState, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Kicker(state.subtitle)
        Text(
            state.title,
            Modifier.semantics { heading() },
            style = MaterialTheme.clarityText.heroTitle.copy(fontSize = 28.sp, lineHeight = 34.sp),
        )
        val meta = state.document?.meta
        val byline = listOfNotNull(meta?.author, meta?.date).joinToString(" · ")
        if (byline.isNotEmpty()) Hint(byline)
    }
}

/** A button per format (opens it in another app) and one that shares a copy in the format chosen. */
@Composable
private fun Downloads(state: ReportUiState, onDownload: (String, Boolean) -> Unit, notice: String?, modifier: Modifier = Modifier) {
    var shareMenu by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            state.formats.forEach { format ->
                val busy = state.downloads[format.wire] == DownloadState.Busy
                FilledTonalButton(
                    onClick = { onDownload(format.wire, false) },
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    modifier = Modifier.semantics { contentDescription = if (busy) "Downloading ${format.label}" else "Open as ${format.label}" },
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                    Text(format.label, Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }
            if (state.formats.isNotEmpty()) {
                Box {
                    OutlinedIconButton(onClick = { shareMenu = true }) { Icon(Icons.Outlined.Share, contentDescription = "Share a copy") }
                    DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                        state.formats.forEach { format ->
                            DropdownMenuItem(text = { Text("Share as ${format.label}") }, onClick = { shareMenu = false; onDownload(format.wire, true) })
                        }
                    }
                }
            }
        }
        val failed = state.downloads.entries.firstNotNullOfOrNull { (wire, s) ->
            (s as? DownloadState.Failed)?.let { f -> (state.formats.firstOrNull { it.wire == wire }?.label ?: wire) to f.message }
        }
        failed?.let { (label, message) -> Text("$label didn’t download. $message", style = MaterialTheme.clarityText.chatSmall, color = MaterialTheme.colorScheme.error) }
        notice?.let { Text(it, style = MaterialTheme.clarityText.chatSmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun SourceRow(source: ReportCitation, shape: androidx.compose.ui.graphics.Shape, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    Surface(onClick = { runCatching { uri.openUri(source.url) } }, modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = shape) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(source.title ?: sourceSite(source.url), style = MaterialTheme.clarityText.chat, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(sourceSite(source.url), style = MaterialTheme.clarityText.chatSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open in the browser", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}
