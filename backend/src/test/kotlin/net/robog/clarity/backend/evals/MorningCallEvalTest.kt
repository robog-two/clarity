package net.robog.clarity.backend.evals

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.LeadRig
import net.robog.clarity.backend.agent.SessionTools
import net.robog.clarity.backend.agent.callTool
import net.robog.clarity.backend.agent.end
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.DevicePrincipal
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.sessions.SessionRoutes
import net.robog.clarity.backend.sessions.TodayService
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.SessionKind
import net.robog.clarity.shared.session.StartSession
import net.robog.clarity.shared.session.Today

private const val AUTH = "Bearer device"

private inline fun <reified T> input(value: T): JsonObject =
    ClarityJson.encodeToJsonElement(kotlinx.serialization.serializer<T>(), value) as JsonObject

/**
 * Eval: a whole morning call as the app drives it, over the real session routes (start, control,
 * Today) with the lead on a scripted model. Ends with Today populated; the lead's tool list never
 * changed while the stages did.
 */
@EnabledIf(ContainerRuntimeAvailable::class)
class MorningCallEvalTest : FunSpec({

    fun ApplicationTestBuilder.setup(r: LeadRig): HttpClient {
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(ClarityJson) }
            install(Authentication) { bearer(AuthProviders.DEVICE) { authenticate { DevicePrincipal(DeviceId("d1"), null) } } }
            SessionRoutes(r.sessionService, TodayService(r.dayPlans, r.tasks, r.heldStore, r.nudges, r.sessionStore, r.settingsProvider, r.clock)).install(this)
        }
        return createClient { install(ContentNegotiation) { json(ClarityJson) } }
    }

    suspend fun HttpClient.control(id: SessionId, control: SessionControl): HttpResponse =
        post("${ApiPaths.SESSIONS}/${id.value}/control") {
            header(HttpHeaders.Authorization, AUTH)
            contentType(ContentType.Application.Json)
            setBody(ClarityJson.encodeToString(SessionControl.serializer(), control))
        }

    test("morning call over the session routes: clear, choose, commit, hand off, and Today shows the plan") {
        val r = EvalDb.rig()
        val script = Script()
        r.model.handler = { script.next() ?: end("(script exhausted)") }
        r.lead.start()

        testApplication {
            val http = setup(r)

            suspend fun open(tag: String) {
                script.add(callTool("open-$tag", "stage_done"), end("opened"))
                r.lead.step() shouldBe true
            }
            suspend fun say(id: SessionId, n: Int, text: String) =
                r.inbox.enqueue(InboxItem.UserInput(ClientInputId("u$n"), text, InputSource.VOICE, id), dedupKey = "user:u$n")

            // --- the phone rings, the person answers
            val started = http.post(ApiPaths.SESSIONS) {
                header(HttpHeaders.Authorization, AUTH); contentType(ContentType.Application.Json)
                setBody(ClarityJson.encodeToString(StartSession.serializer(), StartSession(SessionKind.MORNING_CALL)))
            }
            started.status shouldBe HttpStatusCode.Created
            val session = started.body<Session>()
            session.state shouldBe Session.State.BUSY
            open("clear")
            http.get("${ApiPaths.SESSIONS}/${session.id.value}") { header(HttpHeaders.Authorization, AUTH) }.body<Session>().state shouldBe Session.State.ACTIVE

            // --- CLEAR
            say(session.id, 1, "the spec, Dana's contract, the dentist, feedback for Alex")
            script.add(
                callTool("c1", "capture", input(SessionTools.CaptureInput(listOf("the spec", "Dana's contract", "the dentist", "feedback for Alex").map { SessionTools.ItemInput(it) }))),
                end("captured"),
            )
            r.lead.step() shouldBe true

            // --- CHOOSE; pressing Next while the lead is still working is refused (409)
            http.control(session.id, SessionControl.Next).body<Session>().stage?.name shouldBe "CHOOSE"
            http.control(session.id, SessionControl.Next).status shouldBe HttpStatusCode.Conflict
            open("choose")
            say(session.id, 2, "the spec, and Dana's contract is due today")
            script.add(
                callTool(
                    "p1", "set_priorities",
                    input(SessionTools.SetPrioritiesInput(SessionTools.PriorityInput("the spec", "important"), SessionTools.PriorityInput("Dana's contract", "due today"))),
                ),
                end("chosen"),
            )
            r.lead.step() shouldBe true

            // --- COMMIT
            http.control(session.id, SessionControl.Next).body<Session>().stage?.name shouldBe "COMMIT"
            open("commit")
            say(session.id, 3, "9:30 at my desk; if Dana emails it waits")
            script.add(
                callTool("i1", "set_plan", input(SessionTools.SetPlanInput(listOf(SessionTools.IfThenInput("it's 9:30", "spec at my desk until 12"))))),
                end("planned"),
            )
            r.lead.step() shouldBe true

            // --- HAND_OFF
            http.control(session.id, SessionControl.Next).body<Session>().stage?.name shouldBe "HAND_OFF"
            open("handoff")
            say(session.id, 4, "find the dentist's number, draft Dana's reply, park the rest")
            script.add(
                callTool(
                    "h1", "hand_off",
                    input(
                        SessionTools.HandOffInput(
                            alone = listOf(SessionTools.TaskInput("find the dentist's number")),
                            review = listOf(SessionTools.TaskInput("draft reply to Dana")),
                            parked = listOf("feedback for Alex"),
                        ),
                    ),
                ),
                end("handed off"),
            )
            r.lead.step() shouldBe true

            // --- end the call; the lead carries the hand-off out
            http.control(session.id, SessionControl.Next).body<Session>().state shouldBe Session.State.ENDED
            script.add(end("on it"))
            r.lead.step() shouldBe true

            // --- Today is populated
            val today = http.get(ApiPaths.TODAY) { header(HttpHeaders.Authorization, AUTH) }.body<Today>()
            today.called shouldBe true
            today.priorities.map { it.text } shouldBe listOf("the spec", "Dana's contract")
            today.plan.map { it.sentence() } shouldBe listOf("If it's 9:30, then spec at my desk until 12.")
            today.parked shouldBe listOf("feedback for Alex")
            today.doing shouldHaveSize 2

            // the lead's cached prefix survived every stage
            r.model.requests.map { it.prefix() }.toSet() shouldHaveSize 1
            r.model.requests.first().tools.map { it.name } shouldContain "stage_done"
        }
    }
})
