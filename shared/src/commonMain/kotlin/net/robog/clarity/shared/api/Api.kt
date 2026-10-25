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
    const val SETUP_MODEL_KEY = "$APP/setup/model-key"
    const val SETTINGS = "$APP/settings"
    const val FEED = "$APP/feed"
    const val EVENTS = "$APP/events"
    const val INPUT = "$APP/input"
    const val SESSIONS = "$APP/sessions"
    const val TODAY = "$APP/today"
    const val CONSTITUTION = "$APP/constitution"
    const val HELD = "$APP/held"
    const val ACTIVITY = "$APP/activity"

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
)

/** Constants for device-signed JWTs. */
object DeviceJwt {
    const val AUDIENCE = "clarity"
    const val ALGORITHM = "ES256"
    /** Max lifetime the server accepts (`exp - iat`). */
    const val MAX_LIFETIME_SECONDS = 300L
}
