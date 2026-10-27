package net.robog.clarity.backend.feed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeout
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.InboxStore
import net.robog.clarity.backend.agent.PendingItem
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.DevicePrincipal
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.FeedEvent
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.FeedItemId
import net.robog.clarity.shared.feed.FeedPage
import net.robog.clarity.shared.feed.InputAccepted
import net.robog.clarity.shared.feed.NOTIFY_IN_APP
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.feed.UserInput

private class MemoryFeedStore : FeedStore {
    private val items = CopyOnWriteArrayList<Pair<FeedItem, FeedDraft>>()

    override suspend fun append(draft: FeedDraft): Appended = synchronized(items) {
        draft.clientInputId?.let { id -> items.firstOrNull { it.second.clientInputId == id }?.let { return@synchronized Appended(it.first, created = false) } }
        val item = FeedItem(
            FeedItemId(Uuid.random().toString()), items.size + 1L, Clock.System.now(), draft.author, draft.sessionId, draft.cards, draft.streaming, draft.notify ?: NOTIFY_IN_APP,
        )
        items += item to draft
        Appended(item, created = true)
    }

    override suspend fun page(after: Long, limit: Int): FeedPage {
        val visible = items.filter { it.first.seq > after && !it.second.batchPending }.map { it.first }
        return FeedPage(visible.take(limit), hasMore = visible.size > limit)
    }

    override suspend fun get(id: FeedItemId) = items.firstOrNull { it.first.id == id }?.first
    override suspend fun update(id: FeedItemId, cards: List<Card>, streaming: Boolean): FeedItem? = null
    override suspend fun pendingBatch() = items.filter { it.second.batchPending }.map { it.first }
    override suspend fun markSummarized(ids: Collection<FeedItemId>) = Unit
    override suspend fun notifyOf(id: FeedItemId): String? = null
    override suspend fun heldNotifications(): List<FeedItem> = emptyList()
    override suspend fun aboutOf(ids: Collection<FeedItemId>): Map<FeedItemId, List<net.robog.clarity.shared.noun.Handle>> = emptyMap()
    override suspend fun releaseHold(id: FeedItemId, from: String, to: String, releasedAt: Instant?): FeedItem? = null
    val released = CopyOnWriteArrayList<FeedItem>()
    override suspend fun releasedSince(since: Instant): List<FeedItem> = released.toList()
}

private class MemoryInbox : InboxStore {
    val enqueued = CopyOnWriteArrayList<Pair<InboxItem, String?>>()

    override suspend fun enqueue(item: InboxItem, dedupKey: String?): Boolean = synchronized(enqueued) {
        if (dedupKey != null && enqueued.any { it.second == dedupKey }) false else { enqueued += item to dedupKey; true }
    }

    override suspend fun pending(limit: Int): List<PendingItem> = emptyList()
    override suspend fun markConsumed(seqs: Collection<Long>) = Unit
}

private const val TOKEN = "Bearer device"

