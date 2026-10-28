package net.robog.clarity.app.ui.goods

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    // The download page's link goes to the browser (Download) or the share sheet (Share).
    LaunchedEffect(state.open) {
        val open = state.open ?: return@LaunchedEffect
        viewModel.opened(handOverLink(context, open.url, open.title, share = open.action == LinkAction.Share))
    }
    ReportContent(state, onBack, backLabel, viewModel::link, viewModel::retry, viewModel::turnOffLink)
}

/**
 * The viewer: a top bar with back, Download and Share (both use the Good's one download page, which
 * has every format); what it is and when it changed; the text as it reads; the web sources it cites.
 */
@Composable
fun ReportContent(
    state: ReportUiState,
    onBack: () -> Unit,
    backLabel: String,
    onLink: (LinkAction) -> Unit,
    onRetry: () -> Unit,
    onTurnOffLink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        ViewerTopBar(backLabel, onBack, state, onLink)
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
                item(key = "header") { ReportHeader(state, Modifier.padding(bottom = 12.dp)) }
                if (state.linkOn || state.linkNote != null) item(key = "link") { LinkLine(state, onTurnOffLink, Modifier.padding(bottom = 8.dp)) }
                item(key = "rule") { HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 22.dp), color = MaterialTheme.colorScheme.outlineVariant) }
                val document = state.document
                when {
                    document == null -> item(key = "no-text") {
                        ErrorBox("Can’t show the text here", (state.sourceError ?: "It didn’t come through.") + " Download still works.")
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

/** Back on the left; Download and Share, plain icon buttons, on the right (once there is a Good to act on). */
@Composable
private fun ViewerTopBar(backLabel: String, onBack: () -> Unit, state: ReportUiState, onLink: (LinkAction) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = ScreenGutter, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BackLink(backLabel, onBack)
        Spacer(Modifier.weight(1f))
        if (!state.loading && state.error == null) {
            LinkButton(Icons.Outlined.FileDownload, "Download", state.busy == LinkAction.Download, state.busy == null) { onLink(LinkAction.Download) }
            LinkButton(Icons.Outlined.Share, "Share", state.busy == LinkAction.Share, state.busy == null) { onLink(LinkAction.Share) }
        }
    }
}

@Composable
private fun LinkButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled || busy) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = "$label: getting the link" }, strokeWidth = 2.dp)
        else Icon(icon, contentDescription = label)
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

/** While the download link is on, says so quietly, with a way to turn it off; or the last note about it. */
@Composable
private fun LinkLine(state: ReportUiState, onTurnOffLink: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            state.linkNote ?: "Anyone with the link can download it.",
            Modifier.weight(1f),
            style = MaterialTheme.clarityText.chatSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.linkOn) TextButton(onClick = onTurnOffLink) { Text("Turn off link") }
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
