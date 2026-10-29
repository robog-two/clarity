package net.robog.clarity.backend.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.BrowserPilotConfig
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.ingest.InMemoryIngestEventBus
import net.robog.clarity.backend.ingest.Ingested
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.AgentTriggers
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.scheduler.FireContext
import net.robog.clarity.backend.scheduler.JobSlots
import net.robog.clarity.backend.scheduler.MemoryGcJob
import net.robog.clarity.backend.scheduler.Schedule
import net.robog.clarity.backend.scheduler.ScheduledJob
import net.robog.clarity.backend.scheduler.Scheduler
import net.robog.clarity.backend.triggers.ExposedTriggerStore
import net.robog.clarity.backend.triggers.TriggerMatcher
import net.robog.clarity.backend.vault.VaultTools
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Noun
import net.robog.clarity.shared.query.RecordQuery
import net.robog.clarity.shared.query.TriggerSpec
import net.robog.clarity.shared.session.Settings
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.testcontainers.postgresql.PostgreSQLContainer

/** A clock fixed some way ahead of the real one (the trigger limit's "an hour later"). */
private class AheadClock(private val ahead: kotlin.time.Duration) : Clock {
    override fun now(): Instant = Clock.System.now() + ahead
}

/**
 * Write tools and jobs called again with the same thing, as a looping model or a run resumed after
 * a restart does, against real Postgres: each must leave one record, not one per call. The model is
 * scripted and never reached over the network.
 */
