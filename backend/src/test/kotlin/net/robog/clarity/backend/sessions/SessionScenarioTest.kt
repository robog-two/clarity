package net.robog.clarity.backend.sessions

import arrow.core.Either
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.datetime.LocalTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.LeadRig
import net.robog.clarity.backend.agent.OwnStateTools
import net.robog.clarity.backend.agent.SESSION_TOOL_NAMES
import net.robog.clarity.backend.agent.SessionTools
import net.robog.clarity.backend.agent.callTool
import net.robog.clarity.backend.agent.end
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.Role
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.Amendment
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.Decision
import net.robog.clarity.shared.governance.EmailDraft
import net.robog.clarity.shared.governance.GuardVerdict
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.Line
import net.robog.clarity.shared.governance.LineId
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.Provenance
import net.robog.clarity.shared.governance.Section
import net.robog.clarity.shared.governance.Urgency
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.SessionKind
import net.robog.clarity.shared.session.StartSession
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

private inline fun <reified T> input(value: T): JsonObject = ClarityJson.encodeToJsonElement(ClarityJson.serializersModule.let { kotlinx.serialization.serializer<T>() }, value) as JsonObject

private fun <A> Either<*, A>.ok(): A = (this as? Either.Right)?.value ?: error("expected success but was $this")

private fun results(request: net.robog.clarity.harness.ModelRequest) =
    request.messages.last().content.filterIsInstance<Block.Result>()

/**
 * The sessions against real Postgres with a scripted model: a whole morning call, a values
 * interview and an amend session, driven by the person's controls and the lead's tools. The model
 * is never a real one.
 */
