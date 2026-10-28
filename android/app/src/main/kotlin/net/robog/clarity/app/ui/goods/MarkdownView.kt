package net.robog.clarity.app.ui.goods

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import net.robog.clarity.app.ui.theme.clarityText

/** The colours inline Markdown is drawn with (links, code). */
private data class InlineColors(val link: Color, val codeBackground: Color)

/** Pure apart from the colours: [inlines] as styled text; links open in the browser when tapped. */
private fun annotated(inlines: List<MdInline>, colors: InlineColors): AnnotatedString = buildAnnotatedString {
    fun add(items: List<MdInline>) {
        items.forEach { inline ->
            when (inline) {
                is MdInline.Text -> append(inline.text)
                is MdInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { add(inline.children) }
                is MdInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { add(inline.children) }
                is MdInline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground)) { append(inline.text) }
                is MdInline.Link -> withLink(
                    LinkAnnotation.Url(inline.url, TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline))),
                ) { add(inline.children) }
            }
        }
    }
    add(inlines)
}

@Composable
private fun inlineColors() = InlineColors(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceContainerHighest)

@Composable
private fun InlineText(inlines: List<MdInline>, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val colors = inlineColors()
    val text = remember(inlines, colors) { annotated(inlines, colors) }
    Text(text, modifier, color = color, style = style)
}

/** The document's blocks as lazy items (a long report scrolls without composing all of it). */
fun LazyListScope.markdownItems(blocks: List<MdBlock>, modifier: Modifier = Modifier) {
    blocks.forEachIndexed { i, block ->
        item(key = "md-$i", contentType = block::class) {
            MarkdownBlock(block, modifier.padding(top = spaceBefore(block, i == 0)))
        }
    }
}

/** Blocks inside a quote or a list item, in a plain column. */
@Composable
private fun MarkdownBlocks(blocks: List<MdBlock>, modifier: Modifier = Modifier) {
    Column(modifier) {
        blocks.forEachIndexed { i, block -> MarkdownBlock(block, Modifier.padding(top = spaceBefore(block, i == 0))) }
    }
}

/** Headings get room above them; everything else the paragraph gap. */
private fun spaceBefore(block: MdBlock, first: Boolean) = when {
    first -> 0.dp
    block is MdBlock.Heading -> if (block.level <= 2) 28.dp else 20.dp
    else -> 14.dp
}

@Composable
private fun MarkdownBlock(block: MdBlock, modifier: Modifier = Modifier) {
    val type = MaterialTheme.typography
    val reading = MaterialTheme.clarityText.reading
    val colors = MaterialTheme.colorScheme
    when (block) {
        is MdBlock.Heading -> InlineText(
            block.text,
            when (block.level) {
                1 -> type.headlineSmall
                2 -> type.titleLarge
                else -> type.titleMedium
            },
            modifier.semantics { heading() },
        )
        is MdBlock.Paragraph -> InlineText(block.text, reading, modifier)
        is MdBlock.Quote -> Row(modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(colors.outlineVariant, MaterialTheme.shapes.extraSmall))
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides colors.onSurfaceVariant) {
                MarkdownBlocks(block.blocks, Modifier.padding(start = 14.dp))
            }
        }
        is MdBlock.ListBlock -> Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEachIndexed { i, item ->
                Row {
                    Text(
                        if (block.start == null) "•" else "${block.start + i}.",
                        Modifier.widthIn(min = 24.dp).padding(end = 6.dp),
                        style = reading,
                        color = colors.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f)) {
                        InlineText(item.text, reading)
                        if (item.children.isNotEmpty()) MarkdownBlocks(item.children, Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        is MdBlock.Code -> Text(
            block.text,
            modifier
                .fillMaxWidth()
                .background(colors.surfaceContainer, MaterialTheme.shapes.small)
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
            style = type.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        is MdBlock.Table -> MarkdownTable(block, modifier)
        MdBlock.Rule -> HorizontalDivider(modifier.padding(vertical = 6.dp), color = colors.outlineVariant)
    }
}

@Composable
private fun MarkdownTable(table: MdBlock.Table, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val columns = table.rows.maxOfOrNull { it.size } ?: return
    Column(modifier.fillMaxWidth().background(colors.surfaceContainerLow, MaterialTheme.shapes.small)) {
        table.rows.forEachIndexed { r, row ->
            if (r > 0) HorizontalDivider(color = colors.outlineVariant)
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (c in 0 until columns) {
                    val style = MaterialTheme.typography.bodyMedium.let { if (r == 0) it.copy(fontWeight = FontWeight.SemiBold) else it }
                    InlineText(row.getOrElse(c) { emptyList() }, style, Modifier.weight(1f))
                }
            }
        }
    }
}
