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

private fun load(env: Map<String, String> = emptyMap(), files: Map<String, String> = emptyMap()) =
    AppConfig.load(env, yaml) { path -> files[path.toString()]?.let { Either.Right(it) } ?: Either.Left("missing") }

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
        c.agent.anthropicApiKey shouldBe null
        c.agent.mcpServers shouldBe emptyList()
    }

    test("helpers have their own step limit, 60 by default") {
        val c = load().getOrNull()!!.agent
        c.maxTurns shouldBe 30
        c.helperMaxTurns shouldBe 60
        load(mapOf("CLARITY_AGENT_HELPER_MAX_TURNS" to "80")).getOrNull()!!.agent.helperMaxTurns shouldBe 80
        load(mapOf("CLARITY_AGENT_HELPER_MAX_TURNS" to "0")).shouldBeInstanceOf<Either.Left<ConfigError.Invalid>>()
    }

    test("agent roles default to opus lead, sonnet helper, haiku guard and triage") {
        val roles = load().getOrNull()!!.agent.roles
        roles[AgentRole.LEAD].model shouldBe "claude-opus-5-5"
        roles[AgentRole.LEAD].compaction shouldBe true
        roles[AgentRole.LEAD].cacheTtl shouldBe "1h"
        roles[AgentRole.LEAD].compactAtTokens shouldBe 60_000
        roles[AgentRole.HELPER].model shouldBe "claude-sonnet-5-5"
        roles[AgentRole.GUARD].model shouldBe "claude-haiku-5-5"
        roles[AgentRole.TRIAGE].effort shouldBe "low"
    }

    test("role env overrides win, the legacy single model sets only the lead default") {
        val roles = load(
            mapOf(
                "CLARITY_AGENT_MODEL" to "legacy", "CLARITY_AGENT_HELPER_MODEL" to "h", "CLARITY_AGENT_HELPER_EFFORT" to "high",
                "CLARITY_AGENT_GUARD_CACHE_TTL" to "1h", "CLARITY_AGENT_TRIAGE_COMPACTION" to "true",
            ),
        ).getOrNull()!!.agent.roles
        roles.lead.model shouldBe "legacy"
        roles.helper.model shouldBe "h"
        roles.helper.effort shouldBe "high"
        roles.guard.model shouldBe "claude-haiku-5-5"
        roles.guard.cacheTtl shouldBe "1h"
        roles.triage.compaction shouldBe true
        load(mapOf("CLARITY_AGENT_LEAD_MODEL" to "lead", "CLARITY_AGENT_MODEL" to "legacy")).getOrNull()!!.agent.model shouldBe "lead"
        load(mapOf("CLARITY_AGENT_GUARD_CACHE_TTL" to "2h")).shouldBeInstanceOf<Either.Left<*>>()
        load(mapOf("CLARITY_AGENT_LEAD_COMPACT_AT_TOKENS" to "80000")).getOrNull()!!.agent.roles.lead.compactAtTokens shouldBe 80_000
        load(mapOf("CLARITY_AGENT_LEAD_COMPACT_AT_TOKENS" to "20000")).shouldBeInstanceOf<Either.Left<*>>()
    }

    test("roles run on Anthropic unless a provider is set; OpenRouter needs its key") {
        val defaults = load().getOrNull()!!.agent
        AgentRole.entries.map { defaults.roles[it].provider }.toSet() shouldBe setOf(ModelProvider.ANTHROPIC)
        defaults.openRouterApiKey shouldBe null
        defaults.openRouterBaseUrl shouldBe "https://openrouter.ai/api/v1"
        defaults.providersInUse shouldBe setOf(ModelProvider.ANTHROPIC)

        val missing = load(mapOf("CLARITY_AGENT_GUARD_PROVIDER" to "openrouter")).leftOrNull().shouldBeInstanceOf<ConfigError.Invalid>()
        missing.key shouldBe "CLARITY_AGENT_GUARD_PROVIDER"
        missing.message.contains("OPENROUTER_API_KEY") shouldBe true

        val c = load(
            mapOf(
                "CLARITY_AGENT_GUARD_PROVIDER" to "OpenRouter", "CLARITY_AGENT_GUARD_MODEL" to "anthropic/claude-haiku-5.5",
                "OPENROUTER_API_KEY_FILE" to "/run/secrets/openrouter", "OPENROUTER_BASE_URL" to "http://fake/api/v1",
            ),
            files = mapOf("/run/secrets/openrouter" to "sk-or-file\n"),
        ).getOrNull()!!.agent
        c.roles.guard.provider shouldBe ModelProvider.OPENROUTER
        c.roles.guard.model shouldBe "anthropic/claude-haiku-5.5"
        c.roles.lead.provider shouldBe ModelProvider.ANTHROPIC
        c.openRouterApiKey shouldBe "sk-or-file"
        c.openRouterBaseUrl shouldBe "http://fake/api/v1"
        c.providersInUse shouldBe setOf(ModelProvider.ANTHROPIC, ModelProvider.OPENROUTER)

        // A key alone changes nothing; yaml sets a provider too; nonsense is refused.
        load(mapOf("OPENROUTER_API_KEY" to "sk-or")).getOrNull()!!.agent.providersInUse shouldBe setOf(ModelProvider.ANTHROPIC)
        val withYaml = MapApplicationConfig("clarity.agent.reviewInterval" to "2h", "clarity.agent.roles.triage.provider" to "openrouter")
        AppConfig.load(mapOf("OPENROUTER_API_KEY" to "sk-or"), withYaml).getOrNull()!!.agent.roles.triage.provider shouldBe ModelProvider.OPENROUTER
        load(mapOf("CLARITY_AGENT_TRIAGE_PROVIDER" to "openai")).leftOrNull().shouldBeInstanceOf<ConfigError.Invalid>()
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
        net.robog.clarity.backend.agent.OpenRouterEmbeddingClientFactory(load().getOrNull()!!.agent).create() shouldBe null
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
        net.robog.clarity.backend.agent.OpenRouterDecisionClientFactory(load().getOrNull()!!.agent).create() shouldBe null
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
            "CLARITY_REVIEW_INTERVAL" to "30m", "CLARITY_AGENT_MODEL" to "m", "CLARITY_AGENT_EFFORT" to "high",
            "CLARITY_DATA_DIR" to "/data", "CLARITY_SECRETS_DIR" to "/s",
        )).getOrNull()!!
        c.http.port shouldBe 9090
        c.database.url shouldBe "jdbc:postgresql://env/db"
        c.enrollment.mode shouldBe EnrollmentMode.TOKEN
        c.connectors.autoGrant shouldBe true
        c.agent.reviewInterval shouldBe 30.minutes
        c.agent.model shouldBe "m"
        c.agent.effort shouldBe "high"
        c.dataDir shouldBe Path("/data")
    }

    test("secrets prefer the *_FILE variable, trimmed, then the plain variable") {
        val files = mapOf("/run/secrets/db" to "file-pw\n", "/run/secrets/key" to " sk-ant \n")
        val c = load(
            mapOf(
                "CLARITY_DB_PASSWORD_FILE" to "/run/secrets/db", "CLARITY_DB_PASSWORD" to "plain",
                "ANTHROPIC_API_KEY_FILE" to "/run/secrets/key",
                "CLARITY_ENROLLMENT_TOKEN" to "tok",
            ),
            files,
        ).getOrNull()!!
        c.database.password?.value shouldBe "file-pw"
        c.agent.anthropicApiKey shouldBe "sk-ant"
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
})
