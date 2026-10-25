package net.robog.clarity.harness

import kotlinx.serialization.json.JsonElement

/**
 * The seam between the loop and a model provider. One call: a request in, the finished assistant
 * turn out; text deltas are reported on the side for live UIs. Implementations stream internally
 * (long outputs must not hit HTTP timeouts) but callers only ever see the accumulated result.
 *
 * Implementations throw on transport/API failure and must honour coroutine cancellation.
 */
interface ModelGateway {
    suspend fun generate(request: ModelRequest, onTextDelta: (String) -> Unit = {}): ModelResponse

    /**
     * A one-shot call (guard, triage, summaries): no tools, no loop. Set
     * [ModelRequest.structuredOutput] to constrain the text to a JSON schema; see [completeJson].
     */
    suspend fun complete(request: ModelRequest): ModelResponse = generate(request)
}

/** One-shot structured call: runs [ModelGateway.complete] and parses the text as JSON. */
suspend fun ModelGateway.completeJson(request: ModelRequest): JsonElement {
    require(request.structuredOutput != null) { "completeJson needs a structuredOutput schema" }
    return HarnessJson.parseToJsonElement(complete(request).text)
}
