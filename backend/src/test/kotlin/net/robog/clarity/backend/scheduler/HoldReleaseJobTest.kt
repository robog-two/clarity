package net.robog.clarity.backend.scheduler

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.datetime.TimeZone
import net.robog.clarity.backend.agent.ScheduledJobs
import net.robog.clarity.backend.agent.SettingsProvider
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.feed.ExposedFeedStore
import net.robog.clarity.backend.feed.FeedDraft
import net.robog.clarity.backend.feed.FeedHub
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.feed.isNewerThan
import net.robog.clarity.backend.governance.ConstitutionStore
import net.robog.clarity.backend.governance.GovernanceEvents
import net.robog.clarity.backend.governance.HeldActionStore
import net.robog.clarity.backend.governance.NotifyDecision
import net.robog.clarity.backend.agent.wire
import net.robog.clarity.backend.governance.block
import net.robog.clarity.backend.governance.constitution
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedEvent
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.governance.TimeBlock
import net.robog.clarity.shared.session.Settings
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.update
import org.testcontainers.postgresql.PostgreSQLContainer

private fun at(s: String) = Instant.parse(s)

/** Wednesday 09:00-12:00 UTC. */
private val deepWork = block("deep work", setOf(3), "09:00", "12:00")

private class TestClock(var now: Instant) : Clock {
    override fun now() = now
}

class HoldOutcomeTest : FunSpec({
    val utc = TimeZone.UTC
    val sentAt = at("2026-03-04T10:30:00Z")

    test("inside protected time the hold waits") {
        holdOutcome(sentAt, listOf(deepWork), utc, Settings(), at("2026-03-04T11:59:00Z")) shouldBe HoldOutcome.Wait
    }

    test("at the end of the block it is released") {
        holdOutcome(sentAt, listOf(deepWork), utc, Settings(), at("2026-03-04T12:00:00Z")) shouldBe HoldOutcome.Release
    }

    test("a block removed or shortened releases early; one extended keeps holding") {
        holdOutcome(sentAt, emptyList(), utc, Settings(), at("2026-03-04T10:45:00Z")) shouldBe HoldOutcome.Release
        val longer = block("deep work", setOf(3), "09:00", "14:00")
        holdOutcome(sentAt, listOf(longer), utc, Settings(), at("2026-03-04T12:30:00Z")) shouldBe HoldOutcome.Wait
    }

    test("too old, or urgent push turned off, drops to the app without a notification") {
        holdOutcome(sentAt, emptyList(), utc, Settings(), sentAt + 25.hours) shouldBe HoldOutcome.Drop
        holdOutcome(sentAt, emptyList(), utc, Settings(pushUrgent = false), at("2026-03-04T12:00:00Z")) shouldBe HoldOutcome.Drop
    }

    test("a released item passes the SSE filter even below the replayed position") {
        val item = FeedItem(
            net.robog.clarity.shared.feed.FeedItemId("i"), 3, sentAt, FeedItem.Author.AGENT, cards = emptyList(),
            notify = releasedNotify(at("2026-03-04T12:00:00Z")),
        )
        isNewerThan(FeedEvent.ItemFinal(item), 10) shouldBe true
        isNewerThan(FeedEvent.ItemFinal(item.copy(notify = "push_now")), 10) shouldBe false
    }
})

