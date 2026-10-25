package net.robog.clarity.backend.auth

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Clock as JavaClock
import java.time.ZoneOffset
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import net.robog.clarity.shared.api.DeviceJwt

/** Claims of a token whose signature and lifetime checks passed. */
data class VerifiedDeviceToken(val deviceId: String, val issuedAt: Instant, val expiresAt: Instant)

/** Why a device token was refused. [reason] is for logs, never echoed to the client. */
data class JwtRejection(val reason: String)

/**
 * Pure device-JWT validation (no I/O, time passed in): ES256 signature by the device's key,
 * `aud=clarity`, `iat`/`exp` present with `exp - iat <= 300 s`, `sub == kid`, `jti` present.
 * Short, bounded lifetimes plus a small [leewaySeconds] for clock skew replace any server secret.
 */
object DeviceJwtVerification {
    const val DEFAULT_LEEWAY_SECONDS = 10L

    /** The unverified `kid` header, used only to pick which key to verify with. */
    fun unverifiedKeyId(token: String): String? =
        runCatching { JWT.decode(token).keyId }.getOrNull()?.takeIf(String::isNotBlank)

    fun verify(
        token: String,
        key: ECPublicKey,
        now: Instant,
        leewaySeconds: Long = DEFAULT_LEEWAY_SECONDS,
    ): Either<JwtRejection, VerifiedDeviceToken> {
        val algorithm = Algorithm.ECDSA256(key, null as ECPrivateKey?)
        val clock = JavaClock.fixed(now.toJavaInstant(), ZoneOffset.UTC)
        val decoded = try {
            (JWT.require(algorithm)
                .withAudience(DeviceJwt.AUDIENCE)
                .withClaimPresence("jti")
                .acceptLeeway(leewaySeconds) as JWTVerifier.BaseVerification)
                .build(clock)
                .verify(token)
        } catch (e: JWTVerificationException) {
            return JwtRejection(e.message ?: "verification failed").left()
        }
        val kid = decoded.keyId
        val issuedAt = decoded.issuedAtAsInstant
        val expiresAt = decoded.expiresAtAsInstant
        return when {
            issuedAt == null || expiresAt == null -> JwtRejection("iat and exp are required").left()
            decoded.subject == null || decoded.subject != kid -> JwtRejection("sub must equal kid").left()
            expiresAt.epochSecond - issuedAt.epochSecond !in 1..DeviceJwt.MAX_LIFETIME_SECONDS ->
                JwtRejection("lifetime must be 1..${DeviceJwt.MAX_LIFETIME_SECONDS}s").left()
            else -> VerifiedDeviceToken(
                deviceId = decoded.subject,
                issuedAt = Instant.fromEpochSeconds(issuedAt.epochSecond),
                expiresAt = Instant.fromEpochSeconds(expiresAt.epochSecond),
            ).right()
        }
    }
}
