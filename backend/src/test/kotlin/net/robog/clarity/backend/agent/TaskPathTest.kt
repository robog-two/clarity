package net.robog.clarity.backend.agent

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.testing.testApplication
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.DevicePrincipal
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.feed.ExposedFeedStore
import net.robog.clarity.backend.feed.FeedHub
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.vault.GrantStore
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.CurrentRun
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Role
import net.robog.clarity.harness.Usage
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.path.AgentPath
import net.robog.clarity.shared.path.Outcome
import net.robog.clarity.shared.path.PathItem
import net.robog.clarity.shared.path.PathSubject
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.testcontainers.postgresql.PostgreSQLContainer

private const val PASSWORD = "Tr0ub4dor&3"
private const val CODE = "482913"
private const val MAIL_BODY = "Your order 1234 has shipped"

private fun said(text: String) = Message(Role.ASSISTANT, listOf(Block.Text(text)))
private fun call(id: String, name: String, input: JsonObject = buildJsonObject { }) = Message(Role.ASSISTANT, listOf(Block.ToolUse(id, name, input)))
private fun answer(id: String, content: String) = Message(Role.USER, listOf(Block.Result(id, content)))

/** Tasks as threads: helpers linked to tasks, and the Agent's Path of a task and of a run (V21), read-only and redacted. */
@EnabledIf(ContainerRuntimeAvailable::class)
class TaskPathTest : FunSpec({
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

    test("the lead links helpers to tasks: inferred from the turn's one task, explicit by taskId; an unknown taskId starts nothing") {
        val r = LeadRig(database).also { it.reset() }
        fun lastResult(request: net.robog.clarity.harness.ModelRequest): Block.Result? = request.messages.last().content.filterIsInstance<Block.Result>().lastOrNull()
        fun taskId(request: net.robog.clarity.harness.ModelRequest): String =
            request.messages.flatMap { it.content }.filterIsInstance<Block.Result>().firstNotNullOf { Regex("Task ([0-9a-f-]{36}):").find(it.content)?.groupValues?.get(1) }
        fun lastUserText(request: net.robog.clarity.harness.ModelRequest): String =
            request.messages.last { m -> m.role == Role.USER && m.content.any { it is Block.Text } }.content.filterIsInstance<Block.Text>().joinToString { it.text }
        fun reply(id: String) = callTool(id, "send_to_user", buildJsonObject { put("cards", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "On it.") }))) })
        r.model.handler = { request ->
            if (request.model != LEAD_MODEL) end("Done.")
            else when (lastResult(request)?.toolUseId) {
                null -> when {
                    "order mail" in lastUserText(request) ->
                        callTool("d2", "delegate", buildJsonObject { put("task", "Find the order mail"); put("taskId", taskId(request)) })
                    "bogus" in lastUserText(request) ->
                        callTool("d3", "delegate", buildJsonObject { put("task", "Nothing"); put("taskId", Uuid.random().toString()) })
                    else -> callTool("u1", "update_task", buildJsonObject { put("title", "Sign in to Garden Shop"); put("status", "working") })
                }
                "u1" -> callTool("d1", "delegate", buildJsonObject { put("task", "Sign in to shop.clarity.test") })
                "d1", "d2", "d3" -> reply("s-" + lastResult(request)!!.toolUseId)
                else -> end("ok")
            }
        }
        val helperJob = scope.launch { r.helpers.run() }
        r.lead.start()
        r.inbox.enqueue(InboxItem.UserInput(ClientInputId("in-1"), "Sign me in to the Garden Shop"))
        r.lead.step() shouldBe true
        r.inbox.enqueue(InboxItem.UserInput(ClientInputId("in-2"), "Also check the order mail for it"))
        r.lead.step() shouldBe true
        r.inbox.enqueue(InboxItem.UserInput(ClientInputId("in-3"), "bogus"))
        r.lead.step() shouldBe true
        withTimeout(20.seconds) { while (r.runs.open().isNotEmpty()) delay(50) }
        r.helpers.joinAll()

        val task = r.tasks.list(includeFinished = true).single()
        task.runIds shouldHaveSize 2
        r.runs.list(openOnly = false) shouldHaveSize 2
        val hows = database.tx { AgentTaskRuns.selectAll().orderBy(AgentTaskRuns.createdAt).map { it[AgentTaskRuns.runId].toString() to it[AgentTaskRuns.how] } }
        hows.map { it.second } shouldBe listOf(TaskLink.INFERRED, TaskLink.EXPLICIT)
        hows.map { it.first } shouldBe task.runIds
        // the third turn's delegate named a task that does not exist: an error, and no helper
        r.model.requestsFor(LEAD_MODEL).flatMap { it.messages }.flatMap { it.content }.filterIsInstance<Block.Result>()
            .first { it.toolUseId == "d3" }.let { it.isError shouldBe true; it.content shouldContain "No task with id" }

        // the task's path has both lead turns that touched it and both helpers inline
        val paths = AgentPathService(database, r.tasks, MemoryBrowserAudit(), GrantStore(r.clock), r.clock)
        val path = paths.task(task.id, 0, 100).shouldNotBeNull()
        path.items.filterIsInstance<PathItem.Helper>().map { it.childRunId } shouldBe task.runIds
        path.items.filterIsInstance<PathItem.Heard>().map { it.text } shouldBe listOf("Sign me in to the Garden Shop", "Also check the order mail for it")
        path.items.filterIsInstance<PathItem.Finished>().map { it.depth } shouldBe listOf(1, 1)
        helperJob.cancelAndJoin()
    }

    test("a helper the lead's turn leaves without a task gets one of its own at the turn's end; claimed ones and triage do not") {
        val r = LeadRig(database).also { it.reset() }
        fun lastResult(request: net.robog.clarity.harness.ModelRequest): Block.Result? = request.messages.last().content.filterIsInstance<Block.Result>().lastOrNull()
        fun lastUserText(request: net.robog.clarity.harness.ModelRequest): String =
            request.messages.last { m -> m.role == Role.USER && m.content.any { it is Block.Text } }.content.filterIsInstance<Block.Text>().joinToString { it.text }
        fun reply(id: String) = callTool(id, "send_to_user", buildJsonObject { put("cards", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "On it.") }))) })
        val blogBrief = "Read the latest blog post on robog.net. Summarize it in three lines."
        r.model.handler = { request ->
            if (request.model != LEAD_MODEL) end("Done.")
            else when (lastResult(request)?.toolUseId) {
                null -> when {
                    "kw-weather" in lastUserText(request) ->
                        callTool("d4", "delegate", buildJsonObject { put("task", "Look at the forecast for Ithaca for the coming week and report it"); put("title", "Weather this week") })
                    "kw-garden" in lastUserText(request) -> callTool("d2", "delegate", buildJsonObject { put("task", "Find the order mail") })
                    else -> callTool("d1", "delegate", buildJsonObject { put("task", blogBrief) })
                }
                "d1" -> callTool("t1", "delegate", buildJsonObject { put("task", "Is this mail urgent? Answer yes or no."); put("role", "triage") })
                // the task comes after the helper in the same turn: it claims the helper the usual way
                "d2" -> callTool("u2", "update_task", buildJsonObject { put("title", "Garden order"); put("status", "working") })
                "t1", "u2", "d4" -> reply("s-" + lastResult(request)!!.toolUseId)
                else -> end("ok")
            }
        }
        val helperJob = scope.launch { r.helpers.run() }
        r.lead.start()
        r.inbox.enqueue(InboxItem.UserInput(ClientInputId("in-1"), "kw-blog What does the blog say?"))
        r.lead.step() shouldBe true
        r.inbox.enqueue(InboxItem.UserInput(ClientInputId("in-2"), "kw-garden Find my order"))
        r.lead.step() shouldBe true
        r.inbox.enqueue(InboxItem.UserInput(ClientInputId("in-3"), "kw-weather And the weather?"))
        r.lead.step() shouldBe true
        withTimeout(20.seconds) { while (r.runs.open().isNotEmpty()) delay(50) }
        r.helpers.joinAll()

        val runs = r.runs.list(openOnly = false).associate { it.task to it.id }
        runs.size shouldBe 4
        val links = database.tx { AgentTaskRuns.selectAll().associate { it[AgentTaskRuns.runId].toString() to (it[AgentTaskRuns.taskId].toString() to it[AgentTaskRuns.how]) } }
        links.keys shouldBe setOf(runs.getValue(blogBrief), runs.getValue("Find the order mail"), runs.getValue("Look at the forecast for Ithaca for the coming week and report it"))
        links.getValue(runs.getValue("Find the order mail")).second shouldBe TaskLink.INFERRED
        val tasks = r.tasks.list(includeFinished = true).associateBy { it.id.value }
        tasks.size shouldBe 3
        // the server's tasks: named from the brief or the given title, and done when their helper is
        links.getValue(runs.getValue(blogBrief)).let { (task, how) ->
            how shouldBe TaskLink.AUTO
            tasks.getValue(task).title shouldBe "Read the latest blog post on robog.net"
            tasks.getValue(task).status shouldBe AgentTask.Status.DONE
        }
        links.getValue(runs.getValue("Look at the forecast for Ithaca for the coming week and report it")).let { (task, how) ->
            how shouldBe TaskLink.AUTO
            tasks.getValue(task).title shouldBe "Weather this week"
            tasks.getValue(task).status shouldBe AgentTask.Status.DONE
        }
        // the lead's own task keeps the status the lead gave it
        tasks.getValue(links.getValue(runs.getValue("Find the order mail")).first).let { it.title shouldBe "Garden order"; it.status shouldBe AgentTask.Status.WORKING }
        helperJob.cancelAndJoin()
    }

    test("a server-made task follows its helper to failed or cancelled; a task the lead owns is left alone") {
        val r = LeadRig(database).also { it.reset() }
        val lead = RunScope(r.threads.ensure().id, isLead = true)
        val failing = Uuid.random().toString()
        val cancelled = Uuid.random().toString()
        val owned = Uuid.random().toString()
        listOf(failing, cancelled, owned).forEach { r.runs.create(it, lead.runId, "helper", "Work $it", null, r.clock.now()) }
        val leadTask = r.tasks.upsert(null, "Lead's task", "", AgentTask.Status.WORKING)!!
        r.tasks.link(leadTask.id, owned, TaskLink.EXPLICIT)

        lead.startTurn(untrusted = false, requestedByUser = true)
        lead.turn.started(failing, "Failing work")
        lead.turn.started(cancelled, "Cancelled work")
        // cancelled before the turn ended: its task is made in its final state
        r.runs.finish(cancelled, RunStatus.CANCELLED, null, null, "cancelled by the lead", r.clock.now())
        r.toolbox.endTurn(lead)
        r.toolbox.endTurn(lead) // nothing left: no duplicates

        val failingTask = r.tasks.autoTaskOf(failing).shouldNotBeNull()
        r.tasks.get(failingTask)!!.let { it.title shouldBe "Failing work"; it.status shouldBe AgentTask.Status.WORKING; it.runIds shouldBe listOf(failing) }
        r.tasks.get(r.tasks.autoTaskOf(cancelled).shouldNotBeNull())!!.status shouldBe AgentTask.Status.CANCELLED
        r.tasks.list(includeFinished = true) shouldHaveSize 3

        r.runs.finish(failing, RunStatus.FAILED, "x", null, "x", r.clock.now())
        r.toolbox.runEnded(failing)
        r.tasks.get(failingTask)!!.status shouldBe AgentTask.Status.FAILED

        r.runs.finish(owned, RunStatus.DONE, "ok", null, null, r.clock.now())
        r.toolbox.runEnded(owned)
        r.tasks.autoTaskOf(owned) shouldBe null
        r.tasks.get(leadTask.id)!!.status shouldBe AgentTask.Status.WORKING
        // ...but the helper's result names it by its full id, so the lead can close it; a server-made task is not named
        r.toolbox.openLeadTasksOf(owned) shouldBe listOf("${leadTask.id.value} \"Lead's task\" (working)")
        r.toolbox.openLeadTasksOf(failing) shouldBe emptyList()
        InboxItem.HelperResult(owned, "Work", true, "ok", r.toolbox.openLeadTasksOf(owned)).summary shouldContain
            "It worked on task ${leadTask.id.value} \"Lead's task\" (working), still open: update it with update_task"
    }

    test("a task is found by a unique 8-hex-digit prefix of its id, as the agent shortens it; update_task and delegate accept it") {
        val r = LeadRig(database).also { it.reset() }
        val devils = r.tasks.upsert(null, "Research The Devils (1971) background", "web research", AgentTask.Status.WORKING)!!
        val short = devils.id.value.take(8)
        val update = r.own.updateTask()
        update.invoke(buildJsonObject { put("id", short); put("status", "done") }).shouldBeInstanceOf<net.robog.clarity.harness.ToolResult.Ok>()
            .content shouldBe "Task ${devils.id.value}: Research The Devils (1971) background (done)."
        r.tasks.get(devils.id)!!.status shouldBe AgentTask.Status.DONE
        r.tasks.get(TaskId(short.uppercase()))!!.id shouldBe devils.id
        r.tasks.createdAt(TaskId(short)).shouldNotBeNull()
        val run = Uuid.random().toString()
        r.runs.create(run, r.threads.ensure().id, "helper", "Work", null, r.clock.now())
        r.tasks.link(TaskId(short), run, TaskLink.EXPLICIT) shouldBe true
        r.tasks.get(devils.id)!!.runIds shouldBe listOf(run)

        // too short, not an id, or no task: an error that lists the open tasks by full id
        val soup = r.tasks.upsert(null, "Get soup ingredients", "Thursday 9am", AgentTask.Status.WORKING)!!
        r.tasks.get(TaskId(short.take(7))) shouldBe null
        r.tasks.get(TaskId("will-assign-after")) shouldBe null
        r.tasks.get(TaskId(Uuid.random().toString())) shouldBe null
        r.tasks.upsert(TaskId("0000000a"), null, null, AgentTask.Status.DONE) shouldBe null
        update.invoke(buildJsonObject { put("id", "0000000a"); put("status", "done") }).shouldBeInstanceOf<net.robog.clarity.harness.ToolResult.Error>()
            .message shouldBe "No task with id 0000000a. Open tasks:\n- ${soup.id.value} \"Get soup ingredients\" (working) · Thursday 9am"
        r.own.listTasks().invoke(buildJsonObject { }).shouldBeInstanceOf<net.robog.clarity.harness.ToolResult.Ok>()
            .content shouldBe "- ${soup.id.value} \"Get soup ingredients\" (working) · Thursday 9am"
    }

    test("send_to_user refuses a card without a known type, so nothing blank reaches the feed") {
        val r = LeadRig(database).also { it.reset() }
        val send = r.own.sendToUser()
        fun cards(vararg card: JsonObject) = buildJsonObject { put("cards", JsonArray(card.toList())) }
        // what the model sent: a text card without its `type`
        val missing = send.invoke(cards(buildJsonObject { put("text", "Tim is in your contacts with only a phone number.") }))
        missing.shouldBeInstanceOf<net.robog.clarity.harness.ToolResult.Error>().message shouldContain "Invalid input for send_to_user"
        send.invoke(cards(buildJsonObject { put("type", "txt"); put("text", "x") })).shouldBeInstanceOf<net.robog.clarity.harness.ToolResult.Error>()
        send.invoke(cards(buildJsonObject { put("type", "unknown"); put("fallbackText", "x") })).shouldBeInstanceOf<net.robog.clarity.harness.ToolResult.Error>()
            .message shouldContain "`unknown` is not a card type"
        r.feedStore.page(0, 10).items shouldBe emptyList()

        send.invoke(cards(buildJsonObject { put("type", "text"); put("text", "Found Tim.") })).shouldBeInstanceOf<net.robog.clarity.harness.ToolResult.Ok>()
        r.feedStore.page(0, 10).items.single().cards shouldBe listOf(net.robog.clarity.shared.feed.Card.Text("Found Tim."))
    }

    test("a task's path and a run's path: lead turn, a browser helper and a mail helper, with costs; no secret reaches the app") {
        val clock = StepClock(Instant.parse("2026-10-27T15:00:00Z"))
        database.tx { exec("TRUNCATE agent_journal, agent_thread, agent_run, agent_task, agent_task_run, browser_step, model_call RESTART IDENTITY CASCADE") }
        val feed = FeedService(ExposedFeedStore(database, clock), FeedHub())
        val tasks = ExposedTaskStore(database, clock, feed)
        val runs = ExposedRunStore(database, clock)
        val journal = PgRunJournal(database, clock)
        val audit = PgBrowserAudit(database)
        val lead = ExposedThreadStore(database, clock).ensure().id
        suspend fun lead(m: Message) { journal.append(lead, m); clock.advance(2.seconds) }

        val task = tasks.upsert(null, "Sign in to Garden Shop", "web · signing in", AgentTask.Status.WORKING)!!
        val browserHelper = Uuid.random().toString()
        val mailHelper = Uuid.random().toString()
        runs.create(browserHelper, lead, "helper", "Sign in to shop.clarity.test with the saved login garden", null, clock.now())
        runs.create(mailHelper, lead, "helper", "Find the order confirmation mail", null, clock.now())
        tasks.link(task.id, browserHelper, TaskLink.EXPLICIT) shouldBe true
        tasks.link(task.id, mailHelper, TaskLink.INFERRED) shouldBe true
        tasks.link(task.id, mailHelper, TaskLink.EXPLICIT) shouldBe true // the first link stays
        tasks.link(TaskId(Uuid.random().toString()), mailHelper, TaskLink.EXPLICIT) shouldBe false
        tasks.get(task.id)!!.runIds shouldBe listOf(browserHelper, mailHelper)

        // The lead's turn
        lead(Message.user("Sign me in to the Garden Shop and find my order"))
        lead(call("u1", "update_task", buildJsonObject { put("title", "Sign in to Garden Shop") }))
        lead(answer("u1", "Task ${task.id}: Sign in to Garden Shop (working)."))
        lead(call("d1", "delegate", buildJsonObject { put("task", "Sign in"); put("taskId", task.id.value) }))
        lead(answer("d1", "Helper $browserHelper started (helper). Its result will arrive in your inbox."))
        lead(call("d2", "delegate", buildJsonObject { put("task", "Find the order mail") }))
        lead(answer("d2", "Helper $mailHelper started (helper). Its result will arrive in your inbox."))
        lead(said("Two helpers are on it."))
        withContext(CurrentRun(lead)) { DbUsageRecorder(database, clock).record(LEAD_MODEL, Usage(2000, 50, reportedCostMicros = 4000), AgentRole.LEAD) }
        // an unrelated later turn
        lead(Message.user("What's the weather?"))
        lead(said("Sunny."))

        // The browser helper: a credential fill and a code, with secrets in places they must never leave
        suspend fun helper(m: Message) { journal.append(browserHelper, m); clock.advance(2.seconds) }
        helper(Message.user("Current time: Tue 3:00 pm\n\nTask from the lead agent:\nSign in"))
        helper(call("c1", "list_credentials"))
        helper(answer("c1", "garden [password] for shop.clarity.test: placeholders [secret:garden_email], [secret:garden_password]"))
        helper(call("b1", "browser__act", buildJsonObject { put("intent", "the password field"); put("value", PASSWORD) }))
        helper(answer("b1", "Typed $PASSWORD into the password field"))
        helper(call("k1", "ask_code", buildJsonObject { put("reason", "the SMS code") }))
        helper(answer("k1", "The person entered $CODE. Type [secret:code_h1]."))
        helper(call("b2", "browser__sign_in", buildJsonObject { put("credential", "garden") }))
        for ((seq, gate) in listOf("passed", "code_guard", "passed").withIndex()) {
            clock.advance(1.seconds)
            audit.record(
                BrowserStepRecord(
                    browserHelper, seq + 1, clock.now(), "sign_in", "https://login.clarity.test/code?ticket=$CODE", "Clarity ID", "code", "signed_in",
                    if (seq == 0) byteArrayOf(1, 2) else null, seq != 0, null, null, null, null, gate,
                    "fill_by_uid {\"uid\":\"e3\",\"value\":\"[secret:code_h1]\"}", null, seq == 1, "Typed [secret:code_h1]; password: $PASSWORD", 120,
                ),
            )
        }
        clock.advance(1.seconds)
        helper(answer("b2", "Signed in with code $CODE"))
        helper(said("Signed in. The password is $PASSWORD"))
        withContext(CurrentRun(browserHelper)) {
            DbUsageRecorder(database, clock).record(HELPER_MODEL, Usage(1000, 100, reportedCostMicros = 2500), AgentRole.HELPER)
            recordDecisionCall(database, clock, "typesafe/jev-1.14", DecisionUsage(1000, 0, 194))
        }
        runs.finish(browserHelper, RunStatus.DONE, "Signed in; used code $CODE", null, null, clock.now())

        // The mail helper: a message body it read must not show
        journal.append(mailHelper, Message.user("Task from the lead agent:\nFind the order mail"))
        journal.append(mailHelper, call("m1", "list_messages", buildJsonObject { put("participant", "orders@shop.clarity.test") }))
        journal.append(mailHelper, answer("m1", """[{"body":"$MAIL_BODY"},{"body":"Reset your password"}]"""))
        journal.append(mailHelper, said("Found the order mail."))
        runs.finish(mailHelper, RunStatus.DONE, "Order 1234 shipped Monday.", Usage(500, 50, reportedCostMicros = 700), null, clock.now())

        val paths = AgentPathService(database, tasks, audit, GrantStore(clock), clock)
        val before = database.tx { listOf("agent_journal", "agent_thread", "agent_run", "agent_task_run", "browser_step", "model_call").associateWith { t -> exec("SELECT count(*) FROM $t") { rs -> rs.next(); rs.getLong(1) } } }
        val path = paths.task(task.id, 0, 100).shouldNotBeNull()

        path.subject.kind shouldBe PathSubject.Kind.TASK
        path.subject.title shouldBe "Sign in to Garden Shop"
        path.live shouldBe true // still working
        val kinds = path.items.map { "${it::class.simpleName}@${it.depth}" }
        kinds shouldBe listOf(
            "Heard@0", "Action@0", "Helper@0",
            "Action@1", "Action@1", "Action@1", "Browser@1", "Browser@1", "Browser@1", "Finished@1",
            "Helper@0", "Action@1", "Finished@1",
            "Said@0",
        )
        path.items.filterIsInstance<PathItem.Action>().map { it.line } shouldBe
            listOf("Added a task", "Checked saved logins", "Did a step on the page", "Asked you for a code", "Read 2 messages")
        path.items.filterIsInstance<PathItem.Browser>().map { it.step.gate } shouldBe listOf("passed", "code_guard", "passed")
        path.items.filterIsInstance<PathItem.Browser>().first().step.let { it.beforeShot shouldBe true; it.tabUrl shouldBe "https://login.clarity.test/code"; it.action shouldBe "fill_by_uid" }
        path.items.filterIsInstance<PathItem.Helper>().first().let { it.browserSteps shouldBe 3; it.steps shouldBe 6; it.status shouldBe "done" }
        path.items.filterIsInstance<PathItem.Action>().all { it.outcome == Outcome.OK } shouldBe true
        // costs: the lead's turn and the helpers' calls (recorded per run, or from the run's usage), and Jev's
        path.totals.modelCostMicros shouldBe 4000L + 2500L + 700L
        path.totals.decisionCostMicros shouldBe 194L
        path.totals.steps shouldBe path.items.count(::countsAsStep)

        // nothing secret, typed or read leaves the server
        val json = ClarityJson.encodeToString(AgentPath.serializer(), path)
        listOf(PASSWORD, CODE, MAIL_BODY, "Reset your password", "[secret:", "garden_password", "code_h1", "ticket=").forEach { json shouldNotContain it }

        // a run's own path; the lead thread is not a run to show
        val run = paths.run(browserHelper, 0, 100).shouldNotBeNull()
        run.subject.kind shouldBe PathSubject.Kind.RUN
        run.live shouldBe false
        run.items.last().shouldBeInstanceOf<PathItem.Finished>().ok shouldBe true
        run.totals.modelCostMicros shouldBe 2500L
        run.totals.decisionCostMicros shouldBe 194L
        ClarityJson.encodeToString(AgentPath.serializer(), run).let { s -> listOf(PASSWORD, CODE, "[secret:").forEach { s shouldNotContain it } }
        paths.run(lead, 0, 100) shouldBe null
        paths.run("not-a-uuid", 0, 100) shouldBe null

        // paging
        val first = paths.task(task.id, 0, 5).shouldNotBeNull()
        first.items shouldHaveSize 5
        first.next shouldBe 5
        paths.task(task.id, 10, 5)!!.let { it.items shouldHaveSize 4; it.next shouldBe null }

        // over HTTP, device-authenticated
        testApplication {
            application {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(ClarityJson) }
                install(Authentication) { bearer(AuthProviders.DEVICE) { authenticate { DevicePrincipal(DeviceId("d1"), null) } } }
                AgentPathRoutes(paths).install(this)
            }
            val client = createClient { install(ContentNegotiation) { json(ClarityJson) } }
            val ok = client.get("/api/v1/tasks/${task.id}/path?limit=50") { header(HttpHeaders.Authorization, "Bearer device") }
            ok.status shouldBe HttpStatusCode.OK
            ok.body<AgentPath>().items.size shouldBe path.items.size
            client.get("/api/v1/runs/$browserHelper/path") { header(HttpHeaders.Authorization, "Bearer device") }.bodyAsText().let { body ->
                body shouldContain "\"kind\":\"run\""
                body shouldNotContain PASSWORD
            }
            client.get("/api/v1/tasks/${task.id}/path").status shouldBe HttpStatusCode.Unauthorized
            client.get("/api/v1/tasks/${Uuid.random()}/path") { header(HttpHeaders.Authorization, "Bearer device") }.status shouldBe HttpStatusCode.NotFound
            client.get("/api/v1/runs/$lead/path") { header(HttpHeaders.Authorization, "Bearer device") }.status shouldBe HttpStatusCode.NotFound
            client.get("/api/v1/tasks/${task.id}/path?from=x") { header(HttpHeaders.Authorization, "Bearer device") }.status shouldBe HttpStatusCode.BadRequest
        }

        // reading wrote nothing
        val after = database.tx { listOf("agent_journal", "agent_thread", "agent_run", "agent_task_run", "browser_step", "model_call").associateWith { t -> exec("SELECT count(*) FROM $t") { rs -> rs.next(); rs.getLong(1) } } }
        after shouldBe before
        // model calls made inside a run are tagged with it
        database.tx { ModelCalls.selectAll().map { it[ModelCalls.runId] } }.toSet() shouldBe setOf(browserHelper, lead)
    }
})
