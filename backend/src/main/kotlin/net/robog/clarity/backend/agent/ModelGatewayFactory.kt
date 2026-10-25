package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import net.robog.clarity.harness.AnthropicModelGateway
import net.robog.clarity.harness.ModelGateway

/** Builds the model gateway once an API key is known; a seam so runs can be tested without a network. */
fun interface ModelGatewayFactory {
    fun create(apiKey: String): ModelGateway
}

@Inject
@ContributesBinding(AppScope::class)
class AnthropicGatewayFactory : ModelGatewayFactory {
    override fun create(apiKey: String): ModelGateway = AnthropicModelGateway(apiKey)
}
