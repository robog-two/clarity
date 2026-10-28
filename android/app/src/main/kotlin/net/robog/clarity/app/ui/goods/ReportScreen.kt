package net.robog.clarity.app.ui.goods

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
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
import net.robog.clarity.app.ui.components.PrimaryButton
import net.robog.clarity.app.ui.components.ScreenGutter
import net.robog.clarity.app.ui.components.SecondaryButton
import net.robog.clarity.app.ui.components.groupShape
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.shared.report.ReportCitation

/** One Good, opened from the Goods tab or a card in the thread. */
@OptIn(ExperimentalMaterial3Api::class)
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
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.linkNote) {
        val note = state.linkNote ?: return@LaunchedEffect
        viewModel.noteShown()
        snackbar.showSnackbar(note)
    }
    var settings by rememberSaveable { mutableStateOf(false) }
    ReportContent(state, onBack, backLabel, viewModel::link, viewModel::retry, onShareSettings = { settings = true }, snackbar = snackbar)
    if (settings) {
        ModalBottomSheet(onDismissRequest = { settings = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ShareSettings(state, viewModel::turnOnLink, viewModel::turnOffLink, Modifier.navigationBarsPadding())
        }
    }
}

/**
 * The viewer: what it is and when it changed, the text as it reads, and the web sources it cites,
 * with one FAB at the bottom right whose menu has Download (the Good's download page, in the
 * browser), Share (its link) and the link's settings.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReportContent(
    state: ReportUiState,
    onBack: () -> Unit,
    backLabel: String,
    onLink: (LinkAction) -> Unit,
    onRetry: () -> Unit,
    onShareSettings: () -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    /** Previews: the menu shown open. */
    initialMenuOpen: Boolean = false,
) {
    var menu by rememberSaveable { mutableStateOf(initialMenuOpen) }
    BackHandler(enabled = menu) { menu = false }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            BackLink(backLabel, onBack, Modifier.padding(horizontal = ScreenGutter))
            when {
                state.loading -> LoadingSpinner(Modifier.padding(top = 48.dp))
                state.error != null -> Column(Modifier.padding(horizontal = ScreenGutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ErrorBox("Can’t open this one", state.error)
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
                // The bottom padding keeps the end of the document clear of the FAB.
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 120.dp),
                ) {
                    item(key = "header") { ReportHeader(state, Modifier.padding(bottom = 16.dp)) }
                    item(key = "rule") { HorizontalDivider(Modifier.padding(bottom = 22.dp), color = MaterialTheme.colorScheme.outlineVariant) }
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
        if (!state.loading && state.error == null) {
            // A scrim while the menu is open: a tap outside it closes the menu.
            AnimatedVisibility(menu, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close the menu") { menu = false },
                )
            }
            GoodsFabMenu(menu, { menu = it }, state, onLink, onShareSettings)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 96.dp, bottom = 16.dp))
    }
}

/** The FAB (a rounded square that turns into a close button) and its menu, which opens upward. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BoxScope.GoodsFabMenu(
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    state: ReportUiState,
    onLink: (LinkAction) -> Unit,
    onShareSettings: () -> Unit,
) {
    FloatingActionButtonMenu(
        expanded = open,
        modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding(),
        button = {
            ToggleFloatingActionButton(
                checked = open,
                onCheckedChange = onOpen,
                modifier = Modifier.semantics {
                    traversalIndex = -1f
                    stateDescription = if (open) "Open" else "Closed"
                    contentDescription = if (open) "Close the menu" else "Download or share"
                },
            ) {
                val busy = state.busy != null
                when {
                    busy && !open -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    else -> Icon(
                        if (checkedProgress > 0.5f) Icons.Outlined.Close else Icons.Outlined.Share,
                        contentDescription = null,
                        modifier = Modifier.animateIcon({ checkedProgress }),
                    )
                }
            }
        },
    ) {
        FloatingActionButtonMenuItem(
            onClick = { onOpen(false); onLink(LinkAction.Download) },
            text = { Text("Download") },
            icon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
            containerColor = fabMenuItemColor(),
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        FloatingActionButtonMenuItem(
            onClick = { onOpen(false); onLink(LinkAction.Share) },
            text = { Text("Share") },
            icon = { Icon(Icons.Outlined.Share, contentDescription = null) },
            containerColor = fabMenuItemColor(),
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        FloatingActionButtonMenuItem(
            onClick = { onOpen(false); onShareSettings() },
            text = { Text(shareSettingsLabel(state.linkOn, state.linkDownloads)) },
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            containerColor = fabMenuItemColor(),
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** The FAB menu items' fill: the FAB's peach, lightened halfway toward the page so the pills sit softer than the button. */
@Composable
fun fabMenuItemColor(): Color = lerp(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceContainerLowest, 0.5f)

/**
 * The share settings sheet's content: whether the link is on, how many downloads it has had, and the
 * switch. Download and Share turn the link on by themselves.
 */
@Composable
fun ShareSettings(state: ReportUiState, onTurnOn: () -> Unit, onTurnOff: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = ScreenGutter, end = ScreenGutter, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Share settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (state.linkOn) Icons.Outlined.Link else Icons.Outlined.LinkOff,
                contentDescription = null,
                tint = if (state.linkOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (state.linkOn) "Link on" else "Link off", style = MaterialTheme.clarityText.chat)
                Text(
                    if (state.linkOn) "Anyone with the link can download it · ${downloadsText(state.linkDownloads)}"
                    else "No one can download it from a link. Download or Share turns it on.",
                    style = MaterialTheme.clarityText.chatSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.linkOn) SecondaryButton("turn off link", onTurnOff, Modifier.fillMaxWidth(), busy = state.switching)
        else PrimaryButton("turn on link", onTurnOn, Modifier.fillMaxWidth(), busy = state.switching)
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
