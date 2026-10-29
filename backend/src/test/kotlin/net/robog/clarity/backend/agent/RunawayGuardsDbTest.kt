package net.robog.clarity.backend.agent

import arrow.core.Either
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.governance.ActionOutboxTable
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.AgentRuns
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.CurrentRun
import net.robog.clarity.harness.HarnessJson
import net.robog.clarity.harness.LedgerEntry
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.ModelRequest
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.OutOfSteps
import net.robog.clarity.harness.Role
import net.robog.clarity.harness.StopReason
import net.robog.clarity.harness.ToolCallContext
import net.robog.clarity.harness.Usage
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Constitution
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.testcontainers.postgresql.PostgreSQLContainer

private fun user(text: String) = InboxItem.UserInput(ClientInputId(text), text)

private fun lastHasResults(request: ModelRequest) = request.messages.last().content.any { it is Block.Result }

private fun textOf(m: Message) = m.content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }

private fun ModelRequest.isSummaryCall() = messages.lastOrNull()?.let(::textOf) == CLIENT_COMPACTION_REQUEST

private fun ModelRequest.isTopicCall() = systemText == TOPIC_SYSTEM

private fun reply(id: String, text: String) = Block.ToolUse(id, "send_to_user", buildJsonObject { putJsonArray("cards") { add(buildJsonObject { put("type", "text"); put("text", text) }) } })

/**
 * The run-level guards against loops, repeats and spend, against real Postgres with a scripted model
 * (never the network): each test is a shape that went wrong, or could have, before the guard.
 */
