package net.robog.clarity.shared.api

import kotlinx.serialization.Serializable
import net.robog.clarity.shared.DeviceId

/** HTTP route prefixes, shared so clients and server can't drift. */
object ApiPaths {
    /** Device-authenticated app API. */
    const val APP = "/api/v1"
    /** Connector-authenticated ingest API. */
    const val INGEST = "/ingest/v1"
    /** Model Context Protocol endpoint (streamable HTTP), device-authenticated. */
    const val MCP = "/mcp"
    const val HEALTH = "/healthz"

    // App API (under APP)
    const val SETUP_STATUS = "$APP/setup"
    const val SETUP_MODEL_KEY = "$APP/setup/model-key"
    const val CONNECTORS = "$APP/connectors"
    const val SETTINGS = "$APP/settings"
    /** `GET` every role's model settings; `PUT {role}` sets its overrides, `DELETE {role}` clears them. */
    const val SETTINGS_MODELS = "$SETTINGS/models"
    const val FEED = "$APP/feed"
    const val EVENTS = "$APP/events"
    const val INPUT = "$APP/input"
    const val SESSIONS = "$APP/sessions"
    const val TODAY = "$APP/today"
    const val CONSTITUTION = "$APP/constitution"
    const val HELD = "$APP/held"
    const val ACTIVITY = "$APP/activity"
    /** `GET ?run=<id>` lists a helper run's browser steps (the audit trail); `GET {id}/before.png` and `{id}/after.png` are its screenshots. */
    const val BROWSER_STEPS = "$APP/browser/steps"
    /** `GET {id}/path?from=&limit=`: a task's Agent's Path (its lead turns and every helper run linked to it). */
    const val TASKS = "$APP/tasks"
    /** `GET {id}/path?from=&limit=`: one helper run's Agent's Path. */
    const val RUNS = "$APP/runs"
    /** `GET`: pending nudges and the timeline of resolved ones (`?since=<ISO instant>`, default the last 7 days). */
    const val NUDGES = "$APP/nudges"
    /**
     * `GET` lists reports; `GET {id}` is one with its revisions and sources; `GET {id}/source?rev=` its
     * Markdown; `GET {id}/download/{format}?rev=` the rendered file (pdf, docx, odt, txt, md).
     */
    const val REPORTS = "$APP/reports"

    /** A Good's public download page (`/g/{token}`, its files `/g/{token}/{format}`): the only unauthenticated report routes. */
    const val GOODS_PUBLIC = "/g"

    // Vault (under APP)
    const val VAULT = "$APP/vault"
    /** `POST` registers the caller's vault public key; `GET` lists devices' vault keys. */
    const val VAULT_DEVICES = "$VAULT/devices"
    /** `GET` lists credential metadata; `POST` completes a request with envelopes; `DELETE {id}` removes one; `GET {id}/envelope` is the caller's own ciphertext. */
    const val VAULT_CREDENTIALS = "$VAULT/credentials"
    /** `GET {id}`: a credential request (the form to show). */
    const val VAULT_REQUESTS = "$VAULT/requests"
    /** `POST {heldId}`: release secret fields for an approved use (biometric on the phone). */
    const val VAULT_GRANTS = "$VAULT/grants"
    /** `POST {heldId}`: the two-factor code the person typed. */
    const val VAULT_CODES = "$VAULT/codes"

    // Connector API (under INGEST)
    const val ACTIONS = "$INGEST/actions"
    /** `GET`: the connector's pending connection test (204 when none). `POST`: its report. */
    const val TEST = "$INGEST/test"
    const val READY = "/readyz"
}

/**
 * Device enrollment (`POST /api/v1/enroll`, unauthenticated).
 * The device generates an EC P-256 key pair in the Android Keystore and sends the public half;
 * afterwards it authenticates every request with a short-lived ES256 JWT signed by that key.
 */
@Serializable
data class EnrollmentRequest(
    /** SubjectPublicKeyInfo (DER), base64url without padding. */
    val publicKey: String,
    val deviceName: String,
    /** Required unless the instance is in trust-on-first-use mode and has no devices yet. */
    val enrollmentToken: String? = null,
)

@Serializable
data class EnrollmentResponse(val deviceId: DeviceId, val instance: InstanceInfo)

@Serializable
data class InstanceInfo(val name: String, val version: String, val apiVersion: Int = 1)

/** RFC 9457 problem details: the error body for every non-2xx response. */
@Serializable
data class Problem(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String? = null,
    /** For a refused input: what is wrong with each field. */
    val errors: List<FieldError>? = null,
)

/** One field of a refused input, and why (plain words, shown to the person). */
@Serializable
data class FieldError(val field: String, val message: String)

/** Constants for device-signed JWTs. */
object DeviceJwt {
    const val AUDIENCE = "clarity"
    const val ALGORITHM = "ES256"
    /** Max lifetime the server accepts (`exp - iat`). */
    const val MAX_LIFETIME_SECONDS = 300L
}
