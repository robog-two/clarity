package net.robog.clarity.backend.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** Test-side stand-in for the Android Keystore: an EC key pair that signs device JWTs. */
class TestDevice(val keyPair: KeyPair = generate("secp256r1"), val id: String = UUID.randomUUID().toString()) {
    val publicKey get() = keyPair.public as ECPublicKey
    val spkiBase64Url: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(keyPair.public.encoded)

    /** The same key under the server-assigned device id (the server, not the device, picks the id). */
    fun withId(newId: String) = TestDevice(keyPair, newId)

    fun token(
        issuedAt: Instant,
        lifetimeSeconds: Long = 60,
        audience: String = "clarity",
        subject: String = id,
        keyId: String = id,
        jti: String? = UUID.randomUUID().toString(),
        signWith: KeyPair = keyPair,
    ): String = JWT.create()
        .withKeyId(keyId)
        .withSubject(subject)
        .withAudience(audience)
        .withIssuedAt(issuedAt.toJavaInstant())
        .withExpiresAt(issuedAt.toJavaInstant().plusSeconds(lifetimeSeconds))
        .apply { if (jti != null) withJWTId(jti) }
        .sign(Algorithm.ECDSA256(signWith.public as ECPublicKey, signWith.private as ECPrivateKey))

    companion object {
        fun generate(curve: String): KeyPair =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()
    }
}
