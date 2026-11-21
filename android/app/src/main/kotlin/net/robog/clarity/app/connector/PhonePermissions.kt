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
    /** What the access means, shown on its screen. */
    val access: String = "Read only. Change it in Settings.",
    /** Needed only when the person asks for something that uses it, so setup does not wait for it. */
    val optional: Boolean = false,
) {
    Messages(
        key = "phone-messages",
        title = "Messages",
        permission = Manifest.permission.READ_SMS,
        alsoAsk = listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.RECEIVE_MMS),
        blurb = "Syncs texts to your server.",
    ),
    Contacts(
        key = "phone-contacts",
        title = "Contacts",
        permission = Manifest.permission.WRITE_CONTACTS,
        alsoAsk = listOf(Manifest.permission.READ_CONTACTS),
        blurb = "Saves contacts you approve.",
        access = "Adds only what you approve. Change it in Settings.",
        optional = true,
    ),
}

interface PhonePermissions {
    fun isGranted(kind: PhoneConnectorKind): Boolean
}

class AndroidPhonePermissions(private val context: Context) : PhonePermissions {
    override fun isGranted(kind: PhoneConnectorKind): Boolean =
        ContextCompat.checkSelfPermission(context, kind.permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * The receive permissions to ask for quietly: when texts can already be read but receiving is not
 * allowed, and this app version has not asked yet. Same permission group as reading, so Android
 * grants them without a dialog. Empty when there is nothing to ask.
 */
fun receivePermissionsToAsk(granted: (String) -> Boolean, askedVersion: Long?, currentVersion: Long): List<String> {
    if (!granted(Manifest.permission.READ_SMS)) return emptyList()
    if (askedVersion == currentVersion) return emptyList()
    return PhoneConnectorKind.Messages.alsoAsk.filterNot(granted)
}
