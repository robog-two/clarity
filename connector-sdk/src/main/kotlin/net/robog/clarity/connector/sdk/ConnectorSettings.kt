package net.robog.clarity.connector.sdk

import arrow.core.Either
import arrow.core.raise.either
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Why [ConnectorSettings] could not be built from the environment. */
sealed interface SettingsError {
    val message: String

    /** Neither `CLARITY_CONNECTOR_KEY` nor a readable key file is available. */
    data class MissingKey(val triedFile: String) : SettingsError {
        override val message get() = "no connector key: set CLARITY_CONNECTOR_KEY or provide a readable file at $triedFile"
    }

    /** The key (inline or file) was present but blank. */
    data object BlankKey : SettingsError {
        override val message get() = "connector key is blank"
    }

    /** `CLARITY_SYNC_INTERVAL` is not a positive ISO-8601 or Kotlin duration. */
    data class InvalidInterval(val raw: String) : SettingsError {
        override val message get() = "CLARITY_SYNC_INTERVAL '$raw' is not a positive duration (e.g. PT5M or 5m)"
    }
}

/**
 * Runtime settings shared by every connector, normally read from the environment
 * (see [fromEnv]). The key is never printed by [toString].
 *
 * @property baseUrl backend base URL without trailing slash.
 * @property connectorKey bearer credential for `/ingest/v1`.
 * @property syncInterval pause between two sync runs.
 * @property stateDir directory of the connector's durable volume (`/data`).
 */
data class ConnectorSettings(
    val baseUrl: String,
    val connectorKey: String,
    val syncInterval: Duration,
    val stateDir: Path,
) {
    override fun toString(): String =
        "ConnectorSettings(baseUrl=$baseUrl, connectorKey=<redacted>, syncInterval=$syncInterval, stateDir=$stateDir)"

    companion object {
        const val DEFAULT_URL = "http://backend:8080"
        const val DEFAULT_KEY_FILE = "/run/secrets/connector_key"
        const val DEFAULT_STATE_DIR = "/data"
        val DEFAULT_INTERVAL: Duration = 5.minutes

        /**
         * Pure parse of the environment. [readFile] returns a file's text or null when it cannot be
         * read, which keeps this function free of IO and trivially testable.
         *
         * Precedence for the key: `CLARITY_CONNECTOR_KEY`, then `CLARITY_CONNECTOR_KEY_FILE`
         * (default [DEFAULT_KEY_FILE]).
         */
        fun parse(env: Map<String, String>, readFile: (String) -> String?): Either<SettingsError, ConnectorSettings> =
            either {
                val keyFile = env["CLARITY_CONNECTOR_KEY_FILE"]?.takeIf(String::isNotBlank) ?: DEFAULT_KEY_FILE
                val rawKey = env["CLARITY_CONNECTOR_KEY"]?.takeIf(String::isNotBlank)
                    ?: readFile(keyFile)
                    ?: raise(SettingsError.MissingKey(keyFile))
                val key = rawKey.trim().ifEmpty { raise(SettingsError.BlankKey) }

                val interval = env["CLARITY_SYNC_INTERVAL"]?.takeIf(String::isNotBlank)
                    ?.let { raw -> parseDuration(raw) ?: raise(SettingsError.InvalidInterval(raw)) }
                    ?: DEFAULT_INTERVAL

                ConnectorSettings(
                    baseUrl = (env["CLARITY_URL"]?.takeIf(String::isNotBlank) ?: DEFAULT_URL).trim().trimEnd('/'),
                    connectorKey = key,
                    syncInterval = interval,
                    stateDir = Paths.get(env["CLARITY_STATE_DIR"]?.takeIf(String::isNotBlank) ?: DEFAULT_STATE_DIR),
                )
            }

        /**
         * Reads settings from the process environment and the key file.
         * Misconfiguration is a startup bug, so it fails fast with [IllegalStateException].
         */
        fun fromEnv(): ConnectorSettings =
            parse(System.getenv(), ::readFileOrNull).fold(
                ifLeft = { error("invalid connector settings: ${it.message}") },
                ifRight = { it },
            )

        /** ISO-8601 (`PT5M`) or Kotlin duration (`5m`, `1h 30m`); null unless strictly positive. */
        internal fun parseDuration(raw: String): Duration? =
            (Duration.parseIsoStringOrNull(raw.trim()) ?: Duration.parseOrNull(raw.trim()))
                ?.takeIf { it.isPositive() && it.isFinite() }

        private fun readFileOrNull(path: String): String? =
            try {
                Paths.get(path).readText()
            } catch (_: IOException) {
                null
            }
    }
}
