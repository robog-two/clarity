package net.robog.clarity.connector.sdk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.fetch.FetchFailure
import net.robog.clarity.shared.fetch.FetchPoll
import net.robog.clarity.shared.fetch.FetchRequest

private class FakeFetchBackend(vararg polls: List<FetchRequest>) {
    private val queue = polls.toMutableList()
    val uploads = mutableListOf<Triple<String, Map<String, String>, ByteArray>>()
    val errors = mutableListOf<Pair<String, FetchFailure>>()
    val client = testClient { request ->
        val path = request.url.encodedPath
        when {
            request.method == HttpMethod.Get && path == "/ingest/v1/fetches" ->
                respond(ClarityJson.encodeToString(FetchPoll.serializer(), FetchPoll(queue.removeFirstOrNull().orEmpty())), HttpStatusCode.OK, jsonHeaders)
            request.method == HttpMethod.Put && path.startsWith("/ingest/v1/fetches/") -> {
                uploads += Triple(path.substringAfterLast('/'), request.url.parameters.entries().associate { it.key to it.value.first() }, request.body.toByteArray())
                respond("", HttpStatusCode.NoContent)
            }
            request.method == HttpMethod.Post && path.endsWith("/error") -> {
                errors += path.removePrefix("/ingest/v1/fetches/").removeSuffix("/error") to
                    ClarityJson.decodeFromString(FetchFailure.serializer(), request.body.toByteArray().decodeToString())
                respond("", HttpStatusCode.NoContent)
            }
            else -> respond("", HttpStatusCode.NotFound)
        }
    }.first
}

private fun fetch(id: String, part: String = "2", max: Long = 1_000) = FetchRequest(id, SourceId("acct/INBOX/1/7"), part, max)

class FetchesTest : FunSpec({
    test("a found part is uploaded with its name and type") {
        runTest {
            val backend = FakeFetchBackend(listOf(fetch("f1")))
            val handlers = fetchHandlers { onFetch { FetchOutcome.Found(byteArrayOf(1, 2, 3), "a b.pdf", "application/pdf") } }
            pollAndFetch(backend.client, handlers, 1.seconds) shouldBe null
            val (id, params, body) = backend.uploads.single()
            id shouldBe "f1"
            params["filename"] shouldBe "a b.pdf"
            params["type"] shouldBe "application/pdf"
            body.toList() shouldBe listOf<Byte>(1, 2, 3)
        }
    }

    test("a failure, a throwing handler and an over-size answer are reported as errors") {
        runTest {
            val backend = FakeFetchBackend(listOf(fetch("f1", "1"), fetch("f2", "2"), fetch("f3", "3", max = 2)))
            val handlers = fetchHandlers {
                onFetch { request ->
                    when (request.part) {
                        "1" -> FetchOutcome.notFound("no such part")
                        "2" -> error("imap down")
                        else -> FetchOutcome.Found(ByteArray(5), null)
                    }
                }
            }
            pollAndFetch(backend.client, handlers, 1.seconds) shouldBe null
            backend.uploads.size shouldBe 0
            val byId = backend.errors.toMap()
            byId.getValue("f1").code shouldBe FetchFailure.Code.NOT_FOUND
            byId.getValue("f2").code shouldBe FetchFailure.Code.FAILED
            byId.getValue("f2").message shouldContain "imap down"
            byId.getValue("f3").code shouldBe FetchFailure.Code.TOO_LARGE
        }
    }

    test("a poll failure is returned for back-off") {
        runTest {
            val (client, _) = testClient { respond("", HttpStatusCode.Unauthorized, jsonHeaders) }
            pollAndFetch(client, FetchHandlers.None, 1.seconds).shouldBeInstanceOf<ClientError.Rejected>().status shouldBe 401
        }
    }

    test("no handler means isEmpty, and the cap helper") {
        FetchHandlers.None.isEmpty shouldBe true
        fetchHandlers { onFetch { FetchOutcome.notFound("x") } }.isEmpty shouldBe false
        withinCap(null, 10) shouldBe true
        withinCap(10, 10) shouldBe true
        withinCap(11, 10) shouldBe false
    }
})
