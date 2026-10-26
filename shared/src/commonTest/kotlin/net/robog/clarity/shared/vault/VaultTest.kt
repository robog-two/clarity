package net.robog.clarity.shared.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.governance.ProposedAction

class VaultTest {
    @Test
    fun luhnAcceptsRealNumbersAndRejectsTypos() {
        assertTrue(Validators.luhn("4111 1111 1111 1111"))
        assertTrue(Validators.luhn("4111-1111-1111-1111"))
        assertTrue(Validators.luhn("378282246310005"))
        assertFalse(Validators.luhn("4111 1111 1111 1112"))
        assertFalse(Validators.luhn("1234"))
        assertFalse(Validators.luhn("4111 1111 1111 111a"))
        assertNotNull(Validators.cardNumber("4111 1111 1111 1112"))
        assertNull(Validators.cardNumber("4111111111111111"))
    }

    @Test
    fun cardBrands() {
        assertEquals("visa", Validators.cardBrand("4111 1111 1111 1111"))
        assertEquals("mastercard", Validators.cardBrand("5555 5555 5555 4444"))
        assertEquals("mastercard", Validators.cardBrand("2221000000000009"))
        assertEquals("amex", Validators.cardBrand("378282246310005"))
        assertEquals("discover", Validators.cardBrand("6011111111111117"))
        assertEquals("unknown", Validators.cardBrand("9999"))
    }

    @Test
    fun expiryMustBeRealAndNotPast() {
        assertNull(Validators.cardExpiry("12/29", nowYear = 2026, nowMonth = 10))
        assertNull(Validators.cardExpiry("10/2026", nowYear = 2026, nowMonth = 10))
        assertNull(Validators.cardExpiry("1030", nowYear = 2026, nowMonth = 10))
        assertNotNull(Validators.cardExpiry("09/26", nowYear = 2026, nowMonth = 10))
        assertNotNull(Validators.cardExpiry("13/29", nowYear = 2026, nowMonth = 10))
        assertNotNull(Validators.cardExpiry("1/29", nowYear = 2026, nowMonth = 10))
        assertNotNull(Validators.cardExpiry("soon", nowYear = 2026, nowMonth = 10))
    }

    @Test
    fun cvcLengthFollowsTheBrand() {
        assertNull(Validators.cvc("123", "4111111111111111"))
        assertNotNull(Validators.cvc("1234", "4111111111111111"))
        assertNull(Validators.cvc("1234", "378282246310005"))
        assertNotNull(Validators.cvc("123", "378282246310005"))
        assertNull(Validators.cvc("123"))
        assertNull(Validators.cvc("1234"))
        assertNotNull(Validators.cvc("12"))
        assertNotNull(Validators.cvc("12a"))
    }

    @Test
    fun emailAndPhone() {
        assertNull(Validators.email("dana@example.com"))
        assertNotNull(Validators.email("dana@example"))
        assertNotNull(Validators.email("dana example.com"))
        assertNull(Validators.phone("+1 (415) 555-0123"))
        assertNull(Validators.phone("020 7946 0958"))
        assertNotNull(Validators.phone("12"))
        assertNotNull(Validators.phone("call me"))
    }

    @Test
    fun fieldDispatchHandlesRequiredAndOptional() {
        assertEquals("Required.", Validators.field(FieldType.PASSWORD, " "))
        assertNull(Validators.field(FieldType.PASSWORD, "", required = false))
        assertNull(Validators.field(FieldType.PASSWORD, "hunter2"))
        assertNotNull(Validators.field(FieldType.EMAIL, "nope"))
        assertNotNull(Validators.field(FieldType.OTP, "12"))
        assertNull(Validators.field(FieldType.OTP, "123456"))
    }

    @Test
    fun handlesAndFieldNamesAreSlugs() {
        assertTrue(VaultNames.isSlug("bank_login"))
        assertTrue(VaultNames.isSlug("card_number"))
        assertFalse(VaultNames.isSlug("Bank"))
        assertFalse(VaultNames.isSlug("bank__login"))
        assertFalse(VaultNames.isSlug("_bank"))
        assertFalse(VaultNames.isSlug("bank-login"))
        assertFalse(VaultNames.isSlug("1bank"))
        assertFalse(VaultNames.isSlug("a".repeat(49)))
        assertEquals("bank_of_x_login", VaultNames.slugOf("Bank of X login"))
        assertEquals("c_2fa", VaultNames.slugOf("2fa"))
        assertEquals("credential", VaultNames.slugOf("!!!"))
        assertEquals("[secret:bank_login_password]", VaultNames.placeholder("bank_login", "password"))
    }

    @Test
    fun envelopesAndActionsRoundTrip() {
        val envelope = VaultEnvelope(DeviceId("d1"), epk = "e", iv = "i", ciphertext = "c", context = "req-1")
        val back = ClarityJson.decodeFromString(VaultEnvelope.serializer(), ClarityJson.encodeToString(VaultEnvelope.serializer(), envelope))
        assertEquals(envelope, back)
        assertEquals("ECDH-ES-P256-HKDF-SHA256-A256GCM", back.alg)
        assertEquals("clarity-vault-v1|req-1|d1", VaultSealing.info("req-1", DeviceId("d1")))

        val use: ProposedAction = ProposedAction.UseCredential("k", "Use bank", CredentialId("c1"), "bank.com", listOf("username", "password"))
        val json = ClarityJson.encodeToString(ProposedAction.serializer(), use)
        assertTrue("\"use_credential\"" in json)
        assertEquals(use, ClarityJson.decodeFromString(ProposedAction.serializer(), json))
        val ask: ProposedAction = ProposedAction.AskCode("k2", "code", "bank.com", "login")
        assertEquals(ask, ClarityJson.decodeFromString(ProposedAction.serializer(), ClarityJson.encodeToString(ProposedAction.serializer(), ask)))
    }
}
