package net.robog.clarity.backend.governance

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.ConnectorPrincipal
import net.robog.clarity.backend.auth.DevicePrincipal
import net.robog.clarity.backend.connectors.ActionRoutes
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.Connectors
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.action.ActionPoll
import net.robog.clarity.shared.action.ActionResult
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.ActivityEntry
import net.robog.clarity.shared.governance.Amendment
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.Decision
import net.robog.clarity.shared.governance.GuardVerdict
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldDecision
import net.robog.clarity.shared.governance.LineId
import net.robog.clarity.shared.governance.ProposeAmendment
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.Urgency
import net.robog.clarity.shared.noun.Noun
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.testcontainers.postgresql.PostgreSQLContainer

/** Real time plus a movable offset: lets tests expire leases without sleeping, while long-polls still work. */
private class TestClock : Clock {
    private val offsetMs = AtomicLong(0)
    fun advance(by: Duration) { offsetMs.addAndGet(by.inWholeMilliseconds) }
    override fun now(): Instant = Clock.System.now() + offsetMs.get().milliseconds
}

private suspend inline fun <reified T : Any> HttpClient.postAsDevice(path: String, body: T): HttpResponse = post(path) {
    header(HttpHeaders.Authorization, "Bearer device"); contentType(ContentType.Application.Json); setBody(body)
}

private const val MAIL = "mail"
private const val DEVICE_ID = "11111111-1111-1111-1111-111111111111"

/**
 * Governance end to end against Postgres: constitution + amendments, the action gate, held-action
 * decisions, the outbox with leases/retries/idempotency, and the HTTP routes over all of it. Only
 * the guard's model and authentication are faked.
 */
