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
import net.robog.clarity.harness.OpenRouterRouting

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

    /** Like [string], but a value set to blank is kept as "" (an explicit "none"), not taken as unset. */
    fun stringOrBlank(envName: String, path: String): String? =
        env[envName] ?: yaml.propertyOrNull("clarity.$path")?.getString()

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
    fun fraction(envName: String, path: String, default: Double): Double {
        val raw = string(envName, path) ?: return default
        val value = raw.toDoubleOrNull()
        if (value == null || value !in 0.0..1.0) raise(ConfigError.Invalid(envName, raw, "expected a number from 0 to 1"))
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

    /**
     * `CLARITY_AGENT_<ROLE>_PROVIDER|_MODEL|_EFFORT|_MAX_TOKENS|_CACHE_TTL|_COMPACTION|_COMPACT_AT_TOKENS` (yaml
     * `agent.roles.<role>.provider|model|effort|maxTokens|cacheTtl|compaction|compactAtTokens`). The legacy
     * `CLARITY_AGENT_MODEL` / `CLARITY_AGENT_EFFORT` (yaml `agent.model` / `agent.effort`) are the
     * lead's fallback.
     */
    context(_: Raise<ConfigError>)
    fun agentRoles(): AgentRoles {
        val defaults = AgentRoles()
        fun role(role: AgentRole, base: RoleConfig, legacy: Boolean): RoleConfig {
            val name = role.name
            val yamlKey = name.lowercase()
            // Precedence: role env, legacy env, role yaml, legacy yaml, built-in default.
            fun pick(setting: String, legacyEnv: String, legacyPath: String): String? {
                val roleEnv = env["CLARITY_AGENT_${name}_$setting"]?.takeIf(String::isNotBlank)
                val oldEnv = if (legacy) env[legacyEnv]?.takeIf(String::isNotBlank) else null
                val roleYaml = yaml.propertyOrNull("clarity.agent.roles.$yamlKey.${setting.lowercase()}")?.getString()?.takeIf(String::isNotBlank)
                val oldYaml = if (legacy) yaml.propertyOrNull("clarity.$legacyPath")?.getString()?.takeIf(String::isNotBlank) else null
                return roleEnv ?: oldEnv ?: roleYaml ?: oldYaml
            }
            val model = pick("MODEL", "CLARITY_AGENT_MODEL", "agent.model") ?: base.model
            val effort = pick("EFFORT", "CLARITY_AGENT_EFFORT", "agent.effort") ?: base.effort
            val ttl = string("CLARITY_AGENT_${name}_CACHE_TTL", "agent.roles.$yamlKey.cacheTtl") ?: base.cacheTtl
            if (ttl != "5m" && ttl != "1h") raise(ConfigError.Invalid("CLARITY_AGENT_${name}_CACHE_TTL", ttl, "expected 5m or 1h"))
            val compactAt = int(
                "CLARITY_AGENT_${name}_COMPACT_AT_TOKENS", "agent.roles.$yamlKey.compactAtTokens", base.compactAtTokens.toInt(),
                min = net.robog.clarity.harness.COMPACTION_MIN_TRIGGER_TOKENS.toInt(),
            ).toLong()
            val providerRaw = string("CLARITY_AGENT_${name}_PROVIDER", "agent.roles.$yamlKey.provider")
            val provider = when (providerRaw?.lowercase()) {
                null -> base.provider
                "anthropic" -> ModelProvider.ANTHROPIC
                "openrouter" -> ModelProvider.OPENROUTER
                else -> raise(ConfigError.Invalid("CLARITY_AGENT_${name}_PROVIDER", providerRaw, "expected anthropic or openrouter"))
            }
            return RoleConfig(
                provider = provider,
                model = model,
                effort = effort,
                maxTokens = int("CLARITY_AGENT_${name}_MAX_TOKENS", "agent.roles.$yamlKey.maxTokens", base.maxTokens.toInt()).toLong(),
                cacheTtl = ttl,
                compaction = bool("CLARITY_AGENT_${name}_COMPACTION", "agent.roles.$yamlKey.compaction", base.compaction),
                compactAtTokens = compactAt,
            )
        }
        return AgentRoles(
            lead = role(AgentRole.LEAD, defaults.lead, legacy = true),
            helper = role(AgentRole.HELPER, defaults.helper, legacy = false),
            guard = role(AgentRole.GUARD, defaults.guard, legacy = false),
            triage = role(AgentRole.TRIAGE, defaults.triage, legacy = false),
        )
    }

    /**
     * OpenRouter provider routing: `OPENROUTER_ZDR` (default true), `OPENROUTER_QUANTIZATIONS` (comma
     * list, default fp8,bf16,fp16; blank: any), `OPENROUTER_SORT` (price | throughput | latency,
     * default price; blank: OpenRouter's own balancing), `OPENROUTER_PROVIDER_ORDER` (comma list of
     * providers to try first, e.g. DeepInfra,Novita), `OPENROUTER_ALLOW_FALLBACKS` (default: OpenRouter's,
     * true). Yaml `agent.openRouter.zdr|quantizations|sort|order|allowFallbacks`.
     */
    context(_: Raise<ConfigError>)
    fun openRouterRouting(): OpenRouterRouting {
        val defaults = OpenRouterRouting()
        val quantizations = stringOrBlank("OPENROUTER_QUANTIZATIONS", "agent.openRouter.quantizations")
            ?.split(',')?.map { it.trim().lowercase() }?.filter(String::isNotEmpty)
            ?: defaults.quantizations
        val sortRaw = stringOrBlank("OPENROUTER_SORT", "agent.openRouter.sort")
        val sort = when (val v = sortRaw?.trim()?.lowercase()) {
            null -> defaults.sort
            "" -> null
            "price", "throughput", "latency" -> v
            else -> raise(ConfigError.Invalid("OPENROUTER_SORT", sortRaw, "expected price, throughput, latency or blank"))
        }
        val order = string("OPENROUTER_PROVIDER_ORDER", "agent.openRouter.order")
            ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        val fallbacks = string("OPENROUTER_ALLOW_FALLBACKS", "agent.openRouter.allowFallbacks")
            ?.let { bool("OPENROUTER_ALLOW_FALLBACKS", "agent.openRouter.allowFallbacks", true) }
        return OpenRouterRouting(
            zdr = bool("OPENROUTER_ZDR", "agent.openRouter.zdr", defaults.zdr),
            quantizations = quantizations,
            sort = sort,
            order = order,
            allowFallbacks = fallbacks,
        )
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
        val roles = agentRoles()
        val openRouterKey = secret("OPENROUTER_API_KEY", "agent.openRouterApiKey")
        if (openRouterKey == null) {
            AgentRole.entries.firstOrNull { roles[it].provider == ModelProvider.OPENROUTER }?.let {
                raise(ConfigError.Invalid("CLARITY_AGENT_${it.name}_PROVIDER", "openrouter", "set OPENROUTER_API_KEY (or OPENROUTER_API_KEY_FILE) to use OpenRouter"))
            }
        }
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
                roles = roles,
                maxTurns = int("CLARITY_AGENT_MAX_TURNS", "agent.maxTurns", 30),
                maxConcurrentRuns = int("CLARITY_AGENT_MAX_CONCURRENT_RUNS", "agent.maxConcurrentRuns", 2),
                reviewInterval = duration("CLARITY_REVIEW_INTERVAL", "agent.reviewInterval", Duration.parse("2h")),
                mcpServers = parseMcpServers(mcpRaw),
                anthropicBaseUrl = string("ANTHROPIC_BASE_URL", "agent.anthropicBaseUrl"),
                openRouterApiKey = openRouterKey,
                openRouterBaseUrl = string("OPENROUTER_BASE_URL", "agent.openRouterBaseUrl") ?: DEFAULT_OPENROUTER_BASE_URL,
                memory = MemoryConfig(
                    embeddings = bool("CLARITY_MEMORY_EMBEDDINGS", "agent.memory.embeddings", true),
                    embeddingsModel = string("CLARITY_MEMORY_EMBEDDINGS_MODEL", "agent.memory.embeddingsModel") ?: DEFAULT_EMBEDDINGS_MODEL,
                    embeddingDimensions = int("CLARITY_MEMORY_EMBEDDINGS_DIMENSIONS", "agent.memory.embeddingDimensions", 1024, min = 0),
                ),
                openRouterRouting = openRouterRouting(),
                decisions = DecisionConfig(
                    enabled = bool("CLARITY_DECISIONS", "agent.decisions.enabled", true),
                    model = string("CLARITY_DECISION_MODEL", "agent.decisions.model") ?: DEFAULT_DECISION_MODEL,
                    reviewGate = bool("CLARITY_REVIEW_GATE", "agent.decisions.reviewGate", true),
                    reviewThreshold = fraction("CLARITY_REVIEW_GATE_THRESHOLD", "agent.decisions.reviewThreshold", 0.3),
                    reviewMaxQuiet = duration("CLARITY_REVIEW_MAX_QUIET", "agent.decisions.reviewMaxQuiet", Duration.parse("8h")),
                ),
            ),
        )
    }
}

/**
 * Parses `name=url[;keyFile=/path],name2=url2` (the `CLARITY_MCP_SERVERS` format). Entries without
 * a name or URL are ignored; the URL may itself contain `=` (query strings), only the first one
 * splits. Options follow the URL after `;` as `key=value` (currently only `keyFile`).
 */
fun parseMcpServers(raw: String): List<McpServerConfig> =
    raw.split(',')
        .mapNotNull { entry ->
            val parts = entry.split(';')
            val head = parts.first()
            val name = head.substringBefore('=', "").trim()
            val url = head.substringAfter('=', "").trim()
            val keyFile = parts.drop(1)
                .map { it.trim() }
                .firstOrNull { it.startsWith("keyFile=") }
                ?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }
            if (name.isEmpty() || url.isEmpty()) null else McpServerConfig(name, url, keyFile)
        }
