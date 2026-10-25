package net.robog.clarity.app.identity

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class JoseTest {
    private val keyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    private fun signDer(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run { initSign(keyPair.private); update(data); sign() }

    /** Verifies a JOSE signature by converting R||S back to DER, using only the JCA. */
    private fun verifyJose(data: ByteArray, jose: ByteArray): Boolean {
        fun der(i: ByteArray): ByteArray {
            var b = i.dropWhile { it == 0.toByte() }.toByteArray()
            if (b.isEmpty()) b = byteArrayOf(0)
            if (b[0] < 0) b = byteArrayOf(0) + b
            return byteArrayOf(0x02, b.size.toByte()) + b
        }
        val body = der(jose.copyOfRange(0, 32)) + der(jose.copyOfRange(32, 64))
        val sig = byteArrayOf(0x30, body.size.toByte()) + body
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(keyPair.public); update(data); verify(sig)
        }
    }

    @Test
    fun derToJoseIs64BytesAndVerifies() {
        repeat(200) { i ->
            val data = "message-$i".toByteArray()
            val jose = EcdsaSignature.derToJose(signDer(data))
            assertEquals(64, jose.size)
            assertTrue(verifyJose(data, jose))
        }
    }

    @Test
    fun derToJosePadsShortAndStripsSignPaddedComponents() {
        // r = 0x01 (short), s = 0x00 0x80.. (sign-padded 32 bytes -> 33 with leading zero)
        val s = byteArrayOf(0) + ByteArray(32) { 0x80.toByte() }
        val body = byteArrayOf(0x02, 1, 1) + byteArrayOf(0x02, s.size.toByte()) + s
        val der = byteArrayOf(0x30, body.size.toByte()) + body
        val jose = EcdsaSignature.derToJose(der)
        assertEquals(64, jose.size)
        assertEquals(1, jose[31].toInt())
        assertTrue(jose.copyOfRange(0, 31).all { it == 0.toByte() })
        assertTrue(jose.copyOfRange(32, 64).all { it == 0x80.toByte() })
    }

    @Test
    fun derToJoseRejectsGarbage() {
        assertFailsWith<IllegalArgumentException> { EcdsaSignature.derToJose(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun jwtHasExpectedHeaderClaimsAndVerifiableSignature() {
        val jwt = DeviceJwtBuilder.build("dev-1", 1_000L, "jti-1") { EcdsaSignature.derToJose(signDer(it)) }
        val (h, p, s) = jwt.split('.')
        val dec = Base64.getUrlDecoder()
        assertTrue(!jwt.contains('=') && !jwt.contains('+') && !jwt.contains('/'))

        val header = Json.parseToJsonElement(String(dec.decode(h))).jsonObject
        assertEquals("ES256", header["alg"]!!.jsonPrimitive.content)
        assertEquals("JWT", header["typ"]!!.jsonPrimitive.content)
        assertEquals("dev-1", header["kid"]!!.jsonPrimitive.content)

        val claims = Json.parseToJsonElement(String(dec.decode(p))).jsonObject
        assertEquals("dev-1", claims["sub"]!!.jsonPrimitive.content)
        assertEquals("clarity", claims["aud"]!!.jsonPrimitive.content)
        assertEquals(1_000L, claims["iat"]!!.jsonPrimitive.long)
        assertEquals(1_120L, claims["exp"]!!.jsonPrimitive.long)
        assertEquals("jti-1", claims["jti"]!!.jsonPrimitive.content)

        assertTrue(verifyJose("$h.$p".toByteArray(Charsets.US_ASCII), dec.decode(s)))
        assertTrue(keyPair.public is ECPublicKey)
    }
}
