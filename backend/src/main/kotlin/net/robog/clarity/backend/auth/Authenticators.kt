package net.robog.clarity.backend.auth

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import net.robog.clarity.backend.connectors.ConnectorRegistry
import net.robog.clarity.backend.crypto.sha256
import net.robog.clarity.shared.DeviceId

private val log = KotlinLogging.logger {}

/** A credential that did not authenticate. Reasons are logged, not sent to the caller. */
data class AuthFailure(val reason: String)

/** Resolves a device JWT to a [DevicePrincipal] (key lookup by `kid`, then pure verification). */
@Inject
@SingleIn(AppScope::class)
class DeviceAuthenticator(private val devices: DeviceRepository, private val clock: Clock) {
    suspend fun authenticate(token: String): Either<AuthFailure, DevicePrincipal> {
        val kid = DeviceJwtVerification.unverifiedKeyId(token) ?: return AuthFailure("no kid").left()
        val key = devices.publicKey(kid) ?: return AuthFailure("unknown device").left()
        return DeviceJwtVerification.verify(token, key, clock.now())
            .mapLeft { AuthFailure(it.reason).also { f -> log.debug { "device token rejected: ${f.reason}" } } }
            .map { DevicePrincipal(DeviceId(it.deviceId), it.expiresAt) }
    }
}

/**
 * Resolves a bearer credential to a [ConnectorPrincipal]: a provisioned connector key (looked up
 * by SHA-256), or a device JWT acting as the implicit `device-<id>` connector.
 */
@Inject
@SingleIn(AppScope::class)
class ConnectorAuthenticator(
    private val registry: ConnectorRegistry,
    private val deviceAuth: DeviceAuthenticator,
) {
    suspend fun authenticate(token: String): Either<AuthFailure, ConnectorPrincipal> {
        registry.authenticateKey(sha256(token))?.let { return ConnectorPrincipal(it).right() }
        if (token.count { it == '.' } != 2) return AuthFailure("unknown connector key").left()
        return deviceAuth.authenticate(token).map { ConnectorPrincipal(registry.ensureDeviceConnector(it.deviceId), it.deviceId) }
    }
}
