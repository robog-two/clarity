package net.robog.clarity.backend.auth

import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.DeviceId

/** Names of the Ktor authentication providers. */
object AuthProviders {
    /** Device-signed ES256 JWT. */
    const val DEVICE = "device"
    /** Connector API key, or a device JWT acting as the `device-<id>` connector. */
    const val CONNECTOR = "connector"
}

/** [expiresAt] is the verified JWT's `exp`; long-lived connections (SSE) close when it passes. */
data class DevicePrincipal(val deviceId: DeviceId, val expiresAt: kotlin.time.Instant? = null)

/**
 * A connector, or an enrolled device acting as its own `device-<id>` connector ([device] set: the
 * person's own phone, whose JWT may also manage grants on the app API).
 */
data class ConnectorPrincipal(val connectorId: ConnectorId, val device: DeviceId? = null) {
    val isOwnDevice: Boolean get() = device != null
}