@EnabledIf(ContainerRuntimeAvailable::class)
class RunawayGuardsDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database
    lateinit var rig: LeadRig
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
        rig = LeadRig(database)
    }
    afterSpec {
        scope.cancel()
        postgres.stop()
    }
    beforeTest { rig.reset() }

    suspend fun Tool.call(vararg pairs: Pair<String, String>): ToolResult = invoke(buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } })
    fun ToolResult.okText(): String = shouldBeInstanceOf<ToolResult.Ok>().content

    // ---- update_task -------------------------------------------------------------------------

    // Guards against: update_task without an id making a new task every time it is called with the same title.
    test("update_task without an id, repeated with the same title, leaves one open task") {
        val tool = rig.own.updateTask()
        val first = tool.call("title" to "Reply to Dana", "detail" to "mail").okText()
        val second = tool.call("title" to "  reply to   dana. ", "detail" to "mail").okText()
        val third = tool.call("title" to "Reply to Dana").okText()
        val open = rig.tasks.list()
        open shouldHaveSize 1
        val id = open.single().id.value
        first shouldContain id
        second shouldContain id
        second shouldContain "already open"
        third shouldContain "no new task was made"
    }

    // Guards against: the duplicate check swallowing a real new task.
    test("update_task still makes a new task when the one with that title is finished, or the title differs") {
        val tool = rig.own.updateTask()
        val first = rig.tasks.upsert(null, "Book the dentist", "", net.robog.clarity.shared.feed.AgentTask.Status.DONE).shouldNotBeNull()
        tool.call("title" to "Book the dentist").okText() shouldContain "Task "
        tool.call("title" to "Book the vet").okText()
        val all = rig.tasks.list(includeFinished = true)
        all shouldHaveSize 3
        rig.tasks.list().map { it.title }.toSet() shouldBe setOf("Book the dentist", "Book the vet")
        rig.tasks.list().none { it.id == first.id } shouldBe true
    }

    // ---- create_trigger ----------------------------------------------------------------------

    val invoices = TriggerSpec("invoices", Noun.MESSAGE, RecordQuery.Contains("subject", "invoice"), "file it")

    fun specJson(spec: TriggerSpec): JsonObject = ClarityJson.encodeToJsonElement(TriggerSpec.serializer(), spec).jsonObject

    suspend fun triggerRows(): Int = database.tx { AgentTriggers.selectAll().count().toInt() }

    // Guards against: create_trigger making a second identical trigger, so every matching record wakes the lead twice.
    test("create_trigger with the same match as an active trigger returns that one and makes no second") {
        val tool = rig.domain.all.first { it.name == "create_trigger" }
        val first = tool.invoke(specJson(invoices)).okText()
        val again = tool.invoke(specJson(invoices.copy(name = "invoices again", instruction = "file it, really"))).okText()
        triggerRows() shouldBe 1
        again shouldContain "already active"
        val id = Regex("\"id\":\"([^\"]+)\"").find(first)!!.groupValues[1]
        again shouldContain id
    }

    // Guards against: the duplicate check refusing a trigger that matches something else.
    test("create_trigger with a different query or noun makes a new trigger") {
        val tool = rig.domain.all.first { it.name == "create_trigger" }
        tool.invoke(specJson(invoices)).okText()
        tool.invoke(specJson(invoices.copy(query = RecordQuery.Contains("subject", "receipt")))).okText()
        tool.invoke(specJson(invoices.copy(noun = Noun.CALENDAR_EVENT))).okText()
        triggerRows() shouldBe 3
    }

    // ---- nudge -------------------------------------------------------------------------------

    // Guards against: the same nudge being set twice, or the repeat being an error the model then retries.
    test("nudge repeated exactly answers with the nudge already set and makes no second") {
        val tool = rig.nudgeTools.all().first { it.name == "nudge" }
        val at = agentTime(Clock.System.now() + 3.hours, rig.settingsProvider.homeZone())
        val first = tool.call("at" to at, "note" to "ask how the ML report is going").okText()
        val again = tool.call("at" to at, "note" to "Ask how the ML report is going ").okText()
        rig.nudges.pending() shouldHaveSize 1
        again shouldContain rig.nudges.pending().single().id.value
        again shouldContain "already set"
        first shouldContain "Nothing is sent now"
    }

    // ---- write_journal, set_scratch ----------------------------------------------------------

    // Guards against: write_journal adding the same entry once per repeated call.
    test("write_journal repeated with the same text adds one entry") {
        val tool = rig.own.writeJournal()
        tool.call("text" to "Paid the water bill.").okText() shouldContain "saved"
        tool.call("text" to "Paid the water bill.").okText() shouldContain "Already in the journal"
        tool.call("text" to "paid the water bill").okText() shouldContain "Already in the journal"
        val today = journalDate(Clock.System.now(), rig.settingsProvider.current().homeZone)
        rig.memory.journal(today, today) shouldHaveSize 1
        tool.call("text" to "Booked the dentist.").okText() shouldContain "saved"
        rig.memory.journal(today, today) shouldHaveSize 2
    }

    // Guards against: set_scratch adding the same note once per repeated call.
    test("set_scratch repeated with the same text keeps one live note") {
        val tool = rig.own.setScratch()
        tool.call("text" to "Waiting on Dana's reply").okText() shouldContain "Noted"
        tool.call("text" to "Waiting on Dana's reply").okText() shouldContain "Already noted"
        rig.memory.liveScratch(Clock.System.now()) shouldHaveSize 1
    }

    // ---- ask_code ----------------------------------------------------------------------------

    suspend fun codeRequests(): List<HeldAction> = rig.heldStore.list().filter { it.action is ProposedAction.AskCode }

    // Guards against: each repeated ask_code sending the person another urgent request for the same code.
    test("ask_code repeated for the same site while the first waits reuses it: one request, one placeholder") {
        val ask = VaultTools(rig.vaultService, rig.vaultHolds).all(RunScope("r", isLead = true)).first { it.name == "ask_code" }
        val first = ask.call("site" to "bank.example.com", "purpose" to "Log in to the bank").okText()
        val again = ask.call("site" to "https://www.bank.example.com/login", "purpose" to "Log in to the bank, again").okText()
        val held = codeRequests().single()
        first shouldContain "[secret:code_${held.id}]"
        again shouldContain "[secret:code_${held.id}]"
    }

    // Guards against: the reuse hiding a request for another site, or a request already answered.
    test("ask_code for another site, or after the first code was answered, asks anew") {
        val ask = VaultTools(rig.vaultService, rig.vaultHolds).all(RunScope("r", isLead = true)).first { it.name == "ask_code" }
        ask.call("site" to "bank.example.com", "purpose" to "Log in").okText()
        ask.call("site" to "shop.example.org", "purpose" to "Log in").okText()
        codeRequests() shouldHaveSize 2
        val bank = codeRequests().first { (it.action as ProposedAction.AskCode).site == "example.com" }
        rig.heldStore.transition(bank.id, setOf(HeldAction.Status.HELD), HeldAction.Status.APPROVED) shouldBe true
        val fresh = ask.call("site" to "bank.example.com", "purpose" to "Log in again").okText()
        codeRequests() shouldHaveSize 3
        fresh shouldNotBe ""
        (fresh.contains("[secret:code_${bank.id}]")) shouldBe false
    }

    // ---- triggers that feed themselves --------------------------------------------------------

    fun mail(id: Int, from: String = "billing@vendor.example", account: String = "me@icloud.example") = MessagePayload(
        MessagePayload.Channel.EMAIL, Handle.email(account), Handle.email(from), listOf(Handle.email(account)),
        "Invoice $id", "Your invoice number $id", Clock.System.now(),
    )

    suspend fun triggerItems(): Int = rig.inbox.pending().count { it.item is InboxItem.TriggerFired }

    // Guards against: a trigger waking the lead without limit when matching records keep arriving.
    test("one trigger wakes the lead at most its hourly limit; an hour later it fires again") {
        val store = ExposedTriggerStore(database, Clock.System)
        store.create(invoices)
        val config = AgentConfig(reviewInterval = 2.hours, triggerFiresPerHour = 3)
        val matcher = TriggerMatcher(InMemoryIngestEventBus(), store, rig.inbox, rig.settingsProvider, config, Clock.System)
        (1..8).forEach { matcher.handle(Ingested(ConnectorId("mail"), EntityId("m$it"), mail(it), created = true)) }
        triggerItems() shouldBe 3

        val later = TriggerMatcher(InMemoryIngestEventBus(), store, rig.inbox, rig.settingsProvider, config, AheadClock(61.minutes))
        later.handle(Ingested(ConnectorId("mail"), EntityId("m9"), mail(9), created = true))
        triggerItems() shouldBe 4
    }

    // Guards against: the limit of one trigger holding back another trigger.
    test("the hourly limit is per trigger") {
        val store = ExposedTriggerStore(database, Clock.System)
        store.create(invoices)
        store.create(TriggerSpec("all vendor mail", Noun.MESSAGE, RecordQuery.Contains("sender.value", "vendor"), "look"))
        val matcher = TriggerMatcher(InMemoryIngestEventBus(), store, rig.inbox, rig.settingsProvider, AgentConfig(reviewInterval = 2.hours, triggerFiresPerHour = 2), Clock.System)
        (1..5).forEach { matcher.handle(Ingested(ConnectorId("mail"), EntityId("v$it"), mail(it), created = true)) }
        rig.inbox.pending().map { (it.item as InboxItem.TriggerFired).triggerName }.groupingBy { it }.eachCount() shouldBe
            mapOf("invoices" to 2, "all vendor mail" to 2)
    }

    // Guards against: mail the agent sent (from the person's own account) waking the trigger that made it send it.
    test("a message sent from the person's own account fires no trigger") {
        val store = ExposedTriggerStore(database, Clock.System)
        store.create(invoices)
        val matcher = TriggerMatcher(InMemoryIngestEventBus(), store, rig.inbox, rig.settingsProvider, AgentConfig(reviewInterval = 2.hours), Clock.System)
        matcher.handle(Ingested(ConnectorId("mail"), EntityId("sent1"), mail(1, from = "ME@icloud.example"), created = true))
        triggerItems() shouldBe 0
        matcher.handle(Ingested(ConnectorId("mail"), EntityId("in1"), mail(2), created = true))
        triggerItems() shouldBe 1
    }

    // Guards against: the limit's count matching other triggers' keys (LIKE wildcards in the prefix).
    test("countKeyed counts only keys with that exact prefix and only since the given time") {
        rig.inbox.enqueue(InboxItem.JobDue("a", "x"), "trigger:a_b:1")
        rig.inbox.enqueue(InboxItem.JobDue("a", "x"), "trigger:a_b:2")
        rig.inbox.enqueue(InboxItem.JobDue("a", "x"), "trigger:axb:1")
        rig.inbox.enqueue(InboxItem.JobDue("a", "x"), "trigger:a%b:1")
        rig.inbox.countKeyed("trigger:a_b:", Clock.System.now() - 1.hours) shouldBe 2
        rig.inbox.countKeyed("trigger:a%b:", Clock.System.now() - 1.hours) shouldBe 1
        rig.inbox.countKeyed("trigger:a_b:", Clock.System.now() + 1.minutes) shouldBe 0
    }

    // ---- the memory clean-up job --------------------------------------------------------------

    // Guards against: a ownTx claim being rolled back with the transaction around it.
    test("a job slot claim commits even when the transaction around it rolls back") {
        val slots = JobSlots(database, Clock.System)
        val slot = Instant.parse("2026-10-29T03:30:00Z")
        shouldThrow<IllegalStateException> {
            database.tx {
                slots.claim("memory-gc", slot) shouldBe true
                error("the job failed after claiming")
            }
        }
        slots.claim("memory-gc", slot) shouldBe false
        slots.claim("memory-gc", slot + 1.days) shouldBe true
    }

    // Guards against: a memory clean-up slot that is retried after a failure starting a second helper run.
    test("a retried memory clean-up slot starts one helper; the next slot starts another") {
        rig.model.handler = { end("Tidied nothing.") }
        val helperJob = scope.launch { rig.helpers.run() }
        val today = journalDate(Clock.System.now(), rig.settingsProvider.current().homeZone)
        repeat(6) { rig.memory.writeJournal(today, "entry $it") }
        val gc = MemoryGcJob(rig.memory, rig.constitutions, rig.collections, rig.helpers, JobSlots(database, Clock.System))
        // Fails after the helper was started, as a commit or a later write could.
        val failing = object : ScheduledJob {
            override val name = "memory-gc"
            override fun schedule(settings: Settings) = gc.schedule(settings)
            override suspend fun fire(context: FireContext) {
                gc.fire(context)
                error("failed after starting the helper")
            }
        }
        val scheduler = Scheduler(database, setOf(failing), rig.settingsProvider, Clock.System)
        scheduler.ensureRows()
        suspend fun makeDue() = database.tx { ScheduledJobs.update({ ScheduledJobs.name eq "memory-gc" }) { it[nextRunAt] = Clock.System.now() - 1.minutes } }
        // A helper's first model call: its task, and nothing the model said yet.
        fun gcRuns() = rig.model.requestsFor(HELPER_MODEL).count { r ->
            r.messages.none { it.role == net.robog.clarity.harness.Role.ASSISTANT } && r.messages.toString().contains("Tidy the agent's memory")
        }
        suspend fun settle() {
            withTimeout(10.seconds) { while (gcRuns() == 0) delay(20) }
            rig.helpers.joinAll()
        }
        makeDue()
        scheduler.tickOnce() shouldBe true
        settle()
        repeat(2) {
            makeDue()
            scheduler.tickOnce() shouldBe true
            rig.helpers.joinAll()
        }
        gcRuns() shouldBe 1

        // The next slot is a new slot.
        gc.fire(FireContext(Clock.System.now() + 1.days, Clock.System.now(), null, Settings()))
        withTimeout(10.seconds) { while (gcRuns() < 2) delay(20) }
        rig.helpers.joinAll()
        gcRuns() shouldBe 2
        helperJob.cancelAndJoin()
    }

    // ---- browser caps across a restart ----------------------------------------------------------

    // Guards against: a saved run state losing its sign-in progress or codes on the way through the database.
    test("a browser run state round-trips through Postgres and old ones are dropped") {
        val states = PgBrowserRunStates(database, Clock.System)
        val snapshot = BrowserRunSnapshot(
            actions = 17, seq = 40, hadLease = true, taskKey = "k",
            signIn = mapOf("example.com" to SignInState(codesSent = 2, lastSentAt = Instant.parse("2026-10-29T08:00:00Z"), phase = SignInPhase.CODE_SENT, triggers = setOf("Continue"))),
            starters = listOf(SignInStarter("https://example.com/login", "sign in")),
            cookies = mapOf("example.com" to "sid"),
            usedCodes = listOf("h-1"),
        )
        states.save("run-1", snapshot)
        states.load("run-1") shouldBe snapshot
        states.save("run-1", snapshot.copy(actions = 18))
        states.load("run-1")?.actions shouldBe 18
        states.load("run-2").shouldBeNull()
        states.dropOlderThan(Clock.System.now() - 1.days) shouldBe 0
        states.dropOlderThan(Clock.System.now() + 1.minutes) shouldBe 1
        states.load("run-1").shouldBeNull()
    }

    // Guards against: a helper run resumed after a restart getting a fresh set of browser actions.
    test("a pilot rebuilt for the same run (a restart) keeps its action count from Postgres") {
        val states = PgBrowserRunStates(database, Clock.System)
        val leases = MemoryBrowserLeases()
        val before = PilotRig(settings = BrowserPilotConfig(maxActions = 3), leases = leases, runStates = states)
        before.act("the Details button", "x")
        before.call("look")
        before.act("the Details button", "x")
        states.load(before.scope.runId)?.actions shouldBe 2

        val after = PilotRig(settings = BrowserPilotConfig(maxActions = 3), leases = leases, runStates = states)
        after.act("the Details button", "x")
        after.call("look")
        after.act("the Details button", "x") shouldContain "used its 3 actions"
        after.site.clicked.size shouldBe 1
    }
})
