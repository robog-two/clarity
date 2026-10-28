package net.robog.clarity.backend.agent

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Role
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.HeldAction
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

private fun userText(m: net.robog.clarity.harness.Message) = m.content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
private fun allText(request: net.robog.clarity.harness.ModelRequest) = request.messages.joinToString("\n---\n") { "${it.role}: ${userText(it)}" }

private fun reply(id: String, text: String) = callTool(
    id, "send_to_user",
    buildJsonObject { put("cards", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", text) }))) },
)

/** The lead's reply nudge is the last thing the model sees. */
private fun isNudge(request: net.robog.clarity.harness.ModelRequest) =
    request.messages.last().let { it.role == Role.SYSTEM && userText(it) == REPLY_NUDGE }

private fun lastResultFor(request: net.robog.clarity.harness.ModelRequest, id: String) =
    request.messages.last().content.any { it is Block.Result && it.toolUseId == id }

/**
 * The lead against real Postgres with a scripted model: pre-emption, batching, helpers, gated
 * external tools, crash-resume, memory, compaction. The model is never a real one.
 */
@EnabledIf(ContainerRuntimeAvailable::class)
class LeadScenarioTest : FunSpec({
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

    suspend fun rig(
        clock: kotlin.time.Clock = kotlin.time.Clock.System,
        apiKey: String? = "sk-test",
        roles: net.robog.clarity.backend.config.AgentRoles = TEST_ROLES,
    ): LeadRig = LeadRig(database, clock, apiKey, roles).also { it.reset() }

    fun user(text: String, id: String = text) = InboxItem.UserInput(ClientInputId(id), text)

    test("user input pre-empts a background turn: the model call is cancelled and the person's words go in next") {
        val r = rig()
        val started = CompletableDeferred<Unit>()
        r.model.handler = { request ->
            if (r.model.requests.size == 1) {
                started.complete(Unit)
                awaitCancellation()
            } else {
                end("on it")
            }
        }
        r.inbox.enqueue(InboxItem.System("a background note"))
        r.lead.start()
        val turn = scope.async { r.lead.step() }
        withTimeout(10.seconds) { started.await() }
        r.inbox.enqueue(user("move my 3pm to 4pm"))
        withTimeout(30.seconds) { turn.await() } shouldBe true

        // the person's words came in mid-turn and "on it" never reached them: one reply nudge follows
        r.model.requests shouldHaveSize 3
        isNudge(r.model.requests[2]) shouldBe true
        val second = allText(r.model.requests[1])
        second shouldContain "a background note"
        // the person's words go in a user message ahead of the operator update, never after it (the API refuses that)
        second shouldContain "USER: (operator update follows)\nmove my 3pm to 4pm"
        net.robog.clarity.harness.systemPlacementViolations(r.model.requests[1].messages) shouldBe emptyList()
        // the cancelled call left nothing behind: no assistant message between the two inputs
        r.model.requests[1].messages.count { it.role == Role.ASSISTANT } shouldBe 0
        r.inbox.pending().shouldBeEmpty()
    }

    test("three background events arrive while idle and go into ONE turn as one system message") {
        val r = rig()
        r.inbox.enqueue(InboxItem.TriggerFired("t1", "Dana", "tell me", """{"subject":"contract"}""", "message from connector mail"))
        r.inbox.enqueue(InboxItem.ActionResult("a1", "Send reply to Dana", done = true))
        r.inbox.enqueue(InboxItem.HeldDecided("h1", "declined", "Move the review"))
        r.lead.start()

        r.lead.step() shouldBe true
        r.model.requests shouldHaveSize 1
        val system = r.model.requests.single().messages.filter { it.role == Role.SYSTEM }
        system shouldHaveSize 1
        val text = userText(system.single())
        text shouldContain "Trigger \"Dana\""
        text shouldContain "<untrusted source=\"message from connector mail\">{\"subject\":\"contract\"}</untrusted>"
        text shouldContain "Done: Send reply to Dana"
        text shouldContain "declined"
        r.inbox.pending().shouldBeEmpty()
        r.lead.step() shouldBe false
        // the request is the cache-stable shape: tools sorted, two system blocks with breakpoints
        val request = r.model.requests.single()
        request.tools.map { it.name } shouldBe request.tools.map { it.name }.sorted()
        request.system.map { it.cacheBreakpoint } shouldBe listOf(true, true)
        // every tool is sent with every request (no tool search)
        (request.tools.map { it.name }.containsAll(listOf("send_to_user", "delegate", "remember_fact", "create_reminder", "move_event"))) shouldBe true
        // the lead dispatches: no browser or sandbox tools of its own, and its prompt says to delegate them
        request.tools.none { it.name.contains("__") } shouldBe true
        AgentPrompts.LEAD_SYSTEM shouldContain "You have no browser or sandbox yourself"
    }

    test("a helper's result comes back to the lead as an inbox item, quoted as untrusted") {
        val r = rig()
        r.model.handler = { request ->
            when {
                request.model == HELPER_MODEL -> end("The contract is in Dana's mail from Tuesday.")
                lastResultFor(request, "toolu_ack") -> end("told them")
                isNudge(request) -> reply("toolu_ack", "Looking for it.")
                request.messages.last().content.any { it is Block.Result } -> end("delegated")
                allText(request).contains("find Dana's contract") -> callTool("toolu_d", "delegate", args("task" to "Find Dana's contract in the mail and report where it is."))
                else -> end("noted")
            }
        }
        val helperJob = scope.launch { r.helpers.run() }
        r.inbox.enqueue(user("find Dana's contract"))
        r.lead.start()
        r.lead.step() shouldBe true

        try {
            withTimeout(20.seconds) { while (r.runs.open().isNotEmpty() || r.runs.list(openOnly = false).isEmpty()) delay(50) }
        } catch (e: Exception) {
            println("DEBUG requests=" + r.model.requests.map { it.model + ":" + it.messages.last().content })
            throw e
        }
        r.helpers.joinAll()
        val finished = r.runs.list(openOnly = false).single()
        finished.status shouldBe RunStatus.DONE
        finished.role shouldBe "helper"
        finished.result shouldBe "The contract is in Dana's mail from Tuesday."
        // the helper had its own journal and fresh context, not the lead's
        val helperRequest = r.model.requestsFor(HELPER_MODEL).single()
        helperRequest.system.single().text shouldBe AgentPrompts.HELPER_SYSTEM
        allText(helperRequest) shouldContain "Find Dana's contract"
        val helperTools = helperRequest.tools.map { it.name }
        ("send_to_user" in helperTools) shouldBe false
        ("delegate" in helperTools) shouldBe false

        val pending = r.inbox.pending().single()
        val item = pending.item.shouldBeInstanceOf<InboxItem.HelperResult>()
        item.ok shouldBe true

        r.model.handler = { end("thanks") }
        r.lead.step() shouldBe true
        val last = r.model.requests.last()
        allText(last) shouldContain "<untrusted source=\"helper:${finished.id}\">The contract is in Dana's mail from Tuesday.</untrusted>"
        helperJob.cancelAndJoin()
    }

    test("an external tool never acts: the guard holds it, the tool returns the held text, and the held action exists") {
        val r = rig()
        r.model.handler = { request ->
            if (request.messages.last().content.any { it is Block.Result }) end("held, told the person")
            else callTool(
                "toolu_mail", "send_email",
                buildJsonObject {
                    put("from", "Gmail")
                    put("newThread", true)
                    put("to", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("dana@example.com"))))
                    put("subject", "Contract")
                    put("body", "Signed copy attached.")
                    put("reason", "Dana asked for the signed contract.")
                },
            )
        }
        r.inbox.enqueue(user("send Dana the contract"))
        r.lead.start()
        r.lead.step() shouldBe true

        val held = r.heldStore.list()
        held shouldHaveSize 1
        held.single().status shouldBe HeldAction.Status.HELD
        held.single().agentView shouldBe "Dana asked for the signed contract."
        val result = r.model.requests.last().messages.last().content.filterIsInstance<Block.Result>().single()
        result.content shouldContain "Held for review"
        result.content shouldContain "Don't retry"
        r.outbox.findByKey(held.single().action.idempotencyKey) shouldBe null
        val email = (held.single().action as net.robog.clarity.shared.governance.ProposedAction.SendEmail).email
        email.from?.value shouldBe "me@gmail.example"
        email.fromLabel shouldBe "Gmail"
        held.single().action.summary shouldBe "Send \"Contract\" to dana@example.com from Gmail"
    }

    test("an email from an address that is not one of the person's accounts is a tool error, and nothing is held") {
        val r = rig()
        r.model.handler = { request ->
            if (request.messages.last().content.any { it is Block.Result }) end("asked which account")
            else callTool(
                "toolu_mail", "send_email",
                buildJsonObject {
                    put("from", "sam@work.example")
                    put("newThread", true)
                    put("to", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("dana@example.com"))))
                    put("subject", "Contract")
                    put("body", "Signed copy attached.")
                    put("reason", "Dana asked for the signed contract.")
                },
            )
        }
        r.inbox.enqueue(user("send Dana the contract"))
        r.lead.start()
        r.lead.step() shouldBe true

        r.heldStore.list() shouldHaveSize 0
        val result = r.model.requests.last().messages.last().content.filterIsInstance<Block.Result>().single()
        result.isError shouldBe true
        result.content shouldContain "iCloud <me@icloud.example>, Gmail <me@gmail.example>"
    }

    test("reply guard: the person wrote, the model did the work but ended without send_to_user; one nudge, then it answers") {
        val r = rig()
        r.model.handler = { request ->
            when {
                isNudge(request) -> reply("toolu_reply", "Noted: you're vegetarian.")
                lastResultFor(request, "toolu_reply") -> end("done")
                lastResultFor(request, "toolu_fact") -> end("Saved the fact.")
                else -> callTool("toolu_fact", "remember_fact", args("fact" to "Vegetarian."))
            }
        }
        r.inbox.enqueue(user("I'm vegetarian, remember that"))
        r.lead.start()
        r.lead.step() shouldBe true

        // remember_fact, plain end, the nudged reply, its end: the nudge cost one model step (plus the tool round it asked for)
        r.model.requests shouldHaveSize 4
        r.model.requests.count(::isNudge) shouldBe 1
        // appended at the end, after a placeholder user turn: valid placement, and every earlier message is the previous request's prefix
        val nudged = r.model.requests[2].messages
        net.robog.clarity.harness.systemPlacementViolations(nudged) shouldBe emptyList()
        nudged.subList(0, r.model.requests[1].messages.size) shouldBe r.model.requests[1].messages
        nudged.takeLast(2).map { it.role } shouldBe listOf(Role.USER, Role.SYSTEM)
        val page = r.feed.page(0, 50).items
        page.map { it.author } shouldBe listOf(FeedItem.Author.AGENT)
        page.single().cards shouldBe listOf(Card.Text("Noted: you're vegetarian."))
        r.inbox.pending().shouldBeEmpty()
    }

    test("reply guard: the model answers in the same turn; no nudge") {
        val r = rig()
        r.model.handler = { request ->
            if (lastResultFor(request, "toolu_reply")) end("done") else reply("toolu_reply", "Moved.")
        }
        r.inbox.enqueue(user("move my 3pm to 4pm"))
        r.lead.start()
        r.lead.step() shouldBe true
        r.model.requests shouldHaveSize 2
        r.model.requests.count(::isNudge) shouldBe 0
    }

    test("reply guard: a turn made only of jobs and events is never nudged; silence is fine there") {
        val r = rig()
        r.model.handler = { end("nothing worth saying") }
        r.inbox.enqueue(InboxItem.JobDue("periodic_review", "Look over the day."))
        r.inbox.enqueue(InboxItem.HeldDecided("h1", "declined", "Move the review"))
        r.lead.start()
        r.lead.step() shouldBe true
        r.model.requests shouldHaveSize 1
        r.feed.page(0, 50).items.shouldBeEmpty()
    }

    test("reply guard: a model that ignores the nudge is nudged once, never again, and its plain text is not shown") {
        val r = rig()
        r.model.handler = { end("I would tell them it's done.") }
        r.inbox.enqueue(user("did you get that?"))
        r.lead.start()
        r.lead.step() shouldBe true
        r.model.requests shouldHaveSize 2
        r.model.requests.count(::isNudge) shouldBe 1
        r.feed.page(0, 50).items.shouldBeEmpty()
        // the turn is closed: nothing is resumed or retried
        needsResume(r.journal.load(r.threads.ensure().id)) shouldBe false
        r.lead.step() shouldBe false
        r.model.requests shouldHaveSize 2
    }

    test("reply guard: a send_to_user that failed is not a reply") {
        val r = rig()
        r.model.handler = { request ->
            when {
                isNudge(request) -> reply("toolu_ok", "Here it is.")
                lastResultFor(request, "toolu_bad") || lastResultFor(request, "toolu_ok") -> end("done")
                else -> callTool("toolu_bad", "send_to_user", buildJsonObject { put("cards", JsonArray(emptyList())) })
            }
        }
        r.inbox.enqueue(user("what's next?"))
        r.lead.start()
        r.lead.step() shouldBe true
        r.model.requests.count(::isNudge) shouldBe 1
        r.feed.page(0, 50).items.single().cards shouldBe listOf(Card.Text("Here it is."))
    }

    test("reply guard: a helper that ends with no result text is asked once for it; one that ignores that reports the gap") {
        val r = rig()
        var ignore = false
        r.model.handler = { request ->
            when {
                request.model != HELPER_MODEL -> end("ok")
                !ignore && request.messages.last().let { m -> m.role == Role.USER && userText(m) == HELPER_RESULT_NUDGE } -> end("The contract is in Tuesday's mail.")
                else -> end("")
            }
        }
        val helperJob = scope.launch { r.helpers.run() }
        suspend fun runHelper(): HelperRun {
            r.helpers.delegate("Find the contract.", null, null, RunScope("lead", isLead = true)).isRight() shouldBe true
            withTimeout(20.seconds) { while (r.runs.open().isNotEmpty()) delay(50) }
            r.helpers.joinAll()
            return r.runs.list(openOnly = false).first()
        }
        runHelper().result shouldBe "The contract is in Tuesday's mail."
        r.model.requestsFor(HELPER_MODEL) shouldHaveSize 2

        r.reset()
        r.model.requests.clear()
        ignore = true
        runHelper().result shouldBe "(the helper finished without a result)"
        r.model.requestsFor(HELPER_MODEL) shouldHaveSize 2
        helperJob.cancelAndJoin()
    }

    test("send_to_user: normal items show in the feed, batch items wait for the evening summary") {
        val r = rig()
        var call = 0
        r.model.handler = {
            call++
            when (call) {
                1 -> callTool(
                    "toolu_a", "send_to_user",
                    buildJsonObject {
                        put("cards", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "Dana replied.") })))
                    },
                )
                2 -> callTool(
                    "toolu_b", "send_to_user",
                    buildJsonObject {
                        put("cards", JsonArray(listOf(buildJsonObject { put("type", "note"); put("text", "Newsletter digest ready.") })))
                        put("urgency", "batch")
                    },
                )
                else -> end("done")
            }
        }
        r.inbox.enqueue(user("anything new?"))
        r.lead.start()
        r.lead.step()

        val page = r.feed.page(0, 50).items
        page.map { it.author } shouldBe listOf(FeedItem.Author.AGENT)
        page.single().cards shouldBe listOf(Card.Text("Dana replied."))
        r.feedStore.notifyOf(page.single().id) shouldBe "in_app"
        val waiting = r.feedStore.pendingBatch().single()
        waiting.cards shouldBe listOf(Card.Note("Newsletter digest ready."))
        r.feedStore.notifyOf(waiting.id)!! shouldContain "evening_batch:"
    }

    test("a crash after the model asked for an external action does not repeat it on resume") {
        val first = rig()
        val toolRunning = CompletableDeferred<Unit>()
        first.model.handler = { request ->
            if (request.messages.last().content.any { it is Block.Result }) {
                toolRunning.complete(Unit) // the tool ran and its result was journaled; now the process "dies"
                awaitCancellation()
            } else {
                callTool(
                    "toolu_once", "send_email",
                    buildJsonObject {
                        put("to", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("dana@example.com"))))
                        put("subject", "Contract"); put("body", "Hi"); put("reason", "asked")
                        put("from", "me@icloud.example"); put("newThread", true)
                    },
                )
            }
        }
        first.inbox.enqueue(user("send it"))
        first.lead.start()
        val crashed = scope.launch { first.lead.step() }
        withTimeout(20.seconds) { toolRunning.await() }
        crashed.cancelAndJoin()
        first.heldStore.list() shouldHaveSize 1

        // Worst case: the process died after the tool ran but before its result reached the journal.
        database.tx { exec("DELETE FROM agent_journal WHERE seq = (SELECT max(seq) FROM agent_journal)") }
        val journalNow = first.journal.load(first.threads.ensure().id)
        journalNow.last().role shouldBe Role.ASSISTANT
        journalNow.last().content.single().shouldBeInstanceOf<Block.ToolUse>()

        // A new process: same database, new objects.
        val second = LeadRig(database)
        second.model.handler = { end("held, told the person") }
        second.lead.start()

        second.heldStore.list() shouldHaveSize 1 // not proposed twice
        val resumed = second.model.requests.single().messages.last().content.filterIsInstance<Block.Result>().single()
        resumed.toolUseId shouldBe "toolu_once"
        resumed.content shouldContain "Held for review"
        second.lead.step() shouldBe false
    }

    test("the lead resumes the journal at boot and does not consume inbox rows twice") {
        val r = rig()
        r.inbox.enqueue(user("hello"))
        r.lead.start()
        r.lead.step()
        r.inbox.pending().shouldBeEmpty()
        val again = LeadRig(database)
        again.lead.start() // a finished turn needs no resume
        again.model.requests.shouldBeEmpty()
        again.lead.step() shouldBe false
    }

    test("memory: journal, scratch, full-text search and the thread's memory context") {
        val r = rig()
        r.own.writeJournal().invoke(args("text" to "Decided to postpone the budget review to Friday.")).shouldBeInstanceOf<net.robog.clarity.harness.ToolResult.Ok>()
        r.own.setScratch().invoke(args("text" to "waiting on Dana's reply")).shouldBeInstanceOf<net.robog.clarity.harness.ToolResult.Ok>()
        r.feed.append(net.robog.clarity.backend.feed.FeedDraft(FeedItem.Author.AGENT, listOf(Card.Text("The landlord confirmed the inspection."))))

        val read = r.own.readJournal().invoke(buildJsonObject { }) as net.robog.clarity.harness.ToolResult.Ok
        read.content shouldContain "postpone the budget review"
        val hits = r.memoryTools.searchMemory().invoke(args("query" to "budget")) as net.robog.clarity.harness.ToolResult.Ok
        hits.content shouldContain "[journal "
        val feedHit = r.memoryTools.searchMemory().invoke(args("query" to "inspection")) as net.robog.clarity.harness.ToolResult.Ok
        feedHit.content shouldContain "[feed "
        (r.memoryTools.searchMemory().invoke(args("query" to "zebra")) as net.robog.clarity.harness.ToolResult.Ok).content shouldBe "(no matches)"

        r.inbox.enqueue(user("morning"))
        r.lead.start()
        r.lead.step()
        val seen = allText(r.model.requests.first())
        seen shouldContain "Journal, today:"
        seen shouldContain "postpone the budget review"
        seen shouldContain "waiting on Dana's reply"
        // unchanged memory is not repeated on the next turn
        r.inbox.enqueue(user("again"))
        r.lead.step()
        allText(r.model.requests.last()).split("[memory,").size shouldBe 2
    }

    test("remember_fact saves through a constitution version and respects never-store topics") {
        val r = rig()
        r.constitutions.create(Constitution(version = 1, lines = emptyList(), neverStore = listOf("health"), updatedAt = kotlin.time.Clock.System.now()))
        val remember = r.own.rememberFact()
        (remember.invoke(args("fact" to "Lives in Berlin")) as net.robog.clarity.harness.ToolResult.Ok).content shouldContain "version 2"
        r.constitutions.current()!!.profile shouldBe listOf("Lives in Berlin")
        remember.invoke(args("fact" to "Has a health condition")).shouldBeInstanceOf<net.robog.clarity.harness.ToolResult.Error>()
        (remember.invoke(args("fact" to "lives in berlin")) as net.robog.clarity.harness.ToolResult.Ok).content shouldBe "Already in the profile."
        r.constitutions.current()!!.version shouldBe 2

        val replaced = r.own.replaceProfile().invoke(
            buildJsonObject { put("facts", JsonArray(listOf("Lives in Berlin", "Prefers short replies", "health stuff").map { kotlinx.serialization.json.JsonPrimitive(it) })) },
        ) as net.robog.clarity.harness.ToolResult.Ok
        replaced.content shouldContain "1 -> 2"
        r.constitutions.current()!!.profile shouldBe listOf("Lives in Berlin", "Prefers short replies")
    }

    test("large tool results are stored and read back in slices when the sandbox is not configured") {
        val r = rig()
        val big = "line\n".repeat(10_000)
        val shown = r.large.sink.limit("run-1", "toolu_big", "list_messages", big)
        shown shouldContain "[truncated: 50000 characters in total"
        val id = Regex("result id ([0-9a-f-]{36})").find(shown)!!.groupValues[1]
        val slice = r.large.readResultTool().invoke(buildJsonObject { put("id", id); put("offset", 100); put("length", 10) }) as net.robog.clarity.harness.ToolResult.Ok
        slice.content shouldContain "characters 100-110 of 50000"
        // the same call stored twice keeps one row
        r.large.sink.limit("run-1", "toolu_big", "list_messages", big) shouldContain id
    }

    test("the next turn replays the last one byte for byte from the jsonb journal, with a breakpoint where the last request ended") {
        val r = rig()
        r.model.handler = { request ->
            if (request.messages.last().content.any { it is Block.Result }) end("done")
            // keys in an order jsonb does not keep
            else callTool("toolu_k", "probe", buildJsonObject { put("zeta_long_key", "1"); put("a", "2"); put("mid", "3") })
        }
        r.inbox.enqueue(user("first"))
        r.lead.start()
        r.lead.step() shouldBe true
        r.model.handler = { end("second done") }
        r.inbox.enqueue(user("second"))
        r.lead.step() shouldBe true

        fun wire(request: net.robog.clarity.harness.ModelRequest): List<String> {
            val body = net.robog.clarity.harness.OpenRouterModelGateway.buildBody(request)
            return body["messages"]!!.let { it as JsonArray }.map { message ->
                // Breakpoints move from request to request; they are not part of the cached prefix. A user
                // text with one is sent as a one-part array instead of a string: the same text block upstream.
                val m = message as kotlinx.serialization.json.JsonObject
                val parts = m["content"] as? JsonArray
                val single = (parts?.singleOrNull() as? kotlinx.serialization.json.JsonObject)?.takeIf { it["type"]?.toString() == "\"text\"" }
                val plain = if (single != null) kotlinx.serialization.json.JsonObject(m + ("content" to single["text"]!!)) else m
                plain.toString().replace(Regex(""","cache_control":\{[^}]*\}"""), "")
            }
        }
        val inMemory = r.model.requests[1] // turn 1, after the tool result (history held in memory)
        val reloaded = r.model.requests[2] // turn 2, history loaded from the journal
        val shared = inMemory.messages.size
        val before = wire(inMemory)
        wire(reloaded).take(before.size) shouldBe before
        // the explicit breakpoint sits on the tool result: the end of the previous request's prompt
        val (index, block) = reloaded.messageBreakpoint!!
        reloaded.messages[index].content[block].shouldBeInstanceOf<Block.Result>()
        index shouldBe shared - 1
    }

    test("without an API key the lead stays off and says so once") {
        val r = rig(apiKey = null)
        // With no key the lead waits for one; it says so once however often it is started.
        withTimeoutOrNull(500) { r.lead.run() }
        withTimeoutOrNull(500) { r.lead.run() }
        val items = r.feed.page(0, 10).items
        items shouldHaveSize 1
        items.single().cards.single().shouldBeInstanceOf<Card.Note>()
    }

    test("the feed store: sequence numbers, paging and idempotent client ids") {
        val r = rig()
        val a = r.feed.append(net.robog.clarity.backend.feed.FeedDraft(FeedItem.Author.USER, listOf(Card.Said("hi")), clientInputId = ClientInputId("c1")))
        val again = r.feed.append(net.robog.clarity.backend.feed.FeedDraft(FeedItem.Author.USER, listOf(Card.Said("hi")), clientInputId = ClientInputId("c1")))
        again.created shouldBe false
        again.item.id shouldBe a.item.id
        val b = r.feed.append(net.robog.clarity.backend.feed.FeedDraft(FeedItem.Author.AGENT, listOf(Card.Text("hello"))))
        (b.item.seq > a.item.seq) shouldBe true
        r.feed.page(a.item.seq, 10).items.map { it.id } shouldBe listOf(b.item.id)
        r.feedStore.update(b.item.id, listOf(Card.Text("hello there")), streaming = false)!!.cards shouldBe listOf(Card.Text("hello there"))
    }

    test("inbox enqueueing is idempotent by dedup key") {
        val r = rig()
        r.inbox.enqueue(InboxItem.System("x"), dedupKey = "k") shouldBe true
        r.inbox.enqueue(InboxItem.System("x"), dedupKey = "k") shouldBe false
        r.inbox.pending() shouldHaveSize 1
        r.inbox.markConsumed(r.inbox.pending().map { it.seq })
        r.inbox.enqueue(InboxItem.System("x"), dedupKey = "k") shouldBe false // consumed rows still block replays
    }

    test("the journal keeps order and survives a reload") {
        val r = rig()
        val id = "11111111-1111-1111-1111-111111111111"
        r.journal.append(id, net.robog.clarity.harness.Message.user("one"))
        r.journal.append(id, net.robog.clarity.harness.Message(Role.ASSISTANT, listOf(Block.Text("two"))))
        r.journal.append(id, net.robog.clarity.harness.Message.system("three"))
        r.journal.append("other", net.robog.clarity.harness.Message.user("elsewhere"))
        r.journal.load(id).map { userText(it) } shouldBe listOf("one", "two", "three")
    }
})
