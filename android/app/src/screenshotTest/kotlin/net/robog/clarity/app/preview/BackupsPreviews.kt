package net.robog.clarity.app.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import net.robog.clarity.app.ui.settings.BackupsActions
import net.robog.clarity.app.ui.settings.BackupsContent
import net.robog.clarity.app.ui.settings.BackupsUiState
import net.robog.clarity.app.ui.settings.RollbackConfirm
import net.robog.clarity.app.ui.settings.RollbackStage
import net.robog.clarity.app.ui.settings.SettingsActions
import net.robog.clarity.app.ui.settings.SettingsContent
import net.robog.clarity.app.ui.settings.SettingsPage
import net.robog.clarity.app.ui.settings.SettingsUiState
import net.robog.clarity.app.ui.settings.SourceRow
import net.robog.clarity.app.ui.settings.backupRows
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.shared.backup.BackupInfo
import net.robog.clarity.shared.backup.BackupKind
import net.robog.clarity.shared.session.Settings

private val inTheMorning = Instant.parse("2026-11-10T07:00:00Z")

private fun nightly(day: Int, bytes: Long) = BackupInfo(
    "clarity-2026-11-%02d.dump".format(day), BackupKind.NIGHTLY, "2026-11-%02d".format(day),
    Instant.parse("2026-11-%02dT07:00:00Z".format(day)), bytes, "ab".repeat(32),
)

private val infos = listOf(
    nightly(10, 12_400_000),
    BackupInfo("clarity-2026-11-10T0905-before-rollback.dump", BackupKind.BEFORE_ROLLBACK, "2026-11-10", Instant.parse("2026-11-10T14:05:00Z"), 12_450_000, "cd".repeat(32)),
    nightly(9, 12_100_000),
    nightly(8, 11_900_000),
    nightly(7, 11_700_000),
    nightly(6, 11_400_000),
    nightly(5, 11_200_000),
    nightly(4, 10_900_000),
)

private val rows = backupRows(infos, TimeZone.UTC)

private val settingsState = SettingsUiState(
    instanceUrl = "https://clarity.home.example.org", deviceId = "dev_8f3k2m9q", loading = false,
    settings = Settings(), constitution = "Version 3, 14 lines, amended yesterday",
    serverSources = listOf(SourceRow("mail", "2 accounts", "4 min ago", "mail")),
    smsGranted = true, notificationsOn = true, batteryUnrestricted = true,
)

@Composable
private fun Frame(content: @Composable () -> Unit) = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) { content() }
}

@Composable
private fun BackupsPage(state: BackupsUiState) = Frame {
    SettingsContent(
        settingsState, SettingsActions(),
        backups = { BackupsContent(state, BackupsActions()) },
        startPage = SettingsPage.Backups,
    )
}

/** Settings home, scrolled to the "Your data" group that holds the Backups entry. */
@PreviewTest @Screen @Composable
fun BackupsSettingsEntry() = Frame {
    Box(Modifier.fillMaxSize().clipToBounds()) {
        Box(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).offset(y = (-420).dp).requiredHeight(2600.dp)) {
            SettingsContent(settingsState, SettingsActions())
        }
    }
}

/** The list, newest first, with a copy made before a rollback marked. */
@PreviewTest @Screen @Composable
fun BackupsList() = BackupsPage(BackupsUiState(loading = false, rows = rows))

/** The sheet before the fingerprint or face prompt. */
@PreviewTest @Part @Composable
fun BackupsRollbackConfirm() = Frame {
    Box(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 24.dp)) {
        RollbackConfirm(rows[2], onConfirm = {}, onCancel = {})
    }
}

/** While the server restores and restarts. */
@PreviewTest @Screen @Composable
fun BackupsRollbackWaiting() = BackupsPage(BackupsUiState(loading = false, rows = rows, stage = RollbackStage.Waiting(rows[2])))
