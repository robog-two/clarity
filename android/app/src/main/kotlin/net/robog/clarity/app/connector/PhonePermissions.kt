package net.robog.clarity.app.connector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Connectors that live on this phone. Only the permission step exists in `app-core`. */
enum class PhoneConnectorKind(
    val key: String,
    val title: String,
    val permission: String,
    val blurb: String,
    /** Asked for together with [permission]; the connector works without them, just later (on the next pass). */
    val alsoAsk: List<String> = emptyList(),
) {
    Messages(
        key = "phone-messages",
        title = "Messages",
        permission = Manifest.permission.READ_SMS,
        alsoAsk = listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.RECEIVE_MMS),
        blurb = "Syncs texts to your server.",
    ),
}

interface PhonePermissions {
    fun isGranted(kind: PhoneConnectorKind): Boolean
}

class AndroidPhonePermissions(private val context: Context) : PhonePermissions {
    override fun isGranted(kind: PhoneConnectorKind): Boolean =
        ContextCompat.checkSelfPermission(context, kind.permission) == PackageManager.PERMISSION_GRANTED
}
