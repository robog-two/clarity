package net.robog.clarity.backend.triggers

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException
import net.robog.clarity.backend.agent.AgentDispatcher
import net.robog.clarity.backend.agent.AgentTask
import net.robog.clarity.backend.ingest.IngestEventBus
import net.robog.clarity.backend.ingest.Ingested
import net.robog.clarity.backend.lifecycle.BackgroundService

private val log = KotlinLogging.logger {}

/**
 * Subscribes to ingest events and fires the agent for every enabled trigger whose query matches.
 * Only *newly created* entities fire triggers: a second connector merging into an existing
 * entity is the same fact, not a new arrival.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class TriggerMatcher(
    private val bus: IngestEventBus,
    private val triggers: TriggerStore,
    private val dispatcher: AgentDispatcher,
) : BackgroundService {
    override val name = "trigger-matcher"

    override suspend fun run() {
        bus.events.collect { event ->
            if (event.created) {
                try {
                    handle(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error(e) { "trigger evaluation failed for ${event.entityId}" } // one bad event must not stop matching
                }
            }
        }
    }

    internal suspend fun handle(event: Ingested) {
        val json = event.payload.toJsonElement()
        triggers.enabledFor(event.payload.noun)
            .filter { it.spec.query.matches(json) }
            .forEach { trigger ->
                log.info { "trigger '${trigger.spec.name}' matched ${event.entityId}" }
                dispatcher.dispatch(AgentTask.TriggerFired(trigger.id, trigger.spec.instruction, event))
            }
    }
}
