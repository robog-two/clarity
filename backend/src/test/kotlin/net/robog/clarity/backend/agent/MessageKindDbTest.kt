package net.robog.clarity.backend.agent

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.feed.ExposedFeedStore
import net.robog.clarity.backend.feed.FeedHub
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.governance.ConstitutionStore
import net.robog.clarity.backend.governance.GovernanceEvents
import net.robog.clarity.backend.governance.HeldActionStore
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.scheduler.EveningBatchJob
import net.robog.clarity.backend.scheduler.FireContext
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.Choice
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.MessageKind
import net.robog.clarity.shared.session.Settings
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

private class KindClock(var now: Instant) : Clock {
    override fun now() = now
}

@EnabledIf(ContainerRuntimeAvailable::class)
class MessageKindDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine").withStartupAttempts(3)
    lateinit var database: Database

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }
    beforeTest { database.tx { exec("TRUNCATE feed_item, agent_task, constitution_version RESTART IDENTITY CASCADE") } }

    // Wednesday 14:00 UTC; the evening summary is at 18:00.
    val start = Instant.parse("2026-03-04T14:00:00Z")

    class Rig(database: Database, now: Instant, var settings: Settings = Settings()) {
        val clock = KindClock(now)
        val store = ExposedFeedStore(database, clock)
        val hub = FeedHub()
        val feed = FeedService(store, hub)
        val constitutions = ConstitutionStore(database, clock, HeldActionStore(database, clock), GovernanceEvents())
        val tasks = ExposedTaskStore(database, clock, feed)
        val own = OwnStateTools(
            feed, ExposedMemoryStore(database, clock), constitutions, tasks, { settings }, clock, FailureGate(null),
            net.robog.clarity.backend.sources.SourceResolver(
                net.robog.clarity.backend.persistence.ExposedNounReader(database), { settings }, PgRunJournal(database, clock), ExposedRunStore(database, clock),
            ),
        )

        /** A turn of the lead; [personWrote] is whether the person's input is in its batch. */
        fun scope(personWrote: Boolean) = RunScope("lead", isLead = true).also { it.startTurn(untrusted = false, requestedByUser = personWrote) }

        suspend fun send(
            personWrote: Boolean = false,
            kind: String? = null,
            urgency: String? = null,
            dueBy: String? = null,
            cards: List<Card> = listOf(Card.Text("Something")),
        ): ToolResult = own.sendToUser(scope(personWrote)).invoke(
            buildJsonObject {
                put("cards", ClarityJson.encodeToJsonElement(cards))
                kind?.let { put("kind", it) }
                urgency?.let { put("urgency", it) }
                dueBy?.let { put("dueBy", it) }
            },
        )

        suspend fun last(): FeedItem = feed.page(0, 100).items.last()
    }

    test("a reply is decided by the server: input in the turn makes it one, whatever the model said") {
        val rig = Rig(database, start)
        rig.send(personWrote = true, kind = "finding").shouldBeInstanceOf<ToolResult.Ok>()
        rig.last().kind shouldBe MessageKind.REPLY
        rig.last().notify shouldBe "reply"
        rig.store.countedPushes(start - 24.hours, start + 24.hours) shouldBe 0
    }

    test("a reply claimed outside such a turn is a finding") {
        val rig = Rig(database, start)
        rig.send(personWrote = false, kind = "reply", dueBy = "2026-03-04T16:00").shouldBeInstanceOf<ToolResult.Ok>()
        rig.last().kind shouldBe MessageKind.FINDING
        rig.last().notify shouldBe "push_now"
    }

    test("a finding with no due time is held for the evening summary, out of the feed") {
        val rig = Rig(database, start)
        val ok = rig.send().shouldBeInstanceOf<ToolResult.Ok>()
        ok.content.contains("evening summary") shouldBe true
        rig.feed.page(0, 100).items.shouldBeEmpty()
        rig.store.pendingBatch().single().kind shouldBe MessageKind.FINDING
    }

    test("check-ins push and count; past the daily limit they are shown without a push") {
        val rig = Rig(database, start, Settings(pushesPerDay = 2))
        repeat(3) { rig.send(kind = "check_in").shouldBeInstanceOf<ToolResult.Ok>() }
        rig.feed.page(0, 100).items.map { it.notify } shouldBe listOf("push_now", "push_now", "in_app")
        rig.store.countedPushes(start - 1.hours, start + 1.hours) shouldBe 2
        // the next local day starts over
        rig.clock.now = start + 24.hours
        rig.send(kind = "look_forward")
        rig.last().notify shouldBe "push_now"
    }

    test("urgent is not counted, and replies do not use up the budget") {
        val rig = Rig(database, start, Settings(pushesPerDay = 1))
        rig.send(urgency = "urgent")
        rig.send(personWrote = true)
        rig.store.countedPushes(start - 1.hours, start + 1.hours) shouldBe 0
        rig.send(kind = "check_in")
        rig.last().notify shouldBe "push_now"
    }

    test("an approval or a login waiting on the person is never held for the evening") {
        val rig = Rig(database, start)
        rig.send(cards = listOf(Card.Text("Needs your OK"), Card.Proposal(net.robog.clarity.shared.governance.HeldActionId("h1")))).shouldBeInstanceOf<ToolResult.Ok>()
        rig.feed.page(0, 100).items.single().notify shouldBe "in_app"
        rig.store.pendingBatch().shouldBeEmpty()
    }

    test("a due time that is not a time is refused, and a choices card needs options") {
        val rig = Rig(database, start)
        rig.send(dueBy = "soonish").shouldBeInstanceOf<ToolResult.Error>()
        rig.send(cards = listOf(Card.Choices("Ready?", emptyList()))).shouldBeInstanceOf<ToolResult.Error>()
        rig.feed.page(0, 100).items.shouldBeEmpty()
    }

    test("a choices card is stored unanswered with its fallback text, whatever the model passed") {
        val rig = Rig(database, start)
        val card = Card.Choices("Ready?", listOf(Choice("Yes", "yes"), Choice("No", "no")), chosen = "yes", fallbackText = "wrong")
        rig.send(kind = "check_in", cards = listOf(card)).shouldBeInstanceOf<ToolResult.Ok>()
        val stored = rig.last().cards.single() as Card.Choices
        stored.chosen shouldBe null
        stored.fallbackText shouldBe "Ready? Reply with Yes or No."
    }

    test("the first tap answers a choices card on every device, once") {
        val rig = Rig(database, start)
        rig.send(kind = "check_in", cards = listOf(Card.Choices("Ready?", listOf(Choice("Yes", "yes"), Choice("No", "no")))))
        val item = rig.last()
        val answered = rig.feed.chooseOption(item.id, "no")!!
        (answered.cards.single() as Card.Choices).chosen shouldBe "no"
        (rig.feed.get(item.id)!!.cards.single() as Card.Choices).chosen shouldBe "no"
        rig.feed.chooseOption(item.id, "yes") shouldBe null
        rig.feed.chooseOption(item.id, "typed something else") shouldBe null
        (rig.feed.get(item.id)!!.cards.single() as Card.Choices).chosen shouldBe "no"
        rig.feed.page(0, 100).items.single().seq shouldBe item.seq
    }

    test("the evening summary lists what is coming up, what got done, and what was held, and only those") {
        val rig = Rig(database, start)
        rig.send(kind = "finding", cards = listOf(Card.Text("Newsletter ready")))
        rig.send(kind = "look_forward", cards = listOf(Card.Text("Jazz on Thursday evening?")))
        rig.tasks.upsert(null, "Send the report", "done", AgentTask.Status.DONE)
        rig.tasks.upsert(null, "Still working", "busy", AgentTask.Status.WORKING)
        rig.clock.now = Instant.parse("2026-03-04T18:00:05Z")
        val job = EveningBatchJob(rig.store, rig.feed, rig.tasks)
        val ctx = FireContext(Instant.parse("2026-03-04T18:00:00Z"), rig.clock.now, null, Settings())
        job.fire(ctx)
        val summary = rig.feed.page(0, 100).items.last()
        summary.cards.map { (it as? Card.Chips)?.label ?: "intro" } shouldBe listOf("intro", "Coming up", "Done today", "Held for tonight")
        (summary.cards[1] as Card.Chips).items.map { it.text } shouldBe listOf("Jazz on Thursday evening?")
        (summary.cards[2] as Card.Chips).items.map { it.text } shouldBe listOf("Send the report")
        (summary.cards[3] as Card.Chips).items.map { it.text } shouldBe listOf("Newsletter ready")
        // a look-forward already covered is not listed again by the next day's summary
        val tomorrow = FireContext(Instant.parse("2026-03-05T18:00:00Z"), Instant.parse("2026-03-05T18:00:05Z"), Instant.parse("2026-03-04T18:00:05Z"), Settings())
        val before = rig.feed.page(0, 100).items.size
        job.fire(tomorrow)
        rig.feed.page(0, 100).items shouldHaveSize before
    }

    test("nothing to say, no summary") {
        val rig = Rig(database, start)
        val job = EveningBatchJob(rig.store, rig.feed, rig.tasks)
        job.fire(FireContext(Instant.parse("2026-03-04T18:00:00Z"), Instant.parse("2026-03-04T18:00:05Z"), null, Settings()))
        rig.feed.page(0, 100).items.shouldBeEmpty()
    }

    test("a post inside a session keeps the old behaviour: no batching, no counting") {
        val rig = Rig(database, start)
        val tool = rig.own.sendToUser(rig.scope(false))
        tool.invoke(
            buildJsonObject {
                put("cards", ClarityJson.encodeToJsonElement(listOf<Card>(Card.Text("In the call"))))
                put("sessionId", JsonPrimitive("s1"))
            },
        ).shouldBeInstanceOf<ToolResult.Ok>()
        rig.last().notify shouldBe "in_app"
        rig.store.pendingBatch().shouldBeEmpty()
        // an answer to the person in a session is a reply like anywhere else
        rig.own.sendToUser(rig.scope(true)).invoke(
            buildJsonObject {
                put("cards", ClarityJson.encodeToJsonElement(listOf<Card>(Card.Text("Got it"))))
                put("sessionId", JsonPrimitive("s1"))
            },
        ).shouldBeInstanceOf<ToolResult.Ok>()
        rig.last().notify shouldBe "reply"
    }
})