class FeedRoutesTest : FunSpec({
    val store = MemoryFeedStore()
    val hub = FeedHub()
    val feed = FeedService(store, hub)
    val inbox = MemoryInbox()
    var expiresAt: Instant? = null

    fun ApplicationTestBuilder.setup(heartbeat: kotlin.time.Duration = 20.seconds): HttpClient {
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(ClarityJson) }
            install(Authentication) {
                bearer(AuthProviders.DEVICE) { authenticate { DevicePrincipal(DeviceId("d1"), expiresAt) } }
            }
            FeedRoutes(feed, hub, inbox, Clock.System).also { it.heartbeat = heartbeat }.install(this)
        }
        return createClient {
            install(ContentNegotiation) { json(ClarityJson) }
            install(SSE)
        }
    }

    suspend fun HttpClient.input(clientId: String, text: String) = post("/api/v1/input") {
        header(HttpHeaders.Authorization, TOKEN)
        contentType(ContentType.Application.Json)
        setBody(UserInput(ClientInputId(clientId), text, InputSource.VOICE))
    }

    fun agentItem(text: String) = FeedDraft(FeedItem.Author.AGENT, listOf(Card.Text(text)))

    test("the feed requires a device") {
        testApplication {
            val client = setup()
            client.get("/api/v1/feed").status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("input is stored as a said card, queued for the lead, and idempotent by client id") {
        testApplication {
            val client = setup()
            val first = client.input("c-1", "move my 3pm")
            first.status shouldBe HttpStatusCode.OK
            val accepted = first.body<InputAccepted>()
            accepted.item.author shouldBe FeedItem.Author.USER
            accepted.item.cards shouldBe listOf(Card.Said("move my 3pm", InputSource.VOICE))

            val replay = client.input("c-1", "move my 3pm").body<InputAccepted>()
            replay.item.id shouldBe accepted.item.id
            replay.item.seq shouldBe accepted.item.seq

            inbox.enqueued.map { it.first }.filterIsInstance<InboxItem.UserInput>().filter { it.clientId.value == "c-1" }.size shouldBe 1
            client.get("/api/v1/feed?after=${accepted.item.seq - 1}") { header(HttpHeaders.Authorization, TOKEN) }
                .body<FeedPage>().items.count { it.id == accepted.item.id } shouldBe 1
        }
    }

    test("blank input is a bad request") {
        testApplication {
            val client = setup()
            client.input("c-blank", "   ").status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("the feed pages after a sequence number and hides items waiting for the evening summary") {
        testApplication {
            val client = setup()
            val a = feed.append(agentItem("one")).item
            feed.append(agentItem("batched").copy(batchPending = true))
            val c = feed.append(agentItem("three")).item

            val all = client.get("/api/v1/feed?after=${a.seq - 1}") { header(HttpHeaders.Authorization, TOKEN) }.body<FeedPage>()
            all.items.map { it.id } shouldContainExactly listOf(a.id, c.id)
            all.hasMore shouldBe false

            val limited = client.get("/api/v1/feed?after=${a.seq - 1}&limit=1") { header(HttpHeaders.Authorization, TOKEN) }.body<FeedPage>()
            limited.items.map { it.id } shouldContainExactly listOf(a.id)
            limited.hasMore shouldBe true

            client.get("/api/v1/feed?after=x") { header(HttpHeaders.Authorization, TOKEN) }.status shouldBe HttpStatusCode.BadRequest
        }
    }

    test("the feed carries each item's stored notify decision; items without one default to in-app") {
        testApplication {
            val client = setup()
            val pushed = feed.append(agentItem("urgent").copy(notify = "push_now")).item
            val plain = feed.append(agentItem("plain")).item
            val page = client.get("/api/v1/feed?after=${pushed.seq - 1}") { header(HttpHeaders.Authorization, TOKEN) }.body<FeedPage>()
            page.items.first { it.id == pushed.id }.notify shouldBe "push_now"
            page.items.first { it.id == plain.id }.notify shouldBe "in_app"
        }
    }

    test("a feed item stored or sent without notify still decodes, as in-app") {
        val old = """{"id":"i1","seq":1,"at":"2026-10-25T10:00:00Z","author":"agent","cards":[]}"""
        ClarityJson.decodeFromString(FeedItem.serializer(), old).notify shouldBe "in_app"
    }

    /** SSE is tested against a real server: the in-memory test engine does not stream until a response completes. */
    suspend fun <T> withServer(heartbeat: kotlin.time.Duration = 20.seconds, block: suspend (HttpClient) -> T): T {
        val server = embeddedServer(Netty, port = 0) {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(ClarityJson) }
            install(Authentication) {
                bearer(AuthProviders.DEVICE) { authenticate { DevicePrincipal(DeviceId("d1"), expiresAt) } }
            }
            FeedRoutes(feed, hub, inbox, Clock.System).also { it.heartbeat = heartbeat }.install(this)
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) {
            install(SSE)
            defaultRequest { url("http://127.0.0.1:$port") }
        }
        try {
            return block(client)
        } finally {
            client.close()
            server.stop(100, 500)
        }
    }

    test("SSE replays what came after the position, then delivers live events with their names") {
        val known = feed.append(agentItem("already seen")).item
        val missed = feed.append(agentItem("missed while offline")).item
        val received = mutableListOf<Pair<String?, String?>>()
        withServer { client ->
            withTimeout(10.seconds) {
                client.sse("/api/v1/events?after=${known.seq}", request = { header(HttpHeaders.Authorization, TOKEN) }) {
                    // the first event is the replayed item; then we make two live ones happen
                    incoming.take(3).collect { event ->
                        received += event.event to event.data
                        if (received.size == 1) {
                            feed.append(agentItem("live").copy(notify = "push_now"))
                            hub.publish(FeedEvent.Busy(true))
                        }
                    }
                }
            }
        }
        received.map { it.first } shouldContainExactly listOf("item", "item", "busy")
        ClarityJson.decodeFromString(FeedEvent.serializer(), received[0].second!!)
            .shouldBeInstanceOf<FeedEvent.ItemAppended>().item.id shouldBe missed.id
        ClarityJson.decodeFromString(FeedEvent.serializer(), received[1].second!!)
            .shouldBeInstanceOf<FeedEvent.ItemAppended>().item.let {
                it.cards shouldBe listOf(Card.Text("live"))
                it.notify shouldBe "push_now"
            }
    }

    test("SSE resends a recently released hold the client already has, then a live release below its position") {
        val old = feed.append(agentItem("held during focus").copy(notify = "hold_until:2026-03-04T12:00:00Z")).item
        val seen = feed.append(agentItem("seen")).item
        store.released += old.copy(notify = "released:2026-03-04T12:00:00Z")
        val received = mutableListOf<FeedEvent>()
        try {
            withServer { client ->
                withTimeout(10.seconds) {
                    client.sse("/api/v1/events?after=${seen.seq}", request = { header(HttpHeaders.Authorization, TOKEN) }) {
                        incoming.take(2).collect { event ->
                            received += ClarityJson.decodeFromString(FeedEvent.serializer(), event.data!!)
                            if (received.size == 1) hub.publish(FeedEvent.ItemFinal(seen.copy(notify = "released:2026-03-04T12:01:00Z")))
                        }
                    }
                }
            }
        } finally {
            store.released.clear()
        }
        received.map { (it as FeedEvent.ItemFinal).item.id } shouldContainExactly listOf(old.id, seen.id)
    }

    test("SSE delivers the final version of an item the client was already sent") {
        val known = feed.append(agentItem("seen")).item
        val received = mutableListOf<Pair<String?, String?>>()
        withServer { client ->
            withTimeout(10.seconds) {
                client.sse("/api/v1/events?after=${known.seq}", request = { header(HttpHeaders.Authorization, TOKEN) }) {
                    // A streamed reply: appended while streaming, then finished with its final cards.
                    val started = feed.append(agentItem("").copy(streaming = true, notify = "push_now")).item
                    incoming.take(2).collect { event ->
                        received += event.event to event.data
                        if (received.size == 1) hub.publish(FeedEvent.ItemFinal(started.copy(cards = listOf(Card.Text("done")), streaming = false)))
                    }
                }
            }
        }
        received.map { it.first } shouldContainExactly listOf("item", "item_final")
        ClarityJson.decodeFromString(FeedEvent.serializer(), received[1].second!!)
            .shouldBeInstanceOf<FeedEvent.ItemFinal>().item.let {
                it.streaming shouldBe false
                it.notify shouldBe "push_now"
            }
    }

    test("SSE sends a heartbeat comment while idle (on the wire; clients skip comments)") {
        var line: String? = null
        withServer(heartbeat = 100.milliseconds) { client ->
            withTimeout(10.seconds) {
                client.prepareGet("/api/v1/events?after=999999") {
                    header(HttpHeaders.Authorization, TOKEN)
                    header(HttpHeaders.Accept, "text/event-stream")
                }.execute { response ->
                    val body = response.bodyAsChannel()
                    do { line = body.readUTF8Line() } while (line != null && line != ": heartbeat")
                }
            }
        }
        line shouldBe ": heartbeat"
    }

    test("a caught-up client gets the headers and an open comment at once, not at the first heartbeat") {
        var first: String? = null
        withServer(heartbeat = 1.hours) { client ->
            withTimeout(5.seconds) {
                client.prepareGet("/api/v1/events?after=999999") {
                    header(HttpHeaders.Authorization, TOKEN)
                    header(HttpHeaders.Accept, "text/event-stream")
                }.execute { response ->
                    response.status shouldBe HttpStatusCode.OK
                    first = response.bodyAsChannel().readUTF8Line()
                }
            }
        }
        first shouldBe ": open"
    }

    test("the stream is closed by the server when the device JWT expires") {
        expiresAt = Clock.System.now() + 700.milliseconds
        var closed = false
        try {
            withServer { client ->
                withTimeout(10.seconds) {
                    client.sse("/api/v1/events?after=999999", request = { header(HttpHeaders.Authorization, TOKEN) }) {
                        incoming.collect { }
                        closed = true // the flow completed on its own: the server ended the stream
                    }
                }
            }
        } finally {
            expiresAt = null
        }
        closed shouldBe true
    }
})
