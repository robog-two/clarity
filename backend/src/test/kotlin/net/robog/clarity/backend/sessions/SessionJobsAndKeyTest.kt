package net.robog.clarity.backend.sessions

import arrow.core.Either
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.LeadRig
import net.robog.clarity.backend.agent.Priority
import net.robog.clarity.backend.agent.ScheduledJobs
import net.robog.clarity.backend.agent.end
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.AppConfig
import net.robog.clarity.backend.config.ConnectorsConfig
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.EnrollmentConfig
import net.robog.clarity.backend.config.EnrollmentMode
import net.robog.clarity.backend.config.HttpConfig
import net.robog.clarity.backend.config.InstanceConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.connectors.ConnectorRegistry
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.scheduler.FireContext
import net.robog.clarity.backend.scheduler.Scheduler
import net.robog.clarity.backend.setup.KeyCheck
import net.robog.clarity.backend.setup.ModelKeyStore
import net.robog.clarity.backend.setup.SettingsStore
import net.robog.clarity.backend.setup.SetupError
import net.robog.clarity.backend.setup.SetupService
import net.robog.clarity.backend.setup.StoredModelKeyLoader
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.Chip
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionKind
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.session.SetupStatus
import net.robog.clarity.shared.session.StartSession
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.testcontainers.postgresql.PostgreSQLContainer

private fun <A> Either<*, A>.ok(): A = (this as? Either.Right)?.value ?: error("expected success but was $this")

