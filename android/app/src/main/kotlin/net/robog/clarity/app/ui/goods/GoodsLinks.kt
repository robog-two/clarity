package net.robog.clarity.app.ui.goods

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Hands a Good's download page to the phone: [share] false opens it in the browser, true offers the
 * link on the share sheet. Returns false when nothing on the phone could take it.
 */
fun handOverLink(context: Context, url: String, title: String, share: Boolean): Boolean {
    val intent = if (share) {
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url).putExtra(Intent.EXTRA_SUBJECT, title),
            title,
        )
    } else {
        Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    }
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
