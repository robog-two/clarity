package net.robog.clarity.app.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import net.robog.clarity.app.ui.components.Hint
import net.robog.clarity.app.ui.components.InfoBox
import net.robog.clarity.app.ui.components.PrimaryButton
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.app.ui.theme.clarityText

/** "● Listening" / "● Mic on" / "○ Muted" (`.lis`, idle variant when not hearing). */
@Composable
fun MicStateChip(state: MicChipState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (label, container, content) = when (state) {
        MicChipState.Listening -> Triple("● Listening", scheme.tertiaryContainer, scheme.onTertiaryContainer)
        MicChipState.MicOn -> Triple("● Mic on", scheme.surfaceContainer, scheme.onSurfaceVariant)
        MicChipState.Muted -> Triple("○ Muted", scheme.surfaceContainer, scheme.onSurfaceVariant)
        MicChipState.Off -> return
    }
    Text(
        label,
        modifier
            .background(container, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = content,
        style = MaterialTheme.clarityText.kicker,
    )
}

/**
 * Shows [content] once `RECORD_AUDIO` is granted. Until then it explains why the app wants the
 * microphone (the rationale) and asks; after a refusal it sends the person to the app settings.
 */
@Composable
fun MicPermissionGate(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(granted()) }
    var refused by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        allowed = ok
        refused = !ok
    }
    if (allowed) {
        content()
    } else {
        MicRationale(
            refused = refused,
            onAllow = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
            modifier = modifier,
        )
    }
}

@Composable
fun MicRationale(refused: Boolean, onAllow: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    InfoBox(modifier) {
        Text("Allow the microphone?", style = MaterialTheme.typography.titleMedium)
        Hint(
            "I turn your speech into text on this phone. Only the words are sent to your server, never the audio.",
        )
        if (refused) {
            Hint("The microphone is blocked. Turn it on in the app settings to talk to me.")
            PrimaryButton("open settings", onOpenSettings)
        } else {
            PrimaryButton("allow microphone", onAllow)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MicChipsPreview() {
    ClarityTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MicStateChip(MicChipState.Listening)
            MicStateChip(MicChipState.MicOn)
            MicStateChip(MicChipState.Muted)
            MicRationale(refused = false, onAllow = {}, onOpenSettings = {})
        }
    }
}
