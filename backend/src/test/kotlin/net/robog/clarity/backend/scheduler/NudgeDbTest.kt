package net.robog.clarity.backend.scheduler

import arrow.core.left
import arrow.core.right
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.agent.DecisionAnswer
import net.robog.clarity.backend.agent.DecisionClient
import net.robog.clarity.backend.agent.DecisionError
import net.robog.clarity.backend.agent.DecisionResult
import net.robog.clarity.backend.agent.DecisionUsage
import net.robog.clarity.backend.agent.Decisions
import net.robog.clarity.backend.agent.ExposedInboxStore
import net.robog.clarity.backend.agent.ExposedNudgeStore
import net.robog.clarity.backend.agent.ExposedTaskStore
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.InboxSignals
import net.robog.clarity.backend.agent.MAX_NUDGES_PER_DAY
import net.robog.clarity.backend.agent.MAX_PENDING_NUDGES
import net.robog.clarity.backend.agent.NudgeTools
import net.robog.clarity.backend.agent.homeDay
import net.robog.clarity.backend.agent.isPlan
import net.robog.clarity.backend.agent.SettingsProvider
import net.robog.clarity.backend.agent.agentTime
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.feed.ExposedFeedStore
import net.robog.clarity.backend.feed.FeedHub
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.sessions.PlanItemState
import net.robog.clarity.backend.sessions.PlanItemStore
import net.robog.clarity.backend.sessions.Planned
import net.robog.clarity.shared.time.ScheduleClock
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.backend.persistence.NounReader
import net.robog.clarity.backend.persistence.Stored
import net.robog.clarity.backend.persistence.TimeRange
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.ContactPayload
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Payload
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.Nudge
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.time.zoneOrUtc
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

/** The person's recent activity, as the test sets it. */
private class FakeActivity(var lastMessage: Instant? = null, var live: Boolean = false) : NudgeActivity {
    override suspend fun lastPersonMessageAt() = lastMessage
    override suspend fun sessionLive() = live
    override suspend fun conversation(since: Instant, limit: Int) =
        listOfNotNull(lastMessage?.let { NudgeExchange(it, fromPerson = true, text = "the ML report is done, I sent it") })
}

private fun answer(p: Double) = DecisionResult(mapOf(ALREADY_UPDATED to DecisionAnswer.Noul(p)), "m", "p", DecisionUsage(10, 1, 2)).right()

@EnabledIf(ContainerRuntimeAvailable::class)
private class FakeCalendar : NounReader {
    val events = mutableListOf<Stored<CalendarEventPayload>>()
    override suspend fun calendarEvents(range: TimeRange, limit: Int) = events.filter { it.payload.start < range.until && it.payload.end > range.from }.take(limit)
    override suspend fun searchContacts(query: String, limit: Int) = emptyList<Stored<ContactPayload>>()
    override suspend fun changedSince(since: Instant, limit: Int) = emptyList<Stored<Payload>>()
    override suspend fun messages(range: TimeRange, participant: String?, limit: Int, folder: String?) = emptyList<Stored<MessagePayload>>()
}

class NudgeDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine").withStartupAttempts(3)
    lateinit var database: Database

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }
    beforeTest { database.tx { exec("TRUNCATE nudge, plan_item, agent_task, inbox_item, feed_item, model_call RESTART IDENTITY CASCADE") } }

    val settings = Settings()
    val zone = zoneOrUtc(settings.homeZone)

    class Rig(database: Database, client: DecisionClient? = DecisionClient { _, _ -> answer(0.1) }) {
        val clock: Clock = Clock.System
        val feed = FeedService(ExposedFeedStore(database, clock), FeedHub())
        val tasks = ExposedTaskStore(database, clock, feed)
        val calendar = FakeCalendar()
        val store = ExposedNudgeStore(database, clock)
        val inbox = ExposedInboxStore(database, clock, InboxSignals())
        val activity = FakeActivity()
        val calls = AtomicInteger()
        private val counting = client?.let { inner -> DecisionClient { s, q -> calls.incrementAndGet(); inner.decide(s, q) } }
        val config = AgentConfig(openRouterApiKey = "k", reviewInterval = 2.hours)
        val runner = NudgeRunner(store, tasks, PlanItemStore(database), inbox, activity, Decisions(config, { counting }, database, clock), { Settings() }, clock)
        val tools = NudgeTools(store, tasks, PlanItemStore(database), { Settings() }, clock, calendar).all().associateBy { it.name }

        suspend fun call(name: String, input: JsonObject): ToolResult = tools.getValue(name).invoke(input)
        suspend fun nudgeItems() = inbox.pending().map { it.item }.filterIsInstance<InboxItem.NudgeDue>()
    }

    fun input(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    test("the nudge tool validates, stores, lists and cancels") {
        val r = Rig(database)
        val now = Clock.System.now()
        val at = agentTime(now + 2.hours, zone)
        val task = r.tasks.upsert(null, "ML report", "", AgentTask.Status.WORKING)!!

        r.call("nudge", input("at" to agentTime(now - 5.minutes, zone), "note" to "x")).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "passed"
        r.call("nudge", input("at" to at, "note" to "ask", "taskId" to "00000000-0000-0000-0000-000000000000"))
            .shouldBeInstanceOf<ToolResult.Error>().message shouldContain "No task"
        val ok = r.call("nudge", input("at" to at, "note" to "ask how the ML report is going", "taskId" to task.id.value)).shouldBeInstanceOf<ToolResult.Ok>()
        ok.content shouldContain "Nothing is sent now"
        r.call("nudge", input("at" to agentTime(now + 2.hours + 5.minutes, zone), "note" to "again", "taskId" to task.id.value))
            .shouldBeInstanceOf<ToolResult.Error>().message shouldContain "already covers"

        val listed = r.call("list_nudges", JsonObject(emptyMap())).shouldBeInstanceOf<ToolResult.Ok>().content
        listed shouldContain "ask how the ML report is going"
        listed shouldContain "ML report (working)"

        val id = r.store.pending().single().id.value
        r.call("cancel_nudge", input("id" to id)).shouldBeInstanceOf<ToolResult.Ok>()
        r.store.pending().shouldBeEmpty()
        r.store.get(net.robog.clarity.shared.feed.NudgeId(id))!!.status shouldBe Nudge.Status.CANCELLED

        r.tasks.upsert(task.id, null, null, AgentTask.Status.DONE)
        r.call("nudge", input("at" to at, "note" to "ask", "taskId" to task.id.value)).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "already done"
    }

    test("a nudge the lead makes about a plan item is stored against it, and skipped once the item is done") {
        val r = Rig(database)
        val items = PlanItemStore(database)
        val now = Clock.System.now()
        val today = ScheduleClock.homeDate(now, zone)
        val item = items.plan(today, listOf(Planned("write the onboarding spec"))).single()
        val at = agentTime(now + 2.hours, zone)

        r.call("nudge", input("at" to at, "note" to "ask how the spec is going", "planItemId" to item.id)).shouldBeInstanceOf<ToolResult.Ok>()
        // an id that is no open plan item is not stored
        r.call("nudge", input("at" to agentTime(now + 5.hours, zone), "note" to "ask about something else", "planItemId" to "00000000-0000-0000-0000-000000000000"))
            .shouldBeInstanceOf<ToolResult.Ok>()
        val stored = r.store.pending().associateBy { it.note }
        stored.getValue("ask how the spec is going").planItemId shouldBe item.id
        stored.getValue("ask about something else").planItemId shouldBe null

        items.update(item.id) { it.copy(state = PlanItemState.DONE) }
        r.runner.fireDue(now + 2.hours + 1.minutes, zone)
        r.nudgeItems().shouldBeEmpty()
        val after = r.store.get(stored.getValue("ask how the spec is going").id)!!
        after.status shouldBe Nudge.Status.SKIPPED
        after.reason.shouldNotBeNull() shouldContain "plan item"
    }

    test("a nudge the lead links to a plan item stays an ordinary nudge: it counts, dedupes, and a redone plan leaves it alone") {
        val r = Rig(database)
        val items = PlanItemStore(database)
        val now = Clock.System.now()
        val today = ScheduleClock.homeDate(now, zone)
        val item = items.plan(today, listOf(Planned("write the onboarding spec"))).single()
        val at = now + 2.hours

        r.call("nudge", input("at" to agentTime(at, zone), "note" to "ask how the spec is going", "planItemId" to item.id)).shouldBeInstanceOf<ToolResult.Ok>()
        val linked = r.store.pending().single()
        linked.planItemId shouldBe item.id
        linked.isPlan shouldBe false

        // it counts as the person's own nudge, not as one of the plan flow's slots
        val (from, until) = homeDay(at, zone)
        r.store.countActive(from, until) shouldBe 1
        r.store.countActivePlan(from, until) shouldBe 0
        // and it dedupes like one: the same thing again a few minutes later is refused
        r.call("nudge", input("at" to agentTime(at + 5.minutes, zone), "note" to "check on the spec", "planItemId" to item.id))
            .shouldBeInstanceOf<ToolResult.Error>().message shouldContain "already covers"

        // the plan flow's own check-ins are the plan's slots, and redoing the plan cancels only those
        val auto = r.store.create(now + 4.hours, "ask about the spec", null, item.id, autoCheckIn = true)
        auto.isPlan shouldBe true
        r.store.countActivePlan(from, homeDay(now + 4.hours, zone).second) shouldBe 1
        r.store.cancelForPlanItem(item.id, "plan saved again") shouldBe 1
        r.store.get(auto.id)!!.status shouldBe Nudge.Status.CANCELLED
        r.store.get(linked.id)!!.status shouldBe Nudge.Status.PENDING
    }

    test("freeSlot picks the next gap, and exactly one of at and freeSlot is required") {
        val r = Rig(database)
        val slot = buildJsonObject { put("minMinutes", 30) }
        val ok = r.call("nudge", buildJsonObject { put("note", "ask how the walk went"); put("freeSlot", slot) }).shouldBeInstanceOf<ToolResult.Ok>()
        ok.content shouldContain "free from"
        r.store.pending().single().at.shouldNotBeNull()
        r.call("nudge", buildJsonObject { put("note", "x"); put("at", "23:59"); put("freeSlot", slot) }).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "either"
        r.call("nudge", buildJsonObject { put("note", "x") }).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "either"
    }

    test("freeSlot reads a malformed after by its own name") {
        val r = Rig(database)
        r.call("nudge", buildJsonObject { put("note", "x"); put("freeSlot", buildJsonObject { put("after", "soonish") }) })
            .shouldBeInstanceOf<ToolResult.Error>().message shouldContain "freeSlot.after"
    }

    test("freeSlot with a populated calendar") {
        val r = Rig(database)
        val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        fun event(from: Instant, to: Instant, allDay: Boolean = false) = Stored(
            EntityId("e${r.calendar.events.size}"), CalendarEventPayload(calendar = "c", title = "t", start = from, end = to, allDay = allDay), now, now,
        )
        fun slot(vararg pairs: Pair<String, Int>) = buildJsonObject {
            put("note", "ask how it went" + r.calendar.events.size)
            put("freeSlot", buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } })
        }
        // Back-to-back meetings from now to +90 min, a 6h event and an all-day event around them: only the meetings block.
        r.calendar.events += event(now - 30.minutes, now + 30.minutes)
        r.calendar.events += event(now + 30.minutes, now + 90.minutes)
        r.calendar.events += event(now - 1.hours, now + 5.hours + 1.minutes)
        r.calendar.events += event(now - 12.hours, now + 36.hours, allDay = true)
        val ok = r.call("nudge", slot()).shouldBeInstanceOf<ToolResult.Ok>()
        ok.content shouldContain "free from ${agentTime(now + 90.minutes, zone)}"
        r.store.pending().single().at.shouldNotBeNull() shouldBe (now + 90.minutes)

        // A calendar with a meeting every 10 minutes for the whole window has no 15-minute gap in a short look.
        val r2 = Rig(database)
        (0 until 12).forEach { i -> r2.calendar.events += Stored(EntityId("b$i"), CalendarEventPayload(calendar = "c", title = "t", start = now + (i * 10 - 5).minutes, end = now + (i * 10 + 10).minutes), now, now) }
        r2.call("nudge", buildJsonObject { put("note", "x"); put("freeSlot", buildJsonObject { put("within", 1) }) })
            .shouldBeInstanceOf<ToolResult.Error>().message shouldContain "No 15-minute gap"

        val r3 = Rig(database)
        repeat(310) { i -> r3.calendar.events += Stored(EntityId("m$i"), CalendarEventPayload(calendar = "c", title = "t", start = now + i.minutes, end = now + (i + 1).minutes), now, now) }
        r3.call("nudge", buildJsonObject { put("note", "x"); put("freeSlot", buildJsonObject { }) })
            .shouldBeInstanceOf<ToolResult.Error>().message shouldContain "too full"
    }

    test("at most 10 pending through the tool") {
        val r = Rig(database)
        val now = Clock.System.now()
        repeat(MAX_PENDING_NUDGES) { i ->
            r.call("nudge", input("at" to agentTime(now + (i + 1).hours, zone), "note" to "check $i")).shouldBeInstanceOf<ToolResult.Ok>()
        }
        r.call("nudge", input("at" to agentTime(now + 30.hours, zone), "note" to "one more")).shouldBeInstanceOf<ToolResult.Error>()
            .message shouldContain "already $MAX_PENDING_NUDGES"
    }

    test("a due nudge wakes the lead with its note and task, and posts nothing to the person") {
        val r = Rig(database)
        val now = Clock.System.now()
        val task = r.tasks.upsert(null, "ML report", "", AgentTask.Status.WORKING)!!
        val n = r.store.create(now - 1.minutes, "ask how the ML report is going; due before The Devils at 20:30", task.id)

        val resolved = r.runner.fireDue(now, zone)
        resolved.single().second.status shouldBe Nudge.Status.FIRED
        val item = r.nudgeItems().single()
        item.nudgeId shouldBe n.id.value
        item.summary shouldContain "Nudge you set at ${agentTime(n.at, zone)}: ask how the ML report is going"
        item.summary shouldContain "ML report (working"
        r.store.get(n.id)!!.status shouldBe Nudge.Status.FIRED
        r.feed.page(0, 10).items.shouldBeEmpty()
        r.calls.get() shouldBe 0

        r.runner.fireDue(now + 1.minutes, zone).shouldBeEmpty()
        r.nudgeItems() shouldHaveSize 1
    }

    test("a nudge about a finished task is skipped in code, without the models") {
        val r = Rig(database)
        val now = Clock.System.now()
        r.activity.lastMessage = now - 2.minutes // would otherwise ask the decision model
        val task = r.tasks.upsert(null, "ML report", "", AgentTask.Status.DONE)!!
        val n = r.store.create(now - 1.minutes, "ask about the report", task.id)
        r.runner.fireDue(now, zone)
        r.nudgeItems().shouldBeEmpty()
        r.calls.get() shouldBe 0
        val after = r.store.get(n.id)!!
        after.status shouldBe Nudge.Status.SKIPPED
        after.reason.shouldNotBeNull() shouldContain "done"
        r.store.history(now - 1.hours).single().id shouldBe n.id
    }

    test("a cancelled nudge never fires") {
        val r = Rig(database)
        val now = Clock.System.now()
        val n = r.store.create(now - 1.minutes, "ask", null)
        r.store.cancel(n.id, "cancelled by the agent")!!.status shouldBe Nudge.Status.CANCELLED
        r.runner.fireDue(now, zone).shouldBeEmpty()
        r.nudgeItems().shouldBeEmpty()
        r.store.get(n.id)!!.status shouldBe Nudge.Status.CANCELLED
    }

    test("after the person just gave the update, Decision-1 skips the nudge") {
        val r = Rig(database, DecisionClient { _, _ -> answer(0.92) })
        val now = Clock.System.now()
        r.activity.lastMessage = now - 5.minutes
        val n = r.store.create(now - 1.minutes, "ask how the ML report is going", null)
        r.runner.fireDue(now, zone)
        r.calls.get() shouldBe 1
        r.nudgeItems().shouldBeEmpty()
        r.store.get(n.id)!!.let {
            it.status shouldBe Nudge.Status.SKIPPED
            it.reason.shouldNotBeNull() shouldContain "update"
        }
    }

    test("below the threshold, or in a live call with a low answer, it fires") {
        val r = Rig(database, DecisionClient { _, _ -> answer(0.5) })
        val now = Clock.System.now()
        r.activity.live = true
        r.store.create(now - 1.minutes, "ask", null)
        r.runner.fireDue(now, zone)
        r.calls.get() shouldBe 1
        r.nudgeItems() shouldHaveSize 1
    }

    test("the gate fails open: an error or a throwing client fires the nudge") {
        val failing = Rig(database, DecisionClient { _, _ -> DecisionError.Http(500, "down").left() })
        val now = Clock.System.now()
        failing.activity.lastMessage = now - 1.minutes
        failing.store.create(now - 1.minutes, "first", null)
        failing.runner.fireDue(now, zone)
        failing.nudgeItems() shouldHaveSize 1

        val throwing = Rig(database, DecisionClient { _, _ -> error("boom") })
        throwing.activity.lastMessage = now - 1.minutes
        throwing.store.create(now - 1.minutes, "second", null)
        throwing.runner.fireDue(now, zone)
        throwing.nudgeItems() shouldHaveSize 2
    }

    test("catch-up after downtime: under 2 hours late fires once, older ones are dropped as missed") {
        val r = Rig(database)
        val now = Clock.System.now()
        val recent = r.store.create(now - 90.minutes, "recent", null)
        val stale = r.store.create(now - 3.hours, "stale", null)
        val outcomes = r.runner.fireDue(now, zone).associate { (n, o) -> n.id to o.status }
        outcomes shouldBe mapOf(recent.id to Nudge.Status.FIRED, stale.id to Nudge.Status.MISSED)
        r.nudgeItems().single().note shouldBe "recent"
        r.store.get(stale.id)!!.reason.shouldNotBeNull() shouldContain "offline"
        r.runner.fireDue(now + 1.minutes, zone).shouldBeEmpty()
    }

    test("at most 12 fire in a day; the rest are skipped") {
        val r = Rig(database)
        val now = Clock.System.now()
        repeat(MAX_NUDGES_PER_DAY + 2) { r.store.create(now - 1.minutes, "check $it", null) }
        val outcomes = r.runner.fireDue(now, zone).map { it.second.status }
        outcomes.count { it == Nudge.Status.FIRED } shouldBe MAX_NUDGES_PER_DAY
        outcomes.count { it == Nudge.Status.SKIPPED } shouldBe 2
        r.nudgeItems() shouldHaveSize MAX_NUDGES_PER_DAY
    }

    test("two scheduler instances racing for due nudges resolve each exactly once") {
        val one = Rig(database)
        val two = Rig(database)
        val now = Clock.System.now()
        val ids = (1..10).map { one.store.create(now - 1.minutes, "check $it", null).id }
        val results = coroutineScope {
            (1..8).map { i -> async(Dispatchers.IO) { (if (i % 2 == 0) one else two).runner.fireDue(now, zone) } }.awaitAll()
        }.flatten()
        results.map { it.first.id }.sortedBy { it.value } shouldBe ids.sortedBy { it.value }
        one.nudgeItems() shouldHaveSize 10
        one.store.pending().shouldBeEmpty()
    }
})
