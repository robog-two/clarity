package net.robog.clarity.backend.config

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.config.MapApplicationConfig
import kotlin.io.path.Path
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
        load(mapOf("CLARITY_MCP_SERVERS" to "browser=http://b/mcp")).getOrNull()!!.agent.mcpServers shouldBe
            listOf(McpServerConfig("browser", "http://b/mcp"))
    }
})
