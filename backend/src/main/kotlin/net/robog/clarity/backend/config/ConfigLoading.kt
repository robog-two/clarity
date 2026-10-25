package net.robog.clarity.backend.config

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.context.either
import arrow.core.raise.context.raise
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.yaml.YamlConfig
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.time.Duration

/** Why configuration could not be loaded. */
sealed interface ConfigError {
    val message: String

    data class Invalid(val key: String, val value: String, val reason: String) : ConfigError {
        override val message get() = "invalid configuration $key=$value: $reason"
    }

    data class UnreadableFile(val key: String, val path: String, val cause: String) : ConfigError {
        override val message get() = "cannot read $key file $path: $cause"
    }
}

/** Reads a secret file; returns the failure reason on error. Injected so loading stays testable. */
fun interface FileReader {
    fun read(path: Path): Either<String, String>

    companion object {
        val Disk = FileReader { path ->
            Either.catch { path.readText() }.mapLeft { it.message ?: it::class.simpleName.orEmpty() }
        }
    }
}

/** Loads `application.yaml` from the classpath. */
fun loadYamlConfig(resource: String = "application.yaml"): ApplicationConfig =
    requireNotNull(YamlConfig(resource)) { "$resource not found on the classpath" }

/**
 * Resolves configuration as a pure function of ([env], [yaml]): an environment variable wins,
 * then the YAML value, then the built-in default. Secrets additionally honour the `*_FILE`
 * convention (`CLARITY_DB_PASSWORD_FILE`), which takes precedence over the plain variable.
 */
fun AppConfig.Companion.load(
    env: Map<String, String>,
    yaml: ApplicationConfig,
    files: FileReader = FileReader.Disk,
): Either<ConfigError, AppConfig> = either { Lookup(env, yaml, files).appConfig() }

private class Lookup(
    private val env: Map<String, String>,
    private val yaml: ApplicationConfig,
    private val files: FileReader,
) {
    fun string(envName: String, path: String): String? =
        env[envName]?.takeIf(String::isNotBlank)
            ?: yaml.propertyOrNull("clarity.$path")?.getString()?.takeIf(String::isNotBlank)

    context(_: Raise<ConfigError>)
    fun secret(envName: String, path: String): String? {
        val file = env["${envName}_FILE"]?.takeIf(String::isNotBlank)
        if (file != null) {
            return files.read(Path(file)).fold(
                { raise(ConfigError.UnreadableFile("${envName}_FILE", file, it)) },
                { it.trim() },
            ).takeIf(String::isNotEmpty)
        }
        return string(envName, path)
    }

    context(_: Raise<ConfigError>)
    fun int(envName: String, path: String, default: Int, min: Int = 1): Int {
        val raw = string(envName, path) ?: return default
        val value = raw.toIntOrNull()
        if (value == null || value < min) raise(ConfigError.Invalid(envName, raw, "expected an integer >= $min"))
        return value
    }

    context(_: Raise<ConfigError>)
    fun bool(envName: String, path: String, default: Boolean): Boolean {
        val raw = string(envName, path) ?: return default
        return raw.lowercase().toBooleanStrictOrNull()
            ?: raise(ConfigError.Invalid(envName, raw, "expected true or false"))
    }

    context(_: Raise<ConfigError>)
    fun duration(envName: String, path: String, default: Duration): Duration {
        val raw = string(envName, path) ?: return default
        val value = Duration.parseOrNull(raw)
        if (value == null || !value.isPositive()) {
            raise(ConfigError.Invalid(envName, raw, "expected a positive duration such as 2h, 30m or PT2H"))
        }
        return value
    }

    context(_: Raise<ConfigError>)
    fun appConfig(): AppConfig {
        val mode = string("CLARITY_ENROLLMENT_MODE", "enrollment.mode").let { raw ->
            when (raw?.lowercase()) {
                null, "tofu" -> EnrollmentMode.TOFU
                "token" -> EnrollmentMode.TOKEN
                else -> raise(ConfigError.Invalid("CLARITY_ENROLLMENT_MODE", raw, "expected tofu or token"))
            }
        }
        val mcpRaw = string("CLARITY_MCP_SERVERS", "agent.mcpServers").orEmpty()
        return AppConfig(
            instance = InstanceConfig(string("CLARITY_INSTANCE_NAME", "instance.name") ?: "Clarity"),
            http = HttpConfig(int("CLARITY_HTTP_PORT", "http.port", 8080, min = 0)),
            database = DatabaseConfig(
                url = string("CLARITY_DB_URL", "database.url") ?: "jdbc:postgresql://localhost:5432/clarity",
                user = string("CLARITY_DB_USER", "database.user") ?: "clarity",
                password = secret("CLARITY_DB_PASSWORD", "database.password")?.let(::Secret),
                maxPoolSize = int("CLARITY_DB_POOL_SIZE", "database.maxPoolSize", 10),
            ),
            dataDir = Path(string("CLARITY_DATA_DIR", "dataDir") ?: "./data"),
            enrollment = EnrollmentConfig(
                mode = mode,
                token = secret("CLARITY_ENROLLMENT_TOKEN", "enrollment.token")?.let(::Secret),
            ),
            connectors = ConnectorsConfig(
                autoGrant = bool("CLARITY_CONNECTORS_AUTO_GRANT", "connectors.autoGrant", false),
                secretsDir = Path(string("CLARITY_SECRETS_DIR", "connectors.secretsDir") ?: "/run/secrets"),
            ),
            agent = AgentConfig(
                anthropicApiKey = secret("ANTHROPIC_API_KEY", "agent.anthropicApiKey"),
                model = string("CLARITY_AGENT_MODEL", "agent.model") ?: "claude-opus-5-5",
                effort = string("CLARITY_AGENT_EFFORT", "agent.effort") ?: "medium",
                maxTurns = int("CLARITY_AGENT_MAX_TURNS", "agent.maxTurns", 30),
                maxConcurrentRuns = int("CLARITY_AGENT_MAX_CONCURRENT_RUNS", "agent.maxConcurrentRuns", 2),
                reviewInterval = duration("CLARITY_REVIEW_INTERVAL", "agent.reviewInterval", Duration.parse("2h")),
                mcpServers = parseMcpServers(mcpRaw),
            ),
        )
    }
}

/**
 * Parses `name=url,name2=url2` (the `CLARITY_MCP_SERVERS` format). Entries without a name or URL
 * are ignored; the URL may itself contain `=` (query strings), only the first one splits.
 */
fun parseMcpServers(raw: String): List<McpServerConfig> =
    raw.split(',')
        .mapNotNull { entry ->
            val name = entry.substringBefore('=', "").trim()
            val url = entry.substringAfter('=', "").trim()
            if (name.isEmpty() || url.isEmpty()) null else McpServerConfig(name, url)
        }
