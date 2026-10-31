package net.robog.clarity.backend.config

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.config.MapApplicationConfig
import kotlin.io.path.Path
import net.robog.clarity.harness.OpenRouterRouting
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val yaml = MapApplicationConfig(
    "clarity.http.port" to "8080",
    "clarity.database.url" to "jdbc:postgresql://yaml/db",
    "clarity.database.password" to "yaml-pw",
    "clarity.agent.reviewInterval" to "2h",
)

/** With an OpenRouter key unless [env] sets the key itself or [withKey] is false: the backend does not start without one. */
private fun load(env: Map<String, String> = emptyMap(), files: Map<String, String> = emptyMap(), withKey: Boolean = true) =
    AppConfig.load(
        if (withKey && env.keys.none { it.startsWith("OPENROUTER_API_KEY") }) env + ("OPENROUTER_API_KEY" to "sk-or-test") else env,
        yaml,
    ) { path -> files[path.toString()]?.let { Either.Right(it) } ?: Either.Left("missing") }

class ConfigLoadingTest : FunSpec({
    test("defaults come from yaml and built-ins") {
        val c = load().getOrNull()!!
        c.http.port shouldBe 8080
        c.database.url shouldBe "jdbc:postgresql://yaml/db"
        c.database.password?.value shouldBe "yaml-pw"
        c.enrollment.mode shouldBe EnrollmentMode.TOFU
        c.connectors.autoGrant shouldBe false
        c.connectors.secretsDir shouldBe Path("/run/secrets")
        c.agent.reviewInterval shouldBe 2.hours
        c.agent.openRouterApiKey shouldBe "sk-or-test"
        c.agent.mcpServers shouldBe emptyList()
    }

    test("helpers have their own step limit, 60 by default") {
        val c = load().getOrNull()!!.agent
        c.maxTurns shouldBe 30
        c.helperMaxTurns shouldBe 60
        load(mapOf("CLARITY_AGENT_HELPER_MAX_TURNS" to "80")).getOrNull()!!.agent.helperMaxTurns shouldBe 80
        load(mapOf("CLARITY_AGENT_HELPER_MAX_TURNS" to "0")).shouldBeInstanceOf<Either.Left<ConfigError.Invalid>>()
    }

    test("hard spend stops, the lead's turn cap, and the helper limits: defaults and overrides") {
        val c = load().getOrNull()!!.agent
        c.spend shouldBe SpendLimits()
        // No global stop unless one is set; per-run caps catch runaways.
        c.spend.weeklyUsd shouldBe null
        c.spend.dailyUsd shouldBe emptyMap()
        c.spend.any shouldBe false
        c.leadTurnMaxCostUsd shouldBe 0.5
        c.helperMaxPerDay shouldBe 20
        c.helperMaxResumes shouldBe 3
        val custom = load(
            mapOf(
                "CLARITY_SPEND_WEEKLY_MAX" to "7",
                "CLARITY_SPEND_DAILY_MAX_HELPER" to "1.5",
                "CLARITY_LEAD_TURN_MAX_COST" to "0.25",
                "CLARITY_HELPER_MAX_PER_DAY" to "5",
                "CLARITY_HELPER_MAX_RESUMES" to "0",
            ),
        ).getOrNull()!!.agent
        custom.spend.weeklyUsd shouldBe 7.0
        custom.spend.dailyUsd[AgentRole.HELPER] shouldBe 1.5
        custom.spend.dailyUsd[AgentRole.LEAD] shouldBe null
        custom.spend.any shouldBe true
        custom.leadTurnMaxCostUsd shouldBe 0.25
        custom.helperMaxPerDay shouldBe 5
        custom.helperMaxResumes shouldBe 0
        load(mapOf("CLARITY_SPEND_WEEKLY_MAX" to "0")).shouldBeInstanceOf<Either.Left<ConfigError.Invalid>>()
    }

    test("a helper run may spend $0.15 by default; the cap is a dollar amount above 0") {
        load().getOrNull()!!.agent.helperMaxCostUsd shouldBe 0.15
        load(mapOf("CLARITY_HELPER_MAX_COST" to "0.5")).getOrNull()!!.agent.helperMaxCostUsd shouldBe 0.5
        load(mapOf("CLARITY_HELPER_MAX_COST" to "$1")).getOrNull()!!.agent.helperMaxCostUsd shouldBe 1.0
        for (bad in listOf("0", "-1", "lots")) {
            load(mapOf("CLARITY_HELPER_MAX_COST" to bad)).shouldBeInstanceOf<Either.Left<ConfigError.Invalid>>()
        }
    }

    test("agent roles default to the MiMo lead and DeepSeek Flash helper, guard and triage, all on OpenRouter") {
        val roles = load().getOrNull()!!.agent.roles
        roles[AgentRole.LEAD].model shouldBe "xiaomi/mimo-v2.6-pro"
        roles[AgentRole.LEAD].compaction shouldBe true
        roles[AgentRole.LEAD].cacheTtl shouldBe "1h"
        roles[AgentRole.LEAD].compactAtTokens shouldBe 60_000
        roles[AgentRole.HELPER].model shouldBe "deepseek/deepseek-v4.1-flash-1017"
        roles[AgentRole.GUARD].model shouldBe "deepseek/deepseek-v4.1-flash-1017"
        roles[AgentRole.TRIAGE].model shouldBe "deepseek/deepseek-v4.1-flash-1017"
        roles[AgentRole.TRIAGE].effort shouldBe "low"
    }

    test("the backend needs an OpenRouter key: a clear error without one, from the variable or its file") {
        val missing = load(withKey = false).leftOrNull().shouldBeInstanceOf<ConfigError.Missing>()
        missing.key shouldBe "OPENROUTER_API_KEY"
        missing.message.contains("OPENROUTER_API_KEY_FILE") shouldBe true
        load(mapOf("OPENROUTER_API_KEY" to " ")).leftOrNull().shouldBeInstanceOf<ConfigError.Missing>()
        load(mapOf("OPENROUTER_API_KEY_FILE" to "/run/secrets/openrouter"), files = mapOf("/run/secrets/openrouter" to "\n"))
            .leftOrNull().shouldBeInstanceOf<ConfigError.Missing>()
        load(mapOf("OPENROUTER_API_KEY_FILE" to "/run/secrets/openrouter"), files = mapOf("/run/secrets/openrouter" to "sk-or-file\n"))
            .getOrNull()!!.agent.openRouterApiKey shouldBe "sk-or-file"
        // An Anthropic key alone does nothing any more.
        load(mapOf("ANTHROPIC_API_KEY" to "sk-ant"), withKey = false).leftOrNull().shouldBeInstanceOf<ConfigError.Missing>()
    }

    test("role env overrides win, the legacy single model sets only the lead default") {
        val roles = load(
            mapOf(
                "CLARITY_AGENT_MODEL" to "x/legacy", "CLARITY_AGENT_HELPER_MODEL" to "x/h", "CLARITY_AGENT_HELPER_EFFORT" to "high",
                "CLARITY_AGENT_GUARD_CACHE_TTL" to "1h", "CLARITY_AGENT_TRIAGE_COMPACTION" to "true",
            ),
        ).getOrNull()!!.agent.roles
        roles.lead.model shouldBe "x/legacy"
        roles.helper.model shouldBe "x/h"
        roles.helper.effort shouldBe "high"
        roles.guard.model shouldBe "deepseek/deepseek-v4.1-flash-1017"
        roles.guard.cacheTtl shouldBe "1h"
        roles.triage.compaction shouldBe true
        load(mapOf("CLARITY_AGENT_LEAD_MODEL" to "x/lead", "CLARITY_AGENT_MODEL" to "x/legacy")).getOrNull()!!.agent.model shouldBe "x/lead"
        load(mapOf("CLARITY_AGENT_GUARD_CACHE_TTL" to "2h")).shouldBeInstanceOf<Either.Left<*>>()
        load(mapOf("CLARITY_AGENT_LEAD_COMPACT_AT_TOKENS" to "80000")).getOrNull()!!.agent.roles.lead.compactAtTokens shouldBe 80_000
        load(mapOf("CLARITY_AGENT_LEAD_COMPACT_AT_TOKENS" to "20000")).shouldBeInstanceOf<Either.Left<*>>()
    }

    test("models are OpenRouter ids; a leftover provider setting may say openrouter, never anthropic") {
        val defaults = load().getOrNull()!!.agent
        defaults.openRouterBaseUrl shouldBe "https://openrouter.ai/api/v1"
        val c = load(
            mapOf(
                "CLARITY_AGENT_GUARD_PROVIDER" to "OpenRouter", "CLARITY_AGENT_GUARD_MODEL" to "anthropic/claude-haiku-5.5",
                "OPENROUTER_BASE_URL" to "http://fake/api/v1",
            ),
        ).getOrNull()!!.agent
        c.roles.guard.model shouldBe "anthropic/claude-haiku-5.5"
        c.openRouterBaseUrl shouldBe "http://fake/api/v1"

        val anthropic = load(mapOf("CLARITY_AGENT_LEAD_PROVIDER" to "anthropic")).leftOrNull().shouldBeInstanceOf<ConfigError.Invalid>()
        anthropic.key shouldBe "CLARITY_AGENT_LEAD_PROVIDER"
        anthropic.message.contains("OpenRouter") shouldBe true
        val withYaml = MapApplicationConfig("clarity.agent.reviewInterval" to "2h", "clarity.agent.roles.triage.provider" to "anthropic")
        AppConfig.load(mapOf("OPENROUTER_API_KEY" to "sk-or"), withYaml).leftOrNull().shouldBeInstanceOf<ConfigError.Invalid>()
        // An Anthropic API model name is refused with a pointer to the OpenRouter form.
        val name = load(mapOf("CLARITY_AGENT_HELPER_MODEL" to "claude-sonnet-5-5")).leftOrNull().shouldBeInstanceOf<ConfigError.Invalid>()
        name.key shouldBe "CLARITY_AGENT_HELPER_MODEL"
        name.message.contains("anthropic/claude") shouldBe true
        load(mapOf("CLARITY_AGENT_MODEL" to "claude-opus-5-5")).leftOrNull().shouldBeInstanceOf<ConfigError.Invalid>()
    }

    test("memory embeddings: on with qwen3-embedding-8b at 1024 values by default; env can switch them off or change them") {
        load().getOrNull()!!.agent.memory shouldBe MemoryConfig(true, "qwen/qwen3-embedding-8b", 1024, listOf("deepinfra", "nebius"))
        val m = load(
            mapOf("CLARITY_MEMORY_EMBEDDINGS" to "false", "CLARITY_MEMORY_EMBEDDINGS_MODEL" to "x/y", "CLARITY_MEMORY_EMBEDDINGS_DIMENSIONS" to "0"),
        ).getOrNull()!!.agent.memory
        m.embeddings shouldBe false
        m.embeddingsModel shouldBe "x/y"
        m.embeddingDimensions shouldBe 0
        load(mapOf("CLARITY_MEMORY_EMBEDDINGS_DIMENSIONS" to "-1")).shouldBeInstanceOf<Either.Left<*>>()
        // no client without an OpenRouter key, or when switched off
        net.robog.clarity.backend.agent.OpenRouterEmbeddingClientFactory(load().getOrNull()!!.agent.copy(openRouterApiKey = null)).create() shouldBe null
        net.robog.clarity.backend.agent.OpenRouterEmbeddingClientFactory(load(mapOf("OPENROUTER_API_KEY" to "sk-or")).getOrNull()!!.agent).create()
            .shouldBeInstanceOf<net.robog.clarity.backend.agent.OpenRouterEmbeddingClient>()
        net.robog.clarity.backend.agent.OpenRouterEmbeddingClientFactory(
            load(mapOf("OPENROUTER_API_KEY" to "sk-or", "CLARITY_MEMORY_EMBEDDINGS" to "false")).getOrNull()!!.agent,
        ).create() shouldBe null
    }

    test("decisions: Decision-1 gating the review at 0.3 with an 8h limit by default; blank means default; env changes them") {
        load().getOrNull()!!.agent.decisions shouldBe DecisionConfig(true, "microsoft/microsoft-decision-1", true, 0.3, kotlin.time.Duration.parse("8h"))
        load(mapOf("CLARITY_DECISION_MODEL" to "", "CLARITY_REVIEW_GATE_THRESHOLD" to "")).getOrNull()!!.agent.decisions shouldBe DecisionConfig()
        val d = load(
            mapOf(
                "CLARITY_DECISIONS" to "false", "CLARITY_DECISION_MODEL" to "typesafe/jev-1.14", "CLARITY_REVIEW_GATE" to "false",
                "CLARITY_REVIEW_GATE_THRESHOLD" to "0.5", "CLARITY_REVIEW_MAX_QUIET" to "6h",
            ),
        ).getOrNull()!!.agent.decisions
        d shouldBe DecisionConfig(false, "typesafe/jev-1.14", false, 0.5, kotlin.time.Duration.parse("6h"))
        load(mapOf("CLARITY_REVIEW_GATE_THRESHOLD" to "1.5")).leftOrNull().shouldBeInstanceOf<ConfigError.Invalid>().key shouldBe "CLARITY_REVIEW_GATE_THRESHOLD"
        // no client without an OpenRouter key, or when switched off
        net.robog.clarity.backend.agent.OpenRouterDecisionClientFactory(load().getOrNull()!!.agent.copy(openRouterApiKey = null)).create() shouldBe null
        net.robog.clarity.backend.agent.OpenRouterDecisionClientFactory(load(mapOf("OPENROUTER_API_KEY" to "sk-or")).getOrNull()!!.agent).create()
            .shouldBeInstanceOf<net.robog.clarity.backend.agent.OpenRouterDecisionClient>()
        net.robog.clarity.backend.agent.OpenRouterDecisionClientFactory(
            load(mapOf("OPENROUTER_API_KEY" to "sk-or", "CLARITY_DECISIONS" to "false")).getOrNull()!!.agent,
        ).create() shouldBe null
    }

    test("OpenRouter routing: zdr, fp8 or better and cheapest first by default; blank turns a filter off") {
        load().getOrNull()!!.agent.openRouterRouting shouldBe OpenRouterRouting(true, listOf("fp8", "bf16", "fp16"), "price")
        load(mapOf("OPENROUTER_ZDR" to "false", "OPENROUTER_QUANTIZATIONS" to " BF16, fp16 ,", "OPENROUTER_SORT" to "Latency"))
            .getOrNull()!!.agent.openRouterRouting shouldBe OpenRouterRouting(false, listOf("bf16", "fp16"), "latency")
        load(mapOf("OPENROUTER_QUANTIZATIONS" to "", "OPENROUTER_SORT" to " ")).getOrNull()!!.agent.openRouterRouting shouldBe
            OpenRouterRouting(true, emptyList(), null)
        load(mapOf("OPENROUTER_SORT" to "fastest")).leftOrNull().shouldBeInstanceOf<ConfigError.Invalid>()
        load(mapOf("OPENROUTER_ZDR" to "maybe")).leftOrNull().shouldBeInstanceOf<ConfigError.Invalid>()
    }

    test("environment overrides yaml") {
        val c = load(mapOf(
            "CLARITY_HTTP_PORT" to "9090", "CLARITY_DB_URL" to "jdbc:postgresql://env/db",
            "CLARITY_ENROLLMENT_MODE" to "TOKEN", "CLARITY_CONNECTORS_AUTO_GRANT" to "true",
            "CLARITY_REVIEW_INTERVAL" to "30m", "CLARITY_AGENT_MODEL" to "x/m", "CLARITY_AGENT_EFFORT" to "high",
            "CLARITY_DATA_DIR" to "/data", "CLARITY_SECRETS_DIR" to "/s",
        )).getOrNull()!!
        c.http.port shouldBe 9090
        c.database.url shouldBe "jdbc:postgresql://env/db"
        c.enrollment.mode shouldBe EnrollmentMode.TOKEN
        c.connectors.autoGrant shouldBe true
        c.agent.reviewInterval shouldBe 30.minutes
        c.agent.model shouldBe "x/m"
        c.agent.effort shouldBe "high"
        c.dataDir shouldBe Path("/data")
    }

    test("secrets prefer the *_FILE variable, trimmed, then the plain variable") {
        val files = mapOf("/run/secrets/db" to "file-pw\n", "/run/secrets/key" to " sk-ant \n")
        val c = load(
            mapOf(
                "CLARITY_DB_PASSWORD_FILE" to "/run/secrets/db", "CLARITY_DB_PASSWORD" to "plain",
                "OPENROUTER_API_KEY_FILE" to "/run/secrets/key",
                "CLARITY_ENROLLMENT_TOKEN" to "tok",
            ),
            files,
        ).getOrNull()!!
        c.database.password?.value shouldBe "file-pw"
        c.agent.openRouterApiKey shouldBe "sk-ant"
        c.enrollment.token?.value shouldBe "tok"
    }

    test("an unreadable *_FILE is a typed error, not a silent fallback") {
        load(mapOf("CLARITY_DB_PASSWORD_FILE" to "/nope")).leftOrNull().shouldBeInstanceOf<ConfigError.UnreadableFile>()
    }

    test("invalid values are reported with their key") {
        load(mapOf("CLARITY_HTTP_PORT" to "abc")).leftOrNull().shouldBeInstanceOf<ConfigError.Invalid>().key shouldBe "CLARITY_HTTP_PORT"
        load(mapOf("CLARITY_ENROLLMENT_MODE" to "open")).leftOrNull().shouldBeInstanceOf<ConfigError.Invalid>()
        load(mapOf("CLARITY_REVIEW_INTERVAL" to "-5m")).leftOrNull().shouldBeInstanceOf<ConfigError.Invalid>()
        load(mapOf("CLARITY_CONNECTORS_AUTO_GRANT" to "maybe")).leftOrNull().shouldBeInstanceOf<ConfigError.Invalid>()
    }

    test("secrets never print") {
        Secret("hunter2").toString() shouldBe "Secret(***)"
    }

    test("MCP servers parse name=url pairs") {
        parseMcpServers("browser=http://browser:8931/mcp, other=http://o/mcp?a=b ,bad,=x,y=") shouldBe listOf(
            McpServerConfig("browser", "http://browser:8931/mcp"),
            McpServerConfig("other", "http://o/mcp?a=b"),
        )
        parseMcpServers("") shouldBe emptyList()
        parseMcpServers("sandbox=http://sandbox:8940/mcp;keyFile=/run/secrets/sandbox_key,browser=http://b/mcp") shouldBe listOf(
            McpServerConfig("sandbox", "http://sandbox:8940/mcp", "/run/secrets/sandbox_key"),
            McpServerConfig("browser", "http://b/mcp"),
        )
        load(mapOf("CLARITY_MCP_SERVERS" to "browser=http://b/mcp")).getOrNull()!!.agent.mcpServers shouldBe
            listOf(McpServerConfig("browser", "http://b/mcp"))
    }

    test("web search: on, parallel in fast mode, 10 searches a helper run; firecrawl is refused (zero data retention)") {
        load().getOrNull()!!.agent.webSearch shouldBe WebSearchConfig()
        val custom = load(
            mapOf(
                "CLARITY_WEB_SEARCH_ENGINE" to "exa",
                "CLARITY_WEB_SEARCH_MODE" to "",
                "CLARITY_WEB_SEARCH_MAX_SEARCHES" to "4",
                "CLARITY_WEB_SEARCH_EXCLUDED_DOMAINS" to "Pinterest.com, quora.com",
            ),
        ).getOrNull()!!.agent.webSearch
        custom.engine shouldBe "exa"
        custom.mode shouldBe null
        custom.maxSearchesPerRun shouldBe 4
        custom.excludedDomains shouldBe listOf("pinterest.com", "quora.com")
        load(mapOf("CLARITY_WEB_SEARCH" to "false")).getOrNull()!!.agent.webSearch.tool() shouldBe null
        load(mapOf("CLARITY_WEB_SEARCH_ENGINE" to "firecrawl")).shouldBeInstanceOf<Either.Left<ConfigError.Invalid>>()
        load(mapOf("CLARITY_WEB_SEARCH_MAX_RESULTS" to "40")).shouldBeInstanceOf<Either.Left<ConfigError.Invalid>>()
    }

    test("reports: the render service and a 500 MB cache by default") {
        val reports = load().getOrNull()!!.reports
        reports.renderUrl shouldBe "http://render:8950"
        reports.renderKeyFile shouldBe "/run/secrets/render_key"
        reports.cacheMaxBytes shouldBe 500L * 1024 * 1024
        val custom = load(mapOf("CLARITY_REPORTS_CACHE_MAX_MB" to "50", "CLARITY_RENDER_URL" to "http://127.0.0.1:18950", "CLARITY_RENDER_TIMEOUT" to "2m")).getOrNull()!!.reports
        custom.cacheMaxBytes shouldBe 50L * 1024 * 1024
        custom.renderUrl shouldBe "http://127.0.0.1:18950"
        custom.renderTimeout shouldBe 2.minutes
    }
})
