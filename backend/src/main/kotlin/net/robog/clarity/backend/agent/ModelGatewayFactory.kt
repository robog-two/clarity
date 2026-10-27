package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.harness.ModelGateway
import net.robog.clarity.harness.OpenRouterModelGateway
import net.robog.clarity.harness.httpsProxyFromEnv

/** Builds the model gateway once the OpenRouter key is known; a seam so runs can be tested without a network. */
fun interface ModelGatewayFactory {
    fun create(apiKey: String): ModelGateway
}

@Inject
@ContributesBinding(AppScope::class)
class OpenRouterGatewayFactory(private val config: AgentConfig) : ModelGatewayFactory {
    override fun create(apiKey: String): ModelGateway = openRouterGateway(apiKey, config)
}

/** The OpenRouter gateway with the configured routing, through the egress proxy (`CLARITY_HTTPS_PROXY`). */
fun openRouterGateway(apiKey: String, config: AgentConfig, env: Map<String, String> = System.getenv()): OpenRouterModelGateway =
    OpenRouterModelGateway(apiKey, config.openRouterBaseUrl, httpsProxyFromEnv(env), routing = config.openRouterRouting)
