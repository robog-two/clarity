package net.robog.clarity.shared.backup

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Why a backup file exists. */
@Serializable
enum class BackupKind {
    /** The 2 am dump, one per day on the home clock; the last seven are kept. */
    @SerialName("nightly") NIGHTLY,

    /** A copy of the database taken just before a rollback, so the rollback itself can be undone; the last two are kept. */
    @SerialName("before_rollback") BEFORE_ROLLBACK,
}

/** One backup file. [name] is the file's name and its id; [sha256] is the lowercase hex SHA-256 of its bytes. */
@Serializable
data class BackupInfo(
    val name: String,
    val kind: BackupKind,
    /** The home-clock day a nightly backup is named for (`2026-11-10`); for the other kind, the day it was made. */
    val date: String,
    val createdAt: Instant,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * `GET /api/v1/backups`, newest first. [busy] is true while a backup or rollback is running.
 * [lastRollback] is how the most recent rollback ended; it survives the restart that follows one,
 * so the app can tell once it reconnects.
 */
@Serializable
data class BackupList(
    val backups: List<BackupInfo>,
    val busy: Boolean = false,
    val lastRollback: RollbackResult? = null,
)

/** How a rollback ended. A failed one changed nothing. */
@Serializable
data class RollbackResult(val name: String, val at: Instant, val ok: Boolean, val message: String? = null)

/**
 * `POST /api/v1/backups/rollback`: roll the whole database back to [name]. [signature] is the
 * base64url (no padding) DER ECDSA signature (`SHA256withECDSA`) of [RollbackPayload.payload] made
 * on the phone with the approval key (biometric for every use). It names this exact file and its
 * [sha256], so it works once and for that file only.
 */
@Serializable
data class RollbackRequest(val name: String, val sha256: String, val signature: String)

/** The answer to an accepted rollback: the backend restores, then restarts, so the app waits and reconnects. */
@Serializable
data class RollbackAccepted(
    /** The copy of the database as it was just before; it can be rolled back to as well. */
    val safetyCopy: BackupInfo,
)

/** The exact text the phone signs and the backend verifies for a rollback. */
object RollbackPayload {
    const val PREFIX = "clarity-rollback-v1"

    /** `clarity-rollback-v1|<file name>|<lowercase hex sha256 of the file>`. */
    fun payload(name: String, sha256Hex: String): String = "$PREFIX|$name|$sha256Hex"
}
