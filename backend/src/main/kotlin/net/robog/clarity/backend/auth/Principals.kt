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

data class DevicePrincipal(val deviceId: DeviceId)

data class ConnectorPrincipal(val connectorId: ConnectorId)
