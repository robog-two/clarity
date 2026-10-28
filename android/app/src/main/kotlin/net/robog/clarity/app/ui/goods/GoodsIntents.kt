package net.robog.clarity.app.ui.goods

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import net.robog.clarity.app.data.GoodsFile

/** The FileProvider's authority (AndroidManifest.xml). */
private fun authority(context: Context) = "${context.packageName}.files"

/**
 * Hands a downloaded Good to another app: to view it ([share] false: the app that opens this type,
 * or the share sheet when the phone has none), or to the share sheet. The other app gets read access
 * to that one file only. Returns false when nothing on the phone could take it.
 */
fun handOver(context: Context, file: GoodsFile, title: String, share: Boolean): Boolean {
    val uri = FileProvider.getUriForFile(context, authority(context), file.file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(file.mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TITLE, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri(title, uri) }
    val shareSheet = Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, file.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    for (intent in if (share) listOf(shareSheet) else listOf(view, shareSheet)) {
        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            // try the next way
        }
    }
    return false
}
