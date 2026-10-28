package net.robog.clarity.backend.sessions

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import net.robog.clarity.backend.agent.SESSION_TOOL_NAMES
import net.robog.clarity.backend.agent.buildConstitution
import net.robog.clarity.backend.agent.effortChange
import net.robog.clarity.backend.agent.lastEffort
import net.robog.clarity.backend.agent.mergeCaptured
import net.robog.clarity.backend.agent.parseClock
import net.robog.clarity.backend.agent.requireSession
import net.robog.clarity.backend.setup.connectorsReady
import net.robog.clarity.backend.setup.validateSettings
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Role
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.connector.ConnectorSummary
import net.robog.clarity.shared.connector.GrantState
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.Chip
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Person
import net.robog.clarity.shared.governance.TimeBlock
import net.robog.clarity.shared.noun.Noun
import net.robog.clarity.shared.session.CallStage
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.SessionKind
import net.robog.clarity.shared.session.Settings

private val now = Instant.parse("2026-10-26T08:00:00Z")
private fun rec(kind: SessionKind) = startRecord(SessionId("s"), kind, now)

class SessionPureTest : FunSpec({

    context("playbooks") {
        test("every call stage has its own playbook naming its tools") {
            Playbooks.morningCall(CallStage.CLEAR) shouldContain "capture"
            Playbooks.morningCall(CallStage.CHOOSE) shouldContain "set_priorities"
            Playbooks.morningCall(CallStage.CHOOSE) shouldContain "regret"
            Playbooks.morningCall(CallStage.COMMIT) shouldContain "set_plan"
            Playbooks.morningCall(CallStage.HAND_OFF) shouldContain "hand_off"
            Playbooks.morningCall(CallStage.HAND_OFF) shouldContain "Nothing goes out as you without your review"
            CallStage.entries.map { Playbooks.morningCall(it) }.toSet() shouldHaveSize 4
        }

        test("the interview has the prototype's seven questions, asked one at a time") {
            Playbooks.QUESTIONS shouldHaveSize 7
            (0 until 7).forEach { i ->
                val text = Playbooks.valuesQuestion(i)
                text shouldContain "Question ${i + 1} of 7"
                text shouldContain Playbooks.QUESTIONS[i].text
                text shouldContain "propose_line"
            }
            Playbooks.valuesQuestion(0) shouldContain "seven questions"
            Playbooks.valuesQuestion(1) shouldNotContain "seven questions"
            Playbooks.valuesQuestion(2) shouldContain "tiers"
        }

        test("which playbook goes with which transition") {
            val start = rec(SessionKind.MORNING_CALL)
            Playbooks.forTransition(null, start, null) shouldBe Playbooks.morningCall(CallStage.CLEAR)

            val open = start.stageDone().let { (it as Either.Right).value }
            val choose = (open.control(SessionControl.Next, now) as Either.Right).value
            Playbooks.forTransition(open, choose, SessionControl.Next) shouldBe Playbooks.morningCall(CallStage.CHOOSE)
            Playbooks.forTransition(start, open, null) shouldBe null // stage_done is not news for the lead

            val snoozed = (start.control(SessionControl.Snooze(15), now) as Either.Right).value
            Playbooks.forTransition(start, snoozed, SessionControl.Snooze(15)) shouldBe Playbooks.callSnoozed(15)
            Playbooks.forTransition(snoozed, snoozed.recalled(), null) shouldBe Playbooks.CALL_RECALLED

            val handOff = rec(SessionKind.MORNING_CALL).copy(session = start.session.copy(stage = CallStage.HAND_OFF))
            val ready = handOff.stageDone().let { (it as Either.Right).value }
            val ended = (ready.control(SessionControl.Next, now) as Either.Right).value
            Playbooks.forTransition(ready, ended, SessionControl.Next) shouldBe Playbooks.callEnded(reachedHandOff = true)
            val early = (start.control(SessionControl.End, now) as Either.Right).value
            Playbooks.forTransition(start, early, SessionControl.End) shouldBe Playbooks.callEnded(reachedHandOff = false)
        }

        test("interview transitions: question, reword, finalize") {
            val q0 = rec(SessionKind.VALUES_INTERVIEW)
            Playbooks.forTransition(null, q0, null) shouldBe Playbooks.valuesQuestion(0)
            val line = net.robog.clarity.shared.governance.Line(
                net.robog.clarity.shared.governance.LineId("q1"), net.robog.clarity.shared.governance.Section.VALUES, "t", "b",
            )
            val captured = (q0.lineCaptured(line, emptyList()) as Either.Right).value
            Playbooks.forTransition(q0, captured, null) shouldBe null
            val reword = (captured.control(SessionControl.Reword, now) as Either.Right).value
            Playbooks.forTransition(captured, reword, SessionControl.Reword) shouldBe Playbooks.VALUES_REWORD
            val q1 = (captured.control(SessionControl.Next, now) as Either.Right).value
            Playbooks.forTransition(captured, q1, SessionControl.Next) shouldBe Playbooks.valuesQuestion(1)
            val finalizing = captured.copy(finalizing = true)
            Playbooks.forTransition(captured, finalizing, SessionControl.Next) shouldBe Playbooks.VALUES_FINALIZE
        }

        test("amend sessions mention the held action they came from") {
            val a = rec(SessionKind.AMEND)
            Playbooks.forTransition(null, a, null, "\"Send reply\" (touches money)") shouldContain "touches money"
            Playbooks.forTransition(null, a, null, null) shouldNotContain "started from an action"
        }
    }

    context("session tool guards") {
        test("a tool with no matching session says so and what to do") {
            val none = requireSession(null, "capture", SessionKind.MORNING_CALL) as Either.Left
            none.value shouldContain "No morning call is active"
            val wrong = requireSession(rec(SessionKind.AMEND), "capture", SessionKind.MORNING_CALL) as Either.Left
            wrong.value shouldContain "amend session"
        }

        test("stage gating and snoozed calls") {
            val clear = rec(SessionKind.MORNING_CALL)
            (requireSession(clear, "capture", SessionKind.MORNING_CALL, setOf(CallStage.CLEAR)) is Either.Right) shouldBe true
            val wrongStage = requireSession(clear, "set_plan", SessionKind.MORNING_CALL, setOf(CallStage.COMMIT)) as Either.Left
            wrongStage.value shouldContain "COMMIT"
            wrongStage.value shouldContain "CLEAR"
            val snoozed = (clear.control(SessionControl.Snooze(5), now) as Either.Right).value
            (requireSession(snoozed, "capture", SessionKind.MORNING_CALL) as Either.Left).value shouldContain "snoozed"
        }

        test("the session tool names are the eight the spec lists") {
            SESSION_TOOL_NAMES shouldBe setOf(
                "capture", "set_priorities", "set_plan", "hand_off", "propose_line", "finalize_constitution", "propose_amendment", "stage_done",
            )
        }

        test("captured items merge without repeats") {
            val merged = mergeCaptured(listOf(Chip("Dana's contract")), listOf(Chip("dana's contract "), Chip("call the dentist")))
            merged.map { it.text } shouldBe listOf("Dana's contract", "call the dentist")
        }
    }

    context("constitution assembly") {
        test("v1 carries lines, tiers, people, protected times and cleaned lists") {
            val c = buildConstitution(
                interviewLines = emptyList(),
                fixedLines = emptyList(),
                autonomy = mapOf(ActionKind.CREATE_EVENT to AutonomyTier.DO_THEN_TELL, ActionKind.PAYMENT to AutonomyTier.NEVER_ALONE),
                getsThrough = listOf(Person("Priya")),
                protectedTimes = listOf(TimeBlock("deep work", setOf(1, 2), LocalTime(9, 0), LocalTime(12, 0))),
                neverStore = listOf(" health ", "health", ""),
                profile = listOf("Has two kids", " "),
                now = now,
            )
            c.version shouldBe 1
            c.neverStore shouldBe listOf("health")
            c.profile shouldBe listOf("Has two kids")
            c.autonomy.getValue(ActionKind.PAYMENT) shouldBe AutonomyTier.NEVER_ALONE
        }

        test("clock times") {
            parseClock("9:00") shouldBe LocalTime(9, 0)
            parseClock("18:30") shouldBe LocalTime(18, 30)
            parseClock("later") shouldBe null
            parseClock("25:00") shouldBe null
        }
    }

    context("effort") {
        test("the thread's effort is the last effort-only message, and only a change produces one") {
            val history = listOf(Message.user("hi"), Message.effort("low"), Message.system("x"), Message.effort("medium"), Message.effort("low"))
            lastEffort(history) shouldBe "low"
            lastEffort(listOf(Message.user("hi"))) shouldBe null
            effortChange("medium", "medium") shouldBe null
            effortChange("medium", "low") shouldBe Message(Role.SYSTEM, emptyList(), "low")
            effortChange("low", "medium")?.effort shouldBe "medium"
        }

        test("only a morning call in progress lowers it") {
            callEffort(null) shouldBe null
            callEffort(rec(SessionKind.MORNING_CALL)) shouldBe "low"
            callEffort(rec(SessionKind.VALUES_INTERVIEW)) shouldBe null
            callEffort(rec(SessionKind.AMEND)) shouldBe null
            val snoozed = (rec(SessionKind.MORNING_CALL).control(SessionControl.Snooze(5), now) as Either.Right).value
            callEffort(snoozed) shouldBe null
        }
    }

    context("settings and setup") {
        test("settings validation") {
            (validateSettings(Settings()) is Either.Right) shouldBe true
            (validateSettings(Settings(homeZone = "Europe/Berlin")) is Either.Right) shouldBe true
            (validateSettings(Settings(homeZone = "Mars/Olympus")) as Either.Left).value shouldContain "time zone"
            (validateSettings(Settings(morningCall = Settings.MorningCall(days = setOf(0, 3)))) as Either.Left).value shouldContain "weekdays"
            (validateSettings(Settings(morningCall = Settings.MorningCall(days = emptySet()))) as Either.Left).value shouldContain "day"
            (validateSettings(Settings(morningCall = Settings.MorningCall(enabled = false, days = emptySet()))) is Either.Right) shouldBe true
            (validateSettings(Settings(morningCall = Settings.MorningCall(headsUpMinutes = 0))) as Either.Left).value shouldContain "heads-up"
            (validateSettings(Settings(morningCall = Settings.MorningCall(headsUpMinutes = null))) is Either.Right) shouldBe true
        }

        test("heads-up off survives the wire (ClarityJson omits nulls, the default would turn it back on)") {
            val off = Settings(morningCall = Settings.MorningCall(headsUpMinutes = null))
            val json = net.robog.clarity.shared.ClarityJson.encodeToString(Settings.serializer(), off)
            json shouldContain "\"headsUpMinutes\":null"
            net.robog.clarity.shared.ClarityJson.decodeFromString(Settings.serializer(), json) shouldBe off
            net.robog.clarity.shared.ClarityJson.decodeFromString(Settings.serializer(), net.robog.clarity.shared.ClarityJson.encodeToString(Settings.serializer(), Settings())) shouldBe Settings()
            // an older client that omits the field gets the default
            net.robog.clarity.shared.ClarityJson.decodeFromString(Settings.serializer(), """{"morningCall":{}}""").morningCall.headsUpMinutes shouldBe 10
        }

        test("ready connectors have announced themselves and hold a grant") {
            fun summary(id: String, manifest: Boolean, vararg grants: GrantState) = ConnectorSummary(
                ConnectorId(id),
                if (manifest) net.robog.clarity.shared.connector.ConnectorManifest("mail", "Mail", "1", setOf(Noun.MESSAGE)) else null,
                grants.mapIndexed { i, g -> Noun.entries[i] to g }.toMap(),
                null,
            )
            connectorsReady(
                listOf(
                    summary("a", true, GrantState.GRANTED),
                    summary("b", true, GrantState.PENDING),
                    summary("c", false, GrantState.GRANTED),
                    summary("d", true, GrantState.REVOKED, GrantState.GRANTED),
                ),
            ) shouldBe 2
        }
    }

    context("today and prep") {
        val date = LocalDate(2026, 10, 26)

        test("today is empty before the first call of the day") {
            val t = todayOf(date, null, emptyList(), heldCount = 2)
            t.called shouldBe false
            t.callStarted shouldBe false
            todayOf(date, null, emptyList(), 0, callStarted = true).let { it.callStarted shouldBe true; it.called shouldBe false }
            t.priorities shouldBe emptyList()
            t.heldCount shouldBe 2
        }

        test("today reflects the day's plan, tasks and parked items") {
            val task = AgentTask(TaskId("t"), "Hold 9:30", "alone", AgentTask.Status.WORKING, updatedAt = now)
            val plan = DayPlan(date, listOf(Chip("spec", "important")), listOf(IfThen("it's 9:30", "spec")), listOf("expenses"), called = true)
            val t = todayOf(date, plan, listOf(task), 0)
            t.called shouldBe true
            t.callStarted shouldBe true // a plan made in a call means a call started
            t.doing shouldBe listOf(task)
            t.parked shouldBe listOf("expenses")
            t.plan.single().sentence() shouldBe "If it's 9:30, then spec."
        }

        test("local dates follow the person's zone") {
            localDate(Instant.parse("2026-10-26T23:30:00Z"), "Asia/Tokyo") shouldBe LocalDate(2026, 10, 27)
            localDate(Instant.parse("2026-10-26T23:30:00Z"), "America/New_York") shouldBe LocalDate(2026, 10, 26)
            localDate(Instant.parse("2026-10-26T23:30:00Z"), "nonsense") shouldBe LocalDate(2026, 10, 26)
        }

        test("the prep instruction carries yesterday's plan and parked items, and never asks to message the person") {
            val text = prepInstruction(DayPlan(date, listOf(Chip("spec")), emptyList(), listOf("expenses"), true))
            text shouldContain "parked until today: expenses"
            text shouldContain "Do NOT message the person"
            prepInstruction(null) shouldContain "no plan from yesterday"
        }
    }
})
