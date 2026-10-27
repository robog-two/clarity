package net.robog.clarity.backend.vault

import dev.zacsweers.metro.createGraphFactory
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import net.robog.clarity.backend.auth.TestDevice
import net.robog.clarity.backend.clarity
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.AppConfig
import net.robog.clarity.backend.config.ConnectorsConfig
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.EnrollmentConfig
import net.robog.clarity.backend.config.EnrollmentMode
import net.robog.clarity.backend.config.HttpConfig
import net.robog.clarity.backend.config.InstanceConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.di.AppGraph
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.api.EnrollmentRequest
import net.robog.clarity.shared.api.EnrollmentResponse
import net.robog.clarity.shared.vault.CredentialMeta
import net.robog.clarity.shared.vault.DeviceVaultKey
import net.robog.clarity.shared.vault.RegisterVaultKey
import org.testcontainers.postgresql.PostgreSQLContainer

/** The real application graph with V7 on top of V1-V5: the vault routes are wired and sit behind device authentication. */
@EnabledIf(ContainerRuntimeAvailable::class)
class VaultWiringTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var config: AppConfig

    beforeSpec {
        postgres.start()
        val dir = Files.createTempDirectory("clarity-vault")
        config = AppConfig(
            instance = InstanceConfig("Test Clarity"),
            http = HttpConfig(0),
            database = DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4),
            dataDir = dir.resolve("data"),
            enrollment = EnrollmentConfig(EnrollmentMode.TOFU, token = null),
            connectors = ConnectorsConfig(autoGrant = true, secretsDir = dir.resolve("secrets")),
            agent = AgentConfig(reviewInterval = 2.hours),
        )
    }
    afterSpec { postgres.stop() }

    test("vault routes require a device and work for an enrolled one") {
        val graph = createGraphFactory<AppGraph.Factory>().create(config)
        testApplication {
            application { clarity(graph) }
            val client = createClient { install(ContentNegotiation) { json(ClarityJson) } }
            for (path in listOf("/api/v1/vault/credentials", "/api/v1/vault/devices", "/api/v1/vault/requests/x")) {
                client.get(path).status shouldBe HttpStatusCode.Unauthorized
            }
            client.post("/api/v1/vault/grants/x").status shouldBe HttpStatusCode.Unauthorized

            val device = TestDevice()
            val enrolled = client.post("/api/v1/enroll") {
                contentType(ContentType.Application.Json)
                setBody(EnrollmentRequest(device.spkiBase64Url, "Pixel"))
            }.body<EnrollmentResponse>()
            val jwt = device.withId(enrolled.deviceId.value).token(Clock.System.now())
            fun io.ktor.client.request.HttpRequestBuilder.asDevice() = header(HttpHeaders.Authorization, "Bearer $jwt")

            client.get("/api/v1/vault/credentials") { asDevice() }.body<List<CredentialMeta>>().shouldBeEmpty()
            val key = VaultCryptoReference.publicKeyWire(VaultCryptoReference.newDeviceKey())
            client.post("/api/v1/vault/devices") {
                asDevice(); contentType(ContentType.Application.Json); setBody(RegisterVaultKey(key))
            }.status shouldBe HttpStatusCode.Created
            client.get("/api/v1/vault/devices") { asDevice() }.body<List<DeviceVaultKey>>().single().publicKey shouldBe key
            client.get("/api/v1/vault/requests/${java.util.UUID.randomUUID()}") { asDevice() }.status shouldBe HttpStatusCode.NotFound
        }
    }
})
