package net.robog.clarity.backend.agent

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.backend.config.AgentRoles
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.ModelProvider
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.harness.AnthropicModelGateway
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.ModelRequest
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.OpenRouterModelGateway
import net.robog.clarity.harness.Role
import net.robog.clarity.harness.SUMMARY_MARKER
import net.robog.clarity.harness.StopReason
import net.robog.clarity.harness.Usage
import net.robog.clarity.shared.feed.ClientInputId
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

private const val OR_LEAD = "anthropic/claude-opus-5.5"

private fun text(m: Message) = m.content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }

/** A summarizing call (client-side compaction), told apart from a turn by its last message. */
private fun ModelRequest.isSummaryCall() = messages.lastOrNull()?.let(::text) == CLIENT_COMPACTION_REQUEST

private fun orMessages(request: ModelRequest) = OpenRouterModelGateway.buildBody(request)["messages"]!!.jsonArray.map { it.jsonObject }

private fun orEffort(request: ModelRequest) = OpenRouterModelGateway.buildBody(request)["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content

/** The lead on OpenRouter against real Postgres: client-side compaction, provider switches, effort. The model is always a fake. */
@EnabledIf(ContainerRuntimeAvailable::class)
class LeadOnOpenRouterTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }

    val onOpenRouter = AgentRoles().let { it.copy(lead = it.lead.copy(provider = ModelProvider.OPENROUTER, model = OR_LEAD, compactAtTokens = 60_000)) }

    suspend fun rig(roles: AgentRoles = onOpenRouter, effort: EffortOverride? = EffortOverride { null }) =
        LeadRig(database, roles = roles, openRouterKey = "sk-or", effortOverride = effort).also { it.reset() }

    fun user(text: String) = InboxItem.UserInput(ClientInputId(text), text)

    test("client compaction: once the last prompt reaches compactAtTokens, after the turn, one summary appended to the journal") {
        val r = rig()
        val threadId = r.threads.ensure().id
        var promptTokens = 1_000L
        var beforeSummary: List<Message> = emptyList()
        r.model.handler = { request ->
            when {
                request.isSummaryCall() -> {
                    beforeSummary = r.journal.load(threadId)
                    end("They like tea. Nothing is open.")
                }
                // Mid-turn the prompt is over the threshold already: no compaction until the turn is done.
                request.messages.last().content.none { it is Block.Result } && request.messages.any { text(it).contains("a long one") } ->
                    ModelResponse(listOf(Block.ToolUse("call_1", "no_such_tool", JsonObject(emptyMap()))), StopReason.TOOL_USE, Usage(promptTokens, 10))
                else -> ModelResponse(listOf(Block.Text("ok")), StopReason.END_TURN, Usage(promptTokens, 10))
            }
        }
        r.lead.start()

        // Under the threshold: no compaction.
        r.inbox.enqueue(user("first"))
        r.lead.step() shouldBe true
        r.model.requests.none { it.isSummaryCall() } shouldBe true

        // Over it: the turn runs to its end (two calls), then the one summarizing call.
        promptTokens = 61_000
        r.inbox.enqueue(user("a long one"))
        r.lead.step() shouldBe true
        r.model.requests.map { it.isSummaryCall() } shouldContainExactly listOf(false, false, false, true)
        val summaryCall = r.model.requests.last()
        summaryCall.model shouldBe OR_LEAD
        summaryCall.cache shouldBe null
        summaryCall.compaction shouldBe null
        summaryCall.system.none { it.cacheBreakpoint } shouldBe true
        summaryCall.tools.isNotEmpty() shouldBe true

        // Append-only: everything journaled before stays as it was; the summary is one new user message.
        val journal = r.journal.load(threadId)
        journal.dropLast(1) shouldContainExactly beforeSummary
        journal.last() shouldBe Message.summary("They like tea. Nothing is open.")
        journal.last().isSummary shouldBe true
        (r.threads.ensure().compactedAt != null) shouldBe true

        // A summary at the end is not an open turn: a restart does not run the model.
        val calls = r.model.requests.size
        r.lead.start()
        r.model.requests.size shouldBe calls

        // The next turn starts from the summary; nothing before it is sent, and it does not compact again.
        promptTokens = 2_000
        r.inbox.enqueue(user("next"))
        r.lead.step() shouldBe true
        val next = r.model.requests.last()
        next.isSummaryCall() shouldBe false
        next.messages.first().isSummary shouldBe true
        next.messages.joinToString { text(it) } shouldNotContain "first"
        orMessages(next)[1]["content"]!!.jsonPrimitive.content shouldContain SUMMARY_MARKER
        r.model.requests.count { it.isSummaryCall() } shouldBe 1
    }

    test("Anthropic to OpenRouter: the server compaction summary reaches the OpenRouter model") {
        val r = rig(roles = AgentRoles())
        val compaction = Block.Raw(buildJsonObject { put("type", "compaction"); put("content", "They like tea."); put("signature", "c-sig") })
        r.model.handler = { request ->
            if (request.model == LEAD_MODEL) {
                ModelResponse(listOf(compaction, Block.Text("ok")), StopReason.END_TURN, Usage(100, 10, cacheReadInputTokens = 50))
            } else {
                end("tea it is")
            }
        }
        r.lead.start()
        r.inbox.enqueue(user("before the compaction"))
        r.lead.step() shouldBe true
        r.model.requests.single().compaction!!.triggerInputTokens shouldBe 60_000

        r.modelSettings.set(AgentRole.LEAD, RoleOverride(provider = ModelProvider.OPENROUTER, model = OR_LEAD))
        r.inbox.enqueue(user("what do I drink?"))
        r.lead.step() shouldBe true
        val request = r.model.requests.last()
        request.model shouldBe OR_LEAD
        request.compaction shouldBe null
        request.cache shouldBe null
        val wire = orMessages(request)
        wire.map { it["role"]!!.jsonPrimitive.content }.take(2) shouldContainExactly listOf("system", "user")
        wire[1]["content"]!!.jsonPrimitive.content shouldBe "$SUMMARY_MARKER\nThey like tea."
        wire.toString() shouldNotContain "before the compaction"
        wire.toString() shouldContain "what do I drink?"
    }

    test("OpenRouter to Anthropic: the history replays without OpenRouter's reasoning, with cache and server compaction back") {
        val r = rig()
        val reasoning = OpenRouterModelGateway.reasoningBlock(OR_LEAD, listOf(buildJsonObject { put("type", "reasoning.text"); put("text", "pondering") }))
        r.model.handler = { request ->
            if (request.model == OR_LEAD) ModelResponse(listOf(reasoning, Block.Text("ok")), StopReason.END_TURN, Usage(100, 10)) else end("back")
        }
        r.lead.start()
        r.inbox.enqueue(user("on openrouter"))
        r.lead.step() shouldBe true
        // Replayed to the same OpenRouter model.
        r.inbox.enqueue(user("again"))
        r.lead.step() shouldBe true
        orMessages(r.model.requests.last()).any { it.containsKey("reasoning_details") } shouldBe true

        r.modelSettings.set(AgentRole.LEAD, RoleOverride(provider = ModelProvider.ANTHROPIC, model = LEAD_MODEL))
        r.inbox.enqueue(user("on anthropic"))
        r.lead.step() shouldBe true
        val request = r.model.requests.last()
        request.model shouldBe LEAD_MODEL
        (request.cache != null) shouldBe true
        (request.compaction != null) shouldBe true
        // The breakpoint sits where the previous request ended, as on any Anthropic thread.
        (request.messageBreakpoint != null) shouldBe true
        request.messages.any { m -> reasoning in m.content } shouldBe true
        val body = AnthropicModelGateway.bodyJson(AnthropicModelGateway.buildParams(request))
        body.toString() shouldNotContain "pondering"
        body.toString() shouldNotContain OpenRouterModelGateway.REASONING_TYPE
    }

    test("effort on OpenRouter is per request and follows the live-call override") {
        var live: String? = "low"
        val r = rig(effort = EffortOverride { live })
        r.lead.start()
        r.inbox.enqueue(user("during the call"))
        r.lead.step() shouldBe true
        orEffort(r.model.requests.last()) shouldBe "low"

        r.inbox.enqueue(user("still on the call"))
        r.lead.step() shouldBe true
        orEffort(r.model.requests.last()) shouldBe "low"

        live = null
        r.inbox.enqueue(user("after the call"))
        r.lead.step() shouldBe true
        orEffort(r.model.requests.last()) shouldBe "medium"
        val efforts = r.journal.load(r.threads.ensure().id).mapNotNull { it.effort }
        efforts shouldContainExactly listOf("low", "medium")
    }

    test("idle compaction never runs on OpenRouter") {
        val r = rig(roles = onOpenRouter.copy(lead = onOpenRouter.lead.copy(idleCompaction = true)))
        r.lead.start()
        r.inbox.enqueue(user("hi"))
        r.lead.step() shouldBe true
        r.lead.compactIfIdle() shouldBe false
    }

    test("measure: the lead's prompt on OpenRouter with every tool loaded") {
        val r = rig()
        r.lead.start()
        r.inbox.enqueue(user("hello"))
        r.lead.step() shouldBe true
        val request = r.model.requests.last()
        val body = OpenRouterModelGateway.buildBody(request)
        val tools = body["tools"]!!.jsonArray
        val toolChars = tools.toString().length
        val systemChars = request.systemText.length
        val total = body.toString().length
        println(
            "lead prompt on OpenRouter: ${tools.size} tools (${request.tools.size} loaded + ${request.deferredTools.size} deferred, all sent), " +
                "tools $toolChars chars (~${toolChars / 4} tokens), system $systemChars chars (~${systemChars / 4} tokens), " +
                "whole first request $total chars (~${total / 4} tokens)",
        )
        tools.size shouldBe request.tools.size + request.deferredTools.size
    }
})
