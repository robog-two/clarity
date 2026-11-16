package net.robog.clarity.backend.sessions

import arrow.core.Either
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import net.robog.clarity.backend.agent.CheckInChoices
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.LeadRig
import net.robog.clarity.backend.agent.PLAN_CHECK_IN
import net.robog.clarity.backend.agent.SessionTools
import net.robog.clarity.backend.agent.callTool
import net.robog.clarity.backend.agent.end
import net.robog.clarity.backend.agent.planCheckInNote
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.scheduler.FireContext
import net.robog.clarity.backend.scheduler.NudgeActivity
import net.robog.clarity.backend.scheduler.NudgeExchange
import net.robog.clarity.backend.scheduler.NudgeRunner
import net.robog.clarity.backend.agent.Decisions
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.Choice
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.feed.MessageKind
import net.robog.clarity.shared.feed.Nudge
import net.robog.clarity.shared.session.CallStage
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.SessionKind
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.session.StartSession
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

private class DayClock(var now: Instant) : Clock {
    override fun now() = now
}

private class QuietActivity : NudgeActivity {
    override suspend fun lastPersonMessageAt(): Instant? = null
    override suspend fun sessionLive() = false
    override suspend fun conversation(since: Instant, limit: Int): List<NudgeExchange> = emptyList()
}

private inline fun <reified T> json(value: T): JsonObject = ClarityJson.encodeToJsonElement(value) as JsonObject

private fun <A> Either<*, A>.ok(): A = (this as? Either.Right)?.value ?: error("expected success but was $this")

private fun ToolResult.text(): String = when (this) {
    is ToolResult.Ok -> content
    is ToolResult.Error -> message
    else -> toString()
}

/**
 * A task that keeps coming back, against real Postgres with a scripted model: three days of morning calls
 * (carry-over, the stuck note in the preparation, the automatic check-in), and the check-in's one-tap
 * answers (Starting, Later, Make it smaller) with the limit of two follow-ups a day.
 */
