package net.robog.clarity.app.voice

import net.robog.clarity.app.ui.theme.Space

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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

/** A mic icon and "Listening" / "Mic on" / "Muted" (`.lis`, idle variant when not hearing); a change is announced. */
@Composable
fun MicStateChip(state: MicChipState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (label, container, content) = when (state) {
        MicChipState.Listening -> Triple("Listening", scheme.tertiaryContainer, scheme.onTertiaryContainer)
        MicChipState.MicOn -> Triple("Mic on", scheme.surfaceContainer, scheme.onSurfaceVariant)
        MicChipState.Muted -> Triple("Muted", scheme.surfaceContainer, scheme.onSurfaceVariant)
        MicChipState.Off -> return
    }
    Row(
        modifier
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .background(container, MaterialTheme.shapes.small)
            .padding(horizontal = Space.Medium, vertical = Space.Small),
        horizontalArrangement = Arrangement.spacedBy(Space.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The text names the state, so the icon is only a picture.
        Icon(
            if (state == MicChipState.Muted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(16.dp),
        )
        Text(label, color = content, style = MaterialTheme.clarityText.kicker)
    }
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
        Text("Allow microphone?", style = MaterialTheme.typography.titleMedium)
        Hint(
            "Speech becomes text on this phone. Audio never leaves it.",
        )
        if (refused) {
            Hint("Microphone blocked. Turn it on in settings.")
            PrimaryButton("Open settings", onOpenSettings)
        } else {
            PrimaryButton("Allow", onAllow)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MicChipsPreview() {
    ClarityTheme {
        Column(Modifier.padding(Space.Medium), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
            MicStateChip(MicChipState.Listening)
            MicStateChip(MicChipState.MicOn)
            MicStateChip(MicChipState.Muted)
            MicRationale(refused = false, onAllow = {}, onOpenSettings = {})
        }
    }
}
