package net.robog.clarity.app.connector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Connectors that live on this phone. Only the permission step exists in `app-core`. */
enum class PhoneConnectorKind(val key: String, val title: String, val permission: String, val blurb: String) {
    Messages(
        key = "phone-messages",
        title = "Messages",
        permission = Manifest.permission.READ_SMS,
        blurb = "Your messages are synchronized with your personal server.",
    ),
}

interface PhonePermissions {
    fun isGranted(kind: PhoneConnectorKind): Boolean
}

class AndroidPhonePermissions(private val context: Context) : PhonePermissions {
    override fun isGranted(kind: PhoneConnectorKind): Boolean =
        ContextCompat.checkSelfPermission(context, kind.permission) == PackageManager.PERMISSION_GRANTED
}
