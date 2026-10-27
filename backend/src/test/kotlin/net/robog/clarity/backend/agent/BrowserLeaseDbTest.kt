package net.robog.clarity.backend.agent

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

/** The lease tables (V22) in Postgres, and the lead's tool set. */
@EnabledIf(ContainerRuntimeAvailable::class)
class BrowserLeaseDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database
    val idle = 10.minutes
    val stale = 2.minutes

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }
    beforeTest {
        database.tx {
            exec("UPDATE browser_lease SET holder_run = NULL, acquired_at = NULL, heartbeat_at = NULL, last_activity_at = NULL")
            exec("TRUNCATE browser_lease_queue, browser_resume")
        }
    }

    test("one holder; the others queue first come first served; release hands it to the head of the queue") {
        val leases = PgBrowserLeases(database)
        val clock = StepClock()
        leases.tryAcquire("a", clock.now(), idle, stale) shouldBe LeaseTry.Granted(fresh = true)
        leases.tryAcquire("a", clock.now(), idle, stale) shouldBe LeaseTry.Granted(fresh = false)
        clock.advance(1.seconds)
        leases.tryAcquire("b", clock.now(), idle, stale) shouldBe LeaseTry.Waiting("a", 0)
        clock.advance(1.seconds)
        leases.tryAcquire("c", clock.now(), idle, stale) shouldBe LeaseTry.Waiting("a", 1)
        leases.release("a") shouldBe true
        // c asks first after the release, but b was there before it.
        leases.tryAcquire("c", clock.now(), idle, stale) shouldBe LeaseTry.Waiting(null, 1)
        leases.tryAcquire("b", clock.now(), idle, stale) shouldBe LeaseTry.Granted(fresh = true)
        leases.holder() shouldBe "b"
        leases.release("b")
        leases.tryAcquire("c", clock.now(), idle, stale) shouldBe LeaseTry.Granted(fresh = true)
    }

    test("concurrent tries never give the lease to two runs") {
        val leases = PgBrowserLeases(database)
        val now = StepClock().now()
        val outcomes = (1..8).map { i -> async { leases.tryAcquire("r$i", now, idle, stale) } }.awaitAll()
        outcomes.count { it is LeaseTry.Granted } shouldBe 1
    }

    test("an idle or stale holder loses the lease; releaseExpired says who; activity keeps it") {
        val leases = PgBrowserLeases(database)
        val clock = StepClock()
        leases.tryAcquire("a", clock.now(), idle, stale)
        clock.advance(1.minutes)
        leases.releaseExpired(clock.now(), idle, stale).shouldBeNull()
        leases.touch("a", clock.now())
        clock.advance(3.minutes) // heartbeat stale (a crash)
        leases.releaseExpired(clock.now(), idle, stale) shouldBe "a"
        leases.holder().shouldBeNull()

        leases.tryAcquire("b", clock.now(), idle, stale)
        for (i in 1..11) { clock.advance(1.minutes); leases.heartbeat("b", clock.now()) } // alive, but no browser call
        leases.tryAcquire("c", clock.now(), idle, stale) shouldBe LeaseTry.Granted(fresh = true)
    }

    test("startup clears the lease and queue places of runs that are not running") {
        val leases = PgBrowserLeases(database)
        val now = StepClock().now()
        leases.tryAcquire("gone", now, idle, stale)
        leases.tryAcquire("alive", now, idle, stale)
        leases.tryAcquire("also-gone", now, idle, stale)
        leases.clearUnless { it == "alive" } shouldBe 2
        leases.holder().shouldBeNull()
        leases.tryAcquire("alive", now, idle, stale) shouldBe LeaseTry.Granted(fresh = true)
    }

    test("resumes: the latest of another run of the same task") {
        val leases = PgBrowserLeases(database)
        val clock = StepClock()
        val key = taskKey("Book a haircut")!!
        leases.saveResume(BrowserResume("r1", key, "https://a.example/one", "One", null, clock.now()))
        clock.advance(1.minutes)
        leases.saveResume(BrowserResume("r2", key, "https://a.example/two", "Two", "on the code page of a.example", clock.now()))
        leases.saveResume(BrowserResume("r3", taskKey("Other"), "https://b.example/", "B", null, clock.now()))
        leases.lastResume(key, "r9")!!.url shouldBe "https://a.example/two"
        leases.lastResume(key, "r2")!!.url shouldBe "https://a.example/one"
    }

    test("the lead's tools have no browser tool, no ask_code and no list_credentials") {
        val rig = LeadRig(database)
        val names = rig.toolbox.leadTools(RunScope("lead", isLead = true)).tools.map { it.name }
        names.filter { it.startsWith(BrowserGate.PREFIX) } shouldBe emptyList()
        names shouldNotContain "ask_code"
        names shouldNotContain "list_credentials"
        // Helpers keep them.
        rig.toolbox.helperTools(RunScope("h", isLead = false), null).tools.tools.map { it.name }.let { (("ask_code" in it) && ("list_credentials" in it)) shouldBe true }
    }
})