@EnabledIf(ContainerRuntimeAvailable::class)
class StuckScenarioTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine").withStartupAttempts(3)
    lateinit var database: Database

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }

    // Monday 2026-03-02, 08:30 UTC (the person's home zone is UTC unless a test says otherwise).
    val day1 = Instant.parse("2026-03-02T08:30:00Z")

    class Rig(val r: LeadRig, val clock: DayClock) {
        val tools = r.sessionTools.all().associateBy { it.name }
        val plan = (r.planItemTools.all() + r.nudgeTools.all()).associateBy { it.name }
        val runner = NudgeRunner(
            r.nudges, r.tasks, r.planItems, r.inbox, QuietActivity(), Decisions(r.config, { null }, r.database, clock), { Settings() }, clock,
        )

        suspend fun call(name: String, input: JsonObject): ToolResult = (tools[name] ?: plan.getValue(name)).invoke(input)

        /** The call of a day: rings, then the person moves on until [stage] is open (the lead says each stage is ready with stage_done). */
        suspend fun callAt(now: Instant, stage: CallStage): SessionId {
            clock.now = now
            val id = r.sessionService.start(StartSession(SessionKind.MORNING_CALL)).ok().id
            while (r.sessionService.get(id).ok().stage != stage) {
                call("stage_done", JsonObject(emptyMap())).shouldBeInstanceOf<ToolResult.Ok>()
                r.sessionService.control(id, SessionControl.Next).ok()
            }
            call("stage_done", JsonObject(emptyMap())).shouldBeInstanceOf<ToolResult.Ok>()
            return id
        }

        /** The preparation job as it runs ten minutes before a 09:00 call on [date]. */
        suspend fun prep(date: LocalDate): String {
            val scheduledFor = Instant.parse("${date}T08:50:00Z")
            MorningCallPrepJob(r.inbox, r.dayPlans, r.planItems).fire(FireContext(scheduledFor, scheduledFor, null, Settings()))
            return r.inbox.pending().map { it.item }.filterIsInstance<InboxItem.System>().last { it.text.contains("Prepare for the morning call") }.text
        }
    }

    suspend fun rig(start: Instant = day1): Rig {
        val clock = DayClock(start)
        val r = LeadRig(database, clock)
        r.reset()
        return Rig(r, clock)
    }

    fun priorities(text: String, continues: String? = null) =
        json(SessionTools.SetPrioritiesInput(SessionTools.PriorityInput(text, "important", continues)))

    fun planOf(cue: String, action: String, firstStep: String? = null, item: String? = null) =
        json(SessionTools.SetPlanInput(listOf(SessionTools.IfThenInput(cue, action, firstStep, item))))

    fun idIn(text: String): String = Regex("plan item ([0-9a-f-]{36})").find(text)?.groupValues?.get(1) ?: error("no plan item id in: $text")

    test("a stuck item across three days of morning calls: carried over, flagged in the preparation, checked in on") {
        val rig = rig()
        val r = rig.r

        // --- day 1: new item, a check-in at 9:30 is set by set_plan alone
        rig.prep(localDate(day1, "UTC")) shouldNotContain "stuck:"
        rig.callAt(day1, CallStage.CHOOSE)
        val first = rig.call("set_priorities", priorities("write the onboarding spec")).text()
        first shouldContain "plan item"
        first shouldNotContain "day 2"
        val id = idIn(first)
        rig.callAt(day1, CallStage.CHOOSE) // (a second call the same morning replaces the first; nothing doubles)
        rig.call("set_priorities", priorities("write the onboarding spec")).text() shouldContain id
        r.planItems.open().single().daysCarried shouldBe 1
        rig.callAt(day1, CallStage.COMMIT)
        val planned = rig.call("set_plan", planOf("it's 9:30", "spec at my desk until 12", item = id)).text()
        planned shouldContain "Check-ins set at"
        planned shouldContain "do not nudge for these yourself"
        r.nudges.pending().single().let {
            it.planItemId shouldBe id
            it.at shouldBe Instant.parse("2026-03-02T09:30:00Z")
            it.note shouldContain PLAN_CHECK_IN
        }
        // the model nudging for the same time too is told it is covered, and nothing doubles
        rig.call("nudge", buildJsonObject { put("at", "09:32"); put("note", "ask how the spec is going"); put("planItemId", id) })
            .text() shouldContain "already checks in"
        r.nudges.pending() shouldHaveSize 1
        // ... but the person's own reminder next to it is theirs, and is made (and has its own limits)
        rig.call("nudge", buildJsonObject { put("at", "09:35"); put("note", "remind me to call the dentist") })
            .text() shouldContain "set for"
        r.nudges.pending() shouldHaveSize 2
        r.nudges.pending().single { !it.autoCheckIn }.let { mine -> r.nudges.cancel(mine.id, "test") }

        // --- day 2: reworded, no id: close wording carries it; still not stuck
        val day2 = Instant.parse("2026-03-03T08:30:00Z")
        rig.prep(LocalDate(2026, 3, 3)) shouldNotContain "stuck:"
        rig.callAt(day2, CallStage.CHOOSE)
        val second = rig.call("set_priorities", priorities("Write onboarding spec")).text()
        idIn(second) shouldBe id
        second shouldContain "day 2"
        second shouldNotContain "stuck"
        rig.callAt(day2, CallStage.COMMIT)
        rig.call("set_plan", planOf("it's 9:30", "spec at my desk")).text() shouldContain "Check-ins set at" // one priority: linked without an id
        rig.callAt(day2, CallStage.HAND_OFF)
        rig.call(
            "hand_off",
            json(SessionTools.HandOffInput(parked = listOf("expense report"))),
        ).text() shouldContain "1 parked"
        r.planItems.open().map { it.text }.toSet() shouldBe setOf("expense report", "Write onboarding spec")

        // --- day 3: the preparation lists it as stuck, without a first step yet
        val day3 = Instant.parse("2026-03-04T08:30:00Z")
        val prep = rig.prep(LocalDate(2026, 3, 4))
        prep shouldContain "stuck: Write onboarding spec, day 3 (plan item $id)"
        prep shouldNotContain "stuck: expense report" // parked once, only on its second day
        rig.callAt(day3, CallStage.CHOOSE)
        val third = rig.call("set_priorities", priorities("finish the spec", continues = id)).text()
        idIn(third) shouldBe id
        third shouldContain "day 3"
        third shouldContain "stuck"
        // the first step comes from the COMMIT talk and stays on the item
        rig.callAt(day3, CallStage.COMMIT)
        rig.call("set_plan", planOf("it's 9:30", "spec at my desk", firstStep = "open the doc and write the title", item = id)).text() shouldContain "Check-ins set at"
        r.planItems.get(id)!!.let {
            it.daysCarried shouldBe 3
            it.firstStep shouldBe "open the doc and write the title"
            it.firstDate shouldBe LocalDate(2026, 3, 2)
        }
        rig.callAt(day3, CallStage.HAND_OFF)
        val expenseId = r.planItems.open().single { it.text.contains("expense") }.id
        val handOff = rig.call("hand_off", json(SessionTools.HandOffInput(parkedItems = listOf(SessionTools.ParkedInput("expenses report", continues = expenseId))))).text()
        handOff shouldContain "1 parked"
        r.planItems.open().single { it.text.contains("expense") }.daysCarried shouldBe 2

        // --- the check-in fires at 9:30 on day 3 (the earlier days' were missed while nothing ran) and wakes the lead with the item
        rig.clock.now = Instant.parse("2026-03-04T09:30:20Z")
        val fired = rig.runner.fireDue(rig.clock.now(), TimeZone.UTC)
        fired.map { it.second.status } shouldBe listOf(Nudge.Status.MISSED, Nudge.Status.MISSED, Nudge.Status.FIRED)
        val due = r.inbox.pending().map { it.item }.filterIsInstance<InboxItem.NudgeDue>().single()
        due.planItem.shouldNotBeNull() shouldContain "id $id"
        due.planItem!! shouldContain "day 3"
        due.planItem!! shouldContain "first step: open the doc and write the title"
        due.planItem!! shouldContain "2 follow-up check-ins left today"
        due.summary shouldContain "Make it smaller"
        due.summary shouldNotContain "urgent"
    }

    test("the check-in's one-tap answers: Starting, Later, Make it smaller, and two follow-ups a day") {
        val start = Instant.parse("2026-03-04T10:00:00Z")
        val rig = rig(start)
        val r = rig.r
        val item = r.planItems.plan(LocalDate(2026, 3, 4), listOf(Planned("write the onboarding spec"))).single()
        r.planItems.update(item.id) { it.copy(firstStep = "open the doc") }

        class Script {
            val steps = ArrayDeque<ModelResponse>()
            init { r.model.handler = { steps.removeFirstOrNull() ?: end("(script exhausted)") } }
            fun add(vararg responses: ModelResponse) = steps.addAll(responses)
        }
        val script = Script()
        r.lead.start()

        val choices = Card.Choices(
            "Open the doc now?",
            listOf(
                Choice("Starting", CheckInChoices.STARTING),
                Choice("Later", CheckInChoices.LATER),
                Choice("Make it smaller", CheckInChoices.SMALLER),
            ),
        )

        /** The check-in comes due: the nudge fires and the lead posts the question with its buttons. Returns the feed item. */
        suspend fun checkIn(n: Int, due: Instant): FeedItem {
            rig.clock.now = due + 30.seconds
            rig.runner.fireDue(rig.clock.now(), TimeZone.UTC).map { it.second.status } shouldBe listOf(Nudge.Status.FIRED)
            script.add(
                callTool(
                    "ci$n", "send_to_user",
                    buildJsonObject { put("cards", ClarityJson.encodeToJsonElement(listOf<Card>(choices))); put("kind", "check_in") },
                ),
                end("asked"),
            )
            r.lead.step() shouldBe true
            return r.feed.page(0, 100).items.last { it.author == FeedItem.Author.AGENT && it.cards.any { c -> c is Card.Choices } }
        }

        /** The person taps [label]: the card is marked answered everywhere and the reply reaches the lead as their input. */
        suspend fun tap(n: Int, shown: FeedItem, label: String) {
            r.feed.chooseOption(shown.id, label).shouldNotBeNull().cards.filterIsInstance<Card.Choices>().single().chosen shouldBe label
            r.feed.chooseOption(shown.id, "Later") shouldBe null // the first tap wins
            r.inbox.enqueue(InboxItem.UserInput(ClientInputId("tap$n"), label, InputSource.TYPED, null))
        }

        fun lastResult(): String = r.model.requests.last { req -> req.messages.last().content.any { it is Block.Result } }
            .messages.last().content.filterIsInstance<Block.Result>().last().content

        // --- a check-in is made from the item's if-then: it is the item's first (planned) nudge
        val first = start + 5.minutes
        r.nudges.create(first, planCheckInNote(net.robog.clarity.shared.feed.IfThen("it's 10:05", "spec"), item, LocalDate(2026, 3, 4)), null, item.id)
        val q1 = checkIn(1, first)
        q1.kind shouldBe MessageKind.CHECK_IN
        q1.cards.filterIsInstance<Card.Choices>().single().options.map { it.reply } shouldBe listOf("Starting", "Later", "Make it smaller")

        // --- Later: the lead asks when, the person picks, one more check-in is set
        tap(1, q1, "Later")
        script.add(
            callTool("w1", "send_to_user", buildJsonObject { put("cards", ClarityJson.encodeToJsonElement(listOf<Card>(Card.Text("When?")))) }),
            end("asked when"),
        )
        r.lead.step() shouldBe true
        r.feed.page(0, 100).items.last { it.author == FeedItem.Author.AGENT }.kind shouldBe MessageKind.REPLY // a tap is the person writing
        r.inbox.enqueue(InboxItem.UserInput(ClientInputId("when1"), "3pm", InputSource.TYPED, null))
        script.add(callTool("l1", "plan_item", buildJsonObject { put("id", item.id); put("outcome", "later"); put("at", "15:00") }), end("set"))
        r.lead.step() shouldBe true
        lastResult() shouldContain "set for"
        val laterNudge = r.nudges.pending().single()
        laterNudge.at shouldBe Instant.parse("2026-03-04T15:00:00Z")
        laterNudge.planItemId shouldBe item.id

        // --- Make it smaller: the lead splits the step, the check-in comes back in ten minutes (the second follow-up)
        val q2 = checkIn(2, laterNudge.at)
        tap(2, q2, "Make it smaller")
        script.add(
            callTool("s1", "plan_item", buildJsonObject { put("id", item.id); put("outcome", "smaller"); put("firstStep", "write the title only") }),
            end("split"),
        )
        r.lead.step() shouldBe true
        lastResult() shouldContain "First step now: write the title only"
        r.planItems.get(item.id)!!.firstStep shouldBe "write the title only"
        val smaller = r.nudges.pending().single()
        smaller.at shouldBe Instant.parse("2026-03-04T15:00:30Z") + 10.minutes

        // --- a third follow-up the same day is refused, by the server, whatever the person taps
        val q3 = checkIn(3, smaller.at)
        tap(3, q3, "Later")
        script.add(callTool("l3", "plan_item", buildJsonObject { put("id", item.id); put("outcome", "later"); put("at", "17:00") }), end("tried"))
        r.lead.step() shouldBe true
        lastResult() shouldContain "2 follow-up"
        r.nudges.pending().shouldBeEmpty()

        // --- Starting: in progress, and nothing more that day
        script.add(
            callTool("st1", "plan_item", buildJsonObject { put("id", item.id); put("outcome", "started") }),
            end("started"),
        )
        r.inbox.enqueue(InboxItem.UserInput(ClientInputId("tap4"), "Starting", InputSource.TYPED, null))
        r.lead.step() shouldBe true
        r.planItems.get(item.id)!!.startedOn shouldBe LocalDate(2026, 3, 4)
        rig.call("plan_item", buildJsonObject { put("id", item.id); put("outcome", "later"); put("at", "18:00") }).text() shouldContain "no more check-ins"
        r.nudges.pending().shouldBeEmpty()

        // --- a check-in already waiting is cancelled by Starting, and a nudge due after it is skipped, not asked
        val extra = r.nudges.create(rig.clock.now() + 1.hours, "$PLAN_CHECK_IN: again", null, item.id)
        r.planItems.update(item.id) { it.copy(startedOn = null) } // as if the start were another day's
        rig.call("plan_item", buildJsonObject { put("id", item.id); put("outcome", "started") }).text() shouldContain "1 waiting one cancelled"
        r.nudges.get(extra.id)!!.status shouldBe Nudge.Status.CANCELLED
        val late = r.nudges.create(rig.clock.now() + 1.hours, "$PLAN_CHECK_IN: stray", null, item.id)
        rig.clock.now = rig.clock.now() + 2.hours
        rig.runner.fireDue(rig.clock.now(), TimeZone.UTC).single().let { (n, outcome) ->
            n.id shouldBe late.id
            outcome.status shouldBe Nudge.Status.SKIPPED
            outcome.reason.shouldNotBeNull() shouldContain "starting"
        }

        // --- the next day the limit starts over and a started item can be checked on again
        rig.clock.now = Instant.parse("2026-03-05T09:00:00Z")
        r.planItems.update(item.id) { it.copy(startedOn = LocalDate(2026, 3, 4)) }
        rig.call("plan_item", buildJsonObject { put("id", item.id); put("outcome", "later"); put("at", "11:00") }).shouldBeInstanceOf<ToolResult.Ok>()

        // --- done ends it for good
        rig.call("plan_item", buildJsonObject { put("id", item.id); put("outcome", "done") }).shouldBeInstanceOf<ToolResult.Ok>()
        r.nudges.pending().shouldBeEmpty()
        rig.call("plan_item", buildJsonObject { put("id", item.id); put("outcome", "later"); put("at", "12:00") }).text() shouldContain "already done"
        r.planItems.open().shouldBeEmpty()
    }

    test("a second set_plan the same day replaces its check-ins, leaves follow-ups and reminders alone, and lists cues with no time") {
        val rig = rig()
        val r = rig.r
        rig.callAt(day1, CallStage.CHOOSE)
        val id = idIn(rig.call("set_priorities", priorities("write the onboarding spec")).text())
        rig.callAt(day1, CallStage.COMMIT)
        rig.call("set_plan", planOf("it's 9:30", "write the onboarding spec at my desk", item = id)).text() shouldContain "Check-ins set at"
        val first = r.nudges.pending().single()
        val mine = r.nudges.create(Instant.parse("2026-03-02T12:00:00Z"), "remind me to call the dentist", null)
        val followUp = r.nudges.create(Instant.parse("2026-03-02T14:00:00Z"), "$PLAN_CHECK_IN: later", null, id, followUp = true)

        // the same plan again: nothing changes
        rig.callAt(day1, CallStage.COMMIT)
        rig.call("set_plan", planOf("it's 9:30", "write the onboarding spec at my desk", item = id)).text() shouldNotContain "Replaced"
        r.nudges.get(first.id)!!.status shouldBe Nudge.Status.PENDING

        // another time: the old check-in is cancelled, one new one stands
        rig.callAt(day1, CallStage.COMMIT)
        val moved = rig.call("set_plan", planOf("it's 10:30", "write the onboarding spec at my desk", item = id)).text()
        moved shouldContain "Replaced 1 earlier check-in"
        r.nudges.get(first.id)!!.status shouldBe Nudge.Status.CANCELLED
        r.nudges.pending().filter { it.autoCheckIn }.single().at shouldBe Instant.parse("2026-03-02T10:30:00Z")
        r.nudges.get(mine.id)!!.status shouldBe Nudge.Status.PENDING
        r.nudges.get(followUp.id)!!.status shouldBe Nudge.Status.PENDING

        // the same time with another note replaces it too; a cue with no time is listed for the lead
        rig.callAt(day1, CallStage.COMMIT)
        val reworded = rig.call(
            "set_plan",
            json(SessionTools.SetPlanInput(listOf(SessionTools.IfThenInput("it's 10:30", "write the onboarding spec, title first", item = id), SessionTools.IfThenInput("Dana emails", "it waits")))),
        ).text()
        reworded shouldContain "Replaced 1 earlier check-in"
        reworded shouldContain "No check-in for: If Dana emails, then it waits."
        reworded shouldContain "nudge for them yourself"
        r.nudges.pending().filter { it.autoCheckIn }.single().note shouldContain "title first"
    }

    test("a redone list of priorities puts the dropped ones back, and an unrelated if-then does not attach to the only priority") {
        val rig = rig()
        val r = rig.r
        rig.callAt(day1, CallStage.CHOOSE)
        val a = idIn(rig.call("set_priorities", priorities("write the onboarding spec")).text())
        val day2 = Instant.parse("2026-03-03T08:30:00Z")
        rig.callAt(day2, CallStage.CHOOSE)
        rig.call("set_priorities", priorities("write the onboarding spec")).text() shouldContain "day 2"
        // the person changes their mind in the same call: the spec was not for today after all
        rig.callAt(day2, CallStage.CHOOSE)
        val b = idIn(rig.call("set_priorities", priorities("call the dentist")).text())
        b shouldNotBe a
        r.planItems.get(a)!!.let { it.lastDate shouldBe LocalDate(2026, 3, 2); it.daysCarried shouldBe 1; it.state shouldBe PlanItemState.OPEN }
        // and a priority made and replaced on the same day is not left open
        rig.callAt(day2, CallStage.CHOOSE)
        rig.call("set_priorities", priorities("renew the passport")).text() shouldContain "plan item"
        r.planItems.get(b)!!.state shouldBe PlanItemState.REPLACED
        r.planItems.open().map { it.text }.toSet() shouldBe setOf("write the onboarding spec", "renew the passport")

        // an if-then about something else does not attach to the priority; it still gets its check-in, unlinked
        rig.callAt(day2, CallStage.COMMIT)
        val planned = rig.call("set_plan", planOf("it's 3pm", "call the dentist")).text()
        planned shouldContain "not linked to a plan item"
        r.nudges.pending().filter { it.autoCheckIn }.single().planItemId shouldBe null
    }

    test("the person's reminders and the plan's check-ins do not crowd each other") {
        val start = Instant.parse("2026-03-04T10:00:00Z")
        val rig = rig(start)
        val r = rig.r
        val item = r.planItems.plan(LocalDate(2026, 3, 4), listOf(Planned("write the onboarding spec"))).single()
        // ten reminders of the person's, the most they may have waiting
        (1..10).forEach { i -> r.nudges.create(Instant.parse("2026-03-06T08:00:00Z") + (i * 30).minutes, "reminder $i", null) }
        rig.call("nudge", buildJsonObject { put("at", "2026-03-06T20:00"); put("note", "one more") }).text() shouldContain "already 10"
        // a tap's follow-up still works
        rig.call("plan_item", buildJsonObject { put("id", item.id); put("outcome", "later"); put("at", "15:00") }).shouldBeInstanceOf<ToolResult.Ok>()
        // and the check-in did not use up one of the person's ten (the 11th is still refused, a free day is still open)
        rig.call("nudge", buildJsonObject { put("at", "2026-03-06T20:30"); put("note", "again") }).text() shouldContain "already 10"
        r.nudges.pending() shouldHaveSize 11
    }

    test("Starting cancels only today's waiting check-ins of the item") {
        val start = Instant.parse("2026-03-04T10:00:00Z")
        val rig = rig(start)
        val r = rig.r
        val item = r.planItems.plan(LocalDate(2026, 3, 4), listOf(Planned("write the onboarding spec"))).single()
        val today = r.nudges.create(Instant.parse("2026-03-04T15:00:00Z"), "$PLAN_CHECK_IN: today", null, item.id, followUp = true)
        val tomorrow = r.nudges.create(Instant.parse("2026-03-05T09:00:00Z"), "$PLAN_CHECK_IN: tomorrow", null, item.id, followUp = true)
        rig.call("plan_item", buildJsonObject { put("id", item.id); put("outcome", "started") }).text() shouldContain "1 waiting one cancelled"
        r.nudges.get(today.id)!!.status shouldBe Nudge.Status.CANCELLED
        r.nudges.get(tomorrow.id)!!.status shouldBe Nudge.Status.PENDING
    }

    test("a first step from an earlier day is presented as an earlier one") {
        val rig = rig()
        val r = rig.r
        rig.callAt(day1, CallStage.CHOOSE)
        val id = idIn(rig.call("set_priorities", priorities("write the onboarding spec")).text())
        rig.callAt(day1, CallStage.COMMIT)
        rig.call("set_plan", planOf("it's 9:30", "write the onboarding spec", firstStep = "write the title", item = id))
        r.planItems.get(id)!!.firstStepOn shouldBe LocalDate(2026, 3, 2)
        r.nudges.pending().single().note shouldContain "First step: write the title."
        val day2 = Instant.parse("2026-03-03T08:30:00Z")
        rig.callAt(day2, CallStage.CHOOSE)
        rig.call("set_priorities", priorities("write the onboarding spec", continues = id))
        rig.callAt(day2, CallStage.COMMIT)
        rig.call("set_plan", planOf("it's 9:30", "write the onboarding spec", item = id)).text() shouldContain "Check-ins set at"
        r.nudges.pending().single { it.at > day2 }.note shouldContain "Earlier first step: write the title."
    }
})
