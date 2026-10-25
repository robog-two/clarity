package net.robog.clarity.app.testutil

import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.Session
import net.robog.clarity.app.data.SessionStore
import net.robog.clarity.app.data.feed.FeedStore
import net.robog.clarity.app.identity.DeviceKey
import net.robog.clarity.app.net.FeedBackend
import net.robog.clarity.app.net.FeedEventSource
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedEvent
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.FeedItemId
import net.robog.clarity.shared.feed.FeedPage
import net.robog.clarity.shared.governance.HeldAction

val T0: Instant = Instant.parse("2026-10-26T09:00:00Z")

fun agentItem(seq: Long, text: String, sessionId: net.robog.clarity.shared.session.SessionId? = null, streaming: Boolean = false) =
    FeedItem(FeedItemId("i$seq"), seq, T0, FeedItem.Author.AGENT, sessionId, listOf(Card.Text(text)), streaming)

fun userItem(seq: Long, text: String, sessionId: net.robog.clarity.shared.session.SessionId? = null) =
    FeedItem(FeedItemId("i$seq"), seq, T0, FeedItem.Author.USER, sessionId, listOf(Card.Said(text)))

class InMemoryFeedStore : FeedStore {
    private val itemsState = MutableStateFlow<Map<Long, FeedItem>>(emptyMap())
    private val tasksState = MutableStateFlow<Map<String, AgentTask>>(emptyMap())
    private val heldState = MutableStateFlow<List<HeldAction>>(emptyList())

    override val items: Flow<List<FeedItem>> = itemsState.map { it.values.sortedBy(FeedItem::seq) }
    override val tasks: Flow<List<AgentTask>> = tasksState.map { it.values.toList() }
    override val held: Flow<List<HeldAction>> = heldState

    val snapshot: List<FeedItem> get() = itemsState.value.values.sortedBy { it.seq }
    val writes = mutableListOf<Long>()

    override suspend fun lastSeq(): Long = itemsState.value.keys.maxOrNull() ?: 0L
    override suspend fun item(id: FeedItemId): FeedItem? = itemsState.value.values.firstOrNull { it.id == id }
    override suspend fun itemAt(seq: Long): FeedItem? = itemsState.value[seq]
    override suspend fun upsertItem(item: FeedItem) {
        writes += item.seq
        itemsState.value = itemsState.value + (item.seq to item)
    }
    override suspend fun upsertTask(task: AgentTask) { tasksState.value = tasksState.value + (task.id.value to task) }
    override suspend fun replaceHeld(held: List<HeldAction>) { heldState.value = held }
    override suspend fun clear() {
        itemsState.value = emptyMap(); tasksState.value = emptyMap(); heldState.value = emptyList()
    }
}

/** Serves [pages] by `after`: returns the first page whose items all come after it. */
class FakeBackend(var all: List<FeedItem> = emptyList(), private val pageSize: Int = 2) : FeedBackend {
    val feedCalls = mutableListOf<Long>()
    var failure: ApiError? = null
    var held: List<HeldAction> = emptyList()

    override suspend fun feed(after: Long): ApiResult<FeedPage> {
        feedCalls += after
        failure?.let { return ApiResult.Failure(it) }
        val rest = all.filter { it.seq > after }.sortedBy { it.seq }
        return ApiResult.Success(FeedPage(rest.take(pageSize), hasMore = rest.size > pageSize))
    }

    override suspend fun held(): ApiResult<List<HeldAction>> = ApiResult.Success(held)
}

/** Each [connect] plays the next script: a list of events, then either ends or throws. */
class FakeEventSource(private val scripts: List<Script>) : FeedEventSource {
    class Script(val events: List<FeedEvent> = emptyList(), val failWith: Throwable? = null, val open: Boolean = true)

    val connectedAfter = mutableListOf<Long>()
    private var next = 0
    val done: Boolean get() = next >= scripts.size

    override suspend fun connect(after: Long, onOpen: () -> Unit, onEvent: suspend (FeedEvent) -> Unit) {
        connectedAfter += after
        val script = scripts.getOrNull(next++) ?: throw CancellationException("script exhausted")
        if (script.open) onOpen()
        script.events.forEach { onEvent(it) }
        script.failWith?.let { throw it }
        if (next >= scripts.size) throw CancellationException("script exhausted")
    }

    class CancellationException(message: String) : kotlin.coroutines.cancellation.CancellationException(message)
}

class FakeSessionStore(initial: Session? = null) : SessionStore {
    val state = MutableStateFlow(initial)
    override val session: Flow<Session?> = state
    override suspend fun save(session: Session) { state.value = session }
    override suspend fun clear() { state.value = null }
}

fun signedIn(url: String = "https://clarity.example") = Session(url, DeviceId("dev-1"))

class FakeDeviceKey : DeviceKey {
    override fun publicKeySpki(): String = "pk"
    override fun signJose(data: ByteArray): ByteArray = ByteArray(64)
    override fun delete() = Unit
}

class SignalSource {
    private val flow = MutableSharedFlow<net.robog.clarity.app.data.feed.SyncSignal>(extraBufferCapacity = 8)
    val signals: SharedFlow<net.robog.clarity.app.data.feed.SyncSignal> = flow.asSharedFlow()
    fun emit(s: net.robog.clarity.app.data.feed.SyncSignal) = flow.tryEmit(s)
}
