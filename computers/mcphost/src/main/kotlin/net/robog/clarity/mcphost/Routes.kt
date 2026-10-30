package net.robog.clarity.mcphost

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.writeFully
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.shared.ClarityJson

private val log = KotlinLogging.logger {}

/** Largest request body accepted from the backend. */
const val MAX_REQUEST_BYTES = 4L * 1024 * 1024

/** Bearer check in constant time: both sides are hashed first, so neither length nor content leaks. */
fun bearerMatches(header: String?, key: String): Boolean {
    val presented = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim() ?: return false
    val sha = { s: String -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()) }
    return MessageDigest.isEqual(sha(presented), sha(key))
}

private fun bearerAuth(key: String) = createRouteScopedPlugin("McpHostBearer") {
    onCall { call ->
        if (!bearerMatches(call.request.headers[HttpHeaders.Authorization], key)) {
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
            call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
        }
    }
}

/**
 * `GET /healthz` (no auth), `GET /servers` and `/s/{id}/mcp` (bearer [key]). Stdio servers are
 * served statelessly: POST only, JSON responses, GET and DELETE answer 405. Remote servers get
 * every method passed through, including their SSE streams and session ids.
 */
fun Application.hostModule(supervisor: Supervisor, key: String) {
    routing {
        get("/healthz") { call.respondText("ok") }
        route("/servers") {
            install(bearerAuth(key))
            get {
                val body = ClarityJson.encodeToString(ListSerializer(HostServer.serializer()), supervisor.servers())
                call.respondText(body, ContentType.Application.Json)
            }
        }
        route("/s/{id}/mcp") {
            install(bearerAuth(key))
            handle { mcp(call, supervisor, call.parameters["id"].orEmpty()) }
        }
    }
}

private suspend fun mcp(call: ApplicationCall, supervisor: Supervisor, id: String) {
    if (!ID_PATTERN.matches(id)) return call.refuse(404, "no server $id")
    val method = call.request.httpMethod.value
    val body = if (method == "POST") {
        val bytes = call.receiveChannel().readBuffer(MAX_REQUEST_BYTES + 1).readByteArray()
        if (bytes.size > MAX_REQUEST_BYTES) return call.refuse(413, "request too large")
        bytes
    } else null

    val remote = try {
        supervisor.remote(id)
    } catch (e: Unavailable) {
        if (e.status != 405) return call.refuse(e.status, e.message!!)
        null
    }
    if (remote != null) return forwardRemote(call, supervisor, id, remote, method, body)

    if (method != "POST") {
        call.response.header(HttpHeaders.Allow, "POST")
        return call.refuse(405, "this server takes POST only")
    }
    val json: JsonElement = runCatching { ClarityJson.parseToJsonElement(body!!.toString(Charsets.UTF_8)) }.getOrNull()
        ?: return call.respondText(
            ClarityJson.encodeToString(JsonElement.serializer(), rpcError(null, -32700, "parse error")),
            ContentType.Application.Json, HttpStatusCode.BadRequest,
        )
    when (val outcome = supervisor.handle(id, json)) {
        is McpOutcome.Reply -> call.respondText(ClarityJson.encodeToString(JsonElement.serializer(), outcome.body), ContentType.Application.Json)
        McpOutcome.Accepted -> call.respondText("", status = HttpStatusCode.Accepted)
        is McpOutcome.Refused -> call.refuse(outcome.status, outcome.message)
    }
}

private suspend fun forwardRemote(call: ApplicationCall, supervisor: Supervisor, id: String, remote: RemoteServer, method: String, body: ByteArray?) {
    if (method !in setOf("POST", "GET", "DELETE")) return call.refuse(405, "method not allowed")
    val headers = FORWARDED_REQUEST_HEADERS.mapNotNull { name -> call.request.headers[name]?.let { name to it } }.toMap()
    val response = try {
        remote.forward(method, headers, body)
    } catch (e: Exception) {
        val message = "could not reach ${remote.spec.label}: ${Redactor(remote.spec.secretValues).clean(e.message ?: e.javaClass.simpleName, 200)}"
        supervisor.remoteFailed(id, message)
        return call.refuse(502, message)
    }
    for (name in FORWARDED_RESPONSE_HEADERS) {
        if (name.equals("Content-Type", ignoreCase = true)) continue
        response.headers().firstValue(name).ifPresent { call.response.header(name, it) }
    }
    val type = response.headers().firstValue("Content-Type").map { runCatching { ContentType.parse(it) }.getOrNull() }.orElse(null)
    call.respondBytesWriter(type, HttpStatusCode.fromValue(response.statusCode())) {
        withContext(Dispatchers.IO) {
            response.body().use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    writeFully(buf, 0, n)
                    flush() // server-sent events reach the backend as they come
                }
            }
        }
    }
}

private suspend fun ApplicationCall.refuse(status: Int, message: String) {
    if (status >= 500) log.info { "refused ${request.httpMethod.value} ${request.local.uri}: $status $message" }
    respondText(
        ClarityJson.encodeToString(JsonElement.serializer(), buildJsonObject { put("error", message) }),
        ContentType.Application.Json, HttpStatusCode.fromValue(status),
    )
}
