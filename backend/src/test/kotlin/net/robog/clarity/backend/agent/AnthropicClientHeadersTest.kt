package net.robog.clarity.backend.agent

import com.anthropic.models.models.ModelListParams
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import net.robog.clarity.backend.config.AgentConfig

/** Pins how the SDK authenticates: the Anthropic API takes API keys in `x-api-key`. */
class AnthropicClientHeadersTest : FunSpec({
    test("the configured client sends the API key as x-api-key") {
        val seen = AtomicReference<Map<String, List<String>>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            seen.set(ex.requestHeaders.mapKeys { it.key.lowercase() })
            val body = """{"data":[],"has_more":false,"first_id":null,"last_id":null}""".toByteArray()
            ex.responseHeaders.add("content-type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val config = AgentConfig(anthropicApiKey = null, reviewInterval = kotlin.time.Duration.parse("2h"), anthropicBaseUrl = "http://127.0.0.1:${server.address.port}")
            val client = anthropicClientBuilder("sk-ant-test-key", config, env = emptyMap()).maxRetries(0).build()
            client.models().list(ModelListParams.builder().limit(1).build())
            val headers = seen.get()
            println("auth headers: x-api-key=${headers["x-api-key"]} authorization=${headers["authorization"]}")
            headers["x-api-key"] shouldBe listOf("sk-ant-test-key")
            headers["authorization"] shouldBe null
        } finally {
            server.stop(0)
        }
    }
})
