package net.robog.clarity.connector.sdk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.connector.ConnectorTestReport
import net.robog.clarity.shared.connector.ConnectorTestRequest

private fun backendWithTest(requestId: String, configVersion: Long, reported: CompletableDeferred<ConnectorTestReport>): ClarityClient {
    var served = false
    return testClient { req ->
        when {
            req.method == HttpMethod.Get && req.url.encodedPath == ApiPaths.TEST ->
                if (served) {
                    respond("", HttpStatusCode.NoContent)
                } else {
                    served = true
                    respond(ClarityJson.encodeToString(ConnectorTestRequest.serializer(), ConnectorTestRequest(requestId, Instant.fromEpochSeconds(5))), HttpStatusCode.OK, jsonHeaders)
                }
            req.method == HttpMethod.Post && req.url.encodedPath == ApiPaths.TEST -> {
                reported.complete(ClarityJson.decodeFromString(ConnectorTestReport.serializer(), req.body.toByteArray().decodeToString()))
                respond("", HttpStatusCode.NoContent)
            }
            req.url.encodedPath == "/ingest/v1/config" ->
                respond(ClarityJson.encodeToString(ConnectorConfig.serializer(), ConnectorConfig(configVersion, JsonObject(emptyMap()))), HttpStatusCode.OK, jsonHeaders)
            else -> respond("", HttpStatusCode.NotFound)
        }
    }.first
}

class ConnectorTestLoopTest : FunSpec({
    test("a pending test request is answered with the connector's own result") {
        val reported = CompletableDeferred<ConnectorTestReport>()
        val client = backendWithTest("r1", 3, reported)
        val runtime = ConnectorRuntime("mail", SealingKeys.generate())
        val job = launch {
            runActionLoop(client, InMemoryStateStore(), ActionHandlers.None, wait = 10.milliseconds, runtime = runtime) { config ->
                ConnectorTestResult(true, "config v${config.version} ok", mapOf("folders" to 3))
            }
        }
        val report = withTimeout(5_000) { reported.await() }
        job.cancel()
        report.requestId shouldBe "r1"
        report.ok shouldBe true
        report.message shouldBe "config v3 ok"
        report.counts shouldBe mapOf("folders" to 3)
    }

    test("a failing test is reported as a failure, not thrown") {
        val reported = CompletableDeferred<ConnectorTestReport>()
        val client = backendWithTest("r2", 1, reported)
        val runtime = ConnectorRuntime("mail", SealingKeys.generate())
        val job = launch {
            runActionLoop(client, InMemoryStateStore(), ActionHandlers.None, wait = 10.milliseconds, runtime = runtime) { _ ->
                error("authentication refused")
            }
        }
        val report = withTimeout(5_000) { reported.await() }
        job.cancel()
        report.ok shouldBe false
        report.message shouldBe "authentication refused"
    }
})
