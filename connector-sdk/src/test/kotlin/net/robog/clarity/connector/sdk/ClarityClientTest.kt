package net.robog.clarity.connector.sdk

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.robog.clarity.shared.connector.ConnectorManifest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.connector.ConnectorAccount
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.ingest.IngestBatch
import net.robog.clarity.shared.ingest.IngestResponse

private fun okResponse(batchBody: String): String {
    val batch = ClarityJson.decodeFromString(IngestBatch.serializer(), batchBody)
    return ClarityJson.encodeToString(
        IngestResponse.serializer(),
        IngestResponse(batch.records.map { net.robog.clarity.shared.ingest.RecordResult(it.sourceId, net.robog.clarity.shared.ingest.RecordOutcome.Removed) }),
    )
}

class ClarityClientTest : FunSpec({
    test("push splits into batches of at most MAX_RECORDS and preserves order") {
        runTest {
            val sizes = mutableListOf<Int>()
            val (client, engine) = testClient { req ->
                val body = req.body.toByteArray().decodeToString()
                sizes += ClarityJson.decodeFromString(IngestBatch.serializer(), body).records.size
                respond(okResponse(body), HttpStatusCode.OK, jsonHeaders)
            }
            val results = pushChunked(client, tombstones(1203)).getOrNull()!!
            sizes shouldBe listOf(500, 500, 203)
            results shouldHaveSize 1203
            results.map { it.sourceId.value } shouldBe (1..1203).map { "r$it" }
            engine.requestHistory.all { it.headers[HttpHeaders.Authorization] == "Bearer k" } shouldBe true
        }
    }

    test("empty push makes no request") {
        runTest {
            val (client, engine) = testClient { error("unexpected request") }
            pushChunked(client, emptyList()).getOrNull() shouldBe emptyList()
            engine.requestHistory shouldHaveSize 0
        }
    }

    test("retries on 503 then succeeds") {
        runTest {
            var calls = 0
            val (client, _) = testClient { req ->
                calls++
                if (calls < 3) respond("", HttpStatusCode.ServiceUnavailable)
                else respond(okResponse(req.body.toByteArray().decodeToString()), HttpStatusCode.OK, jsonHeaders)
            }
            client.postBatch(IngestBatch(tombstones(2))).getOrNull()!!.results shouldHaveSize 2
            calls shouldBe 3
        }
    }

    test("gives up with Unavailable after maxAttempts") {
        runTest {
            var calls = 0
            val (client, _) = testClient { calls++; respond("", HttpStatusCode.BadGateway) }
            val err = client.postBatch(IngestBatch(tombstones(1))).leftOrNull()
            err.shouldBeInstanceOf<ClientError.Unavailable>().attempts shouldBe fastRetry.maxAttempts
            calls shouldBe fastRetry.maxAttempts
        }
    }

    test("does not retry 403 and surfaces the problem") {
        runTest {
            var calls = 0
            val (client, _) = testClient {
                calls++
                respond("""{"title":"Forbidden","status":403,"detail":"noun not granted"}""", HttpStatusCode.Forbidden, jsonHeaders)
            }
            val err = client.postBatch(IngestBatch(tombstones(1))).leftOrNull()
            val rejected = err.shouldBeInstanceOf<ClientError.Rejected>()
            rejected.status shouldBe 403
            rejected.problem?.detail shouldBe "noun not granted"
            calls shouldBe 1
        }
    }

    test("SyncScope.push throws ClientException on rejection") {
        runTest {
            val (client, _) = testClient { respond("", HttpStatusCode.Unauthorized) }
            val scope = object : SyncScope {
                override val config = ConnectorConfig(1, JsonObject(emptyMap()))
                override val state = InMemoryStateStore()
                override val log = io.github.oshai.kotlinlogging.KotlinLogging.logger("t")
                override suspend fun pushCatching(records: List<net.robog.clarity.shared.ingest.IngestRecord>) = pushChunked(client, records)
            }
            shouldThrow<ClientException> { scope.push(tombstones(1)) }.error.shouldBeInstanceOf<ClientError.Rejected>()
        }
    }

    test("config: 200 yields Changed with ETag, 304 yields NotModified, If-None-Match is sent") {
        runTest {
            val seen = mutableListOf<String?>()
            val (client, _) = testClient { req ->
                seen += req.headers[HttpHeaders.IfNoneMatch]
                if (req.headers[HttpHeaders.IfNoneMatch] == "\"7\"") respond("", HttpStatusCode.NotModified)
                else respond(
                    """{"version":7,"values":{"emitSample":true}}""", HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("\"7\"")),
                )
            }
            val first = client.pollConfig(null).getOrNull().shouldBeInstanceOf<ConfigPoll.Changed>()
            first.config.version shouldBe 7
            first.etag shouldBe "\"7\""
            client.pollConfig(first.etag).getOrNull() shouldBe ConfigPoll.NotModified
            seen shouldBe listOf(null, "\"7\"")
        }
    }

    test("lifecycle: registers manifest once reachable, then polls config and syncs repeatedly") {
        // Real time: the mock engine runs on its own dispatcher, so virtual time cannot drive it.
        runBlocking {
            val manifestCalls = AtomicInteger()
            val syncs = AtomicInteger()
            val (client, engine) = testClient { req ->
                when (req.method) {
                    HttpMethod.Put ->
                        if (manifestCalls.incrementAndGet() == 1) respond("", HttpStatusCode.ServiceUnavailable)
                        else respond("", HttpStatusCode.NoContent)
                    else -> respond("""{"version":1,"values":{}}""", HttpStatusCode.OK, jsonHeaders)
                }
            }
            val manifest = ConnectorManifest("t", "T", "1", emptySet())
            val job = launch { runLoop(client, InMemoryStateStore(), manifest, 20.milliseconds) { syncs.incrementAndGet() } }
            withTimeout(10.seconds) { while (syncs.get() < 3) delay(10) }
            job.cancelAndJoin()
            manifestCalls.get() shouldBe 2 // 503 retried inside the client, then 204
            engine.requestHistory.first().method shouldBe HttpMethod.Put
        }
    }

    test("the accounts of a config are announced with the manifest once, not on every poll") {
        runBlocking {
            val manifests = java.util.concurrent.CopyOnWriteArrayList<ConnectorManifest>()
            val syncs = AtomicInteger()
            val (client, _) = testClient { req ->
                when (req.method) {
                    HttpMethod.Put -> {
                        manifests += ClarityJson.decodeFromString(ConnectorManifest.serializer(), req.body.toByteArray().decodeToString())
                        respond("", HttpStatusCode.NoContent)
                    }
                    else -> respond("""{"version":1,"values":{"address":"me@example.com"}}""", HttpStatusCode.OK, jsonHeaders)
                }
            }
            val manifest = ConnectorManifest("t", "T", "1", emptySet())
            val accounts = { config: ConnectorConfig ->
                listOf(ConnectorAccount("Home", (config.values["address"] as kotlinx.serialization.json.JsonPrimitive).content))
            }
            val job = launch { runLoop(client, InMemoryStateStore(), manifest, 20.milliseconds, accounts = accounts) { syncs.incrementAndGet() } }
            withTimeout(10.seconds) { while (syncs.get() < 3) delay(10) }
            job.cancelAndJoin()
            manifests.map { it.accounts } shouldBe listOf(emptyList(), listOf(ConnectorAccount("Home", "me@example.com")))
        }
    }
})
