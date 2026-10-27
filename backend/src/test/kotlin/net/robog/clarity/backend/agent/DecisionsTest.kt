package net.robog.clarity.backend.agent

import arrow.core.Either
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.DecisionConfig
import org.jetbrains.exposed.v1.jdbc.Database

private const val KEY = "sk-or-decision-test-key-42"

private data class SeenRequest(val path: String, val auth: String?, val body: String)

/** A fake decisions endpoint on loopback; [respond] gets the exchange and the 0-based call number. Never the real API. */
private class FakeDecisions(private val respond: (HttpExchange, Int) -> Unit) : AutoCloseable {
    val seen = CopyOnWriteArrayList<SeenRequest>()
    private val calls = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            seen += SeenRequest(ex.requestURI.path, ex.requestHeaders.getFirst("Authorization"), ex.requestBody.readAllBytes().decodeToString())
            try {
                respond(ex, calls.getAndIncrement())
            } catch (_: Exception) {
            } finally {
                ex.close()
            }
        }
        executor = java.util.concurrent.Executors.newCachedThreadPool()
        start()
    }
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/api/v1"

    override fun close() = server.stop(0)
}

private fun HttpExchange.json(status: Int, body: String) {
    val bytes = body.toByteArray()
    responseHeaders.add("content-type", "application/json")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private val questions = mapOf(
    "act_now" to DecisionQuestion.Noul("Act now?", whenTrue = "yes, soon", whenFalse = "it can wait"),
    "urgency" to DecisionQuestion.Choice("How urgent?", linkedMapOf("none" to "nothing", "now" to "right now")),
)

private const val OK_BODY = """{"model":"microsoft/microsoft-decision-1-20261009","answers":{
  "act_now":{"type":"noul","noul":0.0036},
  "urgency":{"type":"choice","choice":"none","probabilities":{"none":0.99,"now":0.01},"confidence":0.98}},
  "usage":{"input_tokens":164,"output_tokens":2,"cost":0.000006888},"id":"gen-dec-1","provider":"Azure"}"""

private fun client(fake: FakeDecisions, timeout: kotlin.time.Duration = 5.seconds, zdr: Boolean = true) =
    OpenRouterDecisionClient(KEY, fake.baseUrl, null, "microsoft/microsoft-decision-1", zdr, timeout, retryDelays = listOf(10.milliseconds))

private val synthetic = buildJsonObject { put("note", "synthetic") }

class DecisionsTest : FunSpec({

    test("the endpoint is /api/alpha/decisions, derived from the /api/v1 base") {
        decisionsEndpoint("https://openrouter.ai/api/v1") shouldBe "https://openrouter.ai/api/alpha/decisions"
        decisionsEndpoint("https://openrouter.ai/api/v1/") shouldBe "https://openrouter.ai/api/alpha/decisions"
        decisionsEndpoint("http://gw.local/openrouter/api") shouldBe "http://gw.local/openrouter/api/alpha/decisions"
    }

    test("request: model, state, noul and choice questions in System One format, bearer key, privacy preferences") {
        FakeDecisions { ex, _ -> ex.json(200, OK_BODY) }.use { fake ->
            client(fake).decide(synthetic, questions).isRight() shouldBe true
            val seen = fake.seen.single()
            seen.path shouldBe "/api/alpha/decisions"
            seen.auth shouldBe "Bearer $KEY"
            val body = Json.parseToJsonElement(seen.body).jsonObject
            body["model"]!!.jsonPrimitive.content shouldBe "microsoft/microsoft-decision-1"
            body["state"] shouldBe synthetic
            val q = body["questions"]!!.jsonObject
            q["act_now"] shouldBe Json.parseToJsonElement(
                """{"type":"noul","instructions":"Act now?","criteria":{"true":"yes, soon","false":"it can wait"}}""",
            )
            q["urgency"] shouldBe Json.parseToJsonElement(
                """{"type":"choice","instructions":"How urgent?","criteria":{"none":"nothing","now":"right now"}}""",
            )
            body["provider"] shouldBe Json.parseToJsonElement("""{"zdr":true,"data_collection":"deny"}""")
        }
    }

    test("without zdr no provider preferences are sent") {
        decisionRequestBody("m", JsonPrimitive("s"), questions, zdr = false).containsKey("provider") shouldBe false
    }

    test("response: noul probability, choice with probabilities, usage and cost in micro-dollars") {
        FakeDecisions { ex, _ -> ex.json(200, OK_BODY) }.use { fake ->
            val result = client(fake).decide(synthetic, questions).getOrNull()!!
            result.answers["act_now"] shouldBe DecisionAnswer.Noul(0.0036)
            result.answers["urgency"] shouldBe DecisionAnswer.Choice("none", mapOf("none" to 0.99, "now" to 0.01), 0.98)
            result.model shouldBe "microsoft/microsoft-decision-1-20261009"
            result.provider shouldBe "Azure"
            result.usage shouldBe DecisionUsage(164, 2, 7)
        }
    }

    test("a refused answer is a Refusal; a refused request is an error") {
        val refusal = """{"answers":{"act_now":{"type":"refusal"},"urgency":{"type":"choice","choice":"now"}},"usage":{"input_tokens":1,"output_tokens":0}}"""
        val parsed = parseDecisionResponse(refusal, questions).getOrNull()!!
        parsed.answers["act_now"] shouldBe DecisionAnswer.Refusal
        parsed.usage.costMicros shouldBe null
        parseDecisionResponse("""{"type":"refusal","message":"no"}""", questions).leftOrNull().shouldBeInstanceOf<DecisionError.Refused>()
    }

    test("a missing answer, a wrong type, an unknown option or a bad probability is malformed, never a default") {
        fun bad(answers: String) = parseDecisionResponse("""{"answers":$answers,"usage":{"input_tokens":1,"output_tokens":0}}""", questions)
            .leftOrNull().shouldBeInstanceOf<DecisionError.Malformed>()
        bad("""{"urgency":{"type":"choice","choice":"none"}}""")
        bad("""{"act_now":{"type":"choice","choice":"none"},"urgency":{"type":"choice","choice":"none"}}""")
        bad("""{"act_now":{"type":"noul","noul":0.5},"urgency":{"type":"choice","choice":"later"}}""")
        bad("""{"act_now":{"type":"noul","noul":1.5},"urgency":{"type":"choice","choice":"none"}}""")
        bad("""{"act_now":{"type":"score","score":1},"urgency":{"type":"choice","choice":"none"}}""")
        parseDecisionResponse("not json", questions).leftOrNull().shouldBeInstanceOf<DecisionError.Malformed>()
    }

    test("a 400 is returned at once, with the key redacted") {
        FakeDecisions { ex, _ -> ex.json(400, """{"error":{"code":400,"message":"bad key $KEY"}}""") }.use { fake ->
            val error = client(fake).decide(synthetic, questions).leftOrNull()
            error.shouldBeInstanceOf<DecisionError.Http>()
            error.status shouldBe 400
            error.message shouldContain "[redacted]"
            error.message shouldNotContain KEY
            fake.seen.size shouldBe 1
        }
    }

    test("a 5xx or 429 is retried once") {
        FakeDecisions { ex, n -> if (n == 0) ex.json(503, "{}") else ex.json(200, OK_BODY) }.use { fake ->
            client(fake).decide(synthetic, questions).isRight() shouldBe true
            fake.seen.size shouldBe 2
        }
        FakeDecisions { ex, _ -> ex.json(429, "{}") }.use { fake ->
            (client(fake).decide(synthetic, questions).leftOrNull() as DecisionError.Http).status shouldBe 429
            fake.seen.size shouldBe 2
        }
    }

    test("a slow answer times out as unreachable, after one retry") {
        FakeDecisions { ex, _ -> Thread.sleep(1_500); ex.json(200, OK_BODY) }.use { fake ->
            client(fake, timeout = 200.milliseconds).decide(synthetic, questions).leftOrNull().shouldBeInstanceOf<DecisionError.Unreachable>()
            fake.seen.size shouldBe 2
        }
    }

    test("Decisions is unavailable when off or without an OpenRouter key, and never throws") {
        val off = AgentConfig(anthropicApiKey = null, reviewInterval = 2.seconds, openRouterApiKey = KEY, decisions = DecisionConfig(enabled = false))
        OpenRouterDecisionClientFactory(off).create() shouldBe null
        OpenRouterDecisionClientFactory(off.copy(openRouterApiKey = null, decisions = DecisionConfig())).create() shouldBe null
        OpenRouterDecisionClientFactory(off.copy(decisions = DecisionConfig())).create().shouldBeInstanceOf<OpenRouterDecisionClient>()

        val db = Database.connect("jdbc:postgresql://127.0.0.1:1/none", driver = "org.postgresql.Driver")
        Decisions(off, { null }, db, Clock.System).decide(synthetic, questions) shouldBe null
        val throwing = Decisions(off, { DecisionClient { _, _ -> error("boom") } }, db, Clock.System)
        throwing.decide(synthetic, questions).shouldBeInstanceOf<Either.Left<DecisionError.Unreachable>>()
    }
})
