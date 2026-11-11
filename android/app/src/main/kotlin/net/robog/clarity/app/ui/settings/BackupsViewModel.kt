package net.robog.clarity.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.BackupsRepository
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.app.vault.RollbackSigned
import net.robog.clarity.app.vault.RollbackSigning
import net.robog.clarity.app.vault.approvalSentence
import net.robog.clarity.shared.backup.BackupInfo
import net.robog.clarity.shared.backup.BackupKind
import net.robog.clarity.shared.backup.RollbackResult

/** One backup as the list shows it. */
data class BackupRow(
    val name: String,
    /** "Tue Nov 10" for a nightly backup, "Tue Nov 10 at 2:05 pm" for a copy made before a rollback. */
    val title: String,
    /** The size, and for a copy made before a rollback what it is. */
    val detail: String,
    val beforeRollback: Boolean,
    /** Made by hand with "Back up now". */
    val manual: Boolean = false,
    val info: BackupInfo,
)

/** Where a rollback stands. */
sealed interface RollbackStage {
    data object Idle : RollbackStage

    /** The sheet that says what is lost and asks for a yes. */
    data class Confirm(val row: BackupRow) : RollbackStage

    /** The fingerprint or face prompt is up. */
    data class Signing(val row: BackupRow) : RollbackStage

    /** Sent. The server restores and restarts; the app asks until it answers again. */
    data class Waiting(val row: BackupRow) : RollbackStage

    /** How it ended. */
    data class Finished(val title: String, val detail: String?, val ok: Boolean) : RollbackStage
}

data class BackupsUiState(
    val loading: Boolean = true,
    val rows: List<BackupRow> = emptyList(),
    /** The server is making a backup or restoring. */
    val serverBusy: Boolean = false,
    /** "Back up now" is running. */
    val backingUp: Boolean = false,
    /** False on a server without backups: the page says so. */
    val available: Boolean = true,
    val message: String? = null,
    val stage: RollbackStage = RollbackStage.Idle,
)

private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MONTH_NAMES = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "Tue Nov 10". */
fun dayText(date: LocalDate): String = "${DAY_NAMES[date.dayOfWeek.ordinal]} ${MONTH_NAMES[date.month.ordinal]} ${date.day}"

/** "2:05 pm". */
fun clockText(at: Instant, zone: TimeZone): String {
    val t = at.toLocalDateTime(zone)
    val hour = if (t.hour % 12 == 0) 12 else t.hour % 12
    return "$hour:%02d %s".format(t.minute, if (t.hour < 12) "am" else "pm")
}

/** "12.4 MB", "830 KB". */
fun sizeText(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(java.util.Locale.US, bytes / 1_000_000.0)
    bytes >= 1_000 -> "${(bytes + 500) / 1_000} KB"
    else -> "$bytes B"
}

/** Pure: the rows for [backups], in the order given (the server sends the newest first). */
fun backupRows(backups: List<BackupInfo>, zone: TimeZone = TimeZone.currentSystemDefault()): List<BackupRow> = backups.map { info ->
    val day = runCatching { LocalDate.parse(info.date) }.getOrNull()
    val dayName = day?.let(::dayText) ?: info.date
    when (info.kind) {
        BackupKind.NIGHTLY -> BackupRow(info.name, dayName, sizeText(info.sizeBytes), beforeRollback = false, info = info)
        BackupKind.MANUAL -> BackupRow(
            info.name, "$dayName at ${clockText(info.createdAt, zone)}",
            "Saved by hand. ${sizeText(info.sizeBytes)}", beforeRollback = false, manual = true, info = info,
        )
        BackupKind.BEFORE_ROLLBACK -> BackupRow(
            info.name, "$dayName at ${clockText(info.createdAt, zone)}",
            "Copy kept before a rollback. ${sizeText(info.sizeBytes)}", beforeRollback = true, info = info,
        )
    }
}

/** Pure: how a finished rollback is told. */
fun finishedStage(result: RollbackResult, row: BackupRow): RollbackStage.Finished =
    if (result.ok) RollbackStage.Finished("Rolled back to ${row.title}", "Everything is as it was then.", ok = true)
    else RollbackStage.Finished("Couldn’t roll back", result.message ?: "Nothing was changed.", ok = false)

