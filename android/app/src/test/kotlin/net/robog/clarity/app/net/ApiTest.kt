package net.robog.clarity.app.net

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.robog.clarity.app.data.Session
import net.robog.clarity.shared.DeviceId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.testutil.FakeDeviceKey
import net.robog.clarity.app.testutil.FakeSessionStore
import net.robog.clarity.app.testutil.agentItem
import net.robog.clarity.app.testutil.signedIn
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.feed.FeedEvent
import net.robog.clarity.shared.feed.FeedPage
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionId

private val JSON = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

class KtorApiTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): KtorClarityApi {
        val store = FakeSessionStore(signedIn())
        val engine = MockEngine { request -> requests += request; handler(request) }
        return KtorClarityApi(createHttpClient(DeviceJwtSigner(FakeDeviceKey()), store, engine), store)
    }

    @Test fun missingRoutesBecomeNotFoundNotCrashes() = runTest {
        val api = api { respond("", HttpStatusCode.NotFound) }
        assertIs<ApiError.NotFound>((api.settings() as ApiResult.Failure).error)
        assertIs<ApiError.NotFound>((api.today() as ApiResult.Failure).error)
    }

    @Test fun errorsAreMapped() = runTest {
        assertIs<ApiError.Unauthorized>((api { respond("", HttpStatusCode.Unauthorized) }.held() as ApiResult.Failure).error)
        assertIs<ApiError.Http>((api { respond("", HttpStatusCode.InternalServerError) }.held() as ApiResult.Failure).error)
        val malformed = api { respond("{not json", HttpStatusCode.OK, JSON) }.held()
        assertIs<ApiError.Malformed>((malformed as ApiResult.Failure).error)
    }

    @Test fun feedSendsAfterAndABearerToken() = runTest {
        val body = ClarityJson.encodeToString(FeedPage.serializer(), FeedPage(listOf(agentItem(4, "hi")), hasMore = false))
        val api = api { respond(body, HttpStatusCode.OK, JSON) }
        val page = (api.feed(3) as ApiResult.Success).value
        assertEquals(listOf(4L), page.items.map { it.seq })
        val request = requests.single()
        assertEquals("3", request.url.parameters["after"])
        assertEquals("/api/v1/feed", request.url.encodedPath)
        assertTrue(request.headers[HttpHeaders.Authorization]!!.startsWith("Bearer "))
    }

    @Test fun controlSerializesTheDiscriminator() = runTest {
        val session = """{"id":"s","kind":"values_interview","state":"active","startedAt":"2026-10-26T09:00:00Z"}"""
        val api = api { respond(session, HttpStatusCode.OK, JSON) }
        api.controlSession(SessionId("s"), SessionControl.Reword)
        val sent = requests.single()
        assertEquals(HttpMethod.Post, sent.method)
        assertEquals("/api/v1/sessions/s/control", sent.url.encodedPath)
        assertEquals("""{"type":"reword"}""", String(sent.body.toByteArray()))
    }

    @Test fun healthIsUnauthenticated() = runTest {
        val api = api { respond("ok", HttpStatusCode.OK) }
        assertIs<ApiResult.Success<Unit>>(api.health("https://clarity.example"))
        assertNull(requests.single().headers[HttpHeaders.Authorization])
    }
}

class FeedEventDecodingTest {
    private fun data(e: FeedEvent) = ClarityJson.encodeToString(FeedEvent.serializer(), e)

    @Test fun decodesPayloadsWithTheirTypeField() {
        val e = FeedEvent.Busy(true)
        assertEquals(e, decodeFeedEvent("busy", data(e)))
    }

    @Test fun fallsBackToTheEventNameWhenThePayloadHasNoType() {
        val payload = JsonObject(mapOf("busy" to JsonPrimitive(true))).toString()
        assertEquals(FeedEvent.Busy(true), decodeFeedEvent("busy", payload))
    }

    @Test fun unknownOrBrokenEventsAreIgnored() {
        assertNull(decodeFeedEvent("ping", "x"))
        assertNull(decodeFeedEvent("from_the_future", """{"type":"from_the_future"}"""))
        assertNull(decodeFeedEvent("item", null))
    }
}

/**
 * The SSE plugin needs a real engine (the mock engine has no SSE support), so these run the OkHttp
 * engine against a local MockWebServer.
 */
class KtorFeedEventSourceTest {
    private fun source(url: String, silence: kotlin.time.Duration = FEED_STREAM_SILENCE): KtorFeedEventSource {
        val store = FakeSessionStore(Session(url, DeviceId("dev-1")))
        return KtorFeedEventSource(createHttpClient(DeviceJwtSigner(FakeDeviceKey()), store), store, silence)
    }

    // FeedSync reconnects whether the stream ends or throws; what matters is that it does not hang.
    @Test fun aSilentStreamEndsSoItCanReconnect() = runBlocking {
        MockWebServer().use { server ->
            // Headers arrive, then nothing: what a stream dropped by a proxy looks like from the phone.
            server.enqueue(
                MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream")
                    .body(": heartbeat\n\n").bodyDelay(10, java.util.concurrent.TimeUnit.SECONDS).build(),
            )
            server.start()
            var opened = false
            val started = System.nanoTime()
            runCatching {
                source(server.url("/").toString().trimEnd('/'), silence = kotlin.time.Duration.parse("500ms"))
                    .connect(0, onOpen = { opened = true }) {}
            }
            assertTrue(opened)
            assertTrue(System.nanoTime() - started < 5_000_000_000L, "a silent stream must not hang")
        }
    }

    @Test fun streamsDecodedEventsAndReportsOpen() = runBlocking {
        val first = FeedEvent.ItemAppended(agentItem(7, "hello"))
        val second = FeedEvent.Busy(false)
        val sse = buildString {
            append(": keep-alive\n\n")
            append("event: item\ndata: ${ClarityJson.encodeToString(FeedEvent.serializer(), first)}\n\n")
            append("event: ping\ndata: whatever\n\n")
            append("event: busy\ndata: ${ClarityJson.encodeToString(FeedEvent.serializer(), second)}\n\n")
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(sse).build())
            server.start()
            val events = mutableListOf<FeedEvent>()
            var opened = false
            source(server.url("/").toString().trimEnd('/')).connect(after = 6, onOpen = { opened = true }) { events += it }
            assertTrue(opened)
            assertEquals(listOf(first, second), events)
            val request = server.takeRequest()
            assertEquals("6", request.url.queryParameter("after"))
            assertEquals("/api/v1/events", request.url.encodedPath)
            assertTrue(request.headers["Authorization"]!!.startsWith("Bearer "))
        }
    }

    @Test fun aRejectedStreamBecomesAFeedStreamException() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(404).build())
            server.start()
            val e = runCatching { source(server.url("/").toString().trimEnd('/')).connect(0, {}) {} }.exceptionOrNull()
            assertIs<FeedStreamException>(e)
            assertEquals(404, e.status)
        }
    }
}
