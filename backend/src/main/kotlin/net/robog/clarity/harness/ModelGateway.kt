package net.robog.clarity.harness

/**
 * The seam between the loop and a model provider. One call: a request in, the finished assistant
 * turn out; text deltas are reported on the side for live UIs. Implementations stream internally
 * (long outputs must not hit HTTP timeouts) but callers only ever see the accumulated result.
 *
 * Implementations throw on transport/API failure and must honour coroutine cancellation.
 */
interface ModelGateway {
    suspend fun generate(request: ModelRequest, onTextDelta: (String) -> Unit = {}): ModelResponse
}
