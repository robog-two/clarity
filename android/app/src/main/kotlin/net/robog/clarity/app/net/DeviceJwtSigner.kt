package net.robog.clarity.app.net

import java.util.UUID
import kotlin.time.Clock
import net.robog.clarity.app.identity.DeviceJwtBuilder
import net.robog.clarity.app.identity.DeviceKey
import net.robog.clarity.shared.DeviceId

/** Mints a fresh short-lived device JWT per request (no refresh tokens, see ARCHITECTURE: Authentication). */
class DeviceJwtSigner(
    private val key: DeviceKey,
    private val clock: Clock = Clock.System,
) {
    fun token(deviceId: DeviceId): String = DeviceJwtBuilder.build(
        deviceId = deviceId.value,
        issuedAtEpochSeconds = clock.now().epochSeconds,
        jti = UUID.randomUUID().toString(),
        sign = key::signJose,
    )
}
