package net.robog.clarity.app.net

import io.ktor.client.HttpClient

import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.robog.clarity.app.data.SessionStore
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.feed.FeedEvent

/** Why a feed stream could not be (or stay) open. */
class FeedStreamException(val status: Int?, message: String?, cause: Throwable? = null) : Exception(message, cause)

/** One connection to `GET /api/v1/events`. Returns normally when the server closes the stream. */
interface FeedEventSource {
    /**
     * Opens the stream resuming after [after]; [onOpen] fires once the server accepted the
     * connection; [onEvent] receives each decoded event in order. Throws [FeedStreamException]
     * (or an I/O exception) when the connection fails.
     */
    suspend fun connect(after: Long, onOpen: () -> Unit, onEvent: suspend (FeedEvent) -> Unit)
}

/**
 * SSE `event:` carries the type name and `data:` the JSON. The payload normally already has the
 * `type` discriminator; if it does not, it is taken from the event name. Unknown or malformed
 * events yield null (forward compatible: a newer server may send events this app does not know).
 */
fun decodeFeedEvent(name: String?, data: String?): FeedEvent? {
    if (data.isNullOrBlank()) return null
    return try {
        val element = ClarityJson.parseToJsonElement(data)
        val withType = if (element is JsonObject && "type" !in element && !name.isNullOrBlank()) {
            JsonObject(element + ("type" to JsonPrimitive(name)))
        } else {
            element
        }
        ClarityJson.decodeFromJsonElement(FeedEvent.serializer(), withType)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

class KtorFeedEventSource(
    private val client: HttpClient,
    private val sessions: SessionStore,
) : FeedEventSource {

    override suspend fun connect(after: Long, onOpen: () -> Unit, onEvent: suspend (FeedEvent) -> Unit) {
        val session = sessions.session.first() ?: throw FeedStreamException(null, "not configured")
        try {
            // A fresh request per connection, so the device JWT is minted again.
            client.sse(
                request = {
                    url(session.instanceUrl + ApiPaths.EVENTS)
                    parameter("after", after)
                    // The stream is open indefinitely: no overall request or socket timeout.
                    infiniteTimeouts()
                },
            ) {
                onOpen()
                incoming.collect { sse -> decodeFeedEvent(sse.event, sse.data)?.let { onEvent(it) } }
            }
        } catch (e: SSEClientException) {
            throw FeedStreamException(e.response?.status?.value, e.message, e)
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.infiniteTimeouts() {
        timeout {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
    }

}
