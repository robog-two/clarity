package net.robog.clarity.app.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** The microphone permission as state a screen can show around its own content (unlike [MicPermissionGate]). */
class MicAccess(
    val granted: Boolean,
    /** The person said no (and the system will not ask again): send them to the app settings. */
    val refused: Boolean,
    val request: () -> Unit,
    val openSettings: () -> Unit,
)

/** [onResult] is called after the system dialog closes. */
@Composable
fun rememberMicAccess(onResult: (Boolean) -> Unit = {}): MicAccess {
    val context = LocalContext.current
    fun check() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(check()) }
    var refused by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        refused = !ok
        onResult(ok)
    }
    return MicAccess(
        granted = granted,
        refused = refused,
        request = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
        openSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        },
    )
}
