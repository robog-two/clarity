package net.robog.clarity.backend.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val now = Instant.parse("2026-06-01T12:00:00Z")

private fun TestDevice.verify(token: String, at: Instant = now) = DeviceJwtVerification.verify(token, publicKey, at)

class DeviceJwtVerificationTest : FunSpec({
    val device = TestDevice()

    test("a fresh token verifies and carries the device id") {
        val verified = device.verify(device.token(now)).getOrNull()!!
        verified.deviceId shouldBe device.id
        verified.expiresAt - verified.issuedAt shouldBe 60.seconds
    }

    test("kid can be read before verification") {
        DeviceJwtVerification.unverifiedKeyId(device.token(now)) shouldBe device.id
        DeviceJwtVerification.unverifiedKeyId("garbage") shouldBe null
    }

    test("lifetime longer than 300 seconds is rejected, exactly 300 is accepted") {
        device.verify(device.token(now, lifetimeSeconds = 300)).isRight() shouldBe true
        device.verify(device.token(now, lifetimeSeconds = 301)).leftOrNull()!!.reason shouldContain "lifetime"
    }

    test("expired tokens are rejected beyond the skew allowance, accepted within it") {
        val token = device.token(now, lifetimeSeconds = 60)
        device.verify(token, at = now + 65.seconds).isRight() shouldBe true
        device.verify(token, at = now + 120.seconds).isLeft() shouldBe true
    }

    test("tokens issued in the future are rejected beyond the skew allowance") {
        device.verify(device.token(now + 5.seconds)).isRight() shouldBe true
        device.verify(device.token(now + 120.seconds)).isLeft() shouldBe true
    }

    test("wrong audience, wrong signer, mismatched subject and missing jti are rejected") {
        device.verify(device.token(now, audience = "other")).isLeft() shouldBe true
        device.verify(device.token(now, signWith = TestDevice.generate("secp256r1"))).isLeft() shouldBe true
        device.verify(device.token(now, subject = "someone-else")).leftOrNull()!!.reason shouldContain "sub"
        device.verify(device.token(now, jti = null)).isLeft() shouldBe true
    }

    test("tampered tokens are rejected") {
        val parts = device.token(now).split('.')
        val forgedPayload = parts[1].reversed()
        device.verify("${parts[0]}.$forgedPayload.${parts[2]}").isLeft() shouldBe true
    }

    test("only P-256 SPKI keys are accepted") {
        parseP256PublicKey(device.keyPair.public.encoded).isRight() shouldBe true
        parseP256PublicKey(TestDevice.generate("secp384r1").public.encoded).isLeft() shouldBe true
        parseP256PublicKey(
            java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded,
        ).isLeft() shouldBe true
        parseP256PublicKey(byteArrayOf(1, 2, 3)).isLeft() shouldBe true
        decodeBase64Url("!!!").isLeft() shouldBe true
    }

    test("connector secret file names map to connector ids") {
        connectorIdFromSecretFile("connector_imap_personal_key")?.value shouldBe "imap-personal"
        connectorIdFromSecretFile("connector_example_key")?.value shouldBe "example"
        connectorIdFromSecretFile("connector__key") shouldBe null
        connectorIdFromSecretFile("db_password") shouldBe null
        connectorIdFromSecretFile("connector_UPPER_key") shouldBe null
    }
})
