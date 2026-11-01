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
import kotlinx.coroutines.test.runTest
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.testutil.FakeDeviceKey
import net.robog.clarity.app.testutil.FakeSessionStore
import net.robog.clarity.app.testutil.signedIn
import net.robog.clarity.shared.mcp.McpServerState
import net.robog.clarity.shared.mcp.McpToolMode
import net.robog.clarity.shared.mcp.McpToolPolicyUpdate

private val JSON = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

class McpApiTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): KtorClarityApi {
        val store = FakeSessionStore(signedIn())
        val engine = MockEngine { request -> requests += request; handler(request) }
        return KtorClarityApi(createHttpClient(DeviceJwtSigner(FakeDeviceKey()), store, engine), store)
    }

    @Test fun serversAreRead() = runTest {
        val body = """{"servers":[{"id":"gh1","label":"GitHub","state":"running","tools":[
            {"name":"list_issues","readOnly":true,"mode":"auto"},{"name":"create_issue","destructive":false,"mode":"ask"}]}]}"""
        val servers = (api { respond(body, HttpStatusCode.OK, JSON) }.mcpServers() as ApiResult.Success).value.servers
        assertEquals("/api/v1/mcp/servers", requests.single().url.encodedPath)
        assertEquals(McpServerState.RUNNING, servers.single().state)
        assertEquals(listOf(McpToolMode.AUTO, McpToolMode.ASK), servers.single().tools.map { it.mode })
    }

    @Test fun aToolPolicyIsPutOnItsPath() = runTest {
        val api = api { respond("", HttpStatusCode.NoContent) }
        api.putMcpToolPolicy("gh1", "create_issue", McpToolPolicyUpdate(McpToolMode.AUTO))
        api.putMcpToolPolicy("gh1", "create_issue", McpToolPolicyUpdate(null))
        val (set, reset) = requests
        assertEquals(HttpMethod.Put, set.method)
        assertEquals("/api/v1/mcp/servers/gh1/tools/create_issue", set.url.encodedPath)
        assertEquals("""{"mode":"auto"}""", String(set.body.toByteArray()))
        assertEquals("{}", String(reset.body.toByteArray()), "a reset leaves mode out, which reads as null")
    }

    @Test fun suggestionsAreListedAndDismissed() = runTest {
        val api = api { req ->
            if (req.method == HttpMethod.Get) {
                respond("""{"suggestions":[{"id":"s1","label":"Canvas","why":"Your classes","createdAt":"2026-10-29T08:00:00Z"}]}""", HttpStatusCode.OK, JSON)
            } else {
                respond("", HttpStatusCode.NoContent)
            }
        }
        assertEquals("Canvas", (api.mcpSuggestions() as ApiResult.Success).value.suggestions.single().label)
        assertIs<ApiResult.Success<Unit>>(api.dismissMcpSuggestion("s1"))
        assertEquals(HttpMethod.Delete, requests.last().method)
        assertEquals("/api/v1/mcp/suggestions/s1", requests.last().url.encodedPath)
    }

    @Test fun aServerWithoutTheRoutesSaysNotFound() = runTest {
        assertIs<ApiError.NotFound>((api { respond("", HttpStatusCode.NotFound) }.mcpServers() as ApiResult.Failure).error)
    }
}
