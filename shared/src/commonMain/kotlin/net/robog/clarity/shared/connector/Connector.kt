package net.robog.clarity.shared.connector

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.noun.Noun

/**
 * What a connector announces about itself on startup (`PUT /ingest/v1/manifest`).
 * The app renders [configSchema] (JSON Schema) as a settings form; the backend never
 * interprets connector config beyond storing it.
 */
@Serializable
data class ConnectorManifest(
    val kind: String,
    val displayName: String,
    val version: String,
    /** Nouns this connector wants to push. The user must grant each one before ingest is accepted. */
    val produces: Set<Noun>,
    /** Action kinds this connector can execute (see `shared/action`). */
    val handles: Set<net.robog.clarity.shared.governance.ActionKind> = emptySet(),
    val configSchema: JsonObject = JsonObject(emptyMap()),
    /**
     * Public key (X25519, base64url) the app uses to seal secret config values (passwords,
     * OAuth tokens) so that only the connector can read them; the backend stores ciphertext.
     */
    val sealingKey: String? = null,
)

/** Connector configuration, versioned so connectors can poll cheaply (`If-None-Match`). */
@Serializable
data class ConnectorConfig(
    val version: Long,
    val values: JsonObject,
)

/** A secret config value encrypted to the connector's [ConnectorManifest.sealingKey]. */
@Serializable
@SerialName("sealed")
data class SealedValue(val algorithm: String, val ciphertext: String)

@Serializable
enum class GrantState {
    @SerialName("pending") PENDING,
    @SerialName("granted") GRANTED,
    @SerialName("revoked") REVOKED,
}

/** The app's view of a connector (`GET /api/v1/connectors`). */
@Serializable
data class ConnectorSummary(
    val id: ConnectorId,
    val manifest: ConnectorManifest?,
    val grants: Map<Noun, GrantState>,
    val lastSeenAt: kotlin.time.Instant?,
    /** Newest result of `POST /api/v1/connectors/{id}/test`, if the connector has reported one. */
    val lastTest: ConnectorTestReport? = null,
)

/**
 * A "test this connection" request (`POST /api/v1/connectors/{id}/test`). The connector sees it on
 * its next poll of `GET /ingest/v1/test`, checks its upstream with
 * the current config and answers with [ConnectorTestReport] (`POST /ingest/v1/test`).
 */
@Serializable
data class ConnectorTestRequest(val requestId: String, val requestedAt: kotlin.time.Instant)

@Serializable
data class ConnectorTestReport(
    val requestId: String,
    val ok: Boolean,
    /** Human readable: "Connected to imap.example.com, 3 folders" or what failed. */
    val message: String,
    /** E.g. `{"folders": 3, "calendars": 2}`. */
    val counts: Map<String, Int> = emptyMap(),
    val at: kotlin.time.Instant,
)

/** `PUT /api/v1/connectors/{id}/grants`: the desired state per noun. */
@Serializable
data class GrantsUpdate(val grants: Map<Noun, GrantState>)

/** `POST /api/v1/connectors/{id}/test`: the connector tried its credentials and a first sync. */
@Serializable
data class ConnectorTestResult(
    val ok: Boolean,
    /** Failure reason for the person ("password rejected (401)"). */
    val error: String? = null,
    /** Short summary on success ("4,218 messages"). */
    val summary: String? = null,
    /**
     * The connector has not reported within the server's wait: the result arrives later as
     * [ConnectorSummary.lastTest] with this [requestId].
     */
    val pending: Boolean = false,
    /** The test request this answers; set by the server on `POST /api/v1/connectors/{id}/test`. */
    val requestId: String? = null,
)