/** Schedules that follow the settings, the prep and recall jobs, and the key that wakes the lead without a restart. */
@EnabledIf(ContainerRuntimeAvailable::class)
class SessionJobsAndKeyTest : FunSpec({
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

    suspend fun rig(apiKey: String? = "sk-test") = LeadRig(database, Clock.System, apiKey).also { it.reset() }

    fun appConfig(dir: java.nio.file.Path, agent: AgentConfig) = AppConfig(
        instance = InstanceConfig("t"),
        http = HttpConfig(0),
        database = DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 2),
        dataDir = dir,
        enrollment = EnrollmentConfig(EnrollmentMode.TOFU, token = null),
        connectors = ConnectorsConfig(autoGrant = false, secretsDir = dir.resolve("secrets")),
        agent = agent,
    )

    test("settings persist and a changed call time moves the prep job at once") {
        val r = rig()
        val store = SettingsStore(database, Clock.System)
        store.current() shouldBe Settings()
        val prep = MorningCallPrepJob(r.inbox, r.dayPlans)
        val scheduler = Scheduler(database, setOf(prep), store, Clock.System)
        scheduler.ensureRows()

        val mine = Settings(
            morningCall = Settings.MorningCall(at = LocalTime(7, 15), days = setOf(1, 2, 3, 4, 5, 6, 7), headsUpMinutes = 30),
            timeZone = "Asia/Tokyo",
        )
        store.save(mine).ok() shouldBe mine
        store.current() shouldBe mine
        scheduler.resetSchedules()

        val next = database.tx { ScheduledJobs.selectAll().where { ScheduledJobs.name eq "morning-call-prep" }.single()[ScheduledJobs.nextRunAt] }
        next shouldBe nextPrepAfter(mine.morningCall, Clock.System.now(), TimeZone.of("Asia/Tokyo"))
        // 30 minutes before 07:15 Tokyo time
        (next.toString().substring(11, 16)) shouldBe "21:45" // 06:45 JST in UTC

        store.save(mine.copy(timeZone = "Nowhere/Land")).shouldBeInstanceOf<Either.Left<String>>()
        store.current() shouldBe mine
    }

    test("the prep job asks for a quiet low-priority preparation with yesterday's plan, once per slot") {
        val r = rig()
        val prep = MorningCallPrepJob(r.inbox, r.dayPlans)
        val scheduledFor = Instant.parse("2026-10-26T08:50:00Z") // ten minutes before a 09:00 UTC call
        val settings = Settings()
        r.dayPlans.update(LocalDate(2026, 10, 25)) { it.copy(priorities = listOf(Chip("onboarding spec")), plan = listOf(IfThen("it's 9:30", "spec")), parked = listOf("expense report"), called = true) }

        val context = FireContext(scheduledFor, scheduledFor, null, settings)
        prep.fire(context)
        prep.fire(context) // a retry of the same slot adds nothing
        val items = r.inbox.pending()
        items shouldHaveSize 1
        val system = items.single().item.shouldBeInstanceOf<InboxItem.System>()
        system.priority shouldBe Priority.LOW
        system.text shouldContain "onboarding spec"
        system.text shouldContain "parked until today: expense report"
        system.text shouldContain "Do NOT message the person"

        // next slot, next item; disabled calls prepare nothing
        prep.fire(FireContext(scheduledFor + 24.hours, scheduledFor + 24.hours, scheduledFor, settings))
        r.inbox.pending() shouldHaveSize 2
        prep.fire(FireContext(scheduledFor + 48.hours, scheduledFor + 48.hours, null, settings.copy(morningCall = settings.morningCall.copy(enabled = false))))
        r.inbox.pending() shouldHaveSize 2
    }

    test("the recall job rings a snoozed call that is due") {
        val r = rig()
        val clock = net.robog.clarity.backend.agent.MovableClock()
        val sessions = SessionService(SessionStore(database), r.feed, r.inbox, r.heldStore, r.constitutions, clock)
        val id = sessions.start(StartSession(SessionKind.MORNING_CALL)).ok().id
        sessions.control(id, SessionControl.Snooze(10)).ok()
        val job = SnoozeRecallJob(sessions)
        val now = clock.now()
        job.fire(FireContext(now, now, null, Settings()))
        sessions.get(id).ok().state shouldBe Session.State.SNOOZED
        clock.advance(11.seconds * 60)
        job.fire(FireContext(clock.now(), clock.now(), null, Settings()))
        sessions.get(id).ok().state shouldBe Session.State.BUSY
    }

    test("only one session can be live, even when two start at once") {
        val r = rig()
        val store = SessionStore(database)
        val a = startRecord(net.robog.clarity.shared.session.SessionId(java.util.UUID.randomUUID().toString()), SessionKind.MORNING_CALL, Clock.System.now())
        val b = startRecord(net.robog.clarity.shared.session.SessionId(java.util.UUID.randomUUID().toString()), SessionKind.AMEND, Clock.System.now())
        store.begin(a, Clock.System.now())
        val (_, replaced) = store.begin(b, Clock.System.now())
        replaced?.id shouldBe a.id
        store.live()?.id shouldBe b.id
        store.get(a.id)?.state shouldBe Session.State.ENDED
        r.sessionService.active() shouldBe listOf(store.live()!!.session)
    }

    test("a model key set in setup wakes the lead without a restart; a configured key is never replaced") {
        val r = rig(apiKey = null)
        val dir = Files.createTempDirectory("clarity-key")
        val config = appConfig(dir, r.config)
        var verdict: KeyCheck = KeyCheck.Valid
        val keys = ModelKeyStore(config)
        val setup = SetupService(r.runtime, keys, { verdict }, SettingsStore(database, Clock.System), r.constitutions, ConnectorRegistry(database, Clock.System, config))

        setup.status().modelKey shouldBe SetupStatus.KeyState.MISSING
        r.runtime.enabled shouldBe false

        // the lead is waiting for a key; the person's words queue up meanwhile
        scope.launch { r.lead.run() }
        delay(300)
        r.model.handler = { end("hello") }
        r.inbox.enqueue(InboxItem.UserInput(ClientInputId("u1"), "hi", net.robog.clarity.shared.feed.InputSource.TYPED))
        delay(500)
        r.model.requests.size shouldBe 0

        verdict = KeyCheck.Rejected("no")
        setup.setModelKey("sk-bad").shouldBeInstanceOf<Either.Left<SetupError>>().value.shouldBeInstanceOf<SetupError.Rejected>()
        setup.status().modelKey shouldBe SetupStatus.KeyState.INVALID
        verdict = KeyCheck.Unreachable("offline")
        setup.setModelKey("sk-offline").shouldBeInstanceOf<Either.Left<SetupError>>().value.shouldBeInstanceOf<SetupError.Unreachable>()
        keys.read() shouldBe null

        verdict = KeyCheck.Valid
        setup.setModelKey(" sk-good ").ok().modelKey shouldBe SetupStatus.KeyState.VALID
        keys.read() shouldBe "sk-good"
        withTimeout(20.seconds) { while (r.model.requests.isEmpty()) delay(50) }
        r.inbox.pending().size shouldBe 0

        // and a restart with only the stored key enables a fresh runtime
        val fresh = rig(apiKey = null)
        fresh.runtime.enabled shouldBe false
        StoredModelKeyLoader(keys, fresh.runtime).run()
        fresh.runtime.enabled shouldBe true

        // a configured key wins
        val configured = rig(apiKey = "sk-from-secret")
        val managed = SetupService(configured.runtime, keys, { KeyCheck.Valid }, SettingsStore(database, Clock.System), configured.constitutions, ConnectorRegistry(database, Clock.System, config))
        managed.setModelKey("sk-other").shouldBeInstanceOf<Either.Left<SetupError>>().value shouldBe SetupError.ManagedByServer
        keys.read() shouldBe "sk-good"
        StoredModelKeyLoader(keys, configured.runtime).run()
        configured.runtime.installKey("sk-x") shouldBe false
    }
})
