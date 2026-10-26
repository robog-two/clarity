package net.robog.clarity.backend.agent

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import net.robog.clarity.harness.httpsProxyFromEnv
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.harness.AnthropicModelGateway
import net.robog.clarity.harness.ModelGateway

/** Builds the model gateway once an API key is known; a seam so runs can be tested without a network. */
fun interface ModelGatewayFactory {
    fun create(apiKey: String): ModelGateway
}

@Inject
@ContributesBinding(AppScope::class)
class AnthropicGatewayFactory(private val config: AgentConfig) : ModelGatewayFactory {
    override fun create(apiKey: String): ModelGateway = AnthropicModelGateway(anthropicClientBuilder(apiKey, config).build())
}

/**
 * The one place Anthropic SDK clients are configured: API key, optional base URL (tests), and the
 * egress proxy (`CLARITY_HTTPS_PROXY`), which in a deployment is the backend's only route out.
 */
fun anthropicClientBuilder(apiKey: String, config: AgentConfig, env: Map<String, String> = System.getenv()): AnthropicOkHttpClient.Builder {
    // anthropic-java 3.3.0 turns apiKey(...) into `Authorization: Bearer`, which the API reserves for
    // OAuth tokens. It adds no auth header of its own when X-Api-Key is already set, so set it here.
    // Pinned by AnthropicClientHeadersTest.
    val builder = AnthropicOkHttpClient.builder().apiKey(apiKey).putHeader("X-Api-Key", apiKey)
    config.anthropicBaseUrl?.let { builder.baseUrl(it) }
    httpsProxyFromEnv(env)?.let { builder.proxy(it) }
    return builder
}