@EnabledIf(ContainerRuntimeAvailable::class)
class RunawayGuardsDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec {
        scope.cancel()
        postgres.stop()
    }

    suspend fun LeadRig.serving(block: suspend LeadRig.() -> Unit) {
        val job = scope.launch { helpers.run() }
        try {
            block()
        } finally {
            job.cancelAndJoin()
        }
    }

    suspend fun LeadRig.awaitRun(id: String) {
        withTimeout(30.seconds) { while (runs.get(id)?.status.let { it == null || it in RunStatus.OPEN }) delay(50) }
        helpers.joinAll()
    }

    suspend fun outboxRows() = database.tx { ActionOutboxTable.selectAll().count() }

    test("two lead turns whose calls both come back as call_0 send two emails and two messages, not one") {
        val r = LeadRig(database).also { it.reset() }
        r.constitutions.create(
            Constitution(version = 1, lines = emptyList(), autonomy = mapOf(ActionKind.SEND_EMAIL to AutonomyTier.DO_THEN_TELL), updatedAt = Clock.System.now()),
        )
        r.model.handler = { request ->
            if (lastHasResults(request)) {
                end("done")
            } else {
                val n = r.model.requests.size
                ModelResponse(
                    listOf(
                        Block.ToolUse(
                            "call_0", "send_email",
                            buildJsonObject {
                                put("from", "me@gmail.example")
                                put("newThread", true)
                                put("to", JsonArray(listOf(JsonPrimitive("dana@example.com"))))
                                put("subject", "Update $n")
                                put("body", "Here is update $n.")
                                put("reason", "asked")
                            },
                        ),
                        reply("call_1", "Sent update $n."),
                    ),
                    StopReason.TOOL_USE,
                    Usage(100, 10),
                )
            }
        }
        r.lead.start()
        r.inbox.enqueue(user("send Dana an update"))
        r.lead.step() shouldBe true
        r.inbox.enqueue(user("and another"))
        r.lead.step() shouldBe true
        // Each email is its own action (sent or held for the person), never "already queued" for the first.
        val results = r.journal.load(r.threads.ensure().id).flatMap { it.content }.filterIsInstance<Block.Result>().map { it.content }
        withClue(results.joinToString("\n")) {
            outboxRows() + r.heldStore.list().size shouldBe 2L
            results.none { it.contains("Already queued", ignoreCase = true) } shouldBe true
        }
        val notes = r.feedStore.page(0, 100).items.flatMap { it.cards }.filterIsInstance<Card.Text>().filter { it.text.startsWith("Sent update") }
        notes shouldHaveSize 2
    }

    test("a lead turn that runs out of steps closes its calls, and a restart does not run them") {
        val r = LeadRig(database, configure = { it.copy(maxTurns = 3) }).also { it.reset() }
        r.model.handler = { callTool("call_0", "list_tasks") }
        r.lead.start()
        r.inbox.enqueue(user("check everything"))
        r.lead.step() shouldBe true
        r.model.requests shouldHaveSize 3
        val threadId = r.threads.ensure().id
        val journal = r.journal.load(threadId)
        val last = journal.last()
        last.role shouldBe Role.USER
        last.content.single().shouldBeInstanceOf<Block.Result>().content shouldBe LEAD_OUT_OF_STEPS.notRun
        needsResume(journal) shouldBe false
        // A restart: a new lead over the same journal makes no call and runs no tool.
        val restarted = LeadRig(database)
        restarted.model.handler = { error("no call expected") }
        restarted.lead.start()
        restarted.model.requests shouldHaveSize 0
    }

    test("a lead turn resumed at boot continues its step budget") {
        val r = LeadRig(database, configure = { it.copy(maxTurns = 3) }).also { it.reset() }
        val threadId = r.threads.ensure().id
        // Before the crash: the person's words and two steps; the second step's call never got its result.
        r.journal.append(threadId, Message.user("check everything"))
        r.journal.append(threadId, Message(Role.ASSISTANT, listOf(Block.ToolUse("a", "list_tasks", buildJsonObject { }))))
        r.journal.append(threadId, Message(Role.USER, listOf(Block.Result("a", "(none)"))))
        r.journal.append(threadId, Message(Role.ASSISTANT, listOf(Block.ToolUse("b", "list_tasks", buildJsonObject { }))))
        r.model.handler = { callTool("call_0", "list_tasks") }
        r.lead.start()
        // The open call ran, then one step was left.
        r.model.requests shouldHaveSize 1
        r.journal.load(threadId).last().content.single().shouldBeInstanceOf<Block.Result>().content shouldBe LEAD_OUT_OF_STEPS.notRun
    }

    test("a lead turn resumed after a crash never starts the same helper twice") {
        val ledger = PgToolLedger(database, Clock.System)
        val r = LeadRig(database, ledger = ledger).also { it.reset() }
        val threadId = r.threads.ensure().id
        val task = "Find the dentist's opening hours."
        r.journal.append(threadId, Message.user("when is the dentist open?"))
        r.journal.append(threadId, Message(Role.ASSISTANT, listOf(Block.ToolUse("toolu_d", "delegate", buildJsonObject { put("task", task) }))))
        r.model.handler = { request ->
            val replied = request.messages.any { m -> m.content.any { it is Block.ToolUse && it.name == "send_to_user" } }
            when {
                request.model == HELPER_MODEL || replied -> end("ok")
                lastHasResults(request) -> ModelResponse(listOf(reply("r", "On it.")), StopReason.TOOL_USE, Usage(100, 10))
                else -> end("ok")
            }
        }
        lateinit var id: String
        r.serving {
            // The helper was started, then the crash came before the result was kept anywhere.
            val first = withContext(ToolCallContext(threadId, "toolu_d")) { helpers.start(task, null, null, RunScope(threadId, isLead = true)) }
            id = first.shouldBeInstanceOf<Either.Right<Delegated>>().value.id
            id shouldBe callRunId(threadId, "toolu_d")
            lead.start()
            awaitRun(id)
        }
        r.runs.list(openOnly = false).map { it.id } shouldBe listOf(id)
        r.journal.load(threadId)[2].content.single().shouldBeInstanceOf<Block.Result>().content shouldContain "already started for this call"
    }

    test("a lead tool call already kept in the ledger is not run again on resume") {
        val ledger = PgToolLedger(database, Clock.System)
        val r = LeadRig(database, ledger = ledger).also { it.reset() }
        val threadId = r.threads.ensure().id
        r.journal.append(threadId, Message.user("research the dentist"))
        r.journal.append(threadId, Message(Role.ASSISTANT, listOf(Block.ToolUse("toolu_x", "delegate", buildJsonObject { put("task", "Research the dentist.") }))))
        ledger.record(threadId, "toolu_x", LedgerEntry("delegate", "Helper 123 started (helper).", isError = false))
        ledger.find(threadId, "toolu_x").shouldNotBeNull().content shouldBe "Helper 123 started (helper)."
        // The first result kept stays.
        ledger.record(threadId, "toolu_x", LedgerEntry("delegate", "other", isError = true))
        ledger.find(threadId, "toolu_x")!!.isError shouldBe false
        r.model.handler = { end("ok") }
        r.lead.start()
        r.runs.list(openOnly = false) shouldHaveSize 0
        r.journal.load(threadId)[2].content.single().shouldBeInstanceOf<Block.Result>().content shouldBe "Helper 123 started (helper)."
    }

    test("a helper resumed with its steps spent only wraps up; one resumed too often is failed without a call") {
        val r = LeadRig(database, configure = { it.copy(helperMaxTurns = 4) }).also { it.reset() }
        val spent = "1d6a0b8e-0c55-4f3e-9a1f-2b7c4e5d6a01"
        r.runs.create(spent, null, "helper", "Look things up.", listOf("read_report"), Clock.System.now())
        r.journal.append(spent, Message.user("Look things up."))
        repeat(4) { i ->
            r.journal.append(spent, Message(Role.ASSISTANT, listOf(Block.ToolUse("t$i", "read_report", buildJsonObject { put("id", "x") }))))
            r.journal.append(spent, Message(Role.USER, listOf(Block.Result("t$i", "No report with id x.", isError = true))))
        }
        val looping = "2e7b1c9f-1d66-4a4f-8b2a-3c8d5f6e7b02"
        r.runs.create(looping, null, "helper", "Loop.", listOf("read_report"), Clock.System.now())
        repeat(4) { r.runs.recordStart(looping) }
        r.model.handler = { end("Got as far as nothing.") }
        r.serving {
            awaitRun(spent)
            awaitRun(looping)
        }
        val requests = r.model.requestsFor(HELPER_MODEL)
        requests shouldHaveSize 1
        textOf(requests.single().messages.last()) shouldBe OutOfSteps().report
        r.runs.get(spent)!!.result.shouldNotBeNull() shouldContain "ran out of steps"
        r.runs.get(looping)!!.status shouldBe RunStatus.FAILED
        r.runs.get(looping)!!.result.shouldNotBeNull() shouldContain "resumed 4 times"
    }

    test("a helper's recorded usage is the sum of every call of the run, across resumes") {
        val r = LeadRig(database, usageRecorder = DbUsageRecorder(database, Clock.System)).also { it.reset() }
        val id = "3f8c2d0a-2e77-4b5a-9c3b-4d9e6a7f8c03"
        r.runs.create(id, null, "helper", "Sum it.", listOf("read_report"), Clock.System.now())
        withContext(CurrentRun(id)) {
            repeat(2) { DbUsageRecorder(database, Clock.System).record(HELPER_MODEL, Usage(1_000, 100, reportedCostMicros = 1_000, webSearchRequests = 1), AgentRole.HELPER) }
        }
        r.model.handler = { ModelResponse(listOf(Block.Text("done")), StopReason.END_TURN, Usage(500, 50, reportedCostMicros = 500)) }
        r.serving { awaitRun(id) }
        val stored = database.tx { AgentRuns.selectAll().where { AgentRuns.id eq kotlin.uuid.Uuid.parse(id) }.single()[AgentRuns.usage] }
        val usage = HarnessJson.decodeFromJsonElement(Usage.serializer(), stored!!)
        usage.reportedCostMicros shouldBe 2_500L
        usage.inputTokens shouldBe 2_500L
        usage.webSearchRequests shouldBe 2L
    }

    test("a call that fails or is cancelled is still recorded, as incomplete") {
        val recorder = DbUsageRecorder(database, Clock.System)
        LeadRig(database).reset()
        val failing = object : net.robog.clarity.harness.ModelGateway {
            override suspend fun generate(request: ModelRequest, onTextDelta: (String) -> Unit): ModelResponse = error("503")
        }
        val gateway = RecordingGateway(failing, recorder, AgentRole.HELPER)
        shouldThrow<IllegalStateException> { gateway.generate(ModelRequest("m", "s", listOf(Message.user("hi")), emptyList(), 100, null)) }
        val noUsage = RecordingGateway(
            object : net.robog.clarity.harness.ModelGateway {
                override suspend fun generate(request: ModelRequest, onTextDelta: (String) -> Unit) =
                    ModelResponse(listOf(Block.Text("hi")), StopReason.END_TURN, Usage.ZERO, usageMissing = true)
            },
            recorder, AgentRole.HELPER,
        )
        noUsage.generate(ModelRequest("m", "s", listOf(Message.user("hi")), emptyList(), 100, null))
        val rows = database.tx { ModelCalls.selectAll().map { it[ModelCalls.incomplete] } }
        rows shouldBe listOf(true, true)
    }

    test("no global spend stop unless one is set: spending past $10 a week still calls the model") {
        val r = LeadRig(database).also { it.reset() }
        val guard = DbSpendGuard(database, Clock.System, r.config, r.feed)
        DbUsageRecorder(database, Clock.System).record("m", Usage(1, 1, reportedCostMicros = 50_000_000), AgentRole.LEAD)
        guard.check(AgentRole.LEAD).shouldBeNull()
        guard.check(AgentRole.HELPER).shouldBeNull()
    }

    test("a hard spend stop: no call is made, helpers fail with it, and the person is told once") {
        lateinit var guard: DbSpendGuard
        val r = LeadRig(
            database,
            spendGuard = SpendGuard { guard.check(it) },
            configure = { it.copy(spend = net.robog.clarity.backend.config.SpendLimits(weeklyUsd = 10.0)) },
        ).also { it.reset() }
        guard = DbSpendGuard(database, Clock.System, r.config, r.feed)
        guard.check(AgentRole.HELPER).shouldBeNull()
        // This week's calls already cost the weekly limit.
        DbUsageRecorder(database, Clock.System).record("m", Usage(1, 1, reportedCostMicros = 10_000_000), AgentRole.LEAD)
        guard.check(AgentRole.HELPER).shouldNotBeNull().text shouldContain "last 7 days"
        val model = FakeModel()
        shouldThrow<SpendLimitReached> { RecordingGateway(model, { _, _, _ -> }, AgentRole.HELPER, guard).generate(ModelRequest("m", "s", listOf(Message.user("hi")), emptyList(), 100, null)) }
        model.requests shouldHaveSize 0

        r.model.handler = { end("never") }
        r.serving {
            val id = helpers.start("Look it up.", listOf("read_report"), null, RunScope("lead", isLead = true))
                .shouldBeInstanceOf<Either.Right<Delegated>>().value.id
            awaitRun(id)
            runs.get(id)!!.status shouldBe RunStatus.FAILED
            runs.get(id)!!.result.shouldNotBeNull() shouldContain SPEND_LIMIT_PREFIX
        }
        // The lead's turn stops too, without a note of its own.
        r.lead.start()
        r.inbox.enqueue(user("hello?"))
        r.lead.step() shouldBe true
        r.model.requests shouldHaveSize 0
        val notes = r.feedStore.page(0, 100).items.flatMap { it.cards }.filterIsInstance<Card.Note>()
        notes.map { it.text }.single() shouldContain SPEND_LIMIT_PREFIX
    }

    test("a lead turn stops at its own spend limit") {
        val r = LeadRig(database, configure = { it.copy(leadTurnMaxCostUsd = 0.5) }).also { it.reset() }
        r.model.handler = { ModelResponse(listOf(Block.ToolUse("call_0", "list_tasks", buildJsonObject { })), StopReason.TOOL_USE, Usage(100, 10, reportedCostMicros = 300_000)) }
        r.lead.start()
        r.inbox.enqueue(user("go"))
        r.lead.step() shouldBe true
        r.model.requests shouldHaveSize 2
    }

    test("the same task is not delegated again within hours unless the lead says why; the daily cap holds") {
        val r = LeadRig(database, configure = { it.copy(helperMaxPerDay = 3) }).also { it.reset() }
        r.model.handler = { end("The dentist opens at 9.") }
        val lead = RunScope("lead", isLead = true)
        r.serving {
            val id = helpers.start("Find the dentist's hours.", listOf("read_report"), null, lead).shouldBeInstanceOf<Either.Right<Delegated>>().value.id
            awaitRun(id)
            helpers.start("find the dentist's  hours", listOf("read_report"), null, lead).shouldBeInstanceOf<Either.Left<String>>().value shouldContain "already ran this task"
            val again = helpers.start("find the dentist's hours", listOf("read_report"), null, lead, retryReason = "the site was down")
                .shouldBeInstanceOf<Either.Right<Delegated>>().value.id
            awaitRun(again)
            val third = helpers.start("Something else.", listOf("read_report"), null, lead).shouldBeInstanceOf<Either.Right<Delegated>>().value.id
            awaitRun(third)
            helpers.start("A fourth thing.", listOf("read_report"), null, lead).shouldBeInstanceOf<Either.Left<String>>().value shouldContain "3 helpers"
        }
    }

    test("a compaction keeps the record of actions, and the next turn reads it") {
        val roles = TEST_ROLES.let { it.copy(lead = it.lead.copy(compactAtTokens = 50)) }
        val r = LeadRig(database, roles = roles).also { it.reset() }
        val threadId = r.threads.ensure().id
        r.model.handler = { request ->
            when {
                request.isSummaryCall() -> end("They asked for a report on Silence.")
                request.isTopicCall() -> end("Silence report")
                request.messages.none { m -> m.content.any { it is Block.ToolUse && it.name == "create_report" } } ->
                    callTool("c", "create_report", buildJsonObject { put("title", "Silence"); put("source", "# Silence\n\nText.") })
                lastHasResults(request) && request.messages.last().content.any { it is Block.Result && it.content.startsWith("Report") } ->
                    ModelResponse(listOf(reply("r", "It is in your Goods.")), StopReason.TOOL_USE, Usage(100, 10))
                else -> end("ok")
            }
        }
        r.lead.start()
        r.inbox.enqueue(user("write me a report on Silence"))
        r.lead.step() shouldBe true
        val reportId = r.reportStore.createdBy(threadId).single().id
        val summary = r.journal.load(threadId).last()
        summary.isSummary shouldBe true
        val text = textOf(summary)
        text shouldContain "They asked for a report on Silence."
        text shouldContain SIDE_EFFECT_RECORD_HEADING
        text shouldContain "- create_report(title: Silence) → Report $reportId created"
        text shouldContain "- send_to_user("
        // The next turn starts at the summary, record and all.
        r.inbox.enqueue(user("thanks"))
        r.lead.step() shouldBe true
        val next = r.model.requests.last { !it.isSummaryCall() && !it.isTopicCall() }
        textOf(next.messages.first()) shouldContain "Report $reportId created"
    }

    test("a compaction that keeps failing is tried again only after a few turns") {
        val roles = TEST_ROLES.let { it.copy(lead = it.lead.copy(compactAtTokens = 50)) }
        val r = LeadRig(database, roles = roles).also { it.reset() }
        r.model.handler = { request ->
            when {
                request.isSummaryCall() -> error("summary model down")
                lastHasResults(request) -> end("ok")
                else -> ModelResponse(listOf(reply("r", "Noted.")), StopReason.TOOL_USE, Usage(100, 10))
            }
        }
        r.lead.start()
        repeat(6) { i ->
            r.inbox.enqueue(user("message $i"))
            r.lead.step() shouldBe true
        }
        // Two tries in a row, then a pause of five turns.
        r.model.requests.count { it.isSummaryCall() } shouldBe 2
        r.journal.load(r.threads.ensure().id).none { it.isSummary } shouldBe true
        r.model.requests.none { textOf(it.messages.last()).contains("summary model down") } shouldBe true
    }
})
