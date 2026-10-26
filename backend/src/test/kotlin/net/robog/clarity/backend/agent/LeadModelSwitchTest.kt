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
import net.robog.clarity.backend.config.ModelProvider
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

    test("the lead switches model only at a turn boundary, and replays the earlier model's thinking unchanged") {
        val r = LeadRig(database).also { it.reset() }
        val thinking = Block.Raw(buildJsonObject { put("type", "thinking"); put("thinking", ""); put("signature", "sig-opus") })
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
        r.model.requests.map { it.model } shouldContainExactly listOf(LEAD_MODEL, LEAD_MODEL)
        r.model.requests.map { it.effort }.toSet() shouldBe setOf("medium")

        r.inbox.enqueue(InboxItem.UserInput(ClientInputId("u2"), "second"))
        r.lead.step() shouldBe true
        val next = r.model.requests.last()
        next.model shouldBe HELPER_MODEL
        next.effort shouldBe "high"
        // The Opus thinking block is still in the history: the API drops what Sonnet can't read.
        next.messages.any { m -> thinking in m.content } shouldBe true
    }

    test("overrides survive in model_role_override, and each model call records its role") {
        val store = DbRoleOverrideStore(database, Clock.System)
        store.clear(AgentRole.GUARD)
        store.put(AgentRole.GUARD, RoleOverride(provider = ModelProvider.OPENROUTER, model = "a/b", effort = "low", maxTokens = 3000, cacheTtl = "5m", compaction = false, compactAtTokens = 60_000))
        store.put(AgentRole.GUARD, RoleOverride(model = "claude-sonnet-5-5"))
        store.all()[AgentRole.GUARD]!!.override shouldBe RoleOverride(model = "claude-sonnet-5-5")
        store.clear(AgentRole.GUARD)
        store.all()[AgentRole.GUARD] shouldBe null

        val recorder = DbUsageRecorder(database, Clock.System)
        RecordingGateway(FakeModel(), recorder, AgentRole.TRIAGE)
            .generate(net.robog.clarity.harness.ModelRequest("claude-haiku-5-5", "s", listOf(net.robog.clarity.harness.Message.user("hi")), emptyList(), 10, null))
        database.tx { ModelCalls.selectAll().map { it[ModelCalls.model] to it[ModelCalls.role] } }.last() shouldBe ("claude-haiku-5-5" to "triage")
    }
})
