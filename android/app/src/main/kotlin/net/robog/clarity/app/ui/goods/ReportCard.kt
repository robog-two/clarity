package net.robog.clarity.app.ui.goods

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.GoodsRepository
import net.robog.clarity.app.ui.components.Kicker
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.app.ui.theme.clarityText
import net.robog.clarity.shared.feed.Card

/** The Goods downloads, for cards in the feed (provided by MainActivity; null in previews). */
val LocalGoodsRepository = compositionLocalOf<GoodsRepository?> { null }

/** The widest a card in the thread gets (the same as a bubble). */
private val MaxCard = 340.dp

/**
 * A Good handed over in the thread (`present_report`): its title, what it is, and a button per
 * format that downloads it and opens it in another app. Tapping the card opens the viewer
 * ([onOpen]; null where there is nowhere to go, as on the call screen).
 */
@Composable
fun ReportCardView(
    card: Card.Report,
    onOpen: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    /** Previews: a format shown as downloading. */
    previewBusy: String? = null,
) {
    val repository = LocalGoodsRepository.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(card.reportId) { mutableStateOf(previewBusy) }
    var failed by remember(card.reportId) { mutableStateOf<String?>(null) }
    val formats = remember(card.formats) { cardFormats(card.formats) }

    fun download(format: GoodsFormat) {
        val repo = repository ?: return
        if (busy != null) return
        busy = format.wire
        failed = null
        scope.launch {
            when (val r = repo.download(card.reportId, format.wire, card.title)) {
                is ApiResult.Success -> if (!handOver(context, r.value, card.title, share = false)) failed = "No app on this phone opens ${format.label} files."
                is ApiResult.Failure -> failed = r.error.sentence()
            }
            busy = null
        }
    }

    val body: @Composable () -> Unit = {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                GoodsGlyph()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Kicker("in your Goods")
                    Text(card.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text("Document", style = MaterialTheme.clarityText.chatSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onOpen != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (formats.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    formats.forEach { format -> FormatChip(format, busy == format.wire, enabled = busy == null) { download(format) } }
                }
            }
            failed?.let { Text(it, style = MaterialTheme.clarityText.chatSmall, color = MaterialTheme.colorScheme.error) }
        }
    }
    val cardModifier = modifier.widthIn(max = MaxCard).fillMaxWidth()
    if (onOpen != null) {
        OutlinedCard(onClick = { onOpen(card.reportId) }, modifier = cardModifier.semantics { contentDescription = "${card.title}, in your Goods. Open it." }, shape = MaterialTheme.shapes.large) { body() }
    } else {
        OutlinedCard(cardModifier, shape = MaterialTheme.shapes.large) { body() }
    }
}

/** A format's download button: the label, a spinner while it downloads. */
@Composable
private fun FormatChip(format: GoodsFormat, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        enabled = enabled || busy,
        label = { Text(format.label) },
        leadingIcon = {
            if (busy) CircularProgressIndicator(Modifier.size(AssistChipDefaults.IconSize), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.FileDownload, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize))
        },
        modifier = Modifier.semantics { contentDescription = if (busy) "Downloading ${format.label}" else "Download as ${format.label}" },
    )
}

/** The mark of a Good: a document on a tonal tile. */
@Composable
fun GoodsGlyph(modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
