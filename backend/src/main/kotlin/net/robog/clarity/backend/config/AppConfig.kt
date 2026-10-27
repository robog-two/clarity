package net.robog.clarity.backend.config

import java.nio.file.Path

/** A configuration value that must never appear in logs; [toString] is redacted. */
@JvmInline
value class Secret(val value: String) {
    override fun toString(): String = "Secret(***)"
}

data class InstanceConfig(val name: String)

data class HttpConfig(val port: Int)

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: Secret?,
    val maxPoolSize: Int,
)

/** How a new device may enroll: see ARCHITECTURE.md "Enrollment". */
enum class EnrollmentMode { TOFU, TOKEN }

data class EnrollmentConfig(
    val mode: EnrollmentMode,
    /** Operator-provided enrollment token; when null one is generated on first boot. */
    val token: Secret?,
)

data class ConnectorsConfig(
    /** Dev convenience: grant every manifest noun immediately instead of leaving it `pending`. */
    val autoGrant: Boolean,
    /** Directory scanned at boot for `connector_<id>_key` files (Podman secrets). */
    val secretsDir: Path,
)

/**
 * Fully resolved backend configuration. Built once at startup by [AppConfig.load] from
 * `application.yaml` plus environment overrides, then injected as an immutable value.
 */
data class AppConfig(
    val instance: InstanceConfig,
    val http: HttpConfig,
    val database: DatabaseConfig,
    val dataDir: Path,
    val enrollment: EnrollmentConfig,
    val connectors: ConnectorsConfig,
    val agent: AgentConfig,
    /** Reports: the render service and the rendition cache. */
    val reports: ReportsConfig = ReportsConfig(),
) {
    companion object
}

/**
 * Reports (documents the agent writes). [renderUrl] is the `render` service (pandoc + Typst, no
 * network but the backend's), [renderKeyFile] its bearer key; each render times out after
 * [renderTimeout]. Rendered files are a cache under `<dataDir>/reports`: once they pass
 * [cacheMaxBytes] the least recently downloaded go first ([sweepInterval]); revisions are never touched.
 */
data class ReportsConfig(
    val renderUrl: String = "http://render:8950",
    val renderKeyFile: String? = "/run/secrets/render_key",
    val renderTimeout: kotlin.time.Duration = kotlin.time.Duration.parse("60s"),
    val cacheMaxBytes: Long = 500L * 1024 * 1024,
    val sweepInterval: kotlin.time.Duration = kotlin.time.Duration.parse("1h"),
)
