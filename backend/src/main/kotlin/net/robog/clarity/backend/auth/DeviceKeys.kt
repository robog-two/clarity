package net.robog.clarity.backend.auth

import arrow.core.Either
import arrow.core.flatMap
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Why a submitted public key was rejected. */
data class InvalidDeviceKey(val reason: String)

private val p256: ECParameterSpec by lazy {
    AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }
}

/** Decodes base64url (padding tolerated) SPKI bytes. */
fun decodeBase64Url(text: String): Either<InvalidDeviceKey, ByteArray> =
    Either.catch { Base64.getUrlDecoder().decode(text.trim()) }.mapLeft { InvalidDeviceKey("not valid base64url") }

/** Parses SPKI DER into an EC P-256 key; any other key type or curve is rejected. */
fun parseP256PublicKey(spki: ByteArray): Either<InvalidDeviceKey, ECPublicKey> =
    Either.catch { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) }
        .mapLeft { InvalidDeviceKey("not an EC SubjectPublicKeyInfo") }
        .flatMap { key ->
            val ec = key as? ECPublicKey
            when {
                ec == null -> Either.Left(InvalidDeviceKey("not an EC key"))
                ec.params.order != p256.order || ec.params.curve != p256.curve ->
                    Either.Left(InvalidDeviceKey("key is not on curve P-256"))
                else -> Either.Right(ec)
            }
        }