@EnabledIf(ContainerRuntimeAvailable::class)
class GovernanceFlowTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database
    val clock = TestClock()
    var modelVerdict = GuardVerdict(Decision.ALLOW, emptyList(), "looks fine")
    val modelCalls = mutableListOf<GuardQuestion>()

    lateinit var events: GovernanceEvents
    lateinit var results: ActionResults
    lateinit var heldStore: HeldActionStore
    lateinit var constitutions: ConstitutionStore
    lateinit var outbox: ActionOutbox
    lateinit var gate: ActionGate
    lateinit var held: HeldActionService
    lateinit var activity: ActivityStore

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(
            net.robog.clarity.backend.config.DatabaseConfig(
                postgres.jdbcUrl, postgres.username, net.robog.clarity.backend.config.Secret(postgres.password), maxPoolSize = 6,
            ),
        )
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)

        database.tx {
            Connectors.insert {
                it[id] = MAIL
                it[provisioned] = true
                it[createdAt] = Clock.System.now()
                it[manifest] = ConnectorManifest(
                    "mail", "Mail", "1", setOf(Noun.MESSAGE),
                    handles = setOf(ActionKind.SAVE_DRAFT, ActionKind.SEND_EMAIL, ActionKind.EXTERNAL_TOOL),
                )
            }
            Connectors.insert {
                it[id] = "other"
                it[provisioned] = true
                it[createdAt] = Clock.System.now()
                it[manifest] = ConnectorManifest("other", "Other", "1", setOf(Noun.MESSAGE))
            }
        }

        events = GovernanceEvents()
        results = ActionResults()
        heldStore = HeldActionStore(database, clock)
        constitutions = ConstitutionStore(database, clock, heldStore, events)
        outbox = ActionOutbox(database, clock, heldStore, OutboxSignals(), events, results)
        val router = ConnectorRouter(database, NoConnectorPreferences())
        val guard = Guard(GuardModel { q -> modelCalls += q; modelVerdict })
        gate = ActionGate(guard, constitutions, heldStore, outbox, router, events, UtcPersonZone(), clock)
        held = HeldActionService(database, clock, heldStore, constitutions, outbox, router, events)
        activity = ActivityStore(database)
    }
    afterSpec { postgres.stop() }

    fun ApplicationTestBuilder.setup(): HttpClient {
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(ClarityJson) }
            install(Authentication) {
                bearer(AuthProviders.DEVICE) { authenticate { DevicePrincipal(DeviceId(DEVICE_ID)) } }
                // the "token" is the connector id, so tests can act as any connector
                bearer(AuthProviders.CONNECTOR) { authenticate { ConnectorPrincipal(ConnectorId(it.token)) } }
            }
            GovernanceRoutes(constitutions, held, activity).install(this)
            ActionRoutes(outbox).install(this)
        }
        return createClient { install(ContentNegotiation) { json(ClarityJson) } }
    }

    suspend fun HttpClient.asDevice(path: String) = get(path) { header(HttpHeaders.Authorization, "Bearer device") }
    suspend fun HttpClient.decide(id: String, decision: HeldDecision): HttpResponse = post("/api/v1/held/$id/decision") {
        header(HttpHeaders.Authorization, "Bearer device"); contentType(ContentType.Application.Json)
        setBody(ClarityJson.encodeToString(HeldDecision.serializer(), decision))
    }
    suspend fun HttpClient.poll(connector: String, wait: Int = 0): ActionPoll =
        get("/ingest/v1/actions?wait=$wait") { header(HttpHeaders.Authorization, "Bearer $connector") }.body()
    suspend fun HttpClient.report(connector: String, id: String, outcome: ActionOutcome): HttpResponse =
        post("/ingest/v1/actions/$id/result") {
            header(HttpHeaders.Authorization, "Bearer $connector"); contentType(ContentType.Application.Json); setBody(ActionResult(outcome))
        }

    suspend fun HttpClient.postEmpty(path: String): HttpResponse = post(path) { header(HttpHeaders.Authorization, "Bearer device") }

    /** Completes everything currently deliverable to the mail connector so tests start from an empty queue. */
    suspend fun HttpClient.drain() {
        while (true) {
            val batch = poll(MAIL).actions
            if (batch.isEmpty()) return
            batch.forEach { report(MAIL, it.id.value, ActionOutcome.Done()) }
        }
    }

    test("constitution: absent, created once, amended by saving an amendment (which settles the held action it came from)") {
        testApplication {
            val client = setup()
            client.asDevice("/api/v1/constitution").status shouldBe HttpStatusCode.NotFound

            val v1 = Constitution(
                version = 99, lines = listOf(line("v1", "Family first"), line("h1", "Never email my boss", hard = true)),
                autonomy = mapOf(ActionKind.SAVE_DRAFT to AutonomyTier.DO_THEN_TELL, ActionKind.PAYMENT to AutonomyTier.NEVER), updatedAt = T0,
            )
            val created = client.postAsDevice("/api/v1/constitution", v1)
            created.status shouldBe HttpStatusCode.Created
            created.body<Constitution>().version shouldBe 1
            client.postAsDevice("/api/v1/constitution", v1).status shouldBe HttpStatusCode.Conflict
            client.postAsDevice("/api/v1/constitution", v1.copy(lines = listOf(line("a"), line("a")))).status shouldBe HttpStatusCode.BadRequest

            // a held action from before the amendment
            modelVerdict = GuardVerdict(Decision.ALLOW, emptyList(), "ok")
            val outcome = gate.propose(sample(ActionKind.SEND_EMAIL, "k-amend"), "reply to Dana", emptyList(), trusted(), Urgency.NORMAL)
            val heldId = outcome.shouldBeInstanceOf<ProposalOutcome.Held>().heldId

            val proposed = client.postAsDevice(
                "/api/v1/constitution/amendments",
                ProposeAmendment(LineId("v1"), line("ignored", "Family first, except on release days"), "I said so", fromHeld = heldId),
            )
            proposed.status shouldBe HttpStatusCode.Created
            val amendment = proposed.body<Amendment>()
            amendment.old shouldBe "Family first"
            client.postAsDevice("/api/v1/constitution/amendments", ProposeAmendment(LineId("ghost"), line("x"), "r")).status shouldBe HttpStatusCode.UnprocessableEntity

            // nothing changes until the person saves it
            client.asDevice("/api/v1/constitution").body<Constitution>().version shouldBe 1
            val saved = client.postEmpty("/api/v1/constitution/amendments/${amendment.id}/save")
            saved.status shouldBe HttpStatusCode.OK
            val v2 = saved.body<Constitution>()
            v2.version shouldBe 2
            v2.lines.first { it.id == LineId("v1") }.text shouldBe "Family first, except on release days"
            client.postEmpty("/api/v1/constitution/amendments/${amendment.id}/save").status shouldBe HttpStatusCode.Conflict

            client.asDevice("/api/v1/constitution?version=1").body<Constitution>().lines.first().text shouldBe "Family first"
            client.asDevice("/api/v1/constitution?version=7").status shouldBe HttpStatusCode.NotFound
            client.asDevice("/api/v1/constitution?version=x").status shouldBe HttpStatusCode.BadRequest
            heldStore.get(heldId)!!.status shouldBe HeldAction.Status.RULE_UPDATED

            client.asDevice("/api/v1/constitution/amendments?status=saved").body<List<Amendment>>().map { it.id } shouldBe listOf(amendment.id)
            client.asDevice("/api/v1/constitution/amendments?status=proposed").body<List<Amendment>>() shouldHaveSize 0
        }
    }

    test("gate: allowed actions are queued and logged, asks are held, hard lines are blocked, unroutable kinds fail clearly; all idempotent") {
        modelCalls.clear()
        modelVerdict = GuardVerdict(Decision.ALLOW, emptyList(), "ok")
        val gateEvents = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { events.events.filterIsInstance<GovernanceEvent.HeldChanged>().take(3).toList() } }

        val draft = sample(ActionKind.SAVE_DRAFT, "k-draft")
        val allowed = gate.propose(draft, "a draft", emptyList(), trusted(), Urgency.NORMAL).shouldBeInstanceOf<ProposalOutcome.Allowed>()
        gate.propose(draft, "a draft", emptyList(), trusted(), Urgency.NORMAL).shouldBeInstanceOf<ProposalOutcome.Allowed>().actionId shouldBe allowed.actionId
        activity.recent().filter { it.summary == draft.summary } shouldHaveSize 1

        val send = sample(ActionKind.SEND_EMAIL, "k-send")
        val askedFirst = gate.propose(send, "my view", listOf(net.robog.clarity.shared.feed.ContextItem("mail", "Dana's mail")), trusted(), Urgency.URGENT)
        val heldOutcome = askedFirst.shouldBeInstanceOf<ProposalOutcome.Held>()
        heldOutcome.text shouldContain "Held for review"
        gate.propose(send, "my view", emptyList(), trusted(), Urgency.URGENT).shouldBeInstanceOf<ProposalOutcome.Held>().heldId shouldBe heldOutcome.heldId
        heldStore.get(heldOutcome.heldId)!!.also {
            it.status shouldBe HeldAction.Status.HELD
            it.agentView shouldBe "my view"
            it.urgency shouldBe Urgency.URGENT
            it.context.map { c -> c.title } shouldBe listOf("Dana's mail")
        }

        val pay = sample(ActionKind.PAYMENT, "k-pay")
        gate.propose(pay, "pay it", emptyList(), trusted(requestedByUser = true), Urgency.NORMAL)
            .shouldBeInstanceOf<ProposalOutcome.Blocked>().text shouldContain "Blocked"

        // do-then-tell for a kind no connector handles: nothing is queued, and the agent is told
        constitutions.saveVersion { it.copy(autonomy = it.autonomy + (ActionKind.CREATE_REMINDER to AutonomyTier.DO_THEN_TELL)) }
        val reminder = sample(ActionKind.CREATE_REMINDER, "k-rem")
        val unroutable = gate.propose(reminder, "remind", emptyList(), trusted(), Urgency.NORMAL).shouldBeInstanceOf<ProposalOutcome.Blocked>()
        unroutable.text shouldContain "No connector can do"
        heldStore.get(unroutable.heldId)!!.status shouldBe HeldAction.Status.FAILED
        outbox.findByKey("k-rem") shouldBe null

        // the model can tighten an otherwise-allowed action, and was shown the agent's reason
        modelVerdict = GuardVerdict(Decision.ASK, listOf(LineId("v1")), "it touches your family line")
        val tightened = gate.propose(sample(ActionKind.SAVE_DRAFT, "k-draft2"), "because", emptyList(), trusted(), Urgency.NORMAL)
        tightened.shouldBeInstanceOf<ProposalOutcome.Held>().text shouldContain "family line"
        modelCalls.last().agentReason shouldBe "because"
        modelCalls.last().constitution.version shouldBe constitutions.current()!!.version
        modelCalls.none { it.action.idempotencyKey == "k-pay" } shouldBe true // DENY never reaches the model

        gateEvents.await().map { it.held.status } shouldContainExactly
            listOf(HeldAction.Status.HELD, HeldAction.Status.BLOCKED, HeldAction.Status.FAILED)
    }

    test("held actions over HTTP: list, approve with an edit, decline, keep, hard lines and double decisions") {
        testApplication {
            val client = setup()
            modelVerdict = GuardVerdict(Decision.ALLOW, emptyList(), "ok")
            val a = gate.propose(sample(ActionKind.SEND_EMAIL, "h-a"), "v", emptyList(), trusted(), Urgency.NORMAL).shouldBeInstanceOf<ProposalOutcome.Held>().heldId
            val b = gate.propose(sample(ActionKind.SEND_EMAIL, "h-b"), "v", emptyList(), trusted(), Urgency.BATCH).shouldBeInstanceOf<ProposalOutcome.Held>().heldId
            val k = gate.propose(sample(ActionKind.SEND_EMAIL, "h-k"), "v", emptyList(), trusted(), Urgency.BATCH).shouldBeInstanceOf<ProposalOutcome.Held>().heldId
            val pay = gate.propose(sample(ActionKind.PAYMENT, "h-pay"), "v", emptyList(), trusted(), Urgency.NORMAL).shouldBeInstanceOf<ProposalOutcome.Blocked>().heldId

            val listed = client.asDevice("/api/v1/held?status=held,blocked").body<List<HeldAction>>()
            listed.map { it.id }.containsAll(listOf(a, b, k, pay)) shouldBe true
            listed.all { it.status == HeldAction.Status.HELD || it.status == HeldAction.Status.BLOCKED } shouldBe true
            client.asDevice("/api/v1/held?status=bogus").status shouldBe HttpStatusCode.BadRequest
            client.asDevice("/api/v1/held/${a}").body<HeldAction>().id shouldBe a
            client.asDevice("/api/v1/held/not-a-uuid").status shouldBe HttpStatusCode.NotFound

            // approve with an edited draft: the edit is what is queued, under the original key
            val edited = ProposedAction.SendEmail("ignored-new-key", "edited", email("a@example.com").copy(body = "Edited by the person"))
            val approved = client.decide(a.value, HeldDecision.Approve(edited))
            approved.status shouldBe HttpStatusCode.OK
            val appr = approved.body<HeldAction>()
            appr.status shouldBe HeldAction.Status.APPROVED
            (appr.action as ProposedAction.SendEmail).email.body shouldBe "Edited by the person"
            val queued = outbox.findByKey("h-a")!!
            queued.connectorId shouldBe ConnectorId(MAIL)
            (queued.action as ProposedAction.SendEmail).email.body shouldBe "Edited by the person"
            client.decide(a.value, HeldDecision.Approve()).status shouldBe HttpStatusCode.Conflict

            // an edit may not change what kind of thing it is
            client.decide(b.value, HeldDecision.Approve(sample(ActionKind.SAVE_DRAFT, "x"))).status shouldBe HttpStatusCode.BadRequest

            client.decide(b.value, HeldDecision.Decline).body<HeldAction>().status shouldBe HeldAction.Status.DECLINED
            client.decide(k.value, HeldDecision.Keep).body<HeldAction>().status shouldBe HeldAction.Status.KEPT
            client.decide(k.value, HeldDecision.Decline).status shouldBe HttpStatusCode.Conflict
            outbox.findByKey("h-b") shouldBe null

            // a hard line cannot be approved away
            client.decide(pay.value, HeldDecision.Approve()).status shouldBe HttpStatusCode.Conflict
            heldStore.get(pay)!!.status shouldBe HeldAction.Status.BLOCKED

            client.decide("00000000-0000-0000-0000-000000000000", HeldDecision.Keep).status shouldBe HttpStatusCode.NotFound

            // approving a kind nobody handles fails the held action with a clear message
            modelVerdict = GuardVerdict(Decision.ASK, emptyList(), "ask")
            val ev = gate.propose(sample(ActionKind.RESPOND_TO_INVITE, "h-inv"), "v", emptyList(), trusted(), Urgency.NORMAL).shouldBeInstanceOf<ProposalOutcome.Held>().heldId
            val noHandler = client.decide(ev.value, HeldDecision.Approve())
            noHandler.status shouldBe HttpStatusCode.UnprocessableEntity
            heldStore.get(ev)!!.status shouldBe HeldAction.Status.FAILED
        }
    }

    test("action channel: lease, lease expiry, retry with backoff, final result, duplicate results, wrong connector") {
        testApplication {
            val client = setup()
            client.drain()
            modelVerdict = GuardVerdict(Decision.ALLOW, emptyList(), "ok")
            val heldId = gate.propose(sample(ActionKind.SEND_EMAIL, "ch-1"), "v", emptyList(), trusted(), Urgency.NORMAL).shouldBeInstanceOf<ProposalOutcome.Held>().heldId
            client.decide(heldId.value, HeldDecision.Approve()).status shouldBe HttpStatusCode.OK
            val finalEvents = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { results.events.take(1).toList() } }

            // addressed to mail only
            client.poll("other").actions shouldHaveSize 0
            val first = client.poll(MAIL).actions
            first shouldHaveSize 1
            first.single().attempt shouldBe 1
            val id = first.single().id
            first.single().action.idempotencyKey shouldBe "ch-1"
            client.poll(MAIL).actions shouldHaveSize 0 // leased

            // the connector never answered: the lease lapses and the action comes back
            clock.advance(LEASE_DURATION + 1.seconds)
            client.poll(MAIL).actions.single().also { it.id shouldBe id; it.attempt shouldBe 2 }

            // another connector cannot report on it; neither can an unknown id
            client.report("other", id.value, ActionOutcome.Done()).status shouldBe HttpStatusCode.NotFound
            client.report(MAIL, "00000000-0000-0000-0000-000000000000", ActionOutcome.Done()).status shouldBe HttpStatusCode.NotFound
            client.report(MAIL, "nonsense", ActionOutcome.Done()).status shouldBe HttpStatusCode.NotFound

            // retryable failure: backs off, then is offered again
            client.report(MAIL, id.value, ActionOutcome.Failed("smtp timeout", retryable = true)).status shouldBe HttpStatusCode.NoContent
            client.poll(MAIL).actions shouldHaveSize 0
            heldStore.get(heldId)!!.status shouldBe HeldAction.Status.APPROVED
            clock.advance(retryDelay(2) + 1.seconds)
            client.poll(MAIL).actions.single().attempt shouldBe 3

            client.report(MAIL, id.value, ActionOutcome.Done(SourceId("<msg-1@mail>"))).status shouldBe HttpStatusCode.NoContent
            heldStore.get(heldId)!!.status shouldBe HeldAction.Status.EXECUTED
            // duplicates, even contradicting ones, change nothing and publish nothing more
            client.report(MAIL, id.value, ActionOutcome.Done(SourceId("<msg-1@mail>"))).status shouldBe HttpStatusCode.NoContent
            client.report(MAIL, id.value, ActionOutcome.Failed("late", retryable = false)).status shouldBe HttpStatusCode.NoContent
            outbox.get(id)!!.also { it.status shouldBe OutboxStatus.DONE; it.result shouldBe ActionOutcome.Done(SourceId("<msg-1@mail>")) }
            clock.advance(1.minutes)
            client.poll(MAIL).actions shouldHaveSize 0

            val event = finalEvents.await().single()
            event.actionId shouldBe id
            event.heldId shouldBe heldId
            event.outcome shouldBe ActionOutcome.Done(SourceId("<msg-1@mail>"))
        }
    }

    test("action channel: a non-retryable failure and exhausted retries end the action and its held row; activity turns red") {
        testApplication {
            val client = setup()
            client.drain()
            modelVerdict = GuardVerdict(Decision.ALLOW, emptyList(), "ok")
            constitutions.saveVersion { it.copy(autonomy = it.autonomy + (ActionKind.SEND_EMAIL to AutonomyTier.DO_THEN_TELL)) }

            // allowed outright (no held row), the connector says no
            val one = gate.propose(sample(ActionKind.SEND_EMAIL, "fail-1"), "v", emptyList(), trusted(), Urgency.NORMAL).shouldBeInstanceOf<ProposalOutcome.Allowed>()
            client.poll(MAIL).actions.single().id shouldBe one.actionId
            client.report(MAIL, one.actionId.value, ActionOutcome.Failed("mailbox full", retryable = false)).status shouldBe HttpStatusCode.NoContent
            outbox.get(one.actionId)!!.status shouldBe OutboxStatus.FAILED
            activity.recent().first { it.summary == "reply to Dana" }.ok shouldBe false

            // retryable forever: after MAX_ATTEMPTS deliveries it gives up
            val two = gate.propose(sample(ActionKind.SEND_EMAIL, "fail-2").let {
                (it as ProposedAction.SendEmail).copy(summary = "second mail")
            }, "v", emptyList(), trusted(), Urgency.NORMAL).shouldBeInstanceOf<ProposalOutcome.Allowed>()
            repeat(MAX_ATTEMPTS) { n ->
                client.poll(MAIL).actions.single().attempt shouldBe n + 1
                client.report(MAIL, two.actionId.value, ActionOutcome.Failed("timeout", retryable = true)).status shouldBe HttpStatusCode.NoContent
                clock.advance(1.minutes.times(40))
            }
            outbox.get(two.actionId)!!.status shouldBe OutboxStatus.FAILED
            client.poll(MAIL).actions shouldHaveSize 0
            activity.recent().first { it.summary == "second mail" }.ok shouldBe false

            // leased the last time and never answered: the sweep fails it
            val three = gate.propose((sample(ActionKind.SEND_EMAIL, "fail-3") as ProposedAction.SendEmail).copy(summary = "third"), "v", emptyList(), trusted(), Urgency.NORMAL)
                .shouldBeInstanceOf<ProposalOutcome.Allowed>()
            val sweepEvents = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(10_000) { results.events.take(1).toList() } }
            repeat(MAX_ATTEMPTS) {
                client.poll(MAIL).actions.single().id shouldBe three.actionId
                clock.advance(LEASE_DURATION + 1.seconds)
            }
            client.poll(MAIL).actions shouldHaveSize 0
            outbox.get(three.actionId)!!.status shouldBe OutboxStatus.FAILED
            sweepEvents.await().single().outcome.shouldBeInstanceOf<ActionOutcome.Failed>().message shouldContain "never reported"
        }
    }

    test("action channel: a long-poll returns as soon as work is queued, and times out empty otherwise") {
        testApplication {
            val client = setup()
            client.drain()
            modelVerdict = GuardVerdict(Decision.ALLOW, emptyList(), "ok")
            val empty = client.poll(MAIL, wait = 1)
            empty.actions shouldHaveSize 0

            val waiting = async { withTimeout(20_000) { client.poll(MAIL, wait = 25) } }
            delay(500)
            val started = System.nanoTime()
            val action = (sample(ActionKind.SAVE_DRAFT, "lp-1") as ProposedAction.SaveDraft).copy(summary = "long poll draft")
            outbox.enqueue(ConnectorId(MAIL), action)
            val polled = waiting.await()
            polled.actions.single().action.summary shouldBe "long poll draft"
            ((System.nanoTime() - started) / 1_000_000 < 5_000) shouldBe true
        }
    }

    test("outbox: enqueueing the same idempotency key concurrently creates one row and one activity entry") {
        val action = (sample(ActionKind.SAVE_DRAFT, "idem-1") as ProposedAction.SaveDraft).copy(summary = "idempotent draft")
        val ids = (1..8).map { async { outbox.enqueue(ConnectorId(MAIL), action, alone = true) } }.awaitAll()
        ids.toSet() shouldHaveSize 1
        activity.recent(500).count { it.summary == "idempotent draft" } shouldBe 1
        val again = outbox.enqueue(ConnectorId(MAIL), action.copy(summary = "changed text, same key"), alone = true)
        again shouldBe ids.first()
        outbox.get(again)!!.action.summary shouldBe "idempotent draft"
    }

    test("activity lists what was done alone, newest first") {
        val entries: List<ActivityEntry> = activity.recent(500)
        (entries.first().at >= entries.last().at) shouldBe true
        entries.map { it.kind }.toSet().contains(ActionKind.SAVE_DRAFT) shouldBe true
    }
})
