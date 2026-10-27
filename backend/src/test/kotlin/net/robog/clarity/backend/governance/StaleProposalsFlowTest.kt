package net.robog.clarity.backend.governance

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.scheduler.StaleProposals
import net.robog.clarity.backend.sessions.AgentSessions
import net.robog.clarity.backend.vault.CreateOutcome
import net.robog.clarity.backend.vault.CredentialRequests
import net.robog.clarity.backend.vault.Credentials
import net.robog.clarity.backend.vault.ExposedVaultStore
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.Amendment
import net.robog.clarity.shared.governance.AmendmentId
import net.robog.clarity.shared.governance.AutonomyChange
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.Line
import net.robog.clarity.shared.governance.LineId
import net.robog.clarity.shared.governance.ProposeAmendment
import net.robog.clarity.shared.governance.Section
import net.robog.clarity.backend.sessions.SessionData
import net.robog.clarity.shared.vault.CredentialKind
import net.robog.clarity.shared.vault.CredentialRequestId
import net.robog.clarity.shared.vault.CredentialRequestStatus
import net.robog.clarity.shared.vault.FieldSpec
import net.robog.clarity.shared.vault.FieldType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.testcontainers.postgresql.PostgreSQLContainer

/** A fixed start plus a movable offset. */
private class MovableClock(private val start: Instant) : Clock {
    private val offsetMs = AtomicLong(0)
    fun advance(by: Duration) { offsetMs.addAndGet(by.inWholeMilliseconds) }
    override fun now(): Instant = start + offsetMs.get().milliseconds
}

private val PROD_NOW = Instant.parse("2026-10-27T03:00:00Z")
private val LOGIN_FIELDS = listOf(FieldSpec("email", "Email", FieldType.EMAIL), FieldSpec("password", "Password", FieldType.PASSWORD))

/**
 * Stale amendments and credential requests against Postgres: no duplicates when proposing, saves
 * supersede, old and orphaned proposals expire, and the healing pass on rows shaped like the
 * production ones of 2026-10-26.
 */
