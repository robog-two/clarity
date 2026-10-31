package net.robog.clarity.backend.triggers

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.InboxStore
import net.robog.clarity.backend.agent.SettingsProvider
import net.robog.clarity.backend.agent.homeZone
import net.robog.clarity.backend.agent.localizeTimes
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.ingest.IngestEventBus
import net.robog.clarity.backend.ingest.Ingested
import net.robog.clarity.backend.lifecycle.BackgroundService
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Payload

private val log = KotlinLogging.logger {}

/**
 * Subscribes to ingest events and puts a [InboxItem.TriggerFired] in the lead's inbox for every enabled trigger whose query matches.
 * Only *newly created* entities fire triggers: a second connector merging into an existing
 * entity is the same fact, not a new arrival. Neither is history a connector imports (a first
 * import, a mail backfill): those records are stored but fire nothing. Nor do messages sent from
 * the person's own account, and one trigger wakes the lead at most
 * [AgentConfig.triggerFiresPerHour] times an hour: a trigger can never feed itself.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class TriggerMatcher(
    private val bus: IngestEventBus,
    private val triggers: TriggerStore,
    private val inbox: InboxStore,
    private val settings: SettingsProvider = SettingsProvider { net.robog.clarity.shared.session.Settings() },
    private val config: AgentConfig = AgentConfig(reviewInterval = 2.hours),
    private val clock: Clock = Clock.System,
) : BackgroundService {
    override val name = "trigger-matcher"

    override suspend fun run() {
        bus.events.collect { event ->
            if (event.created && !event.history) {
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
        // The person's own outgoing mail and texts (what the agent sends goes out from their account):
        // a reply the agent wrote must not wake the trigger that made it write.
        if (ownOutgoing(event.payload)) return
        val json = event.payload.toJsonElement()
        triggers.enabledFor(event.payload.noun)
            .filter { it.spec.query.matches(json) }
            .forEach { trigger ->
                val recent = inbox.countKeyed(triggerKeyPrefix(trigger.id), clock.now() - 1.hours)
                if (recent >= config.triggerFiresPerHour) {
                    log.warn { "trigger '${trigger.spec.name}' matched ${event.entityId} but already fired $recent times this hour; not waking the lead" }
                    return@forEach
                }
                log.info { "trigger '${trigger.spec.name}' matched ${event.entityId}" }
                inbox.enqueue(
                    InboxItem.TriggerFired(
                        triggerId = trigger.id,
                        triggerName = trigger.spec.name,
                        instruction = trigger.spec.instruction,
                        // Matched on the stored record; shown to the lead with its times on the person's clock.
                        record = localizeTimes(json, settings.homeZone()).toString(),
                        recordSource = "${event.payload.noun.name.lowercase()} from connector ${event.connectorId}",
                    ),
                    dedupKey = triggerKeyPrefix(trigger.id) + event.entityId.value,
                )
            }
    }
}

/** The dedup key prefix of [triggerId]'s inbox items (`trigger:<id>:<entity>`). */
fun triggerKeyPrefix(triggerId: String): String = "trigger:$triggerId:"

/** Pure: whether [payload] is a message the person's own account sent (the agent sends as them). */
fun ownOutgoing(payload: Payload): Boolean =
    payload is MessagePayload && payload.sender.kind == payload.account.kind && payload.sender.value.trim().equals(payload.account.value.trim(), ignoreCase = true)
