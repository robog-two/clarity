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
)
