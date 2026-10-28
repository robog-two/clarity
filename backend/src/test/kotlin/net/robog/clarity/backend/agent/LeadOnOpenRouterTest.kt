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
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.CachePolicy
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

private const val OR_LEAD = LEAD_MODEL
private const val MIMO = "xiaomi/mimo-v2.6-pro"

private fun text(m: Message) = m.content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }

/** A summarizing call (client-side compaction), told apart from a turn by its last message. */
private fun ModelRequest.isSummaryCall() = messages.lastOrNull()?.let(::text) == CLIENT_COMPACTION_REQUEST

/** The tiny call that titles a compacted stretch. */
private fun ModelRequest.isTopicCall() = systemText == TOPIC_SYSTEM

private fun orMessages(request: ModelRequest) = OpenRouterModelGateway.buildBody(request)["messages"]!!.jsonArray.map { it.jsonObject }

private fun orEffort(request: ModelRequest) = OpenRouterModelGateway.buildBody(request)["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content

/** The lead on OpenRouter against real Postgres: client-side compaction, stored Anthropic history, model switches, effort. The model is always a fake. */
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

    val onOpenRouter = TEST_ROLES.let { it.copy(lead = it.lead.copy(model = OR_LEAD, compactAtTokens = 60_000)) }

    suspend fun rig(roles: AgentRoles = onOpenRouter, effort: EffortOverride? = EffortOverride { null }) =
        LeadRig(database, roles = roles, effortOverride = effort).also { it.reset() }

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
                request.isTopicCall() -> end("“Tea Preferences.”")
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

        // Over it: the turn runs to its end (two calls, then the reply nudge: "ok" never reached the person), then the one summarizing call.
        promptTokens = 61_000
        r.inbox.enqueue(user("a long one"))
        r.lead.step() shouldBe true
        r.model.requests.map { it.isSummaryCall() } shouldContainExactly listOf(false, false, false, false, false, false, true, false)
        val summaryCall = r.model.requests[6]
        // Then the topic call on the triage model, tiny, from the summary; its title becomes a marker in the feed.
        val topicCall = r.model.requests.last()
        topicCall.isTopicCall() shouldBe true
        topicCall.model shouldBe onOpenRouter.triage.model
        topicCall.maxTokens shouldBe TOPIC_MAX_TOKENS
        topicCall.tools.isEmpty() shouldBe true
        text(topicCall.messages.single()) shouldContain "They like tea."
        val marker = r.feedStore.page(0, 100).items.single { it.topic != null }
        marker.topic shouldBe "Tea Preferences"
        marker.cards.isEmpty() shouldBe true
        summaryCall.model shouldBe OR_LEAD
        // Same system blocks, tools and cache policy as the thread, so on Claude it reads the cached prefix.
        summaryCall.cache shouldBe CachePolicy(ttl = "1h")
        summaryCall.system.map { it.cacheBreakpoint } shouldContainExactly listOf(true, true)
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

    test("a failed topic call leaves no marker and the compaction stands") {
        val r = rig()
        val threadId = r.threads.ensure().id
        r.model.handler = { request ->
            when {
                request.isSummaryCall() -> end("They like tea.")
                request.isTopicCall() -> error("model down")
                else -> ModelResponse(listOf(Block.Text("ok")), StopReason.END_TURN, Usage(61_000, 10))
            }
        }
        r.lead.start()
        r.inbox.enqueue(user("hello"))
        r.lead.step() shouldBe true
        r.model.requests.count { it.isTopicCall() } shouldBe 1
        r.journal.load(threadId).last() shouldBe Message.summary("They like tea.")
        r.feedStore.page(0, 100).items.none { it.topic != null } shouldBe true
    }

    test("a journal from the Anthropic API: its stored compaction block is the cut, and its summary reaches the model") {
        val r = rig(roles = onOpenRouter.copy(lead = onOpenRouter.lead.copy(model = MIMO)))
        val threadId = r.threads.ensure().id
        // As the lead journaled it on the Anthropic API: thinking with a signature, server-side compaction.
        val thinking = Block.Raw(buildJsonObject { put("type", "thinking"); put("thinking", "hm"); put("signature", "EqQBsig") })
        val compaction = Block.Raw(buildJsonObject { put("type", "compaction"); put("content", "They like tea.") })
        r.journal.append(threadId, Message.user("before the compaction"))
        r.journal.append(threadId, Message(Role.ASSISTANT, listOf(thinking, compaction, Block.Text("ok"))))
        val stored = r.journal.load(threadId)
        r.model.handler = { end("tea it is") }
        r.lead.start()
        r.model.requests.size shouldBe 0 // a finished turn: nothing to resume

        r.inbox.enqueue(user("what do I drink?"))
        r.lead.step() shouldBe true
        val request = r.model.requests.first()
        request.model shouldBe MIMO
        request.cache shouldBe null
        val wire = orMessages(request)
        wire.map { it["role"]!!.jsonPrimitive.content }.take(3) shouldContainExactly listOf("system", "user", "assistant")
        wire[1]["content"]!!.jsonPrimitive.content shouldBe "$SUMMARY_MARKER\nThey like tea."
        wire.toString() shouldNotContain "before the compaction"
        wire.toString() shouldNotContain "EqQBsig"
        wire.toString() shouldContain "what do I drink?"
        // Read as it is: the stored rows are untouched.
        r.journal.load(threadId).take(stored.size) shouldContainExactly stored
    }

    test("switching to Claude: reasoning replays only to the model that wrote it, and cache breakpoints come on") {
        val r = rig(roles = onOpenRouter.copy(lead = onOpenRouter.lead.copy(model = MIMO)))
        val reasoning = OpenRouterModelGateway.reasoningBlock(MIMO, listOf(buildJsonObject { put("type", "reasoning.text"); put("text", "pondering") }))
        r.model.handler = { request ->
            if (request.model == MIMO) ModelResponse(listOf(reasoning, Block.Text("ok")), StopReason.END_TURN, Usage(100, 10)) else end("back")
        }
        r.lead.start()
        r.inbox.enqueue(user("on mimo"))
        r.lead.step() shouldBe true
        r.model.requests.last().cache shouldBe null
        // Replayed to the same model.
        r.inbox.enqueue(user("again"))
        r.lead.step() shouldBe true
        orMessages(r.model.requests.last()).any { it.containsKey("reasoning_details") } shouldBe true

        r.modelSettings.set(AgentRole.LEAD, RoleOverride(model = OR_LEAD))
        r.inbox.enqueue(user("on claude"))
        r.lead.step() shouldBe true
        val request = r.model.requests.last()
        request.model shouldBe OR_LEAD
        request.cache shouldBe CachePolicy(ttl = "1h")
        (request.messageBreakpoint != null) shouldBe true
        request.messages.any { m -> reasoning in m.content } shouldBe true
        val body = OpenRouterModelGateway.buildBody(request)
        body.toString() shouldNotContain "pondering"
        body.toString() shouldContain "cache_control"
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

    test("an open model that saves a fact but never answers is nudged once, on the wire as a trailing operator update in a user message, then replies") {
        val mimo = "xiaomi/mimo-v2.6-pro"
        val r = rig(roles = onOpenRouter.copy(lead = onOpenRouter.lead.copy(model = mimo)))
        fun last(request: ModelRequest) = request.messages.last()
        r.model.handler = { request ->
            when {
                last(request).role == Role.SYSTEM && text(last(request)) == REPLY_NUDGE -> ModelResponse(
                    listOf(Block.ToolUse("call_reply", "send_to_user", buildJsonObject {
                        put("cards", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "Noted.") })))
                    })),
                    StopReason.TOOL_USE, Usage(100, 10),
                )
                last(request).content.any { it is Block.Result } -> end("")
                else -> ModelResponse(listOf(Block.ToolUse("call_fact", "remember_fact", buildJsonObject { put("fact", "Drinks tea.") })), StopReason.TOOL_USE, Usage(100, 10))
            }
        }
        r.lead.start()
        r.inbox.enqueue(user("I drink tea, remember that"))
        r.lead.step() shouldBe true

        r.model.requests.map { it.model }.toSet() shouldBe setOf(mimo)
        r.model.requests.size shouldBe 4
        val wire = orMessages(r.model.requests[2])
        wire.takeLast(2).map { it["role"]!!.jsonPrimitive.content } shouldContainExactly listOf("assistant", "user")
        wire.last()["content"]!!.jsonPrimitive.content shouldBe "${OpenRouterModelGateway.OPERATOR_UPDATE_HEADING}\n$REPLY_NUDGE"
        r.feed.page(0, 10).items.single().cards shouldBe listOf(net.robog.clarity.shared.feed.Card.Text("Noted."))
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
            "lead prompt on OpenRouter: ${tools.size} tools, " +
                "tools $toolChars chars (~${toolChars / 4} tokens), system $systemChars chars (~${systemChars / 4} tokens), " +
                "whole first request $total chars (~${total / 4} tokens)",
        )
        tools.size shouldBe request.tools.size
    }
})
