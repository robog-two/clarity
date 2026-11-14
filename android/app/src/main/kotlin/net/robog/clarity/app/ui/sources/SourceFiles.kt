package net.robog.clarity.app.ui.sources

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The cache folder downloaded attachments sit in (the `sources` path of `file_paths.xml`). */
private const val FOLDER = "sources"

/**
 * Writes a downloaded attachment to the app's cache and hands it to the phone: opened by an app that
 * knows its type, else offered on the share sheet. Returns false when nothing could take it. The
 * cache is cleared of earlier files first, so nothing lingers past the next download.
 */
suspend fun handOverFile(context: Context, bytes: ByteArray, name: String, mimeType: String?): Boolean {
    val file = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, FOLDER).apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }
        File(folder, name).apply { writeBytes(bytes) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val type = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(view)
        true
    } catch (_: ActivityNotFoundException) {
        val share = Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            name,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(share)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
