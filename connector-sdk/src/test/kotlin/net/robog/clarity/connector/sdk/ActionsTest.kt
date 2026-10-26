package net.robog.clarity.connector.sdk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.action.ActionId
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.action.ActionPoll
import net.robog.clarity.shared.action.ActionResult
import net.robog.clarity.shared.action.ConnectorAction
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.ProposedAction

private fun reminder(key: String, text: String = "call mum") =
    ProposedAction.CreateReminder(key, "remind: $text", text, Instant.parse("2026-03-04T12:00:00Z"))

private fun delivered(id: String, action: ProposedAction, attempt: Int = 1) =
    ConnectorAction(ActionId(id), action, attempt = attempt, createdAt = Instant.parse("2026-03-04T11:00:00Z"))

/** A scripted backend: serves queued polls in order and records the results posted to it. */
private class FakeBackend(vararg polls: List<ConnectorAction>) {
    private val queue = ArrayDeque(polls.toList())
    val results = mutableListOf<Pair<String, ActionOutcome>>()
    val pollQueries = mutableListOf<String?>()
    var failResults = false

    val client = testClient { req ->
        when {
            req.method == HttpMethod.Get && req.url.encodedPath == "/ingest/v1/actions" -> {
                pollQueries += req.url.parameters["wait"]
                respond(ClarityJson.encodeToString(ActionPoll.serializer(), ActionPoll(queue.removeFirstOrNull() ?: emptyList())), HttpStatusCode.OK, jsonHeaders)
            }
            req.method == HttpMethod.Post && req.url.encodedPath.endsWith("/result") -> {
                if (failResults) return@testClient respond("", HttpStatusCode.NotFound, jsonHeaders)
                val id = req.url.encodedPath.removePrefix("/ingest/v1/actions/").removeSuffix("/result")
                val body = ClarityJson.decodeFromString(ActionResult.serializer(), req.body.toByteArray().decodeToString())
                results += id to body.outcome
                respond("", HttpStatusCode.NoContent)
            }
            else -> error("unexpected ${req.method} ${req.url}")
        }
    }.first
}