@EnabledIf(ContainerRuntimeAvailable::class)
class SessionScenarioTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }

    suspend fun rig(): LeadRig = LeadRig(database).also { it.reset() }

    /** The model answers from a queue; each lead turn consumes the steps up to its `end`. */
    class Script(val rig: LeadRig) {
        val steps = ArrayDeque<ModelResponse>()
        init { rig.model.handler = { steps.removeFirstOrNull() ?: end("(script exhausted)") } }
        fun add(vararg responses: ModelResponse) = steps.addAll(responses)
    }

    fun user(sessionId: SessionId, id: String, text: String) =
        InboxItem.UserInput(ClientInputId(id), text, InputSource.VOICE, sessionId)

    /** The lead opens a new step (the playbook is in its inbox) and releases it for the person. */
    suspend fun open(r: LeadRig, script: Script, tag: String) {
        script.add(callTool("open-$tag", "stage_done"), end("opened"))
        r.lead.step() shouldBe true
    }

    fun item(text: String, sub: String = "") = SessionTools.ItemInput(text, sub)

    test("a full morning call: four stages produce a plan, tasks, a held action and parked items, and Today shows them") {
        val r = rig()
        val script = Script(r)
        r.lead.start()

        // --- the phone rings and is answered: the CLEAR playbook reaches the lead
        val session = r.sessionService.start(StartSession(SessionKind.MORNING_CALL)).ok()
        val id = session.id
        session.state shouldBe Session.State.BUSY
        r.inbox.pending().map { it.item }.single().shouldBeInstanceOf<InboxItem.SessionEvent>().event shouldContain "stage CLEAR"

        script.add(
            callTool("c1", "send_to_user", input(OwnStateTools.SendToUserInput(listOf(Card.Text("Morning. What's on your mind?")), sessionId = id.value))),
            callTool("c2", "stage_done"),
            end("opened"),
        )
        r.lead.step() shouldBe true
        r.sessionService.get(id).ok().state shouldBe Session.State.ACTIVE
        r.model.requests.first().messages.any { it.role == Role.SYSTEM && it.effort == "low" } shouldBe true // a live call runs at low effort
        r.feed.page(0, 50).items.single().sessionId shouldBe id

        // --- CLEAR: the person talks, the lead captures; capturing again updates the same card
        r.inbox.enqueue(user(id, "u1", "the onboarding spec, Dana's contract, call the dentist, feedback for Alex, the expense report"))
        script.add(
            callTool(
                "c3", "capture",
                input(SessionTools.CaptureInput(listOf(item("onboarding spec"), item("Dana’s contract"), item("call the dentist"), item("feedback for Alex"), item("expense report")))),
            ),
            end("captured"),
        )
        r.lead.step() shouldBe true
        r.inbox.enqueue(user(id, "u2", "oh, and the gym"))
        script.add(callTool("c4", "capture", input(SessionTools.CaptureInput(listOf(item("gym"), item("Dana’s contract"))))), end("captured"))
        r.lead.step() shouldBe true
        val chipCards = r.feed.page(0, 50).items.filter { f -> f.cards.any { it is Card.Chips } }
        chipCards shouldHaveSize 1
        (chipCards.single().cards.single() as Card.Chips).let {
            it.label shouldBe "captured · 6"
            it.items.map { c -> c.text } shouldContain "gym"
        }

        // --- CHOOSE: a tool from another stage is refused, the right one works
        val toCommit = r.sessionService.control(id, SessionControl.Next).ok()
        toCommit.stage?.name shouldBe "CHOOSE"
        toCommit.state shouldBe Session.State.BUSY
        r.sessionService.control(id, SessionControl.Next).shouldBeInstanceOf<Either.Left<SessionError>>().value shouldBe SessionError.Busy
        open(r, script, "choose")
        script.add(
            callTool("p0", "set_plan", input(SessionTools.SetPlanInput(listOf(SessionTools.IfThenInput("it's 9:30", "spec"))))),
            callTool(
                "p1", "set_priorities",
                input(SessionTools.SetPrioritiesInput(SessionTools.PriorityInput("onboarding spec", "important"), SessionTools.PriorityInput("Dana’s contract", "due today"))),
            ),
            end("two chosen"),
        )
        r.inbox.enqueue(user(id, "u3", "the spec, and Dana's contract is due today"))
        r.lead.step() shouldBe true
        val refused = results(r.model.requests[r.model.requests.size - 2]).single()
        refused.isError shouldBe true
        refused.content shouldContain "COMMIT"
        refused.content shouldContain "CHOOSE"

        // --- COMMIT
        r.sessionService.control(id, SessionControl.Next).ok().stage?.name shouldBe "COMMIT"
        open(r, script, "commit")
        script.add(
            callTool(
                "i1", "set_plan",
                input(SessionTools.SetPlanInput(listOf(SessionTools.IfThenInput("it's 9:30", "spec at my desk until 12"), SessionTools.IfThenInput("Dana emails", "it waits until 12")))),
            ),
            end("planned"),
        )
        r.inbox.enqueue(user(id, "u4", "9:30 at my desk; if Dana emails it waits"))
        r.lead.step() shouldBe true

        // --- HAND_OFF
        r.sessionService.control(id, SessionControl.Next).ok().stage?.name shouldBe "HAND_OFF"
        open(r, script, "handoff")
        script.add(
            callTool(
                "h1", "hand_off",
                input(
                    SessionTools.HandOffInput(
                        alone = listOf(SessionTools.TaskInput("hold 9:30–12 for the spec"), SessionTools.TaskInput("find the dentist’s number")),
                        review = listOf(SessionTools.TaskInput("draft reply to Dana")),
                        parked = listOf("feedback for Alex", "expense report"),
                    ),
                ),
            ),
            end("handed off"),
        )
        r.inbox.enqueue(user(id, "u5", "hold the morning, draft Dana's reply, find the dentist"))
        r.lead.step() shouldBe true
        r.tasks.list() shouldHaveSize 3
        r.feed.page(0, 50).items.flatMap { it.cards }.filterIsInstance<Card.Chips>().map { it.label } shouldContain "parked until tomorrow"
        r.feed.page(0, 50).items.flatMap { it.cards }.filterIsInstance<Card.Tasks>().single().taskIds shouldHaveSize 3

        // --- end call: the lead carries the hand-off out; the reply to Dana is held for review
        val ended = r.sessionService.control(id, SessionControl.Next).ok()
        ended.state shouldBe Session.State.ENDED
        r.inbox.pending().map { it.item }.single().shouldBeInstanceOf<InboxItem.SessionEvent>().event shouldContain "carry it out"
        val taskId = r.tasks.list().first().id.value
        var n = 0
        r.model.handler = {
            when (++n) {
                1 -> callTool(
                    "e1", "create_event",
                    buildJsonObject {
                        put("title", kotlinx.serialization.json.JsonPrimitive("Spec"))
                        put("start", kotlinx.serialization.json.JsonPrimitive("2026-10-26T09:30:00Z"))
                        put("end", kotlinx.serialization.json.JsonPrimitive("2026-10-26T12:00:00Z"))
                        put("reason", kotlinx.serialization.json.JsonPrimitive("Hold the morning for the spec."))
                    },
                )
                2 -> {
                    r.guardVerdict = GuardVerdict(Decision.ASK, emptyList(), "It goes out as you.")
                    callTool(
                        "e2", "send_email",
                        buildJsonObject {
                            put("to", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("dana@example.com"))))
                            put("subject", kotlinx.serialization.json.JsonPrimitive("Contract"))
                            put("body", kotlinx.serialization.json.JsonPrimitive("Signed copy attached."))
                            put("reason", kotlinx.serialization.json.JsonPrimitive("Dana asked for the contract."))
                            put("from", kotlinx.serialization.json.JsonPrimitive("me@gmail.example"))
                            put("newThread", kotlinx.serialization.json.JsonPrimitive(true))
                        },
                    )
                }
                3 -> callTool("e3", "update_task", input(OwnStateTools.UpdateTaskInput(id = taskId, status = AgentTask.Status.DONE)))
                else -> end("done")
            }
        }
        r.lead.step() shouldBe true

        // the thread went back to the role's own effort after the call, in the same cached prefix
        r.model.requests.last().messages.any { it.role == Role.SYSTEM && it.effort == "medium" } shouldBe true
        // the lead's tool list never changed during the call
        r.model.requests.map { req -> req.tools.map { it.name } }.toSet() shouldHaveSize 1
        r.model.requests.first().tools.map { it.name }.toSet().containsAll(SESSION_TOOL_NAMES) shouldBe true

        // --- Today reflects the call
        val today = TodayService(r.dayPlans, r.tasks, r.heldStore, r.nudges, r.settingsProvider, r.clock).today()
        today.called shouldBe true
        today.priorities.map { it.text } shouldBe listOf("onboarding spec", "Dana’s contract")
        today.priorities.map { it.sub } shouldBe listOf("important", "due today")
        today.plan.map { it.sentence() } shouldBe listOf("If it's 9:30, then spec at my desk until 12.", "If Dana emails, then it waits until 12.")
        today.parked shouldBe listOf("feedback for Alex", "expense report")
        today.doing shouldHaveSize 3 // three created, one finished today and still listed
        today.doing.count { it.status == AgentTask.Status.DONE } shouldBe 1
        // the reply to Dana waits for review; the hold has no connector in this rig, so it waits too
        today.heldCount shouldBe 2
        r.heldStore.list().map { it.status }.toSet() shouldBe setOf(HeldAction.Status.HELD)
        r.heldStore.list().map { it.action.kind } shouldContain ActionKind.SEND_EMAIL
    }

    test("session tools fail clearly when no matching session is active, and the lead's tool list does not depend on it") {
        val r = rig()
        val inputs = mapOf(
            "capture" to """{"items":[{"text":"x"}]}""",
            "set_priorities" to """{"important":{"text":"x"}}""",
            "set_plan" to """{"ifThens":[{"cue":"a","action":"b"}]}""",
            "hand_off" to """{"parked":["x"]}""",
            "propose_line" to """{"section":"values","text":"t","because":"b"}""",
            "finalize_constitution" to "{}",
            "propose_amendment" to """{"text":"t","because":"b","reason":"r"}""",
            "stage_done" to "{}",
        )
        val tools = r.sessionTools.all()
        tools.map { it.name }.toSet() shouldBe SESSION_TOOL_NAMES
        tools.forEach { tool ->
            val out = tool.invoke(ClarityJson.parseToJsonElement(inputs.getValue(tool.name)) as JsonObject)
            out.shouldBeInstanceOf<ToolResult.Error>().message shouldContain "active"
        }
        // a call is running, so the interview and amend tools name the mismatch
        r.sessionService.start(StartSession(SessionKind.MORNING_CALL)).ok()
        val wrong = tools.first { it.name == "propose_line" }.invoke(ClarityJson.parseToJsonElement(inputs.getValue("propose_line")) as JsonObject)
        wrong.shouldBeInstanceOf<ToolResult.Error>().message shouldContain "values interview"
        // starting another session ends the call: one at a time
        val interview = r.sessionService.start(StartSession(SessionKind.VALUES_INTERVIEW)).ok()
        r.sessionService.active().map { it.id } shouldBe listOf(interview.id)
        r.sessionService.recent().count { it.state == Session.State.ENDED } shouldBe 1
    }

    test("set_priorities decodes an object parameter the model sent as a JSON string (seen in production)") {
        val r = rig()
        val production = """{"important": "{\"text\": \"Finish ML midterm (data mining report)\", \"note\": \"needs to be done\"}"}"""
        val tool = r.sessionTools.all().first { it.name == "set_priorities" }
        val out = tool.invoke(ClarityJson.parseToJsonElement(production) as JsonObject).shouldBeInstanceOf<ToolResult.Error>()
        // past decoding: it reaches the session check instead of failing as invalid input
        out.message shouldContain "active"
    }

    test("a values interview: seven kept lines become constitution v1 with tiers, people and protected times") {
        val r = rig()
        val script = Script(r)
        r.lead.start()
        val id = r.sessionService.start(StartSession(SessionKind.VALUES_INTERVIEW)).ok().id

        suspend fun question(i: Int, sectionLine: SessionTools.ProposeLineInput) {
            r.sessionService.get(id).ok().question shouldBe i
            // the lead asks, and releases the step
            script.add(callTool("q$i-a", "stage_done"), end("asked"))
            r.lead.step() shouldBe true
            // a "next" before the person answered is refused: nothing was captured
            r.sessionService.control(id, SessionControl.Next).shouldBeInstanceOf<Either.Left<SessionError>>()
            // the person answers, the lead writes the line
            r.inbox.enqueue(user(id, "ans$i", "an answer to question $i"))
            script.add(callTool("q$i-b", "propose_line", input(sectionLine)), end("captured"))
            r.lead.step() shouldBe true
        }

        val sections = listOf(Section.VALUES, Section.HARD_LINES, Section.AUTONOMY, Section.VALUES, Section.COLLISIONS, Section.PEOPLE, Section.REPAIR)
        for (i in 0..6) {
            val tiers = if (i == 2) {
                listOf(
                    SessionTools.TierInput(AutonomyTier.DO_THEN_TELL, "holds on my calendar, reminders, drafts, lookups"),
                    SessionTools.TierInput(AutonomyTier.ASK_FIRST, "email, replies to invites, anything sent as me"),
                    SessionTools.TierInput(AutonomyTier.NEVER_ALONE, "payments and purchases"),
                )
            } else {
                null
            }
            question(i, SessionTools.ProposeLineInput(sections[i], "line for question ${i + 1}", "reason ${i + 1}", tiers))
            if (i == 0) {
                // reword: another phrasing replaces the captured line
                r.sessionService.control(id, SessionControl.Reword).ok().state shouldBe Session.State.BUSY
                r.sessionService.control(id, SessionControl.Next).shouldBeInstanceOf<Either.Left<SessionError>>().value shouldBe SessionError.Busy
                script.add(
                    callTool("rw", "propose_line", input(SessionTools.ProposeLineInput(Section.VALUES, "reworded line", "reason 1"))),
                    end("reworded"),
                )
                r.lead.step() shouldBe true
                r.sessionService.get(id).ok().capturedLine?.text shouldBe "reworded line"
            }
            r.sessionService.get(id).ok().state shouldBe Session.State.ACTIVE
            r.sessionService.control(id, SessionControl.Next).ok()
        }

        // every question kept: the lead is asked to write the constitution
        r.sessionService.get(id).ok().state shouldBe Session.State.BUSY
        r.inbox.pending().map { it.item }.filterIsInstance<InboxItem.SessionEvent>().last().event shouldContain "finalize_constitution"
        script.add(
            callTool(
                "fin", "finalize_constitution",
                input(
                    SessionTools.FinalizeInput(
                        lines = listOf(SessionTools.FixedLineInput(Section.HARD_LINES, "Never move money or buy anything on my own.", hard = true)),
                        autonomy = listOf(
                            SessionTools.AutonomyInput(ActionKind.CREATE_EVENT, AutonomyTier.DO_THEN_TELL),
                            SessionTools.AutonomyInput(ActionKind.SEND_EMAIL, AutonomyTier.ASK_FIRST),
                            SessionTools.AutonomyInput(ActionKind.PAYMENT, AutonomyTier.NEVER_ALONE),
                        ),
                        getsThrough = listOf(SessionTools.PersonInput("Priya", listOf("priya@example.com")), SessionTools.PersonInput("The school")),
                        protectedTimes = listOf(SessionTools.TimeBlockInput("deep work", listOf(1, 2, 3, 4, 5), "9:00", "12:00")),
                        neverStore = listOf("health"),
                        profile = listOf("Has two kids"),
                    ),
                ),
            ),
            end("written"),
        )
        r.lead.step() shouldBe true

        val c: Constitution = r.constitutions.current()!!
        c.version shouldBe 1
        c.lines.map { it.text } shouldBe listOf(
            "reworded line", "line for question 2", "line for question 3", "line for question 4",
            "line for question 5", "line for question 6", "line for question 7", "Never move money or buy anything on my own.",
        )
        c.lines[0].because shouldBe "reason 1"
        c.lines[1].hard shouldBe true // question 2 yields a hard line
        c.autonomy[ActionKind.PAYMENT] shouldBe AutonomyTier.NEVER_ALONE
        c.getsThrough.map { it.name } shouldBe listOf("Priya", "The school")
        c.getsThrough.first().handles shouldBe listOf(Handle.email("priya@example.com"))
        c.protectedTimes.single().start shouldBe LocalTime(9, 0)
        c.neverStore shouldBe listOf("health")
        c.profile shouldBe listOf("Has two kids")
        r.sessionService.get(id).ok().state shouldBe Session.State.ENDED

        // the autonomy tiers were shown as a card with question 3
        val labels = r.feed.page(0, 100).items.flatMap { it.cards }.filterIsInstance<Card.Chips>().map { it.label }
        labels shouldContain "how much I do alone"
        labels.count { it.startsWith("captured as") } shouldBe 8 // seven questions and one reword
        // the list of tools never changed
        r.model.requests.map { req -> req.tools.map { it.name } }.toSet() shouldHaveSize 1
    }

    test("finalize_constitution before the interview is done explains where it is") {
        val r = rig()
        val id = r.sessionService.start(StartSession(SessionKind.VALUES_INTERVIEW)).ok().id
        val tool = r.sessionTools.all().first { it.name == "finalize_constitution" }
        val out = tool.invoke(ClarityJson.parseToJsonElement("{}") as JsonObject)
        out.shouldBeInstanceOf<ToolResult.Error>().message shouldContain "question 1 of 7"
        r.constitutions.current() shouldBe null
        r.sessionService.get(id).ok().state shouldBe Session.State.BUSY
    }

    test("an amend session from a held action proposes an amendment; saving it ends the session and resolves the held action") {
        val r = rig()
        val script = Script(r)
        r.lead.start()
        r.constitutions.create(
            Constitution(
                1,
                listOf(
                    Line(LineId("l-calendar"), Section.VALUES, "Ask before anything leaves the building.", "a sent mail cannot be unsent"),
                    Line(LineId("l-money"), Section.HARD_LINES, "Never move money on my own.", "money is hard to undo", hard = true),
                ),
                updatedAt = Instant.parse("2026-10-26T08:00:00Z"),
            ),
        ).ok()
        r.guardVerdict = GuardVerdict(Decision.ASK, listOf(LineId("l-calendar")), "It goes out as you.")
        val action = ProposedAction.SendEmail("amend-k1", "Reply to Dana", EmailDraft(to = listOf(Handle.email("dana@example.com")), subject = "Contract", body = "Signed."))
        r.gate.propose(action, "Dana asked.", emptyList(), Provenance("run", untrustedInput = false), Urgency.NORMAL)
        val held = r.heldStore.list().single()

        val session = r.sessionService.start(StartSession(SessionKind.AMEND, held.id)).ok()
        session.state shouldBe Session.State.ACTIVE
        session.heldActionId shouldBe held.id
        r.inbox.pending().map { it.item }.single().shouldBeInstanceOf<InboxItem.SessionEvent>().event shouldContain "Reply to Dana"

        // a hard line cannot be amended by talking; the soft one can
        script.add(
            callTool("a1", "propose_amendment", input(SessionTools.ProposeAmendmentInput("l-money", "Money is fine up to 20 euros.", "small things", "less friction"))),
            callTool(
                "a2", "propose_amendment",
                input(SessionTools.ProposeAmendmentInput("l-calendar", "Replies to people I write to every week can go out after a short wait.", "I trust those", "Dana is a weekly contact")),
            ),
            end("proposed"),
        )
        r.lead.step() shouldBe true
        results(r.model.requests[1]).single().let {
            it.isError shouldBe true
            it.content shouldContain "hard line"
        }
        val proposed = r.constitutions.amendments(Amendment.Status.PROPOSED).single()
        proposed.fromHeld shouldBe held.id
        proposed.lineId shouldBe LineId("l-calendar")
        r.sessionService.get(session.id).ok().amendmentId shouldBe proposed.id
        r.sessionService.get(session.id).ok().state shouldBe Session.State.ACTIVE

        // the person saves it on the constitution screen; the session notices
        r.constitutions.saveAmendment(proposed.id).ok().version shouldBe 2
        r.sessionService.get(session.id).ok().state shouldBe Session.State.ENDED
        r.heldStore.get(held.id)!!.status shouldBe HeldAction.Status.RULE_UPDATED
        r.sessionService.active() shouldBe emptyList()
    }

    test("discarding the amendment ends the session too, and a session ends on request") {
        val r = rig()
        val script = Script(r)
        r.lead.start()
        r.constitutions.create(Constitution(1, listOf(Line(LineId("l1"), Section.VALUES, "A rule.", "because")), updatedAt = Instant.parse("2026-10-26T08:00:00Z"))).ok()
        val id = r.sessionService.start(StartSession(SessionKind.AMEND)).ok().id
        script.add(
            callTool("a", "propose_amendment", input(SessionTools.ProposeAmendmentInput(null, "A brand new rule.", "I said so", "new", Section.PEOPLE))),
            end("proposed"),
        )
        r.lead.step() shouldBe true
        val amendment = r.constitutions.amendments(Amendment.Status.PROPOSED).single()
        amendment.lineId shouldBe null
        amendment.proposed.section shouldBe Section.PEOPLE
        r.constitutions.discardAmendment(amendment.id).ok()
        r.sessionService.sweep()
        r.sessionService.get(id).ok().state shouldBe Session.State.ENDED

        val another = r.sessionService.start(StartSession(SessionKind.AMEND)).ok().id
        r.sessionService.control(another, SessionControl.End).ok().state shouldBe Session.State.ENDED
        r.sessionService.control(another, SessionControl.End).shouldBeInstanceOf<Either.Left<SessionError>>()
    }

    test("an amendment that lets the agent add events changes the tier the pre-check reads") {
        val r = rig()
        val script = Script(r)
        r.lead.start()
        r.constitutions.create(Constitution(1, listOf(Line(LineId("l1"), Section.VALUES, "A rule.", "because")), updatedAt = Instant.parse("2026-10-26T08:00:00Z"))).ok()
        r.sessionService.start(StartSession(SessionKind.AMEND)).ok()
        script.add(
            callTool(
                "a", "propose_amendment",
                input(
                    SessionTools.ProposeAmendmentInput(
                        null, "Creating calendar events is okay: add them, then tell me.", "it's fine", "so I add events myself", Section.AUTONOMY,
                        SessionTools.AutonomyInput(ActionKind.CREATE_EVENT, AutonomyTier.DO_THEN_TELL),
                    ),
                ),
            ),
            end("proposed"),
        )
        r.lead.step() shouldBe true
        val amendment = r.constitutions.amendments(Amendment.Status.PROPOSED).single()
        amendment.autonomy shouldBe net.robog.clarity.shared.governance.AutonomyChange(ActionKind.CREATE_EVENT, AutonomyTier.DO_THEN_TELL)
        val saved = r.constitutions.saveAmendment(amendment.id).ok()
        saved.autonomy[ActionKind.CREATE_EVENT] shouldBe AutonomyTier.DO_THEN_TELL
        r.constitutions.current()!!.autonomy[ActionKind.CREATE_EVENT] shouldBe AutonomyTier.DO_THEN_TELL
    }

    test("a snoozed call rings again when its time comes") {
        val r = rig()
        val clock = net.robog.clarity.backend.agent.MovableClock()
        val service = SessionService(SessionStore(database), r.feed, r.inbox, r.heldStore, r.constitutions, clock)
        val id = service.start(StartSession(SessionKind.MORNING_CALL)).ok().id
        service.control(id, SessionControl.Snooze(15)).ok().state shouldBe Session.State.SNOOZED
        service.live()?.snoozedUntil shouldBe service.live()?.snoozedUntil
        service.recallDue() shouldBe 0
        clock.advance(kotlin.time.Duration.parse("16m"))
        service.recallDue() shouldBe 1
        service.get(id).ok().state shouldBe Session.State.BUSY
        r.inbox.pending().map { it.item }.filterIsInstance<InboxItem.SessionEvent>().last().event shouldContain "ringing again"
    }
})
