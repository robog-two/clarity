package net.robog.clarity.app.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import net.robog.clarity.app.call.CallPermission
import net.robog.clarity.app.ui.main.CallPermissionDialog
import net.robog.clarity.app.ui.settings.SettingsActions
import net.robog.clarity.app.ui.settings.SettingsContent
import net.robog.clarity.app.ui.settings.SettingsPage
import net.robog.clarity.app.ui.settings.SettingsUiState
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.shared.session.Settings

// The prompt the app shows when it opens and the morning call is missing a permission.

@Composable
private fun Prompt(dark: Boolean, permission: CallPermission) = ClarityTheme(darkTheme = dark) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) { CallPermissionDialog(permission, {}, {}) }
    }
}

@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable
fun CallPermissionFullScreenLight() = Prompt(false, CallPermission.FullScreen)

@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable
fun CallPermissionFullScreenDark() = Prompt(true, CallPermission.FullScreen)

@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable
fun CallPermissionNotificationsLight() = Prompt(false, CallPermission.Notifications)

@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable
fun CallPermissionExactAlarmsLight() = Prompt(false, CallPermission.ExactAlarms)

/** Settings, morning call page, with only the full-screen permission missing. */
@PreviewTest @Preview(widthDp = 412, heightDp = 915) @Composable
fun SettingsCallWithoutFullScreen() = ClarityTheme(darkTheme = false) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        SettingsContent(
            SettingsUiState(
                instanceUrl = "https://clarity.home.example.org", deviceId = "dev_8f3k2m9q", loading = false,
                settings = Settings(), notificationsOn = true, needsExactAlarms = false, fullScreenOk = false,
            ),
            SettingsActions(),
            startPage = SettingsPage.MorningCall,
        )
    }
}
