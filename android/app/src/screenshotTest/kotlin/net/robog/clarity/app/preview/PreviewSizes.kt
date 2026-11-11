package net.robog.clarity.app.preview

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

// Every screenshot size lives here; previews elsewhere use these annotations, never a raw @Preview
// (PreviewSizesTest checks). All render 412 dp wide at 1x density (160 dpi), so 412 px: about 7 times
// fewer pixels than a phone's density, which keeps the suite fast and light. Heights are a few fixed
// steps; pick the smallest one that fits the content.

/** The device every size starts from. */
const val PREVIEW_DEVICE = "spec:width=412dp,height=915dp,dpi=160"

/** Wraps its content: a card or a part of a screen. */
@Preview(device = PREVIEW_DEVICE, widthDp = 412)
annotation class PartPreview

/** A short block, up to 420 dp. */
@Preview(device = PREVIEW_DEVICE, widthDp = 412, heightDp = 420)
annotation class ShortPreview

/** Most of a screen, 700 dp. */
@Preview(device = PREVIEW_DEVICE, widthDp = 412, heightDp = 700)
annotation class HalfPreview

/** One phone screen, 915 dp. */
@Preview(device = PREVIEW_DEVICE, widthDp = 412, heightDp = 915)
annotation class PhonePreview

/** A screen that scrolls a little past one view, 1500 dp. */
@Preview(device = PREVIEW_DEVICE, widthDp = 412, heightDp = 1500)
annotation class LongPreview

/** A long scrolling screen, 2900 dp. */
@Preview(device = PREVIEW_DEVICE, widthDp = 412, heightDp = 2900)
annotation class VeryLongPreview

/** A whole long page, 4200 dp. */
@Preview(device = PREVIEW_DEVICE, widthDp = 412, heightDp = 4200)
annotation class FullPagePreview

/** One phone screen, light and dark. */
@Preview(device = PREVIEW_DEVICE, name = "light", widthDp = 412, heightDp = 915)
@Preview(device = PREVIEW_DEVICE, name = "dark", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class Screen

/** Wraps its content, light and dark. */
@Preview(device = PREVIEW_DEVICE, name = "light", widthDp = 412)
@Preview(device = PREVIEW_DEVICE, name = "dark", widthDp = 412, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class Part

/** A list that scrolls past one view, light and dark, 1500 dp. */
@Preview(device = PREVIEW_DEVICE, name = "light", widthDp = 412, heightDp = 1500)
@Preview(device = PREVIEW_DEVICE, name = "dark", widthDp = 412, heightDp = 1500, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class TallScreen