class ActionsTest : FunSpec({

    test("an action is handled by its type and the outcome is reported") {
        runTest {
            val seen = mutableListOf<String>()
            val handlers = actionHandlers {
                on<ProposedAction.CreateReminder> { seen += it.text; ActionOutcome.Done(SourceId("rem-1")) }
            }
            val backend = FakeBackend(listOf(delivered("a1", reminder("k1"))))
            val error = pollAndExecute(backend.client, handlers, ActionLedger(InMemoryStateStore()), 25.seconds)

            error shouldBe null
            seen shouldBe listOf("call mum")
            backend.results shouldContainExactly listOf("a1" to ActionOutcome.Done(SourceId("rem-1")))
            backend.pollQueries shouldBe listOf("25")
        }
    }

    test("a re-delivered action returns the stored outcome without executing again") {
        runTest {
            var runs = 0
            val handlers = actionHandlers { on<ProposedAction.CreateReminder> { runs++; ActionOutcome.Done(SourceId("rem-$runs")) } }
            val action = reminder("same-key")
            val backend = FakeBackend(listOf(delivered("a1", action)), listOf(delivered("a1", action, attempt = 2)), listOf(delivered("a2", action)))
            val ledger = ActionLedger(InMemoryStateStore())
            repeat(3) { pollAndExecute(backend.client, handlers, ledger, 1.seconds) }

            runs shouldBe 1
            backend.results.map { it.second }.toSet() shouldBe setOf(ActionOutcome.Done(SourceId("rem-1")))
            backend.results shouldHaveSize 3
        }
    }

    test("the ledger survives a restart because it lives in the state store") {
        runTest {
            val state = InMemoryStateStore()
            var runs = 0
            val handlers = actionHandlers { on<ProposedAction.CreateReminder> { runs++; ActionOutcome.Done() } }
            pollAndExecute(FakeBackend(listOf(delivered("a1", reminder("k")))).client, handlers, ActionLedger(state), 1.seconds)
            pollAndExecute(FakeBackend(listOf(delivered("a1", reminder("k")))).client, handlers, ActionLedger(state), 1.seconds)
            runs shouldBe 1
        }
    }

    test("retryable failures are not remembered, so the retry executes again; permanent failures are") {
        runTest {
            var runs = 0
            val handlers = actionHandlers {
                on<ProposedAction.CreateReminder> {
                    runs++
                    if (it.text == "flaky") ActionOutcome.Failed("timeout", retryable = true) else ActionOutcome.Failed("nope", retryable = false)
                }
            }
            val ledger = ActionLedger(InMemoryStateStore())
            val flaky = reminder("flaky-key", "flaky")
            val backend = FakeBackend(listOf(delivered("a1", flaky)), listOf(delivered("a1", flaky, 2)))
            repeat(2) { pollAndExecute(backend.client, handlers, ledger, 1.seconds) }
            runs shouldBe 2

            val perm = reminder("perm-key", "perm")
            val backend2 = FakeBackend(listOf(delivered("b1", perm)), listOf(delivered("b1", perm, 2)))
            repeat(2) { pollAndExecute(backend2.client, handlers, ledger, 1.seconds) }
            runs shouldBe 3
            backend2.results.map { it.second } shouldBe List(2) { ActionOutcome.Failed("nope", retryable = false) }
        }
    }

    test("an unhandled type fails permanently and a throwing handler fails retryably") {
        runTest {
            val handlers = actionHandlers { on<ProposedAction.SaveDraft> { error("imap down") } }
            val draft = ProposedAction.SaveDraft("d1", "draft", net.robog.clarity.shared.governance.EmailDraft(to = emptyList(), subject = "s", body = "b"))
            val backend = FakeBackend(listOf(delivered("a1", reminder("k1")), delivered("a2", draft)))
            pollAndExecute(backend.client, handlers, ActionLedger(InMemoryStateStore()), 1.seconds)

            backend.results[0].second.shouldBeInstanceOf<ActionOutcome.Failed>().also {
                it.retryable shouldBe false
                it.message shouldContain "no handler"
            }
            backend.results[1].second.shouldBeInstanceOf<ActionOutcome.Failed>().also {
                it.retryable shouldBe true
                it.message shouldContain "imap down"
            }
        }
    }

    test("actions in one poll run in order, and a failed report does not stop the loop") {
        runTest {
            val order = mutableListOf<String>()
            val handlers = actionHandlers { on<ProposedAction.CreateReminder> { order += it.text; ActionOutcome.Done() } }
            val backend = FakeBackend(listOf(delivered("a1", reminder("k1", "first")), delivered("a2", reminder("k2", "second"))))
            backend.failResults = true
            pollAndExecute(backend.client, handlers, ActionLedger(InMemoryStateStore()), 1.seconds) shouldBe null
            order shouldBe listOf("first", "second")
        }
    }

    test("a poll failure is returned for back-off, not thrown") {
        runTest {
            val (client, _) = testClient { respond("", HttpStatusCode.Unauthorized, jsonHeaders) }
            val error = pollAndExecute(client, ActionHandlers.None, ActionLedger(InMemoryStateStore()), 1.seconds)
            error.shouldBeInstanceOf<ClientError.Rejected>().status shouldBe 401
        }
    }

    test("handled kinds come from the handlers' types") {
        val handlers = actionHandlers {
            on<ProposedAction.CreateReminder> { ActionOutcome.Done() }
            on<ProposedAction.SendEmail> { ActionOutcome.Done() }
        }
        handlers.kinds shouldBe setOf(ActionKind.CREATE_REMINDER, ActionKind.SEND_EMAIL)
        ActionHandlers.None.isEmpty shouldBe true
    }

    test("the ledger keeps only the most recent keys") {
        runTest {
            val ledger = ActionLedger(InMemoryStateStore(), capacity = 3)
            (1..5).forEach { ledger.record("k$it", ActionOutcome.Done()) }
            ledger.lookup("k1") shouldBe null
            ledger.lookup("k2") shouldBe null
            ledger.lookup("k5") shouldBe ActionOutcome.Done()
            ledger.record("k3", ActionOutcome.Done()) // refreshing k3 protects it from the next eviction
            ledger.record("k6", ActionOutcome.Done())
            ledger.lookup("k4") shouldBe null
            ledger.lookup("k3") shouldBe ActionOutcome.Done()
        }
    }
})
