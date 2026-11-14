package net.robog.clarity.backend.agent

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import net.robog.clarity.harness.ToolImage

/** The picture formats `view_image` shows: what vision models take. */
enum class ImageKind(val mimeType: String) { PNG("image/png"), JPEG("image/jpeg"), GIF("image/gif"), WEBP("image/webp") }

/** Longest side sent to the model; bigger pictures are scaled down (models scale them anyway, and tokens grow with pixels). */
const val MAX_IMAGE_SIDE = 1568

/** The largest file `view_image` opens at all. */
const val MAX_IMAGE_FILE_BYTES = 20L * 1024 * 1024

/** The most a picture weighs when it is sent (a base64 copy stays in the journal). */
const val MAX_SENT_IMAGE_BYTES = 3 * 1024 * 1024

/** More pixels than this are refused before decoding (a small file can hold a huge picture). */
const val MAX_IMAGE_PIXELS = 40_000_000L

/** Pure: the format of [bytes] by its first bytes, or null when it is not one `view_image` shows. */
fun imageKind(bytes: ByteArray): ImageKind? = when {
    bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> ImageKind.PNG
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> ImageKind.JPEG
    bytes.size >= 6 && bytes.copyOfRange(0, 4).decodeToString() == "GIF8" -> ImageKind.GIF
    bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" && bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> ImageKind.WEBP
    else -> null
}

/** Pure: [width] x [height] scaled to fit [maxSide] on the longer side, keeping proportions; unchanged when it already fits. */
fun scaledSize(width: Int, height: Int, maxSide: Int = MAX_IMAGE_SIDE): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= maxSide) return width to height
    val factor = maxSide.toDouble() / longest
    return maxOf(1, Math.round(width * factor).toInt()) to maxOf(1, Math.round(height * factor).toInt())
}

/** What `view_image` puts in front of the model, and what it says about it. */
data class PreparedImage(val image: ToolImage, val width: Int?, val height: Int?, val scaledFrom: Pair<Int, Int>?)

/**
 * The picture in [bytes] ready to show: checked by its content (not its name), refused when it is too big to
 * decode safely, and scaled down (PNG stays PNG, anything else becomes JPEG when that is smaller) when its longer side is over
 * [MAX_IMAGE_SIDE] or its file over [MAX_SENT_IMAGE_BYTES]. A picture that already fits is sent as it is. WebP
 * is sent as it is when small enough (the JDK cannot decode it to scale it). Left with a sentence for the model
 * when it cannot be shown.
 */
fun prepareImage(bytes: ByteArray): Either<String, PreparedImage> {
    if (bytes.size > MAX_IMAGE_FILE_BYTES) return "The file is ${bytes.size / 1024 / 1024} MB, too large to show. Make a smaller copy with the shell first.".left()
    val kind = imageKind(bytes) ?: return "This is not a PNG, JPEG, GIF or WebP picture. View other files with the shell (pdftotext, file) instead.".left()
    if (kind == ImageKind.WEBP) {
        return if (bytes.size <= MAX_SENT_IMAGE_BYTES) PreparedImage(ToolImage(kind.mimeType, base64(bytes)), null, null, null).right()
        else "The WebP picture is ${bytes.size / 1024 / 1024} MB and cannot be scaled here. Convert it to a smaller PNG or JPEG with the shell first.".left()
    }
    val (width, height) = dimensions(bytes) ?: return "The picture could not be read; it may be damaged.".left()
    if (width.toLong() * height > MAX_IMAGE_PIXELS) return "The picture is $width x $height pixels, too large to open. Make a smaller copy with the shell first.".left()
    val fits = maxOf(width, height) <= MAX_IMAGE_SIDE && bytes.size <= MAX_SENT_IMAGE_BYTES
    if (fits) {
        // Small enough to decode safely: a truncated or damaged file is caught here, not by the model's provider.
        val readable = try { ImageIO.read(ByteArrayInputStream(bytes)) != null } catch (e: Exception) { false } catch (e: LinkageError) { true }
        return if (readable) PreparedImage(ToolImage(kind.mimeType, base64(bytes)), width, height, null).right()
        else "The picture could not be read; it may be damaged.".left()
    }

    val scaled = try {
        scale(bytes, kind, width, height)
    } catch (e: Exception) {
        null
    } catch (e: LinkageError) {
        null // no AWT in this runtime
    } ?: return "The picture could not be scaled down; it may be damaged.".left()
    if (scaled.second.size > MAX_SENT_IMAGE_BYTES) return "The picture is still ${scaled.second.size / 1024 / 1024} MB after scaling. Make a smaller copy with the shell first.".left()
    val (w, h) = scaledSize(width, height)
    return PreparedImage(ToolImage(scaled.first, base64(scaled.second)), w, h, width to height).right()
}

private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

private fun dimensions(bytes: ByteArray): Pair<Int, Int>? {
    val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: return null
    stream.use {
        val readers = ImageIO.getImageReaders(it)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        return try {
            reader.input = it
            reader.getWidth(0) to reader.getHeight(0)
        } catch (e: Exception) {
            null
        } finally {
            reader.dispose()
        }
    }
}

/** (media type, bytes) of the picture scaled to fit; PNG stays PNG unless that is still too heavy, then JPEG. */
private fun scale(bytes: ByteArray, kind: ImageKind, width: Int, height: Int): Pair<String, ByteArray>? {
    val source = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
    val (w, h) = scaledSize(width, height)
    val target = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = target.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.color = java.awt.Color.WHITE // transparent pixels become white, which JPEG needs and a model reads fine
        g.fillRect(0, 0, w, h)
        g.drawImage(source, 0, 0, w, h, null)
    } finally {
        g.dispose()
    }
    if (kind == ImageKind.PNG) {
        val png = ByteArrayOutputStream().also { ImageIO.write(target, "png", it) }.toByteArray()
        if (png.size <= MAX_SENT_IMAGE_BYTES) return ImageKind.PNG.mimeType to png
    }
    return ImageKind.JPEG.mimeType to jpeg(target, 0.85f)
}

private fun jpeg(image: BufferedImage, quality: Float): ByteArray {
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val out = ByteArrayOutputStream()
    try {
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val params = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
            writer.write(null, IIOImage(image, null, null), params)
        }
    } finally {
        writer.dispose()
    }
    return out.toByteArray()
}
