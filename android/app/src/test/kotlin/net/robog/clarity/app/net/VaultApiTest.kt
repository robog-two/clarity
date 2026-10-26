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
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.testutil.FakeDeviceKey
import net.robog.clarity.app.testutil.FakeSessionStore
import net.robog.clarity.app.testutil.signedIn
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.SignedDecision
import net.robog.clarity.shared.vault.CredentialId
import net.robog.clarity.shared.vault.RegisterVaultKey
import net.robog.clarity.shared.vault.ReleaseCode
import net.robog.clarity.shared.vault.ReleaseGrant

private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

class VaultApiTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): KtorClarityApi {
        val store = FakeSessionStore(signedIn())
        val engine = MockEngine { request -> requests += request; handler(request) }
        return KtorClarityApi(createHttpClient(DeviceJwtSigner(FakeDeviceKey()), store, engine), store)
    }

    private suspend fun HttpRequestData.json() = String(body.toByteArray())

    @Test fun signedDecisionPostsToItsOwnRoute() = runTest {
        val api = api { respond("{}", HttpStatusCode.OK, JSON_HEADERS) }
        assertIs<ApiResult.Success<Unit>>(api.signedDecision(HeldActionId("h1"), SignedDecision(SignedDecision.APPROVE, "sig")))
        val r = requests.single()
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("/api/v1/held/h1/signed-decision", r.url.encodedPath)
        assertEquals(SignedDecision(SignedDecision.APPROVE, "sig"), ClarityJson.decodeFromString(SignedDecision.serializer(), r.json()))
        assertTrue(r.headers[HttpHeaders.Authorization]!!.startsWith("Bearer "))
    }

    @Test fun releasesAndKeyRegistrationUseTheVaultRoutes() = runTest {
        val api = api { respond("""{"expiresAt":"2026-10-26T09:05:00Z","site":"example.com"}""", HttpStatusCode.OK, JSON_HEADERS) }
        api.releaseGrant(HeldActionId("h1"), ReleaseGrant(mapOf("password" to "x"), 300, "sig"))
        api.releaseCode(HeldActionId("h2"), ReleaseCode("123456", 300, "sig"))
        assertEquals(listOf("/api/v1/vault/grants/h1", "/api/v1/vault/codes/h2"), requests.map { it.url.encodedPath })
        assertEquals("sig", ClarityJson.decodeFromString(ReleaseGrant.serializer(), requests[0].json()).signature)
    }

    @Test fun registrationCarriesTheApprovalKey() = runTest {
        val api = api { respond("""{"deviceId":"d1","publicKey":"pk"}""", HttpStatusCode.Created, JSON_HEADERS) }
        api.registerVaultKeys(RegisterVaultKey("pk", approvalKey = "ak"))
        val r = requests.single()
        assertEquals("/api/v1/vault/devices", r.url.encodedPath)
        assertEquals("ak", ClarityJson.decodeFromString(RegisterVaultKey.serializer(), r.json()).approvalKey)
    }

    @Test fun deleteAndEnvelopeRoutes() = runTest {
        val api = api { respond("", HttpStatusCode.NoContent) }
        api.deleteCredential(CredentialId("c1"))
        api.envelope(CredentialId("c1"))
        assertEquals(HttpMethod.Delete, requests[0].method)
        assertEquals("/api/v1/vault/credentials/c1", requests[0].url.encodedPath)
        assertEquals("/api/v1/vault/credentials/c1/envelope", requests[1].url.encodedPath)
    }
}
