package net.robog.clarity.backend.mcpservers

import io.kotest.assertions.withClue
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.agent.HELPER_MODEL
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.LeadRig
import net.robog.clarity.backend.agent.RunScope
import net.robog.clarity.backend.agent.RunStatus
import net.robog.clarity.backend.agent.callTool
import net.robog.clarity.backend.agent.end
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.DevicePrincipal
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.governance.ConnectorRouter
import net.robog.clarity.backend.governance.HeldActionService
import net.robog.clarity.backend.governance.NoConnectorPreferences
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.scheduler.FireContext
import net.robog.clarity.backend.scheduler.JobSlots
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.Role
import net.robog.clarity.harness.StopReason
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.Usage
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldDecision
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.mcp.McpPaths
import net.robog.clarity.shared.mcp.McpServerList
import net.robog.clarity.shared.mcp.McpSuggestionList
import net.robog.clarity.shared.mcp.McpToolMode
import net.robog.clarity.shared.mcp.McpToolPolicyUpdate
import net.robog.clarity.shared.mcp.McpToolStatus
import net.robog.clarity.shared.session.Settings
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

/** A host that serves [servers] and answers every call with [answer]; never the network. */
class FakeMcpHost(var servers: List<HostServer>) : McpHost {
    data class Call(val server: String, val tool: String, val input: JsonObject)

    val calls = CopyOnWriteArrayList<Call>()
    var listings = 0
    var reachable = true
    var answer: (Call) -> ToolResult = { ToolResult.Ok("${it.tool} done") }

    override suspend fun servers(): List<HostServer> {
        if (!reachable) throw java.io.IOException("down")
        listings++
        return servers
    }

    override suspend fun call(serverId: String, tool: String, input: JsonObject): ToolResult =
        Call(serverId, tool, input).also { calls += it }.let(answer)
}

private fun github() = hostServer("gh", "GitHub", readTool("list_issues"), writeTool("create_issue"), writeTool("delete_repo"))

private fun canvas() = hostServer("cv", "Canvas", readTool("list_assignments"))

private fun ok(result: ToolResult): String = result.shouldBeInstanceOf<ToolResult.Ok>().content

private fun err(result: ToolResult): String = result.shouldBeInstanceOf<ToolResult.Error>().message

private fun text(m: Message) = m.content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }

private fun helperScope() = RunScope(UUID.randomUUID().toString(), isLead = false)

private suspend fun Tool.call(vararg pairs: Pair<String, String>): ToolResult = invoke(buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } })

/** Third-party MCP servers against real Postgres, a fake host and a scripted model. */
@EnabledIf(ContainerRuntimeAvailable::class)
class McpDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database
    val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec {
        background.cancel()
        postgres.stop()
    }

    suspend fun rig(host: FakeMcpHost, wait: kotlin.time.Duration = kotlin.time.Duration.ZERO) =
        LeadRig(database, mcpHost = host, mcpWait = wait).also { it.reset() }

    fun LeadRig.decisions() = HeldActionService(database, Clock.System, heldStore, constitutions, outbox, ConnectorRouter(database, NoConnectorPreferences()), events, vaultStore)

    /** The person lets external tools run alone, so only the tool's own mode can hold a call. */
    suspend fun LeadRig.letToolsRunAlone() {
        constitutions.create(Constitution(version = 1, lines = emptyList(), autonomy = mapOf(ActionKind.EXTERNAL_TOOL to AutonomyTier.DO_THEN_TELL), updatedAt = Clock.System.now()))
    }

    test("a server listed for the first time gets its collection, once; a later relabel or listing makes no other") {
        val host = FakeMcpHost(listOf(github()))
        val r = rig(host)
        r.mcpCatalog.servers().single().collection shouldBe "github"
        r.mcpCatalog.servers()
        host.servers = listOf(github().copy(label = "GH Work"), canvas())
        val servers = r.mcpCatalog.servers()
        host.listings shouldBe 3
        servers.map { it.collection } shouldContainExactly listOf("github", "canvas")
        r.collections.collections().map { it.name } shouldContainExactly listOf("canvas", "github")
        r.collections.find("github").shouldNotBeNull().description shouldBe "Notes for using GitHub: names, IDs, formatting rules, how to find what is coming up"
        r.mcpCatalog.collectionNames() shouldContainExactly listOf("canvas", "github")
    }

    test("the host listing is kept while the host is down") {
        val host = FakeMcpHost(listOf(github()))
        val r = rig(host)
        r.mcpCatalog.servers() shouldHaveSize 1
        host.reachable = false
        r.mcpCatalog.servers() shouldHaveSize 1
        // never reachable: the app is told so
        val fresh = rig(FakeMcpHost(emptyList()).also { it.reachable = false })
        fresh.mcpCatalog.list() shouldBe null
        rig(FakeMcpHost(emptyList())).mcpCatalog.list() shouldBe McpServerList(emptyList())
    }

    test("a helper gets only the servers named, never off tools, with notes limited to their collections; the lead gets none") {
        val host = FakeMcpHost(listOf(github(), canvas()))
        val r = rig(host)
        r.mcpCatalog.setMode("gh", "delete_repo", McpToolMode.OFF)
        val scope = helperScope()
        val none = r.toolbox.helperTools(scope, null).tools.tools.map { it.name }
        none.filter { it.startsWith("mcp_") }.shouldBeEmpty()
        val names = r.toolbox.helperTools(scope, null, listOf("gh")).tools.tools.map { it.name }
        names.filter { it.startsWith("mcp_") } shouldContainExactly listOf("mcp_github__create_issue", "mcp_github__list_issues")
        names shouldContain "save_note"
        names shouldContain "open_collection"
        // a narrowed helper still gets the server's notes
        val narrow = r.toolbox.helperTools(scope, listOf("read_journal"), listOf("cv")).tools.tools.map { it.name }
        narrow shouldContainExactly listOf("mcp_canvas__list_assignments", "open_collection", "read_journal", "save_note", "search_memory")
        val saveNote = r.toolbox.helperTools(scope, null, listOf("gh")).tools.tools.first { it.name == "save_note" }
        err(saveNote.call("collection" to "canvas", "text" to "Course ENGL 201 is id 77.")) shouldContain "only in github"
        ok(saveNote.call("collection" to "github", "text" to "The person's main repo is robog-two/clarity.")) shouldContain "in github"
        err(saveNote.call("collection" to "github", "text" to "The password: hunter2xyz")) shouldContain "Not saved"
        val lead = r.toolbox.leadTools(RunScope("lead", isLead = true)).tools.map { it.name }
        lead.filter { it.startsWith("mcp_") }.shouldBeEmpty()
        lead shouldContain "suggest_mcp_server"
        r.toolbox.resolveServers(listOf("github", "Nope")).leftOrNull().shouldNotBeNull() shouldContain "There is no MCP server \"Nope\""
    }

    test("an auto tool runs at once; its result is quoted as untrusted and taints the run") {
        val host = FakeMcpHost(listOf(github()))
        host.answer = { ToolResult.Ok("#3 Fix login") }
        val r = rig(host)
        val scope = helperScope()
        val list = r.toolbox.helperTools(scope, null, listOf("gh")).tools.tools.first { it.name == "mcp_github__list_issues" }
        list.description shouldStartWith "[GitHub] "
        scope.untrusted shouldBe false
        ok(list.call("repo" to "clarity")) shouldBe "<untrusted source=\"mcp:GitHub\">#3 Fix login</untrusted>"
        scope.untrusted shouldBe true
        host.calls.single() shouldBe FakeMcpHost.Call("gh", "list_issues", buildJsonObject { put("repo", "clarity") })
    }

    test("an auto read passes only the person's hard limits, with no model call: within them it runs, never blocks it, never-alone holds it") {
        val host = FakeMcpHost(listOf(hostServer("st", "Stripe", readTool("list_payments"), readTool("list_customers"))))
        val r = rig(host)
        // the guard model would refuse everything: a read never asks it
        r.guardVerdict = net.robog.clarity.shared.governance.GuardVerdict(net.robog.clarity.shared.governance.Decision.DENY, emptyList(), "model says no")
        suspend fun tools() = r.toolbox.helperTools(helperScope(), null, listOf("st"))
        suspend fun tool(name: String) = tools().tools.tools.first { it.name == name }
        ok(tool("mcp_stripe__list_customers").call()) shouldContain "list_customers done"
        host.calls shouldHaveSize 1
        r.heldStore.list().shouldBeEmpty()
        // payments are ruled out: the read is blocked and not sent
        r.constitutions.create(
            Constitution(
                version = 1, lines = emptyList(),
                autonomy = mapOf(ActionKind.PAYMENT to AutonomyTier.NEVER, ActionKind.EXTERNAL_TOOL to AutonomyTier.NEVER_ALONE),
                updatedAt = Clock.System.now(),
            ),
        )
        ok(tool("mcp_stripe__list_payments").call()) shouldContain "Blocked"
        r.heldStore.list(setOf(HeldAction.Status.BLOCKED)).single().action.summary shouldBe "Stripe: list_payments"
        // never alone: held for the phone, not sent
        ok(tool("mcp_stripe__list_customers").call()) shouldContain "has not answered"
        r.heldStore.list(setOf(HeldAction.Status.HELD)).single().verdict.phoneApproval shouldBe true
        host.calls shouldHaveSize 1
    }

    test("a write tool the person set to Auto goes through the full guard: allowed it runs, otherwise it is held") {
        val host = FakeMcpHost(listOf(github()))
        val r = rig(host)
        r.letToolsRunAlone()
        r.mcpCatalog.setMode("gh", "create_issue", McpToolMode.AUTO)
        suspend fun create() = r.toolbox.helperTools(helperScope(), null, listOf("gh")).tools.tools.first { it.name == "mcp_github__create_issue" }
        create().description shouldNotContain "waits for the person's approval"
        ok(create().call("title" to "a")) shouldContain "create_issue done"
        host.calls shouldHaveSize 1
        r.heldStore.list().shouldBeEmpty()
        r.guardVerdict = net.robog.clarity.shared.governance.GuardVerdict(net.robog.clarity.shared.governance.Decision.ASK, emptyList(), "the model wants a look")
        ok(create().call("title" to "b")) shouldContain "has not answered"
        r.heldStore.list(setOf(HeldAction.Status.HELD)).single().verdict.rationale shouldContain "the model wants a look"
        host.calls shouldHaveSize 1
        // a server claiming a write is read-only is not believed: it asks by default
        val liar = FakeMcpHost(listOf(hostServer("x", "Liar", parseHostTool(ClarityJson.parseToJsonElement("""{"name":"delete_everything","annotations":{"readOnlyHint":true}}"""))!!)))
        val r2 = rig(liar)
        r2.mcpCatalog.servers().single().tools.single().mode shouldBe McpToolMode.ASK
    }

    test("a call held because the run read outside content says so in terms of the person's own server") {
        val r = rig(FakeMcpHost(listOf(github())))
        r.letToolsRunAlone()
        val scope = helperScope()
        val tools = r.toolbox.helperTools(scope, null, listOf("gh")).tools.tools
        ok(tools.first { it.name == "mcp_github__list_issues" }.call("repo" to "clarity"))
        scope.untrusted shouldBe true
        ok(tools.first { it.name == "mcp_github__create_issue" }.call("title" to "From the list")) shouldContain "has not answered"
        r.heldStore.list(setOf(HeldAction.Status.HELD)).single().verdict.rationale shouldBe
            "I want to use create_issue on GitHub. It changes something there, and what I'm sending was shaped by outside content (results from GitHub or a web page)."
    }

    test("an ask tool is held even when the person's rules would allow it; it runs once on approval, and the next call asks again") {
        val host = FakeMcpHost(listOf(github()))
        host.answer = { ToolResult.Ok("created #7") }
        val r = rig(host, wait = 30.seconds)
        r.letToolsRunAlone()
        val scope = helperScope()
        val create = r.toolbox.helperTools(scope, null, listOf("gh")).tools.tools.first { it.name == "mcp_github__create_issue" }
        create.description shouldContain "waits for the person's approval"
        val pending = background.async { create.call("repo" to "clarity", "title" to "Fix login") }
        val held = withTimeout(10.seconds) {
            var h: HeldAction? = null
            while (h == null) { h = r.heldStore.list(setOf(HeldAction.Status.HELD)).singleOrNull(); if (h == null) delay(50) }
            h
        }
        val action = held.action.shouldBeInstanceOf<ProposedAction.ExternalTool>()
        action.server shouldBe "mcp:gh"
        action.tool shouldBe "create_issue"
        action.summary shouldBe "GitHub: create_issue (repo: clarity, title: Fix login)"
        held.verdict.rationale shouldContain "you approve every call of create_issue on GitHub"
        held.context.map { it.tag } shouldContainExactly listOf("server", "tool", "input")
        host.calls.shouldBeEmpty()
        // the helper waits on it, so the lead is not woken for it
        withTimeout(5.seconds) { while (r.heldWaiters.helperFor(held.id) == null) delay(20) }
        r.heldWaiters.helperFor(held.id) shouldBe scope.runId
        r.decisions().decide(held.id, HeldDecision.Approve()).isRight() shouldBe true
        val result = ok(pending.await())
        result shouldContain "The person approved it"
        result shouldContain "<untrusted source=\"mcp:GitHub\">created #7</untrusted>"
        host.calls shouldHaveSize 1
        r.heldStore.get(held.id)?.status shouldBe HeldAction.Status.EXECUTED
        // the yes was for that call only
        val again = background.async { create.call("repo" to "clarity", "title" to "Fix login") }
        val second = withTimeout(10.seconds) {
            var h: HeldAction? = null
            while (h == null) { h = r.heldStore.list(setOf(HeldAction.Status.HELD)).singleOrNull(); if (h == null) delay(50) }
            h
        }
        second.id shouldNotBe held.id
        r.decisions().decide(second.id, HeldDecision.Decline).isRight() shouldBe true
        ok(again.await()) shouldContain "did not approve"
        host.calls shouldHaveSize 1
    }

    test("a yes after the helper stopped waiting: the backend runs the call once and the result reaches the lead's inbox") {
        val host = FakeMcpHost(listOf(github()))
        host.answer = { ToolResult.Ok("created #8") }
        val r = rig(host)
        val scope = helperScope()
        val create = r.toolbox.helperTools(scope, null, listOf("gh")).tools.tools.first { it.name == "mcp_github__create_issue" }
        ok(create.call("repo" to "clarity", "title" to "Docs")) shouldContain "has not answered"
        val held = r.heldStore.list(setOf(HeldAction.Status.HELD)).single()
        val approvals = McpApprovals(r.events, r.mcpCalls, lazy { r.helpers }, r.inbox)
        val job = background.launch { approvals.run() }
        delay(200) // the collector is subscribed
        r.decisions().decide(held.id, HeldDecision.Approve()).isRight() shouldBe true
        withTimeout(10.seconds) { while (r.inbox.pending().none { it.item is InboxItem.McpCallResult }) delay(50) }
        job.cancelAndJoin()
        val item = r.inbox.pending().map { it.item }.filterIsInstance<InboxItem.McpCallResult>().single()
        item.server shouldBe "GitHub"
        item.ok shouldBe true
        item.untrusted.single().content shouldContain "created #8"
        host.calls shouldHaveSize 1
        // a second delivery of the same yes runs nothing
        approvals.deliver(r.heldStore.get(held.id).shouldNotBeNull())
        host.calls shouldHaveSize 1
        // the forwarder does not also wake the lead with the bare approval
        r.inbox.pending().map { it.item }.filterIsInstance<InboxItem.HeldDecided>().shouldBeEmpty()
    }

    test("the lead sees the server index once, again when it changes, and a note when the servers are gone") {
        val host = FakeMcpHost(listOf(github()))
        val r = rig(host)
        suspend fun turn(n: Int): String {
            r.inbox.enqueue(InboxItem.UserInput(ClientInputId("u$n"), "hi $n"))
            r.lead.step()
            return r.model.requests.last().messages.joinToString("\n") { text(it) }
        }
        r.lead.start()
        val first = turn(1)
        first shouldContain "[mcp servers] The person's MCP servers. You cannot use them yourself"
        first shouldContain "- GitHub: Your GitHub things"
        turn(2).split(MCP_INDEX_PREFIX).size shouldBe 2
        host.servers = listOf(github(), canvas())
        val third = turn(3)
        third.split(MCP_INDEX_PREFIX).size shouldBe 3
        third shouldContain "- Canvas: Your Canvas things"
        host.servers = emptyList()
        turn(4) shouldContain NO_MCP_SERVERS
        turn(5).split(NO_MCP_SERVERS).size shouldBe 2
    }

    test("delegate(servers) starts a helper with those servers' tools, kept for a resume; an unknown one is refused") {
        val host = FakeMcpHost(listOf(github(), canvas()))
        val r = rig(host)
        val job = background.launch { r.helpers.run() }
        try {
            r.model.handler = { request ->
                if (request.model == HELPER_MODEL) end("done") else end("ok")
            }
            val delegate = r.toolbox.leadTools(RunScope("lead", isLead = true)).tools.first { it.name == "delegate" }
            val started = delegate.invoke(
                buildJsonObject {
                    put("task", "List the open issues in robog-two/clarity and report the titles.")
                    put("servers", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("github"))))
                },
            )
            val text = ok(started)
            text shouldContain "It has the tools of github"
            val run = r.runs.list(openOnly = false).single()
            run.servers shouldContainExactly listOf("gh")
            withTimeout(20.seconds) { while (r.runs.get(run.id)?.status in RunStatus.OPEN) delay(50) }
            val tools = r.model.requestsFor(HELPER_MODEL).first().tools.map { it.name }
            tools shouldContain "mcp_github__list_issues"
            tools.filter { it.startsWith("mcp_canvas") }.shouldBeEmpty()
            val refused = delegate.invoke(
                buildJsonObject {
                    put("task", "Check Asana.")
                    put("servers", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("Asana"))))
                },
            )
            err(refused) shouldContain "There is no MCP server \"Asana\" (available: GitHub, Canvas)"
        } finally {
            job.cancelAndJoin()
        }
    }

    test("the morning sweep: auto tools only, its caps, the coming-up note, no chat post and no inbox item") {
        val host = FakeMcpHost(listOf(github(), canvas()))
        host.answer = { ToolResult.Ok("Essay 2 due Thu") }
        val r = rig(host)
        val job = background.launch { r.helpers.run() }
        try {
            var calls = 0
            r.model.handler = { request ->
                val results = request.messages.flatMap { it.content }.filterIsInstance<Block.Result>().size
                calls++
                when {
                    results < 13 -> callTool("c$results", "mcp_canvas__list_assignments", buildJsonObject { put("course", "ENGL 201") })
                    results == 13 -> callTool("s", "set_scratch", buildJsonObject { put("text", "Canvas: Essay 2 due Thu 23:59\nGitHub: nothing due") })
                    else -> end("2 items")
                }
            }
            val sweep = McpSweepJob(r.mcpKit, r.helpers, JobSlots(database, Clock.System))
            val now = Clock.System.now()
            sweep.fire(FireContext(now, now, null, Settings()))
            // the same slot again starts nothing
            sweep.fire(FireContext(now, now, null, Settings()))
            val run = r.runs.list(openOnly = false).single()
            run.profile shouldBe MCP_SWEEP_PROFILE
            withTimeout(30.seconds) { while (r.runs.get(run.id)?.status in RunStatus.OPEN) delay(50) }
            val request = r.model.requestsFor(HELPER_MODEL).first()
            request.tools.map { it.name } shouldContainExactly listOf("mcp_canvas__list_assignments", "mcp_github__list_issues", "open_collection", "save_note", "set_scratch")
            text(request.messages.first()) shouldContain "Find what is coming up for the person"
            // 12 calls ran; the 13th was refused
            host.calls shouldHaveSize MCP_SWEEP_MAX_TOOL_CALLS
            val results = r.journal.load(run.id).flatMap { it.content }.filterIsInstance<Block.Result>()
            results.single { it.toolUseId == "c12" }.content shouldContain "used your $MCP_SWEEP_MAX_TOOL_CALLS tool calls"
            val note = r.memory.liveScratch(Clock.System.now()).single()
            note.key shouldBe COMING_UP_KEY
            note.text shouldBe "Canvas: Essay 2 due Thu 23:59\nGitHub: nothing due"
            (note.expiresAt - Clock.System.now()).inWholeHours shouldBe 19L
            r.runs.get(run.id)?.status shouldBe RunStatus.DONE
            r.inbox.pending().shouldBeEmpty()
            r.feedStore.page(0, 100).items.shouldBeEmpty()
        } finally {
            job.cancelAndJoin()
        }
    }

    test("the sweep uses only read-only auto tools and never holds: what the person's limits would hold is skipped") {
        val faked = parseHostTool(ClarityJson.parseToJsonElement("""{"name":"delete_y","annotations":{"readOnlyHint":true}}""")).shouldNotBeNull()
        val host = FakeMcpHost(listOf(hostServer("gh", "GitHub", readTool("list_a"), writeTool("create_x"), faked), hostServer("w", "Writer", writeTool("post_z"))))
        val r = rig(host)
        r.mcpCatalog.setMode("gh", "create_x", McpToolMode.AUTO)
        r.mcpCatalog.setMode("gh", "delete_y", McpToolMode.AUTO)
        r.mcpCatalog.setMode("w", "post_z", McpToolMode.AUTO)
        // a server whose only auto tool writes is not swept
        r.mcpKit.sweepable().map { it.id } shouldContainExactly listOf("gh")
        val scope = helperScope()
        val tools = r.toolbox.sweepTools(scope, listOf("gh", "w"), 0).tools
        tools.map { it.name }.filter { it.startsWith("mcp_") } shouldContainExactly listOf("mcp_github__list_a")
        val list = tools.first { it.name == "mcp_github__list_a" }
        ok(list.call()) shouldContain "list_a done"
        // never alone: skipped, with no approval request and no wait
        r.constitutions.create(Constitution(version = 1, lines = emptyList(), autonomy = mapOf(ActionKind.EXTERNAL_TOOL to AutonomyTier.NEVER_ALONE), updatedAt = Clock.System.now()))
        err(list.call()) shouldContain "Skipped: needs approval"
        r.heldStore.list().shouldBeEmpty()
        host.calls shouldHaveSize 1
    }

    test("the sweep stops at its spend cap, and is skipped when no server has a tool that runs without asking") {
        val host = FakeMcpHost(listOf(github()))
        val r = rig(host)
        val job = background.launch { r.helpers.run() }
        try {
            r.model.handler = { _ ->
                ModelResponse(listOf(Block.ToolUse("t${UUID.randomUUID()}", "mcp_github__list_issues", buildJsonObject { })), StopReason.TOOL_USE, Usage(100, 10, reportedCostMicros = 20_000))
            }
            val sweep = McpSweepJob(r.mcpKit, r.helpers, JobSlots(database, Clock.System))
            val now = Clock.System.now()
            sweep.fire(FireContext(now, now, null, Settings()))
            val run = r.runs.list(openOnly = false).single()
            withTimeout(30.seconds) { while (r.runs.get(run.id)?.status in RunStatus.OPEN) delay(50) }
            r.runs.get(run.id)?.result.orEmpty() shouldContain "spend limit"
            r.model.requestsFor(HELPER_MODEL).size shouldBe 3
            // every tool now asks: no sweep
            r.mcpCatalog.setMode("gh", "list_issues", McpToolMode.ASK)
            val later = now + 1.seconds
            sweep.fire(FireContext(later, later, null, Settings()))
            r.runs.list(openOnly = false) shouldHaveSize 1
            // the call switched off: no sweep either
            r.mcpCatalog.setMode("gh", "list_issues", McpToolMode.AUTO)
            val off = later + 1.seconds
            sweep.fire(FireContext(off, off, null, Settings(morningCall = Settings.MorningCall(enabled = false))))
            r.runs.list(openOnly = false) shouldHaveSize 1
        } finally {
            job.cancelAndJoin()
        }
    }

    test("the sweep's note must name each line's server and fit in 1,200 characters") {
        val r = rig(FakeMcpHost(listOf(github())))
        val scratch = r.mcpKit.comingUp(r.mcpCatalog.servers())
        err(scratch.call("text" to "Essay due Thu")) shouldContain "Start every line with its server's name (GitHub)"
        err(scratch.call("text" to "GitHub: " + "x".repeat(1300))) shouldContain "At most 1200 characters"
        ok(scratch.call("text" to "GitHub: PR 12 review due Fri"))
        ok(scratch.call("text" to "GitHub: PR 13 review due Sat"))
        r.memory.liveScratch(Clock.System.now()).map { it.text } shouldContainExactly listOf("GitHub: PR 13 review due Sat")
    }

    test("suggestions: one open per label, three a week even when dismissed, never for a server already set up") {
        val r = rig(FakeMcpHost(listOf(github())))
        val suggest = r.toolbox.leadTools(RunScope("lead", isLead = true)).tools.first { it.name == "suggest_mcp_server" }
        ok(suggest.call("label" to "Canvas", "why" to "Your assignments and due dates.", "preset" to "Canvas LMS")) shouldContain "Suggested Canvas"
        err(suggest.call("label" to "canvas", "why" to "Again.")) shouldContain "already suggested"
        err(suggest.call("label" to "GitHub", "why" to "Code.")) shouldContain "set up already"
        val canvasId = r.mcpStore.suggestions().single().id
        r.mcpStore.deleteSuggestion(canvasId) shouldBe true
        ok(suggest.call("label" to "Asana", "why" to "Your tasks."))
        ok(suggest.call("label" to "Notion", "why" to "Your notes."))
        err(suggest.call("label" to "Todoist", "why" to "Your todos.")) shouldContain "the limit"
        r.mcpStore.suggestions().map { it.label } shouldContainExactly listOf("Notion", "Asana")
        r.feedStore.page(0, 100).items.shouldBeEmpty()
    }

    test("in production only the refresher lists the host: reads never wait on it; at boot it retries until the host answers") {
        val host = FakeMcpHost(listOf(github()))
        host.reachable = false
        val r = LeadRig(database, mcpHost = host, mcpRefreshOnRead = false).also { it.reset() }
        r.mcpCatalog.servers().shouldBeEmpty()
        r.mcpCatalog.list() shouldBe null
        r.mcpCatalog.leadIndex() shouldBe null
        host.listings shouldBe 0
        val refresher = McpRefresher(r.mcpCatalog, every = 100.milliseconds, bootAttempts = 50, bootPause = 20.milliseconds)
        val job = background.launch { refresher.run() }
        try {
            delay(100)
            r.mcpCatalog.servers().shouldBeEmpty()
            host.reachable = true
            withTimeout(5.seconds) { while (r.mcpCatalog.servers().isEmpty()) delay(20) }
            r.mcpCatalog.servers().single().label shouldBe "GitHub"
            // a later change arrives with the next refresh, never with a read
            host.servers = listOf(github(), canvas())
            val before = host.listings
            r.mcpCatalog.servers() shouldHaveSize 1
            host.listings shouldBe before
            withTimeout(5.seconds) { while (r.mcpCatalog.servers().size < 2) delay(20) }
            // the host goes down: the last listing stays
            host.reachable = false
            delay(300)
            r.mcpCatalog.servers() shouldHaveSize 2
        } finally {
            job.cancelAndJoin()
        }
    }

    test("after a restart: a recent approval runs once into the lead's inbox; an old one expires and never runs") {
        val host = FakeMcpHost(listOf(github()))
        host.answer = { ToolResult.Ok("created #${it.input["title"]}") }
        val r = rig(host)
        val scope = helperScope()
        val create = r.toolbox.helperTools(scope, null, listOf("gh")).tools.tools.first { it.name == "mcp_github__create_issue" }
        ok(create.call("repo" to "clarity", "title" to "recent")) shouldContain "has not answered"
        ok(create.call("repo" to "clarity", "title" to "old")) shouldContain "has not answered"
        val heldItems = r.heldStore.list(setOf(HeldAction.Status.HELD))
        val recent = heldItems.single { it.action.summary.contains("recent") }
        val old = heldItems.single { it.action.summary.contains("old") }
        // both approved while the backend was down for the calls (nothing listening)
        r.decisions().decide(recent.id, HeldDecision.Approve()).isRight() shouldBe true
        r.decisions().decide(old.id, HeldDecision.Approve()).isRight() shouldBe true
        database.tx { exec("UPDATE held_action SET decided_at = now() - interval '2 hours' WHERE id = '${old.id.value}'") }
        host.calls.shouldBeEmpty()
        // the restart: a fresh service over the same database
        val approvals = McpApprovals(r.events, r.mcpCalls, lazy { r.helpers }, r.inbox)
        approvals.recoverAtBoot()
        host.calls.map { it.input["title"].toString() } shouldContainExactly listOf("\"recent\"")
        r.heldStore.get(recent.id)?.status shouldBe HeldAction.Status.EXECUTED
        r.heldStore.get(old.id)?.status shouldBe HeldAction.Status.EXPIRED
        val item = r.inbox.pending().map { it.item }.filterIsInstance<InboxItem.McpCallResult>().single()
        item.heldId shouldBe recent.id.value
        item.untrusted.single().content shouldContain "created #\"recent\""
        // a second boot runs nothing
        approvals.recoverAtBoot()
        host.calls shouldHaveSize 1
        r.inbox.pending().map { it.item }.filterIsInstance<InboxItem.McpCallResult>() shouldHaveSize 1
    }

    test("routes: servers with modes, a tool's mode set and reset, suggestions listed and dismissed; all need a device") {
        val r = rig(FakeMcpHost(listOf(github())))
        r.mcpStore.addSuggestion("Canvas", "Your assignments.", null, Clock.System.now())
        testApplication {
            val client = routes(r)
            client.get(McpPaths.SERVERS).status shouldBe HttpStatusCode.Unauthorized
            client.put(McpPaths.toolPolicy("gh", "create_issue")) { contentType(ContentType.Application.Json); setBody("{}") }.status shouldBe HttpStatusCode.Unauthorized
            client.get(McpPaths.SUGGESTIONS).status shouldBe HttpStatusCode.Unauthorized

            val list = client.get(McpPaths.SERVERS) { header(HttpHeaders.Authorization, DEVICE) }.body<McpServerList>()
            val gh = list.servers.single()
            gh.collection shouldBe "github"
            gh.tools.map { it.name to it.mode } shouldContainExactly listOf(
                "list_issues" to McpToolMode.AUTO, "create_issue" to McpToolMode.ASK, "delete_repo" to McpToolMode.ASK,
            )
            gh.tools.first { it.name == "delete_repo" }.destructive shouldBe true

            suspend fun put(tool: String, mode: McpToolMode?) = client.put(McpPaths.toolPolicy("gh", tool)) {
                header(HttpHeaders.Authorization, DEVICE)
                contentType(ContentType.Application.Json)
                setBody(McpToolPolicyUpdate(mode))
            }
            val set = put("create_issue", McpToolMode.AUTO)
            set.status shouldBe HttpStatusCode.OK
            set.body<McpToolStatus>().let { it.mode shouldBe McpToolMode.AUTO; it.overridden shouldBe true }
            r.mcpStore.policies() shouldBe mapOf("gh" to mapOf("create_issue" to McpToolMode.AUTO))
            put("create_issue", null).body<McpToolStatus>().let { it.mode shouldBe McpToolMode.ASK; it.overridden shouldBe false }
            r.mcpStore.policies() shouldBe emptyMap()
            // the app's reset: ClarityJson leaves the null out, so the body is `{}`; an explicit null works too
            suspend fun putRaw(body: String) = client.put(McpPaths.toolPolicy("gh", "create_issue")) {
                header(HttpHeaders.Authorization, DEVICE)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            ClarityJson.encodeToString(McpToolPolicyUpdate.serializer(), McpToolPolicyUpdate(null)) shouldBe "{}"
            for (reset in listOf("{}", """{"mode":null}""")) {
                put("create_issue", McpToolMode.OFF).status shouldBe HttpStatusCode.OK
                r.mcpStore.policies() shouldBe mapOf("gh" to mapOf("create_issue" to McpToolMode.OFF))
                val cleared = putRaw(reset)
                cleared.status shouldBe HttpStatusCode.OK
                cleared.body<McpToolStatus>().let { it.mode shouldBe McpToolMode.ASK; it.overridden shouldBe false }
                r.mcpStore.policies() shouldBe emptyMap()
            }
            put("nope", McpToolMode.OFF).status shouldBe HttpStatusCode.NotFound
            client.put(McpPaths.toolPolicy("zz", "x")) {
                header(HttpHeaders.Authorization, DEVICE); contentType(ContentType.Application.Json); setBody(McpToolPolicyUpdate(McpToolMode.OFF))
            }.status shouldBe HttpStatusCode.NotFound

            val suggestions = client.get(McpPaths.SUGGESTIONS) { header(HttpHeaders.Authorization, DEVICE) }.body<McpSuggestionList>()
            val id = suggestions.suggestions.single().id
            client.delete("${McpPaths.SUGGESTIONS}/$id") { header(HttpHeaders.Authorization, DEVICE) }.status shouldBe HttpStatusCode.NoContent
            client.delete("${McpPaths.SUGGESTIONS}/$id") { header(HttpHeaders.Authorization, DEVICE) }.status shouldBe HttpStatusCode.NotFound
            r.mcpStore.suggestions().shouldBeEmpty()
        }
    }
})

private const val DEVICE = "Bearer device"

private fun ApplicationTestBuilder.routes(r: LeadRig): HttpClient {
    application {
        install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(ClarityJson) }
        install(Authentication) { bearer(AuthProviders.DEVICE) { authenticate { DevicePrincipal(DeviceId("d1"), null) } } }
        McpRoutes(r.mcpCatalog, r.mcpStore).install(this)
    }
    return createClient { install(ContentNegotiation) { json(ClarityJson) } }
}
