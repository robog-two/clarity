package net.robog.clarity.backend.triggers

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

private val FLUSH_EVERY = 1.minutes

/**
 * Subscribes to ingest events and puts a [InboxItem.TriggerFired] in the lead's inbox for every enabled trigger whose query matches.
 * Only *newly created* entities fire triggers: a second connector merging into an existing
 * entity is the same fact, not a new arrival. Neither is history a connector imports (a first
 * import, a mail backfill): those records are stored but fire nothing. Nor do messages sent from
 * the person's own account, and one trigger wakes the lead at most
 * [AgentConfig.triggerFiresPerHour] times an hour: a trigger can never feed itself. Matches past
 * that limit are held and wake the lead once together when the hour has room, so none is lost.
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

    /** Matches held over the limit, by trigger id. In memory: after a restart the review pass still finds the records. */
    private val held = ConcurrentHashMap<String, HeldMatches>()

    override suspend fun run() = coroutineScope {
        launch { flushLoop() }
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
                // Matched on the stored record; shown to the lead with its times on the person's clock.
                val record = localizeTimes(json, settings.homeZone()).toString()
                if (atLimit(trigger)) {
                    log.info { "trigger '${trigger.spec.name}' matched ${event.entityId} over its hourly limit; held for one later wake" }
                    held.getOrPut(trigger.id) { HeldMatches(trigger) }.add(record)
                    return@forEach
                }
                log.info { "trigger '${trigger.spec.name}' matched ${event.entityId}" }
                inbox.enqueue(
                    InboxItem.TriggerFired(
                        triggerId = trigger.id,
                        triggerName = trigger.spec.name,
                        instruction = trigger.spec.instruction,
                        record = record,
                        recordSource = "${event.payload.noun.name.lowercase()} from connector ${event.connectorId}",
                    ),
                    dedupKey = triggerKeyPrefix(trigger.id) + event.entityId.value,
                )
            }
    }

    private suspend fun atLimit(trigger: StoredTrigger): Boolean =
        inbox.countKeyed(triggerKeyPrefix(trigger.id), clock.now() - 1.hours) >= config.triggerFiresPerHour

    /** Wakes the lead once per trigger for what it held, as soon as that trigger's hour has room. */
    internal suspend fun flushHeld() {
        for (matches in held.values.toList()) {
            val trigger = matches.trigger
            if (atLimit(trigger)) continue
            val batch = matches.take() ?: continue
            inbox.enqueue(
                InboxItem.TriggerFired(
                    triggerId = trigger.id,
                    triggerName = trigger.spec.name,
                    instruction = trigger.spec.instruction,
                    record = batch.records.joinToString(",", "[", "]") +
                        if (batch.omitted > 0) " (${batch.omitted} more matched, not shown)" else "",
                    recordSource = "${batch.count} ${trigger.spec.noun.name.lowercase()} records that matched while the hourly limit was reached",
                ),
                dedupKey = triggerKeyPrefix(trigger.id) + "held:" + clock.now().toEpochMilliseconds(),
            )
        }
    }

    private suspend fun flushLoop() {
        while (true) {
            delay(FLUSH_EVERY)
            try {
                flushHeld()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "flushing held trigger matches failed" }
            }
        }
    }
}

/** What one trigger matched past its limit: the first records up to a cap, and how many did not fit. */
internal class HeldMatches(val trigger: StoredTrigger) {
    class Batch(val records: List<String>, val omitted: Int) {
        val count get() = records.size + omitted
    }

    private val lock = Any()
    private val records = ArrayList<String>()
    private var omitted = 0

    fun add(record: String) = synchronized(lock) {
        if (records.size < MAX_HELD) records += record else omitted++
    }

    /** Empties the buffer into one batch, or null when it holds nothing. */
    fun take(): Batch? = synchronized(lock) {
        if (records.isEmpty()) return null
        Batch(records.toList(), omitted).also { records.clear(); omitted = 0 }
    }

    private companion object {
        const val MAX_HELD = 25
    }
}

/** The dedup key prefix of [triggerId]'s inbox items (`trigger:<id>:<entity>`). */
fun triggerKeyPrefix(triggerId: String): String = "trigger:$triggerId:"

/** Pure: whether [payload] is a message the person's own account sent (the agent sends as them). */
fun ownOutgoing(payload: Payload): Boolean =
    payload is MessagePayload && payload.sender.kind == payload.account.kind && payload.sender.value.trim().equals(payload.account.value.trim(), ignoreCase = true)
