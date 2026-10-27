package net.robog.clarity.backend.agent

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.StopReason
import net.robog.clarity.harness.Usage
import net.robog.clarity.shared.feed.ClientInputId
import org.jetbrains.exposed.v1.jdbc.Database
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.testcontainers.postgresql.PostgreSQLContainer

/** Model settings changed at runtime, against real Postgres; the model is always a fake. */
@EnabledIf(ContainerRuntimeAvailable::class)
class LeadModelSwitchTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }

    test("the lead switches model only at a turn boundary, and keeps the earlier model's reasoning in the history") {
        val r = LeadRig(database).also { it.reset() }
        val thinking = net.robog.clarity.harness.OpenRouterModelGateway.reasoningBlock(LEAD_MODEL, listOf(buildJsonObject { put("type", "reasoning.text"); put("text", "hmm") }))
        r.model.handler = { request ->
            if (r.model.requests.size == 1) {
                // Mid-turn: the person picks Sonnet. This turn must finish on Opus.
                r.modelSettings.set(AgentRole.LEAD, RoleOverride(model = HELPER_MODEL, effort = "high"))
                ModelResponse(listOf(thinking, Block.ToolUse("toolu_1", "no_such_tool", buildJsonObject { })), StopReason.TOOL_USE, Usage(100, 10))
            } else {
                end("done")
            }
        }
        r.inbox.enqueue(InboxItem.UserInput(ClientInputId("u1"), "first"))
        r.lead.start()
        r.lead.step() shouldBe true
        // "done" never reached the person, so the turn ends with one reply nudge, still on Opus.
        r.model.requests.map { it.model } shouldContainExactly listOf(LEAD_MODEL, LEAD_MODEL, LEAD_MODEL)
        r.model.requests.map { it.effort }.toSet() shouldBe setOf("medium")

        r.inbox.enqueue(InboxItem.UserInput(ClientInputId("u2"), "second"))
        r.lead.step() shouldBe true
        val next = r.model.requests.last()
        next.model shouldBe HELPER_MODEL
        next.effort shouldBe "high"
        // The Opus reasoning is still in the history (the gateway replays it to Opus only).
        next.messages.any { m -> thinking in m.content } shouldBe true
    }

    test("overrides survive in model_role_override, and each model call records its role") {
        val store = DbRoleOverrideStore(database, Clock.System)
        store.clear(AgentRole.GUARD)
        store.put(AgentRole.GUARD, RoleOverride(model = "a/b", effort = "low", maxTokens = 3000, cacheTtl = "5m", compaction = false, compactAtTokens = 60_000))
        store.all()[AgentRole.GUARD]!!.override shouldBe RoleOverride(model = "a/b", effort = "low", maxTokens = 3000, cacheTtl = "5m", compaction = false, compactAtTokens = 60_000)
        store.put(AgentRole.GUARD, RoleOverride(model = "anthropic/claude-sonnet-5.5"))
        store.all()[AgentRole.GUARD]!!.override shouldBe RoleOverride(model = "anthropic/claude-sonnet-5.5")
        store.clear(AgentRole.GUARD)
        store.all()[AgentRole.GUARD] shouldBe null

        val recorder = DbUsageRecorder(database, Clock.System)
        RecordingGateway(FakeModel(), recorder, AgentRole.TRIAGE)
            .generate(net.robog.clarity.harness.ModelRequest("anthropic/claude-haiku-5.5", "s", listOf(net.robog.clarity.harness.Message.user("hi")), emptyList(), 10, null))
        database.tx { ModelCalls.selectAll().map { it[ModelCalls.model] to it[ModelCalls.role] } }.last() shouldBe ("anthropic/claude-haiku-5.5" to "triage")
    }

    test("V18: overrides saved for the Anthropic API move to OpenRouter ids, nothing else changes, the provider column goes") {
        // CREATE DATABASE cannot run in a transaction: a plain auto-commit connection.
        java.sql.DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { it.createStatement().execute("CREATE DATABASE v18_check") }
        val url = postgres.jdbcUrl.replace(Regex("/[^/?]+(\\?|$)"), "/v18_check$1")
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(url, postgres.username, Secret(postgres.password), maxPoolSize = 2))
        try {
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("16").load().migrate()
            val old = Database.connect(dataSource)
            old.tx {
                exec(
                    "INSERT INTO model_role_override (role, provider, model, effort, max_tokens) VALUES " +
                        "('lead', 'anthropic', 'claude-opus-5-5', 'high', 64000), " +
                        "('helper', 'anthropic', 'claude-sonnet-4-5-20250929', NULL, NULL), " +
                        "('guard', 'openrouter', 'deepseek/deepseek-v4.1-flash-1017', 'low', NULL), " +
                        "('triage', NULL, 'my-local-model', NULL, 2000)",
                )
            }
            DatabaseFactory.migrate(dataSource)
            val after = DbRoleOverrideStore(Database.connect(dataSource), Clock.System).all().mapValues { it.value.override }
            after[AgentRole.LEAD] shouldBe RoleOverride(model = "anthropic/claude-opus-5.5", effort = "high", maxTokens = 64_000)
            after[AgentRole.HELPER] shouldBe RoleOverride(model = "anthropic/claude-sonnet-4.5")
            after[AgentRole.GUARD] shouldBe RoleOverride(model = "deepseek/deepseek-v4.1-flash-1017", effort = "low")
            // Kept as stored; the role falls back to its default model at runtime.
            after[AgentRole.TRIAGE] shouldBe RoleOverride(model = "my-local-model", maxTokens = 2_000)
            RoleOverride(model = "my-local-model").applyTo(TEST_ROLES.triage).model shouldBe TEST_ROLES.triage.model
            Database.connect(dataSource).tx {
                exec("SELECT count(*) FROM information_schema.columns WHERE table_name = 'model_role_override' AND column_name = 'provider'") { rs ->
                    rs.next()
                    rs.getInt(1) shouldBe 0
                }
            }
        } finally {
            dataSource.close()
        }
    }
})
