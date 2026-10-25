package net.robog.clarity.connector.sdk

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Paths
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.noun.Noun

val testSettings = ConnectorSettings("http://backend:8080", "k", 5.minutes, Paths.get("/tmp/unused"))

val fastRetry = RetryPolicy(maxAttempts = 4, baseDelay = 1.milliseconds, maxDelay = 4.milliseconds)

val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

fun tombstones(n: Int): List<IngestRecord> =
    (1..n).map { IngestRecord.Tombstone(SourceId("r$it"), "1", Instant.fromEpochSeconds(0), Noun.MESSAGE) }

fun testClient(retry: RetryPolicy = fastRetry, handler: MockRequestHandler): Pair<ClarityClient, MockEngine> {
    val engine = MockEngine(handler)
    return ClarityClient(clarityHttpClient(testSettings, engine), retry, jitter = { 0.5 }) to engine
}