@EnabledIf(ContainerRuntimeAvailable::class)
class HoldReleaseJobTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }
    beforeTest { database.tx { exec("TRUNCATE scheduled_job, feed_item, constitution_version RESTART IDENTITY CASCADE") } }

    val sentAt = at("2026-03-04T10:30:00Z")
    val holdUntil = NotifyDecision.HoldUntil(at("2026-03-04T12:00:00Z")).wire()

    class Rig(database: Database, now: Instant) {
        val clock = TestClock(now)
        val store = ExposedFeedStore(database, clock)
        val hub = FeedHub()
        val feed = FeedService(store, hub)
        val constitutions = ConstitutionStore(database, clock, HeldActionStore(database, clock), GovernanceEvents())
        val job = HoldReleaseJob(store, feed, constitutions)
        val published = CopyOnWriteArrayList<FeedEvent>()

        suspend fun held(text: String = "Dana called twice", notify: String): FeedItem =
            feed.append(FeedDraft(FeedItem.Author.AGENT, listOf(Card.Text(text)), notify = notify)).item

        /** Runs [block] while recording what is published (an unconfined collector sees each event at once). */
        suspend fun <T> recording(block: suspend () -> T): T = coroutineScope {
            val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { hub.events.collect { published += it } }
            val result = block()
            yield()
            collector.cancelAndJoin()
            result
        }

        /** Runs the job's work at [now] while recording what it publishes. */
        suspend fun releaseAt(now: Instant, blocks: List<TimeBlock> = listOf(deepWork)): List<FeedItem> {
            clock.now = now
            return recording { job.release(store.heldNotifications(), blocks, Settings(), now) }
        }
    }

    test("not due: inside protected time nothing changes and nothing is published") {
        val rig = Rig(database, sentAt)
        val item = rig.held(notify = holdUntil)
        rig.releaseAt(at("2026-03-04T11:00:00Z")).shouldBeEmpty()
        rig.store.notifyOf(item.id) shouldBe holdUntil
        rig.published.shouldBeEmpty()
    }

    test("due: released once, persisted and published as a final item") {
        val rig = Rig(database, sentAt)
        val item = rig.held(notify = holdUntil)
        val now = at("2026-03-04T12:00:30Z")
        rig.releaseAt(now).single().id shouldBe item.id
        rig.store.notifyOf(item.id) shouldBe "released:2026-03-04T12:00:30Z"
        (rig.published.single() as FeedEvent.ItemFinal).item.notify shouldBe "released:2026-03-04T12:00:30Z"
        rig.store.heldNotifications().shouldBeEmpty()
        rig.store.releasedSince(now - RELEASE_RESEND).map { it.id } shouldBe listOf(item.id)
    }

    test("already released: a second run, or a stale copy of the hold, does not release again") {
        val rig = Rig(database, sentAt)
        val item = rig.held(notify = holdUntil)
        rig.releaseAt(at("2026-03-04T12:01:00Z")) shouldHaveSize 1
        rig.releaseAt(at("2026-03-04T12:02:00Z")).shouldBeEmpty()
        // two instances that both read the hold before either released it
        rig.job.release(listOf(item), listOf(deepWork), Settings(), at("2026-03-04T12:03:00Z")).shouldBeEmpty()
        rig.published shouldHaveSize 1
        rig.store.notifyOf(item.id) shouldBe "released:2026-03-04T12:01:00Z"
    }

    test("restart: a hold that ended while the backend was down is released on the first tick, once") {
        val before = Rig(database, sentAt)
        val item = before.held(notify = holdUntil)
        before.constitutions.create(constitution(protectedTimes = listOf(deepWork)))
        val other = before.held("Plain", notify = "push_now")

        // A new process, hours after the block ended.
        val after = Rig(database, at("2026-03-04T15:00:00Z"))
        val scheduler = Scheduler(database, setOf(after.job), SettingsProvider { Settings() }, after.clock)
        scheduler.ensureRows()
        database.tx { ScheduledJobs.update({ ScheduledJobs.name eq "hold-release" }) { it[nextRunAt] = at("2026-03-04T10:31:00Z") } }
        after.recording {
            scheduler.tickOnce() shouldBe true
            scheduler.tickOnce() shouldBe false
            // and once more after another restart and another minute
            after.clock.now = at("2026-03-04T15:02:00Z")
            Scheduler(database, setOf(after.job), SettingsProvider { Settings() }, after.clock).also { it.ensureRows() }.tickOnce() shouldBe true
        }
        after.store.notifyOf(item.id) shouldBe "released:2026-03-04T15:00:00Z"
        after.store.notifyOf(other.id) shouldBe "push_now"
        after.published.map { (it as FeedEvent.ItemFinal).item.id } shouldBe listOf(item.id)
    }

    test("still protected after a restart: a hold from yesterday waits for the block to end") {
        // sent Tuesday evening during a block that has since been edited away; Wednesday's block is on
        val rig = Rig(database, at("2026-03-03T18:00:00Z"))
        val item = rig.held(notify = NotifyDecision.HoldUntil(at("2026-03-03T12:00:00Z")).wire())
        // a Wednesday block covers now; the recorded time has passed but protection is on again
        rig.releaseAt(at("2026-03-04T10:00:00Z")).shouldBeEmpty()
        rig.store.notifyOf(item.id) shouldBe "hold_until:2026-03-03T12:00:00Z"
        rig.releaseAt(at("2026-03-04T12:00:00Z")) shouldHaveSize 1
    }

    test("protection that ends early releases early; a very old hold is dropped to the app") {
        val rig = Rig(database, sentAt)
        val early = rig.held(notify = holdUntil)
        rig.releaseAt(at("2026-03-04T10:40:00Z"), blocks = emptyList()).single().id shouldBe early.id

        val old = Rig(database, sentAt - 3.days).held(notify = holdUntil)
        rig.releaseAt(at("2026-03-04T12:10:00Z")).shouldBeEmpty()
        rig.store.notifyOf(old.id) shouldBe "in_app"
        rig.store.releasedSince(sentAt - 10.days).map { it.id } shouldBe listOf(early.id)
    }

    test("the release window covers only recent releases") {
        val rig = Rig(database, sentAt)
        rig.held(notify = holdUntil)
        val now = at("2026-03-04T12:00:00Z")
        rig.releaseAt(now) shouldHaveSize 1
        rig.store.releasedSince(now + 1.minutes).shouldBeEmpty()
    }
})
