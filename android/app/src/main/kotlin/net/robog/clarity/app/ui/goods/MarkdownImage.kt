package net.robog.clarity.app.ui.goods

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import net.robog.clarity.app.ui.path.FullScreenFrame
import net.robog.clarity.app.ui.path.OpenFrame
import net.robog.clarity.app.ui.theme.Space

/** The tallest a report's image is drawn. */
private val MaxImageHeight = 480.dp

/** Previews: a painter to draw in place of fetching the image, by the address written in the Markdown. */
val LocalImagePainter = staticCompositionLocalOf<((String) -> Painter)?> { null }

/**
 * A report's image. The phone never asks the original host: it fetches the copy the instance kept
 * (`reports/{id}/images/{sha256 of the address}`) over the app's authenticated client, which caches
 * it in memory and on disk. While it loads a placeholder holds its place; when it is missing only
 * the caption shows, in italics. A tap opens it full screen.
 */
@Composable
internal fun MarkdownImage(block: MdBlock.Image, imagesBase: String?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val key = remember(block.url) { imageKey(block.url) }
    val address = imagesBase?.let { it + key }
    val override = LocalImagePainter.current
    var failed by remember(address) { mutableStateOf(false) }
    var open by rememberSaveable(address) { mutableStateOf(false) }
    if (address == null && override == null || failed) {
        if (block.alt.isNotBlank()) {
            Text(block.alt, modifier, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = colors.onSurfaceVariant)
        }
        return
    }
    val description = block.alt.ifBlank { null }
    val tap = Modifier.clickable(onClickLabel = "Open full screen") { open = true }
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
            if (override != null) {
                val painter = override(block.url)
                Image(painter, description, Modifier.fitted(painter).then(tap), contentScale = ContentScale.Fit)
            } else {
                val request = ImageRequest.Builder(LocalContext.current).data(address).memoryCacheKey(address).diskCacheKey(address).build()
                SubcomposeAsyncImage(
                    model = request,
                    contentDescription = description,
                    contentScale = ContentScale.Fit,
                    onError = { failed = true },
                    loading = { Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(colors.surfaceContainer)) },
                    success = { state -> Image(state.painter, description, Modifier.fitted(state.painter).then(tap), contentScale = ContentScale.Fit) },
                )
            }
        }
        if (block.alt.isNotBlank()) {
            Text(block.alt, Modifier.padding(top = Space.Small), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
    if (open && address != null) {
        FullScreenFrame(OpenFrame(address, null, Color.Unspecified, block.alt)) { open = false }
    }
}

/** Full width at the painter's own shape, no taller than [MaxImageHeight]. */
private fun Modifier.fitted(painter: Painter): Modifier {
    val size = painter.intrinsicSize
    val ratio = if (size.isSpecified && size.width > 0f && size.height > 0f) size.width / size.height else 16f / 9f
    return fillMaxWidth().heightIn(max = MaxImageHeight).aspectRatio(ratio)
}
