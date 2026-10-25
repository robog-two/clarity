package net.robog.clarity.connector.example

import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import io.github.oshai.kotlinlogging.KotlinLogging
import net.robog.clarity.connector.sdk.StateStore
import net.robog.clarity.connector.sdk.SyncScope
import net.robog.clarity.connector.sdk.actionHandlers
import net.robog.clarity.connector.sdk.getAs
import net.robog.clarity.connector.sdk.putAs
import net.robog.clarity.connector.sdk.runConnector
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Noun

/*
 * TEMPLATE: copy this module to create a real connector (see connectors/README.md).
 *
 * A connector is three things:
 *  1. a [manifest]: who you are, which nouns you produce, a JSON Schema for your settings form;
 *  2. a pure function from (config, source data) to [IngestRecord]s; here [sampleRecords];
 *  3. a sync block that reads config, talks to the source, pushes, and stores a cursor;
 *  4. optionally, handlers for the actions the agent may ask this connector to execute
 *     (declared in `manifest.handles`, registered with `actionHandlers { on<T> { ... } }`).
 *
 * Everything else (registration, config polling, retries, batching, shutdown) is the SDK's job.
 */

/** What this connector announces on startup. The user must grant [Noun.MESSAGE] in the app before data is accepted. */
val manifest = ConnectorManifest(
    kind = "example",
    displayName = "Example connector",
    version = "0.1.0",
    produces = setOf(Noun.MESSAGE),
    handles = setOf(ActionKind.CREATE_REMINDER),
    configSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("emitSample") {
                put("type", "boolean")
                put("title", "Emit a sample message")
                put("description", "When enabled, each sync pushes one deterministic sample message.")
                put("default", false)
            }
        }
        put("additionalProperties", false)
    },
)

/** Fixed timestamps and ids make the sample idempotent: replays come back as `Unchanged`. */
private val SAMPLE_TIME = Instant.parse("2026-01-01T09:00:00Z")
const val SAMPLE_SOURCE_VERSION = "1"
const val CURSOR_KEY = "lastPushedSample"

/** Pure: the single record this connector can produce. Real connectors map source items here. */
fun sampleRecords(): List<IngestRecord> = listOf(
    IngestRecord.Upsert(
        sourceId = SourceId("example-sample-1"),
        sourceVersion = SAMPLE_SOURCE_VERSION,
        observedAt = SAMPLE_TIME,
        payload = MessagePayload(
            channel = MessagePayload.Channel.CHAT,
            account = Handle.email("me@example.com"),
            sender = Handle.email("hello@example.com", "Clarity Example"),
            recipients = listOf(Handle.email("me@example.com")),
            subject = "Hello from the example connector",
            body = "If you can read this, the connector pipeline works end to end.",
            sentAt = SAMPLE_TIME,
        ),
    ),
)

/** Pure: reads the typed setting from the opaque config JSON (missing = default). */
fun emitSample(config: ConnectorConfig): Boolean =
    (config.values["emitSample"] as? JsonPrimitive)?.takeIf { !it.isString }?.boolean ?: false

/** One sync run. Extracted from `main` so it can be unit tested with a fake [SyncScope]. */
suspend fun SyncScope.syncOnce(config: ConnectorConfig) {
    if (!emitSample(config)) {
        log.info { "emitSample is off; nothing to do" }
        return
    }
    val results = push(sampleRecords())
    // Cursor: remember what was last delivered. Real connectors store IMAP UIDs, sync tokens, etc.
    state.putAs(CURSOR_KEY, SAMPLE_SOURCE_VERSION)
    log.info { "pushed ${results.size} record(s): ${results.map { it.outcome }}" }
}

/** Last cursor written by [syncOnce], if any. */
suspend fun StateStore.lastCursor(): String? = getAs<String>(CURSOR_KEY)

private val actionLog = KotlinLogging.logger("example.actions")

/**
 * Executes a `create_reminder` action by logging it. A real connector would call its upstream here
 * and use `action.idempotencyKey` to make that call idempotent; the SDK additionally never runs
 * the same key twice once it has returned `Done`.
 */
fun handleReminder(action: ProposedAction.CreateReminder): ActionOutcome {
    actionLog.info { "reminder for ${action.at}: ${action.text}" }
    return ActionOutcome.Done(SourceId("example-reminder-${action.idempotencyKey}"))
}

/** The actions this connector handles; must match [ConnectorManifest.handles]. */
val actions = actionHandlers { on<ProposedAction.CreateReminder> { handleReminder(it) } }

suspend fun main() = runConnector(manifest, actions = actions) { config -> syncOnce(config) }
