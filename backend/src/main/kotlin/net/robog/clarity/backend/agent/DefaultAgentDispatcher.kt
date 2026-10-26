package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.harness.AnthropicModelGateway
import net.robog.clarity.harness.FinishReason
import net.robog.clarity.harness.Harness
import net.robog.clarity.harness.HarnessEvent
import net.robog.clarity.harness.HarnessJson
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.ModelGateway
import net.robog.clarity.harness.RunRequest
import net.robog.clarity.harness.ToolPolicy
import net.robog.clarity.harness.Usage

private val log = KotlinLogging.logger {}

/** Builds the model gateway once an API key is known; a seam so runs can be tested without a network. */
fun interface ModelGatewayFactory {
    fun create(apiKey: String): ModelGateway
}

@Inject
@ContributesBinding(AppScope::class)
class AnthropicGatewayFactory : ModelGatewayFactory {
    override fun create(apiKey: String): ModelGateway = AnthropicModelGateway(apiKey, net.robog.clarity.harness.httpsProxyFromEnv(System.getenv()))
}

/**
 * Runs agents in the background. [dispatch] persists a `queued` run and returns immediately; the
 * run itself executes in the application scope, at most `maxConcurrentRuns` at a time (the rest
 * wait on the semaphore, in the queued state). Without an API key it does nothing: it logs once
 * and returns null so callers (scheduler, trigger matcher) need no special-casing.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultAgentDispatcher(
    private val config: AgentConfig,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val runs: AgentRunStore,
    private val domainTools: DomainTools,
    private val mcpSources: McpToolSources,
    gatewayFactory: ModelGatewayFactory,
) : AgentDispatcher {
    private val apiKey: String? = config.anthropicApiKey?.takeIf { it.isNotBlank() }
    private val permits = Semaphore(config.maxConcurrentRuns.coerceAtLeast(1))
    private val disabledLogged = AtomicBoolean(false)
    private val harness: Harness? = apiKey?.let { Harness(gatewayFactory.create(it)) }

    override suspend fun dispatch(task: AgentTask): AgentRunId? {
        if (harness == null) {
            if (disabledLogged.compareAndSet(false, true)) {
                log.warn { "No Anthropic API key configured (clarity.agent.anthropicApiKey): agent runs are disabled" }
            }
            return null
        }
        val id = AgentRunId(Uuid.random().toString())
        runs.create(id, AgentPrompts.reason(task), AgentPrompts.describe(task), clock.now())
        scope.launch { permits.withPermit { execute(harness, id, task) } }
        return id
    }

    private suspend fun execute(harness: Harness, id: AgentRunId, task: AgentTask) {
        try {
            runs.markRunning(id)
            val request = RunRequest(
                model = config.model,
                system = AgentPrompts.SYSTEM,
                messages = listOf(Message.user(AgentPrompts.render(task, clock.now()))),
                tools = domainTools.all + mcpSources.tools(),
                policy = ToolPolicy.Default,
                maxTurns = config.maxTurns,
                effort = config.effort,
            )
            var terminal: HarnessEvent.Terminal? = null
            harness.run(request).collect { event ->
                log.debug { "run ${id.value}: $event" }
                if (event is HarnessEvent.Terminal) terminal = event
                if (event is HarnessEvent.ApprovalRequired) log.info { "run ${id.value}: approval pending for ${event.name}" }
            }
            when (val end = terminal) {
                is HarnessEvent.Finished -> finish(id, end.reason.status(), end.transcript, end.usage, null)
                is HarnessEvent.Failed -> finish(id, RunStatus.FAILED, end.transcript, end.usage, end.message)
                null -> finish(id, RunStatus.FAILED, null, null, "run ended without a result")
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { finish(id, RunStatus.CANCELLED, null, null, "cancelled (shutdown)") }
            throw e
        } catch (e: Exception) {
            log.error(e) { "agent run ${id.value} crashed" }
            finish(id, RunStatus.FAILED, null, null, e.message ?: e::class.simpleName)
        }
    }

    private suspend fun finish(id: AgentRunId, status: String, transcript: List<Message>?, usage: Usage?, error: String?) {
        log.info { "agent run ${id.value} -> $status${usage?.let { " (in=${it.inputTokens} out=${it.outputTokens} cacheRead=${it.cacheReadInputTokens})" } ?: ""}" }
        runs.finish(
            id = id,
            status = status,
            transcript = transcript?.let { HarnessJson.encodeToJsonElement(ListSerializer(Message.serializer()), it) },
            usage = usage?.let { HarnessJson.encodeToJsonElement(Usage.serializer(), it) },
            error = error,
            finishedAt = clock.now(),
        )
    }

    private fun FinishReason.status(): String = name.lowercase()
}

