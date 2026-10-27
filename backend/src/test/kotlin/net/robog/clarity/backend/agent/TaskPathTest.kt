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
