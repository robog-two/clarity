package net.robog.clarity.app.identity

import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.shared.api.DeviceJwt

private val base64Url = Base64.getUrlEncoder().withoutPadding()

fun base64UrlEncode(bytes: ByteArray): String = base64Url.encodeToString(bytes)

/** Pure ECDSA signature encoding helpers (DER as produced by the JCA vs. fixed-width JOSE R||S). */
object EcdsaSignature {
    /** Size in bytes of each of R and S for P-256. */
    const val P256_COMPONENT_SIZE = 32

    /** Converts an ASN.1 DER `SEQUENCE { INTEGER r, INTEGER s }` to the JOSE `R || S` form. */
    fun derToJose(der: ByteArray, componentSize: Int = P256_COMPONENT_SIZE): ByteArray {
        require(der.size >= 8 && der[0] == 0x30.toByte()) { "not a DER ECDSA signature" }
        var offset = 1
        val seqLen = der[offset].toInt() and 0xFF
        offset += if (seqLen and 0x80 != 0) 1 + (seqLen and 0x7F) else 1
        val r = readInteger(der, offset).also { offset = it.nextOffset }
        val s = readInteger(der, offset)
        val out = ByteArray(componentSize * 2)
        copyRightAligned(r.value, out, 0, componentSize)
        copyRightAligned(s.value, out, componentSize, componentSize)
        return out
    }

    private class IntegerRead(val value: ByteArray, val nextOffset: Int)

    private fun readInteger(der: ByteArray, start: Int): IntegerRead {
        require(start + 2 <= der.size && der[start] == 0x02.toByte()) { "malformed DER signature" }
        val len = der[start + 1].toInt() and 0xFF
        require(len < 0x80 && start + 2 + len <= der.size) { "malformed DER signature" }
        return IntegerRead(der.copyOfRange(start + 2, start + 2 + len), start + 2 + len)
    }

    private fun copyRightAligned(value: ByteArray, out: ByteArray, outOffset: Int, size: Int) {
        // Strip DER sign-padding zeros, then left-pad to the fixed width.
        var from = 0
        while (from < value.size - 1 && value[from] == 0.toByte()) from++
        val len = value.size - from
        require(len <= size) { "signature component too large" }
        System.arraycopy(value, from, out, outOffset + size - len, len)
    }
}

/** Pure assembly of the ES256 device JWT; signing is injected so tests can use a software key. */
object DeviceJwtBuilder {
    const val LIFETIME_SECONDS = 120L

    /**
     * @param sign signs the ASCII bytes of `header.payload` and returns a JOSE (R||S) signature.
     */
    fun build(
        deviceId: String,
        issuedAtEpochSeconds: Long,
        jti: String,
        sign: (ByteArray) -> ByteArray,
    ): String {
        val header = buildJsonObject {
            put("alg", DeviceJwt.ALGORITHM)
            put("typ", "JWT")
            put("kid", deviceId)
        }
        val claims = buildJsonObject {
            put("sub", deviceId)
            put("aud", DeviceJwt.AUDIENCE)
            put("iat", JsonPrimitive(issuedAtEpochSeconds))
            put("exp", JsonPrimitive(issuedAtEpochSeconds + LIFETIME_SECONDS))
            put("jti", jti)
        }
        val signingInput =
            base64UrlEncode(header.toString().toByteArray()) + "." +
                base64UrlEncode(claims.toString().toByteArray())
        return signingInput + "." + base64UrlEncode(sign(signingInput.toByteArray(Charsets.US_ASCII)))
    }
}
