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

    suspend fun rig(clock: kotlin.time.Clock = kotlin.time.Clock.System, apiKey: String? = "sk-test"): LeadRig =
        LeadRig(database, clock, apiKey).also { it.reset() }

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

        r.model.requests shouldHaveSize 2
        val second = allText(r.model.requests[1])
        second shouldContain "a background note"
        second shouldContain "USER: move my 3pm to 4pm"
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
        request.deferredTools.map { it.name } shouldBe request.deferredTools.map { it.name }.sorted()
        (request.tools.map { it.name }.containsAll(listOf("send_to_user", "delegate", "remember_fact"))) shouldBe true
        (request.deferredTools.map { it.name }.containsAll(listOf("create_reminder", "move_event"))) shouldBe true
    }

    test("a helper's result comes back to the lead as an inbox item, quoted as untrusted") {
        val r = rig()
        r.model.handler = { request ->
            when {
                request.model == HELPER_MODEL -> end("The contract is in Dana's mail from Tuesday.")
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
        val helperTools = helperRequest.tools.map { it.name } + helperRequest.deferredTools.map { it.name }
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
        val hits = r.own.searchMemory().invoke(args("query" to "budget")) as net.robog.clarity.harness.ToolResult.Ok
        hits.content shouldContain "[journal "
        val feedHit = r.own.searchMemory().invoke(args("query" to "inspection")) as net.robog.clarity.harness.ToolResult.Ok
        feedHit.content shouldContain "[feed "
        (r.own.searchMemory().invoke(args("query" to "zebra")) as net.robog.clarity.harness.ToolResult.Ok).content shouldBe "(no matches)"

        r.inbox.enqueue(user("morning"))
        r.lead.start()
        r.lead.step()
        val seen = allText(r.model.requests.single())
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

    test("idle compaction runs once, only when the person is away, the thread is long and the cache is warm") {
        val clock = MovableClock()
        val r = rig(clock)
        r.model.handler = { request ->
            if (request.compaction?.pauseAfterCompaction == true) {
                net.robog.clarity.harness.ModelResponse(
                    listOf(Block.Raw(buildJsonObject { put("type", "compaction"); put("content", "summary of the day") })),
                    net.robog.clarity.harness.StopReason.COMPACTION,
                    net.robog.clarity.harness.Usage(100, 10),
                )
            } else end("noted")
        }
        r.lead.start()
        r.lead.compactIfIdle() shouldBe false // no turn yet

        r.inbox.enqueue(user("x".repeat(250_000), id = "long"))
        r.lead.step()
        r.lead.compactIfIdle() shouldBe false // the person just spoke

        clock.advance(5.minutes)
        r.lead.compactIfIdle() shouldBe true
        val compaction = r.model.requests.last()
        compaction.compaction!!.pauseAfterCompaction shouldBe true
        compaction.compaction!!.triggerInputTokens shouldBe 50_000
        (r.threads.ensure().compactedAt != null) shouldBe true
        r.lead.compactIfIdle() shouldBe false // already compacted since the last turn

        // and a person's message after compaction continues from it
        r.inbox.enqueue(user("what did I miss?"))
        r.lead.step()
        r.model.requests.last().messages.any { m -> m.content.any { it is Block.Raw && it.isCompaction } } shouldBe true
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