@EnabledIf(ContainerRuntimeAvailable::class)
class StaleProposalsFlowTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database
    lateinit var clock: MovableClock
    lateinit var constitutions: ConstitutionStore
    lateinit var vault: ExposedVaultStore
    lateinit var stale: StaleProposals

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(
            net.robog.clarity.backend.config.DatabaseConfig(
                postgres.jdbcUrl, postgres.username, net.robog.clarity.backend.config.Secret(postgres.password), maxPoolSize = 4,
            ),
        )
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }

    beforeTest {
        clock = MovableClock(PROD_NOW)
        val events = GovernanceEvents()
        constitutions = ConstitutionStore(database, clock, HeldActionStore(database, clock), events)
        vault = ExposedVaultStore(database, clock)
        stale = StaleProposals(constitutions, vault)
        database.tx {
            exec("TRUNCATE amendment, agent_session, credential_request, credential, constitution_version, held_action CASCADE")
        }
    }

    fun status(id: String): Pair<String, String?> = kotlinx.coroutines.runBlocking {
        database.tx {
            Amendments.selectAll().where { Amendments.id eq Uuid.parse(id) }.single().let { it[Amendments.status] to it[Amendments.closedReason] }
        }
    }

    fun requestStatus(id: String): String = kotlinx.coroutines.runBlocking {
        database.tx { CredentialRequests.selectAll().where { CredentialRequests.id eq Uuid.parse(id) }.single()[CredentialRequests.status] }
    }

    suspend fun seedConstitution(vararg lines: Line) {
        constitutions.create(Constitution(version = 1, lines = lines.toList(), autonomy = emptyMap(), updatedAt = PROD_NOW)).isRight() shouldBe true
    }

    suspend fun heldRow(id: String) = database.tx {
        exec(
            "INSERT INTO held_action (id, idempotency_key, action, verdict, agent_view, urgency, status) " +
                "VALUES ('$id', 'k-$id', '{}'::jsonb, '{}'::jsonb, 'view', 'normal', 'held')",
        )
    }

    suspend fun amendmentRow(id: String, text: String, at: String, status: String, fromHeld: String?, autonomy: AutonomyChange? = null) = database.tx {
        Amendments.insert {
            it[Amendments.id] = Uuid.parse(id)
            it[lineId] = null
            it[oldText] = null
            it[proposed] = Line(LineId("amended-${id.take(8)}"), Section.AUTONOMY, text, "because")
            it[reason] = "r"
            it[Amendments.fromHeld] = fromHeld?.let(Uuid::parse)
            it[Amendments.status] = status
            it[createdAt] = Instant.parse(at)
            it[Amendments.autonomy] = autonomy
        }
    }

    suspend fun amendSession(id: String, amendmentId: String?, state: String, started: String, held: String) = database.tx {
        AgentSessions.insert {
            it[AgentSessions.id] = Uuid.parse(id)
            it[kind] = "amend"
            it[AgentSessions.state] = state
            it[startedAt] = Instant.parse(started)
            it[endedAt] = if (state == "ended") Instant.parse(started) + 60_000.milliseconds else null
            it[heldActionId] = held
            it[AgentSessions.amendmentId] = amendmentId
            it[finalizing] = false
            it[version] = 1
            it[data] = SessionData()
        }
    }

    test("healing: the production rows of 2026-10-26 settle, history stays, and a second pass changes nothing") {
        val calendarLine = "Creating calendar events is okay: go ahead and add them, then tell me."
        seedConstitution(
            Line(LineId("q1-values"), Section.VALUES, "When something needs information from me, keep getting back to me.", "b"),
            Line(LineId("amended-a80c7160"), Section.AUTONOMY, calendarLine, "b"),
        )
        val h = mapOf(
            "browse" to "98790000-0000-4000-8000-000000000001",
            "calendar" to "63330000-0000-4000-8000-000000000002",
            "login" to "c54d0000-0000-4000-8000-000000000003",
            "click" to "eeb90000-0000-4000-8000-000000000004",
        )
        h.values.forEach { heldRow(it) }
        val browse = "Browsing and clicking through websites is fine to do on your own; only ask me before the final step."
        val signIn = "When I approve using a saved login, go ahead and sign in to that site and carry on."
        val e15 = "e15baebb-0000-4000-8000-000000000001"
        val a8366 = "8366e44e-0000-4000-8000-000000000002"
        val f797 = "f797cd43-0000-4000-8000-000000000003"
        val d386 = "d3867c7a-0000-4000-8000-000000000004"
        val c2ae = "c2ae3655-0000-4000-8000-000000000005"
        val a0139 = "0139cc66-0000-4000-8000-000000000006"
        val ea18 = "ea18f1d9-0000-4000-8000-000000000007"
        amendmentRow(e15, browse, "2026-10-26T05:03:56Z", "proposed", h["browse"])
        amendmentRow(a8366, browse, "2026-10-26T05:04:55Z", "proposed", h["browse"])
        amendmentRow(f797, calendarLine, "2026-10-26T13:31:50Z", "proposed", h["calendar"])
        amendmentRow(d386, calendarLine, "2026-10-26T13:42:40Z", "saved", h["calendar"])
        amendmentRow(c2ae, signIn, "2026-10-26T14:57:31Z", "proposed", h["login"])
        amendmentRow(a0139, signIn, "2026-10-26T16:12:55Z", "discarded", h["click"])
        amendmentRow(ea18, "Clicking navigation buttons like \"Continue\" or \"Next\" on a site is fine without asking.", "2026-10-26T16:13:28Z", "proposed", h["click"])
        amendSession("3ff7a99d-0000-4000-8000-000000000001", e15, "ended", "2026-10-26T05:03:31Z", h.getValue("browse"))
        amendSession("7048973f-0000-4000-8000-000000000002", a8366, "ended", "2026-10-26T05:04:51Z", h.getValue("browse"))
        amendSession("13d6ce75-0000-4000-8000-000000000003", f797, "ended", "2026-10-26T13:31:43Z", h.getValue("calendar"))
        amendSession("dd683926-0000-4000-8000-000000000004", d386, "ended", "2026-10-26T13:42:32Z", h.getValue("calendar"))
        amendSession("1b1bf4b6-0000-4000-8000-000000000005", c2ae, "ended", "2026-10-26T14:57:11Z", h.getValue("login"))
        amendSession("ad5d9225-0000-4000-8000-000000000006", ea18, "ended", "2026-10-26T16:12:53Z", h.getValue("click"))

        // credentials: ulta_login (first entry, later replaced), ulta_login_v2 left open, ulta_login fulfilled
        val cred = "62b0477f-0000-4000-8000-000000000001"
        database.tx {
            Credentials.insert {
                it[id] = Uuid.parse(cred); it[name] = "ulta_login"; it[kind] = "login"; it[site] = "ulta.com"; it[purpose] = "p"
                it[fieldSpecs] = LOGIN_FIELDS; it[hints] = emptyMap(); it[createdAt] = Instant.parse("2026-10-26T14:54:25Z")
            }
            listOf(
                Triple("158c745b-0000-4000-8000-000000000001", "ulta_login" to "fulfilled", "2026-10-26T05:25:31Z"),
                Triple("2c15cb2e-0000-4000-8000-000000000002", "ulta_login_v2" to "open", "2026-10-26T13:05:09Z"),
                Triple("68261554-0000-4000-8000-000000000003", "ulta_login" to "fulfilled", "2026-10-26T14:47:55Z"),
            ).forEach { (id, ns, at) ->
                CredentialRequests.insert {
                    it[CredentialRequests.id] = Uuid.parse(id); it[name] = ns.first; it[kind] = "login"; it[fieldSpecs] = LOGIN_FIELDS
                    it[site] = "ulta.com"; it[purpose] = "Sign in to Ulta"; it[status] = ns.second; it[createdAt] = Instant.parse(at)
                    if (id.startsWith("6826")) it[credentialId] = Uuid.parse(cred)
                }
            }
        }

        stale.tidy() shouldBe 6

        status(e15).let { (s, why) -> s shouldBe "superseded"; why!! shouldContain a8366 }
        status(a8366).let { (s, why) -> s shouldBe "expired"; why!! shouldContain "ended without saving" }
        status(f797).let { (s, why) -> s shouldBe "superseded"; why!! shouldContain "already in the constitution" }
        status(d386).first shouldBe "saved"
        status(c2ae).first shouldBe "expired"
        status(a0139).first shouldBe "discarded"
        status(ea18).first shouldBe "expired"
        requestStatus("2c15cb2e-0000-4000-8000-000000000002") shouldBe "superseded"
        requestStatus("158c745b-0000-4000-8000-000000000001") shouldBe "fulfilled"
        requestStatus("68261554-0000-4000-8000-000000000003") shouldBe "fulfilled"

        // what the app sees: nothing waiting; closed ones read as discarded / cancelled with a reason
        constitutions.amendments(Amendment.Status.PROPOSED).shouldBeEmpty()
        constitutions.amendments(Amendment.Status.DISCARDED).map { it.id.value }.toSet() shouldBe setOf(e15, a8366, f797, c2ae, a0139, ea18)
        constitutions.amendment(AmendmentId(f797))!!.closedReason!! shouldContain "already in the constitution"
        vault.request(CredentialRequestId("2c15cb2e-0000-4000-8000-000000000002"))!!.let {
            it.status shouldBe CredentialRequestStatus.CANCELLED
            it.closedReason!! shouldContain "ulta_login"
        }
        database.tx { Amendments.selectAll().count() } shouldBe 7L

        stale.tidy() shouldBe 0
    }

    test("proposing the same change twice gives the waiting one back; a different change is new") {
        seedConstitution(Line(LineId("v1"), Section.VALUES, "Family first", "b"))
        val held = "aaaa0000-0000-4000-8000-000000000001"
        heldRow(held)
        val request = ProposeAmendment(null, line("x", "Browsing is fine on your own."), "r", net.robog.clarity.shared.governance.HeldActionId(held))
        val first = constitutions.propose(request).getOrNull()!!
        val again = constitutions.propose(request.copy(proposed = line("y", "browsing is FINE on your own"), reason = "other words")).getOrNull()!!
        again.id shouldBe first.id
        val other = constitutions.propose(request.copy(proposed = line("z", "Booking is fine."))).getOrNull()!!
        other.id shouldNotBe first.id
        constitutions.amendments(Amendment.Status.PROPOSED).size shouldBe 2

        // the same text as a line already there changes nothing
        constitutions.propose(ProposeAmendment(null, line("w", "family first."), "r")).leftOrNull()
            .shouldBeInstanceOf<GovernanceError.AlreadyInConstitution>()
    }

    test("an equal proposal from a conversation that ended is not handed back; it expires and a new one is made") {
        seedConstitution(Line(LineId("v1"), Section.VALUES, "Family first", "b"))
        val first = constitutions.propose(ProposeAmendment(null, line("x", "Booking is fine."), "r")).getOrNull()!!
        amendSession("bbbb0000-0000-4000-8000-000000000001", first.id.value, "ended", "2026-10-27T02:00:00Z", "none")
        val second = constitutions.propose(ProposeAmendment(null, line("x", "Booking is fine."), "r")).getOrNull()!!
        second.id shouldNotBe first.id
        status(first.id.value).first shouldBe "expired"
    }

    test("saving supersedes waiting proposals with the same text or for the same line") {
        seedConstitution(Line(LineId("v1"), Section.VALUES, "Family first", "b"))
        val tier = AutonomyChange(ActionKind.CREATE_EVENT, AutonomyTier.DO_THEN_TELL)
        // same text, different tier change: a different proposal while both wait
        val a = constitutions.propose(ProposeAmendment(null, line("x", "Add events, then tell me."), "r")).getOrNull()!!
        val b = constitutions.propose(ProposeAmendment(null, line("y", "Add events, then tell me"), "r", autonomy = tier)).getOrNull()!!
        a.id shouldNotBe b.id
        val l1 = constitutions.propose(ProposeAmendment(LineId("v1"), line("v1", "Family first, except release days"), "r")).getOrNull()!!
        val l2 = constitutions.propose(ProposeAmendment(LineId("v1"), line("v1", "Work first"), "r")).getOrNull()!!

        constitutions.saveAmendment(b.id).isRight() shouldBe true
        status(a.id.value).first shouldBe "superseded"
        status(l1.id.value).first shouldBe "proposed"

        constitutions.saveAmendment(l2.id).isRight() shouldBe true
        status(l1.id.value).let { (s, why) -> s shouldBe "superseded"; why!! shouldContain "changed since" }
        constitutions.amendments(Amendment.Status.PROPOSED).shouldBeEmpty()
        constitutions.saveAmendment(l1.id).leftOrNull().shouldBeInstanceOf<GovernanceError.AmendmentNotProposed>()
    }

    test("a proposal that waits in a live conversation stays; after a week it expires") {
        seedConstitution(Line(LineId("v1"), Section.VALUES, "Family first", "b"))
        val a = constitutions.propose(ProposeAmendment(null, line("x", "Booking is fine."), "r")).getOrNull()!!
        amendSession("cccc0000-0000-4000-8000-000000000001", a.id.value, "active", "2026-10-27T02:59:00Z", "none")
        stale.tidy() shouldBe 0
        clock.advance(6.days)
        stale.tidy() shouldBe 0
        clock.advance(1.days + 1.milliseconds)
        stale.tidy() shouldBe 1
        status(a.id.value).let { (s, why) -> s shouldBe "expired"; why!! shouldContain "7 days" }
    }

    test("credential requests: one open form per site and kind; saving closes the others; old ones expire") {
        val first = vault.createRequest("ulta_login_v2", CredentialKind.LOGIN, LOGIN_FIELDS, "ulta.com", "p", replace = false)
        first.existing shouldBe false
        val again = vault.createRequest("ulta_login", CredentialKind.LOGIN, LOGIN_FIELDS, "https://www.ulta.com/signin", "p", replace = false)
        again.existing shouldBe true
        again.request.id shouldBe first.request.id
        // another kind on the same site is its own form
        val card = vault.createRequest("ulta_card", CredentialKind.PAYMENT_CARD, LOGIN_FIELDS, "ulta.com", "p", replace = false)
        card.existing shouldBe false

        // re-entering a saved login under its own name takes over from the open form under another name
        val replace = vault.createRequest("ulta_login", CredentialKind.LOGIN, LOGIN_FIELDS, "ulta.com", "p", replace = true)
        replace.existing shouldBe false
        requestStatus(first.request.id.value) shouldBe "superseded"

        // an open login form for the site is answered by saving any login for it
        database.tx {
            CredentialRequests.insert {
                it[id] = Uuid.parse("dddd0000-0000-4000-8000-000000000001"); it[name] = "ulta_old"; it[kind] = "login"; it[fieldSpecs] = LOGIN_FIELDS
                it[site] = "ulta.com"; it[purpose] = "p"; it[status] = "open"; it[createdAt] = PROD_NOW - 1.days
            }
        }
        vault.createCredential(replace.request.id, emptyMap(), emptyList()).shouldBeInstanceOf<CreateOutcome.Created>()
        requestStatus("dddd0000-0000-4000-8000-000000000001") shouldBe "superseded"
        requestStatus(card.request.id.value) shouldBe "open"
        vault.request(CredentialRequestId("dddd0000-0000-4000-8000-000000000001"))!!.closedReason!! shouldContain "ulta_login"

        clock.advance(7.days + 1.milliseconds)
        vault.tidyRequests() shouldBe 1
        requestStatus(card.request.id.value) shouldBe "expired"
        // a closed form cannot be completed
        vault.createCredential(card.request.id, emptyMap(), emptyList()) shouldBe CreateOutcome.RequestNotOpen
        database.tx { CredentialRequests.deleteAll() }
    }
})
