package net.robog.clarity.backend.scheduler

import arrow.core.right
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid
import net.robog.clarity.backend.agent.AgentTasks
import net.robog.clarity.backend.agent.DecisionAnswer
import net.robog.clarity.backend.agent.DecisionClient
import net.robog.clarity.backend.agent.DecisionResult
import net.robog.clarity.backend.agent.DecisionUsage
import net.robog.clarity.backend.agent.Decisions
import net.robog.clarity.backend.agent.ExposedInboxStore
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.InboxSignals
import net.robog.clarity.backend.agent.ModelCalls
import net.robog.clarity.backend.agent.Priority
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.governance.HeldActionStore
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.CalendarEvents
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.Messages
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.governance.Decision
import net.robog.clarity.shared.governance.EmailDraft
import net.robog.clarity.shared.governance.GuardVerdict
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.Urgency
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.session.Settings
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.testcontainers.postgresql.PostgreSQLContainer

@EnabledIf(ContainerRuntimeAvailable::class)
class ReviewGateDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database
    val now = Clock.System.now()
    val since = now - 2.hours

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }
    beforeTest {
        database.tx { exec("TRUNCATE message, calendar_event, inbox_item, held_action, agent_task, model_call, scheduled_job RESTART IDENTITY CASCADE") }
    }

    suspend fun message(subject: String, seen: Instant, from: String = "alice@example.com", deleted: Boolean = false) = database.tx {
        Messages.insert {
            it[id] = Uuid.random()
            it[dedupKey] = Uuid.random().toByteArray()
            it[firstSeenAt] = seen
            it[updatedAt] = seen
            it[deletedAt] = if (deleted) seen else null
            it[channel] = "email"
            it[account] = Handle.email("me@example.com")
            it[sender] = Handle.email(from, "Alice")
            it[recipients] = listOf(Handle.email("me@example.com"))
            it[participants] = listOf(from, "me@example.com")
            it[Messages.subject] = subject
            it[body] = "Body of $subject"
            it[sentAt] = seen
        }
    }

    suspend fun event(title: String, start: Instant, firstSeen: Instant, updated: Instant = firstSeen, deleted: Instant? = null) = database.tx {
        CalendarEvents.insert {
            it[id] = Uuid.random()
            it[dedupKey] = Uuid.random().toByteArray()
            it[firstSeenAt] = firstSeen
            it[updatedAt] = updated
            it[deletedAt] = deleted
            it[calendar] = "personal"
            it[CalendarEvents.title] = title
            it[startAt] = start
            it[endAt] = start + 1.hours
            it[allDay] = false
            it[attendees] = emptyList()
        }
    }

    test("what changed: new messages newest first, calendar changes and events starting soon, waiting items") {
        message("old", since - 1.hours)
        message("first", since + 10.minutes)
        message("second", since + 50.minutes)
        message("deleted", since + 20.minutes, deleted = true)
        event("Added", now + 30.hours, firstSeen = since + 5.minutes)
        event("Moved", now + 40.hours, firstSeen = since - 10.hours, updated = since + 15.minutes)
        event("Cancelled", now + 20.hours, firstSeen = since - 10.hours, deleted = since + 30.minutes)
        event("Dentist", now + 90.minutes, firstSeen = since - 48.hours)
        event("Old and far", now + 50.hours, firstSeen = since - 48.hours)
        val inbox = ExposedInboxStore(database, Clock.System, InboxSignals())
        inbox.enqueue(InboxItem.JobDue("periodic-review", "review"), "job:periodic-review:earlier")
        inbox.enqueue(InboxItem.JobDue("memory-gc", "tidy", Priority.LOW), "job:memory-gc:x")
        HeldActionStore(database, Clock.System).insert(
            ProposedAction.SaveDraft("k1", "Draft a reply to Alice", EmailDraft(to = listOf(Handle.email("alice@example.com")), subject = "Re", body = "Hi")),
            GuardVerdict(Decision.ASK, rationale = "goes out as you"), "", emptyList(), Urgency.NORMAL, HeldAction.Status.HELD,
        )
        database.tx {
            AgentTasks.insert {
                it[id] = Uuid.random(); it[title] = "Find a plumber"; it[detail] = "waiting on two quotes"; it[status] = "working"
                it[createdAt] = since; it[updatedAt] = since
            }
            AgentTasks.insert {
                it[id] = Uuid.random(); it[title] = "Finished"; it[status] = "done"; it[createdAt] = since; it[updatedAt] = since
            }
        }

        val snapshot = ExposedReviewChanges(database).since(since, now, soonWindow(2.hours))

        snapshot.messageCount shouldBe 2
        snapshot.messages.map { it.subject } shouldContainExactly listOf("second", "first")
        snapshot.messages.first().from shouldBe "Alice <alice@example.com>"
        snapshot.events.map { it.title to it.change } shouldContainExactly listOf(
            "Dentist" to ReviewEvent.Change.STARTING_SOON,
            "Cancelled" to ReviewEvent.Change.CANCELLED,
            "Added" to ReviewEvent.Change.ADDED,
            "Moved" to ReviewEvent.Change.CHANGED,
        )
        snapshot.pendingInbox.single().text shouldContain "memory-gc"
        snapshot.held.single().text shouldBe "Draft a reply to Alice"
        snapshot.openTasks.single().text shouldBe "Find a plumber (working): waiting on two quotes"
        snapshot.changed shouldBe true
    }

    test("last wake is the newest periodic-review inbox item") {
        val changes = ExposedReviewChanges(database)
        changes.lastWakeAt() shouldBe null
        val inbox = ExposedInboxStore(database, Clock.System, InboxSignals())
        inbox.enqueue(InboxItem.JobDue("memory-gc", "tidy"), "job:memory-gc:a")
        changes.lastWakeAt() shouldBe null
        inbox.enqueue(InboxItem.JobDue("periodic-review", "review"), "job:periodic-review:a")
        val first = changes.lastWakeAt()!!
        inbox.enqueue(InboxItem.JobDue("periodic-review", "review"), "job:periodic-review:b")
        (changes.lastWakeAt()!! >= first) shouldBe true
    }

    test("end to end: a decision call is recorded as role decision with its cost, and a 'can wait' skips the review") {
        val inbox = ExposedInboxStore(database, Clock.System, InboxSignals())
        inbox.enqueue(InboxItem.JobDue("periodic-review", "review"), "job:periodic-review:earlier")
        inbox.markConsumed(inbox.pending().map { it.seq })
        message("Weekly deals", Clock.System.now())
        val config = AgentConfig(anthropicApiKey = null, reviewInterval = 2.hours, openRouterApiKey = "k")
        val client = DecisionClient { _, _ ->
            DecisionResult(
                mapOf("act_now" to DecisionAnswer.Noul(0.02), "urgency" to DecisionAnswer.Choice("none", emptyMap(), null)),
                "microsoft/microsoft-decision-1-20261009", "Azure", DecisionUsage(164, 2, 7),
            ).right()
        }
        val gate = ReviewGate(config, ExposedReviewChanges(database), Decisions(config, { client }, database, Clock.System))
        val at = Clock.System.now() + 1.minutes
        PeriodicReviewJob(config, inbox, gate).fire(FireContext(at, at, at - 2.hours, Settings()))

        inbox.pending().size shouldBe 0
        database.tx {
            ModelCalls.selectAll().map { Triple(it[ModelCalls.role], it[ModelCalls.model], it[ModelCalls.costMicros]) }
        } shouldContainExactly listOf(Triple("decision", "microsoft/microsoft-decision-1", 7L))
    }
})
