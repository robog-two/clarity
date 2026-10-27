package net.robog.clarity.backend.agent

import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/** A rectangle on a screenshot, in its pixels. */
data class PixelRect(val x: Int, val y: Int, val w: Int, val h: Int)

/** Pure: width and height from a PNG's header, or null when [png] is not a PNG. */
fun pngSize(png: ByteArray): Pair<Int, Int>? {
    if (png.size < 24 || png[0] != 0x89.toByte() || png[1] != 'P'.code.toByte() || png[12] != 'I'.code.toByte()) return null
    fun int(at: Int) = ((png[at].toInt() and 0xff) shl 24) or ((png[at + 1].toInt() and 0xff) shl 16) or ((png[at + 2].toInt() and 0xff) shl 8) or (png[at + 3].toInt() and 0xff)
    return int(16) to int(20)
}

/** Jev takes images of 256 to 1,568 pixels a side and 78,400 to 2,458,624 pixels in all (OpenRouter's multimodal decisions guide). */
fun jevAcceptsSize(width: Int, height: Int): Boolean =
    width in 256..1568 && height in 256..1568 && (width.toLong() * height) in 78_400L..2_458_624L

/** Pure: a box in fractions of an image, as pixels of a [width] x [height] image. */
fun boxPixels(box: DecisionAnswer.Box, width: Int, height: Int): PixelRect {
    val x = (box.left * width).roundToInt()
    val y = (box.top * height).roundToInt()
    return PixelRect(x, y, ((box.right * width).roundToInt() - x).coerceAtLeast(1), ((box.bottom * height).roundToInt() - y).coerceAtLeast(1))
}

/**
 * [png] with [rects] painted over in solid black (secret fields), [outline] drawn as a thick red
 * frame (the element a step would act on) and [secondary] as a dashed blue one (Jev's box). Null when the image cannot be read or written: callers
 * then must not send or keep the frame.
 */
fun editPng(png: ByteArray, rects: List<PixelRect> = emptyList(), outline: PixelRect? = null, secondary: PixelRect? = null): ByteArray? = try {
    val image = ImageIO.read(ByteArrayInputStream(png)) ?: throw IllegalArgumentException("not an image")
    val canvas = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
    val g = canvas.createGraphics()
    try {
        g.drawImage(image, 0, 0, null)
        g.color = Color.BLACK
        rects.forEach { g.fillRect(it.x - 2, it.y - 2, it.w + 4, it.h + 4) }
        // Jev's own box, for the audit trail: dashed blue, under the red outline of what is clicked.
        secondary?.let {
            g.color = Color(0, 110, 255)
            g.stroke = BasicStroke(3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(10f, 6f), 0f)
            g.drawRect(it.x, it.y, it.w, it.h)
        }
        outline?.let {
            g.color = Color(230, 0, 0)
            g.stroke = BasicStroke(4f)
            g.drawRect(it.x - 3, it.y - 3, it.w + 6, it.h + 6)
        }
    } finally {
        g.dispose()
    }
    ByteArrayOutputStream().also { ImageIO.write(canvas, "png", it) }.toByteArray()
} catch (e: Exception) {
    null
} catch (e: LinkageError) {
    null // no AWT in this runtime
}
