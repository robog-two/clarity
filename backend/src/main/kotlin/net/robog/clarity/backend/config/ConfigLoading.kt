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

    data class Missing(val key: String, val reason: String) : ConfigError {
        override val message get() = "missing configuration $key: $reason"
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
 * convention (`CLARITY_DB_PASSWORD_FILE`), which takes precedence over the plain variable unless the file is empty.
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
            // An empty file (an unfilled Podman secret) falls back to the plain variable.
            return files.read(Path(file)).fold(
                { raise(ConfigError.UnreadableFile("${envName}_FILE", file, it)) },
                { it.trim() },
            ).takeIf(String::isNotEmpty) ?: string(envName, path)
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
    fun dollars(envName: String, path: String, default: Double): Double = dollarsOrNull(envName, path) ?: default

    context(_: Raise<ConfigError>)
    fun dollarsOrNull(envName: String, path: String): Double? {
        val raw = string(envName, path) ?: return null
        val value = raw.trim().removePrefix("$").toDoubleOrNull()
        if (value == null || value <= 0.0 || value.isInfinite()) raise(ConfigError.Invalid(envName, raw, "expected a dollar amount above 0, e.g. 0.15"))
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
     * `CLARITY_AGENT_<ROLE>_MODEL|_EFFORT|_MAX_TOKENS|_CACHE_TTL|_COMPACTION|_COMPACT_AT_TOKENS` (yaml
     * `agent.roles.<role>.model|effort|maxTokens|cacheTtl|compaction|compactAtTokens`). The legacy
     * `CLARITY_AGENT_MODEL` / `CLARITY_AGENT_EFFORT` (yaml `agent.model` / `agent.effort`) are the
     * lead's fallback. Every model is an OpenRouter id (`vendor/model`). The former
     * `CLARITY_AGENT_<ROLE>_PROVIDER` may still say `openrouter` (ignored); `anthropic` is refused,
     * since the direct Anthropic API is gone (Claude runs through OpenRouter as `anthropic/...`).
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
            if ('/' !in model || model.any(Char::isWhitespace)) {
                raise(ConfigError.Invalid("CLARITY_AGENT_${name}_MODEL", model, "expected an OpenRouter model id such as $DEFAULT_LEAD_MODEL or anthropic/claude-opus-5.5"))
            }
            val effort = pick("EFFORT", "CLARITY_AGENT_EFFORT", "agent.effort") ?: base.effort
            val ttl = string("CLARITY_AGENT_${name}_CACHE_TTL", "agent.roles.$yamlKey.cacheTtl") ?: base.cacheTtl
            if (ttl != "5m" && ttl != "1h") raise(ConfigError.Invalid("CLARITY_AGENT_${name}_CACHE_TTL", ttl, "expected 5m or 1h"))
            val compactAt = int(
                "CLARITY_AGENT_${name}_COMPACT_AT_TOKENS", "agent.roles.$yamlKey.compactAtTokens", base.compactAtTokens.toInt(),
                min = net.robog.clarity.harness.MIN_COMPACT_AT_TOKENS.toInt(),
            ).toLong()
            val providerRaw = string("CLARITY_AGENT_${name}_PROVIDER", "agent.roles.$yamlKey.provider")
            if (providerRaw != null && providerRaw.trim().lowercase() != "openrouter") {
                raise(ConfigError.Invalid("CLARITY_AGENT_${name}_PROVIDER", providerRaw, "every model runs on OpenRouter now; remove this setting (Claude models are anthropic/... there)"))
            }
            return RoleConfig(
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

    /**
     * `CLARITY_WEB_SEARCH` (default true), `CLARITY_WEB_SEARCH_ENGINE` (parallel), `CLARITY_WEB_SEARCH_MODE`
     * (fast; blank: the engine's default), `CLARITY_WEB_SEARCH_MAX_SEARCHES` (10 per helper run),
     * `CLARITY_WEB_SEARCH_MAX_RESULTS` (5 per search), `CLARITY_WEB_SEARCH_EXCLUDED_DOMAINS` (comma list),
     * `CLARITY_WEB_SEARCH_MAX_CHARACTERS` (400: the excerpt of each result; pages are fetched by the sandbox).
     * Yaml `agent.webSearch.*`. Firecrawl is refused: OpenRouter rejects it under zero data retention.
     */
    context(_: Raise<ConfigError>)
    fun webSearch(): WebSearchConfig {
        val defaults = WebSearchConfig()
        val engine = (string("CLARITY_WEB_SEARCH_ENGINE", "agent.webSearch.engine") ?: defaults.engine).trim().lowercase()
        if (engine !in WebSearchConfig.ENGINES) {
            raise(ConfigError.Invalid("CLARITY_WEB_SEARCH_ENGINE", engine, "expected one of ${WebSearchConfig.ENGINES.joinToString()} (firecrawl is not allowed under zero data retention)"))
        }
        val modeRaw = stringOrBlank("CLARITY_WEB_SEARCH_MODE", "agent.webSearch.mode")
        val mode = when {
            modeRaw == null -> defaults.mode
            modeRaw.isBlank() -> null
            else -> modeRaw.trim().lowercase()
        }
        val maxResults = int("CLARITY_WEB_SEARCH_MAX_RESULTS", "agent.webSearch.maxResults", defaults.maxResults)
        if (maxResults > 25) raise(ConfigError.Invalid("CLARITY_WEB_SEARCH_MAX_RESULTS", maxResults.toString(), "expected 1 to 25"))
        val maxCharacters = int("CLARITY_WEB_SEARCH_MAX_CHARACTERS", "agent.webSearch.maxCharactersPerResult", defaults.maxCharactersPerResult)
        if (maxCharacters > 100_000) raise(ConfigError.Invalid("CLARITY_WEB_SEARCH_MAX_CHARACTERS", maxCharacters.toString(), "expected 1 to 100000"))
        return WebSearchConfig(
            enabled = bool("CLARITY_WEB_SEARCH", "agent.webSearch.enabled", defaults.enabled),
            engine = engine,
            mode = mode,
            maxSearchesPerRun = int("CLARITY_WEB_SEARCH_MAX_SEARCHES", "agent.webSearch.maxSearchesPerRun", defaults.maxSearchesPerRun),
            maxResults = maxResults,
            excludedDomains = string("CLARITY_WEB_SEARCH_EXCLUDED_DOMAINS", "agent.webSearch.excludedDomains")
                ?.split(',')?.map { it.trim().lowercase() }?.filter(String::isNotEmpty).orEmpty(),
            maxCharactersPerResult = maxCharacters,
        )
    }

    /**
     * Reports: the render service (`CLARITY_RENDER_URL`, `CLARITY_RENDER_KEY_FILE`, `CLARITY_RENDER_TIMEOUT`)
     * and the rendition cache's size cap (`CLARITY_REPORTS_CACHE_MAX_MB`, default 500).
     */
    context(_: Raise<ConfigError>)
    fun reports(): ReportsConfig {
        val defaults = ReportsConfig()
        return ReportsConfig(
            renderUrl = string("CLARITY_RENDER_URL", "reports.renderUrl") ?: defaults.renderUrl,
            renderKeyFile = string("CLARITY_RENDER_KEY_FILE", "reports.renderKeyFile") ?: defaults.renderKeyFile,
            renderTimeout = duration("CLARITY_RENDER_TIMEOUT", "reports.renderTimeout", defaults.renderTimeout),
            cacheMaxBytes = int("CLARITY_REPORTS_CACHE_MAX_MB", "reports.cacheMaxMb", (defaults.cacheMaxBytes / MB).toInt()).toLong() * MB,
            sweepInterval = duration("CLARITY_REPORTS_SWEEP_INTERVAL", "reports.sweepInterval", defaults.sweepInterval),
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
            ?: raise(ConfigError.Missing("OPENROUTER_API_KEY", "every model runs on OpenRouter; set OPENROUTER_API_KEY or OPENROUTER_API_KEY_FILE"))
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
                roles = roles,
                maxTurns = int("CLARITY_AGENT_MAX_TURNS", "agent.maxTurns", 30),
                helperMaxTurns = int("CLARITY_AGENT_HELPER_MAX_TURNS", "agent.helperMaxTurns", 60),
                helperMaxCostUsd = dollars("CLARITY_HELPER_MAX_COST", "agent.helperMaxCost", 0.15),
                leadTurnMaxCostUsd = dollars("CLARITY_LEAD_TURN_MAX_COST", "agent.leadTurnMaxCost", 0.50),
                spend = SpendLimits(
                    weeklyUsd = dollarsOrNull("CLARITY_SPEND_WEEKLY_MAX", "agent.spend.weeklyMax"),
                    dailyUsd = AgentRole.entries.mapNotNull { role ->
                        val name = role.name
                        dollarsOrNull("CLARITY_SPEND_DAILY_MAX_$name", "agent.spend.dailyMax.${name.lowercase()}")?.let { role to it }
                    }.toMap(),
                ),
                helperMaxPerDay = int("CLARITY_HELPER_MAX_PER_DAY", "agent.helperMaxPerDay", 20),
                helperMaxResumes = int("CLARITY_HELPER_MAX_RESUMES", "agent.helperMaxResumes", 3, min = 0),
                maxConcurrentRuns = int("CLARITY_AGENT_MAX_CONCURRENT_RUNS", "agent.maxConcurrentRuns", 2),
                triggerFiresPerHour = int("CLARITY_TRIGGER_FIRES_PER_HOUR", "agent.triggerFiresPerHour", 6, min = 1),
                reviewInterval = duration("CLARITY_REVIEW_INTERVAL", "agent.reviewInterval", Duration.parse("2h")),
                mcpServers = parseMcpServers(mcpRaw),
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
                    failureGate = bool("CLARITY_FAILURE_GATE", "agent.decisions.failureGate", true),
                    memoryGateThreshold = fraction("CLARITY_MEMORY_GATE_THRESHOLD", "agent.decisions.memoryGateThreshold", 0.6),
                    briefGateThreshold = fraction("CLARITY_BRIEF_GATE_THRESHOLD", "agent.decisions.briefGateThreshold", 0.6),
                ),
                browser = BrowserPilotConfig(
                    jevModel = string("CLARITY_JEV_MODEL", "agent.browser.jevModel") ?: DEFAULT_JEV_MODEL,
                    visibleThreshold = fraction("CLARITY_JEV_VISIBLE_THRESHOLD", "agent.browser.visibleThreshold", 0.7),
                    approveThreshold = fraction("CLARITY_JEV_APPROVE_THRESHOLD", "agent.browser.approveThreshold", 0.9),
                    resendThreshold = fraction("CLARITY_JEV_RESEND_THRESHOLD", "agent.browser.resendThreshold", 0.3),
                    jevMayApprove = bool("CLARITY_JEV_MAY_APPROVE", "agent.browser.jevMayApprove", false),
                    textGrounding = bool("CLARITY_BROWSER_TEXT_GROUNDING", "agent.browser.textGrounding", false),
                    maxActions = int("CLARITY_BROWSER_MAX_ACTIONS", "agent.browser.maxActions", 25),
                    frameRetention = duration("CLARITY_BROWSER_FRAME_RETENTION", "agent.browser.frameRetention", Duration.parse("30d")),
                    leaseWait = duration("CLARITY_BROWSER_LEASE_WAIT", "agent.browser.leaseWait", Duration.parse("30m")),
                    leaseIdle = duration("CLARITY_BROWSER_LEASE_IDLE", "agent.browser.leaseIdle", Duration.parse("10m")),
                    leaseStale = duration("CLARITY_BROWSER_LEASE_STALE", "agent.browser.leaseStale", Duration.parse("2m")),
                ),
                webSearch = webSearch(),
            ),
            reports = reports(),
        )
    }
}

private const val MB = 1024L * 1024L

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