/**
 * "Backups" in Settings: the list, "Back up now", and rolling back. A rollback is signed on the phone
 * (fingerprint or face), sent, and then the app waits: the server restores and restarts, and the
 * app asks for the list until it answers with a new result.
 */
@Inject
@ViewModelKey(BackupsViewModel::class)
@ContributesIntoMap(AppScope::class)
class BackupsViewModel(
    private val backups: BackupsRepository,
    private val signer: RollbackSigning,
) : ViewModel() {
    private val _state = MutableStateFlow(BackupsUiState())
    val state: StateFlow<BackupsUiState> = _state.asStateFlow()

    /** How the server's last rollback ended before this one: a new value means ours is over. */
    private var lastSeen: RollbackResult? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        when (val r = backups.list()) {
            is ApiResult.Success -> {
                lastSeen = r.value.lastRollback
                _state.update {
                    it.copy(loading = false, rows = backupRows(r.value.backups), serverBusy = r.value.busy, available = true, message = null)
                }
            }
            is ApiResult.Failure -> _state.update {
                it.copy(
                    loading = false,
                    available = r.error !is net.robog.clarity.app.data.ApiError.NotFound,
                    message = r.error.takeUnless { e -> e is net.robog.clarity.app.data.ApiError.NotFound }?.sentence(),
                )
            }
        }
    }

    fun backUpNow() {
        if (_state.value.backingUp || _state.value.stage != RollbackStage.Idle) return
        _state.update { it.copy(backingUp = true, message = null) }
        viewModelScope.launch {
            val made = backups.backUpNow()
            _state.update { s -> s.copy(backingUp = false, message = (made as? ApiResult.Failure)?.error?.approvalSentence()) }
            load()
        }
    }

    /** "Roll back" on a row: opens the sheet. */
    fun askRollback(name: String) {
        val row = _state.value.rows.firstOrNull { it.name == name } ?: return
        if (_state.value.stage == RollbackStage.Idle) _state.update { it.copy(stage = RollbackStage.Confirm(row), message = null) }
    }

    fun cancelRollback() {
        if (_state.value.stage is RollbackStage.Confirm) _state.update { it.copy(stage = RollbackStage.Idle) }
    }

    /** The sheet's yes: the prompt, the signed request, then the wait. */
    fun confirmRollback() {
        val row = (_state.value.stage as? RollbackStage.Confirm)?.row ?: return
        _state.update { it.copy(stage = RollbackStage.Signing(row)) }
        viewModelScope.launch {
            when (val signed = signer.sign(row.info, "Roll back to ${row.title}")) {
                is RollbackSigned.No -> _state.update { it.copy(stage = RollbackStage.Idle, message = signed.message) }
                is RollbackSigned.Ok -> when (val sent = backups.rollback(signed.request)) {
                    is ApiResult.Failure -> _state.update { it.copy(stage = RollbackStage.Idle, message = sent.error.approvalSentence()) }
                    is ApiResult.Success -> {
                        _state.update { it.copy(stage = RollbackStage.Waiting(row)) }
                        waitForRestart(row)
                    }
                }
            }
        }
    }

    /** Asks for the list until the server answers with a new rollback result, or gives up after a few minutes. */
    private suspend fun waitForRestart(row: BackupRow) {
        repeat(MAX_POLLS) {
            delay(POLL_EVERY)
            val r = backups.list()
            if (r is ApiResult.Success && r.value.lastRollback != lastSeen && r.value.lastRollback != null) {
                val result = checkNotNull(r.value.lastRollback)
                lastSeen = result
                _state.update {
                    it.copy(rows = backupRows(r.value.backups), serverBusy = r.value.busy, stage = finishedStage(result, row))
                }
                return
            }
        }
        _state.update {
            it.copy(stage = RollbackStage.Finished("Still restarting", "It is taking longer than usual. Check again in a minute.", ok = false))
        }
    }

    fun dismissResult() {
        if (_state.value.stage is RollbackStage.Finished) {
            _state.update { it.copy(stage = RollbackStage.Idle) }
            refresh()
        }
    }

    private companion object {
        val POLL_EVERY = 2.seconds
        const val MAX_POLLS = 90
    }
}
