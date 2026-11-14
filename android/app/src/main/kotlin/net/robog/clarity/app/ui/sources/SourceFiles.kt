package net.robog.clarity.app.ui.sources

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The cache folder downloaded attachments sit in (the `sources` path of `file_paths.xml`). */
private const val FOLDER = "sources"

/** Deletes what earlier downloads left: done once as a viewer opens, never while a download is on its way. */
suspend fun clearDownloads(context: Context) = withContext(Dispatchers.IO) {
    runCatching { File(context.cacheDir, FOLDER).deleteRecursively() }
    Unit
}

/**
 * Writes a downloaded attachment to the app's cache, in a folder of its own so two downloads never
 * clash, and hands it to the phone: opened by an app that knows its type when the type is one that is
 * safe to open ([canView]), else offered on the share sheet. Never throws: a failed write or an
 * unreadable path is [HandOver.Failed].
 */
suspend fun handOverFile(context: Context, bytes: ByteArray, name: String, mimeType: String?): HandOver = runCatching {
    val file = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "$FOLDER/${UUID.randomUUID()}").apply { mkdirs() }
        File(folder, name).apply { writeBytes(bytes) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val type = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
    fun view() = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    fun share() = Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        name,
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(if (canView(type)) view() else share())
        HandOver.Opened
    } catch (_: ActivityNotFoundException) {
        // No app reads it: a viewable type falls back to the share sheet; one that went there already has nowhere left.
        if (!canView(type)) HandOver.NoApp
        else try {
            context.startActivity(share())
            HandOver.Opened
        } catch (_: ActivityNotFoundException) {
            HandOver.NoApp
        }
    }
}.getOrElse { HandOver.Failed }
