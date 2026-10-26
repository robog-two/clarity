package net.robog.clarity.app.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.testutil.FakeVault
import net.robog.clarity.app.testutil.FakeVaultApi
import net.robog.clarity.app.testutil.FakeVaultKeys
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.credentialRequest
import net.robog.clarity.app.testutil.loginFields
import net.robog.clarity.app.testutil.newKeyPair
import net.robog.clarity.app.testutil.spki
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.vault.CredentialId
import net.robog.clarity.shared.vault.CredentialKind
import net.robog.clarity.shared.vault.CredentialMeta
import net.robog.clarity.shared.vault.CredentialRequestStatus
import net.robog.clarity.shared.vault.DeviceVaultKey
import net.robog.clarity.shared.vault.FieldSpec
import net.robog.clarity.shared.vault.FieldType
import net.robog.clarity.shared.vault.NewCredential
import net.robog.clarity.shared.vault.VaultNames

class CredentialFormTest {
    private val cardFields = listOf(
        FieldSpec("number", "Card number", FieldType.CARD_NUMBER),
        FieldSpec("expiry", "Expiry", FieldType.CARD_EXPIRY),
        FieldSpec("cvc", "CVC", FieldType.CARD_CVC),
        FieldSpec("name", "Name on card", FieldType.NAME, required = false),
    )
    private val cardRequest = credentialRequest(id = "req2", kind = CredentialKind.PAYMENT_CARD, fields = cardFields)

    private fun card(vararg pairs: Pair<String, String>): CredentialForm {
        var form = CredentialForm(cardRequest, nowYear = 2026, nowMonth = 10)
        pairs.forEach { (name, value) -> form = form.with(cardFields.first { it.name == name }, value) }
        return form
    }

    @Test fun luhnExpiryAndCvcAreValidatedLive() {
        val good = card("number" to "4242 4242 4242 4242", "expiry" to "03/28", "cvc" to "123")
        assertTrue(good.valid)
        assertEquals("That card number doesn't look right.", card("number" to "4242 4242 4242 4241").error(cardFields[0]))
        assertEquals("That card has expired.", card("expiry" to "09/26").error(cardFields[1]))
        assertNull(card("expiry" to "10/26").error(cardFields[1]))
        assertFalse(card("number" to "4242 4242 4242 4242", "expiry" to "03/28", "cvc" to "12").valid)
    }

    @Test fun cvcLengthFollowsTheCardBrand() {
        val amex = card("number" to "3782 822463 10005", "cvc" to "123")
        assertEquals("The security code has 4 digits.", amex.error(cardFields[2]))
        assertNull(card("number" to "3782 822463 10005", "cvc" to "1234").error(cardFields[2]))
        assertEquals("The security code has 3 digits.", card("number" to "4242 4242 4242 4242", "cvc" to "1234").error(cardFields[2]))
    }

    @Test fun requiredFieldsMustBeFilledOptionalOnesNeedNot() {
        val empty = card()
        assertEquals("Required.", empty.error(cardFields[0]))
        assertNull(empty.error(cardFields[3]))
    }

    @Test fun errorsShowOnlyOnceAFieldWasTouched() {
        val form = card("number" to "1234")
        assertNull(form.shownError(cardFields[0]))
        assertEquals("That card number doesn't look right.", form.touch(cardFields[0]).shownError(cardFields[0]))
        assertEquals("Required.", form.touchAll().shownError(cardFields[1]))
    }

    @Test fun emailAndPhoneAreChecked() {
        val fields = listOf(FieldSpec("email", "Email", FieldType.EMAIL), FieldSpec("phone", "Phone", FieldType.PHONE))
        val form = CredentialForm(credentialRequest(fields = fields, kind = CredentialKind.IDENTITY), 2026, 10)
        assertEquals("That email address doesn't look right.", form.with(fields[0], "dana@").error(fields[0]))
        assertNull(form.with(fields[0], "dana@example.com").error(fields[0]))
        assertNull(form.with(fields[1], "+1 (415) 555-0123").error(fields[1]))
        assertEquals("Use a full phone number, e.g. +14155550123.", form.with(fields[1], "12").error(fields[1]))
    }

    @Test fun valuesAreNormalizedBeforeSealing() {
        val form = card("number" to "4242 4242-4242 4242", "expiry" to "0328", "cvc" to " 123 ", "name" to "  Dana Lee ")
        assertEquals(
            mapOf("number" to "4242424242424242", "expiry" to "03/28", "cvc" to "123", "name" to "Dana Lee"),
            form.normalized(),
        )
        assertEquals("03/28", normalizeExpiry("03/2028"))
        assertEquals("03/28", normalizeExpiry("03-28"))
    }

    @Test fun passwordsAreNeverTrimmedAndBlankOptionalsAreDropped() {
        val form = CredentialForm(credentialRequest(), 2026, 10)
            .with(loginFields[0], " dana ").with(loginFields[1], " pass word ")
        assertEquals(mapOf("username" to "dana", "password" to " pass word "), form.normalized())
        assertFalse("name" in card("number" to "4242424242424242").normalized())
    }

    @Test fun secretFieldsAreMaskedAndUseThePrivateKeyboards() {
        assertTrue(FieldType.PASSWORD.isSecret())
        assertTrue(FieldType.CARD_NUMBER.isSecret())
        assertTrue(FieldType.CARD_CVC.isSecret())
        assertFalse(FieldType.USERNAME.isSecret())
        assertFalse(FieldType.EMAIL.isSecret())
        val options = secretKeyboard()
        assertFalse(options.autoCorrectEnabled == true)
        assertEquals(androidx.compose.ui.text.input.KeyboardType.NumberPassword, keyboardFor(FieldType.CARD_NUMBER))
        assertEquals(androidx.compose.ui.text.input.KeyboardType.Password, keyboardFor(FieldType.PASSWORD))
    }

    // --- the model ---

    @Test fun theModelLoadsEditsAndSavesThenForgetsTheSecrets() = runTest {
        val vault = FakeVault(outcome = SaveOutcome.Saved(meta()))
        val model = CredentialFormModel(cardRequest.id, vault, now = { 2026 to 10 })
        vault.request = ApiResult.Success(cardRequest)
        model.load()
        assertIs<FormCardState.Editing>(model.state)

        // invalid: nothing is sent, every field shows its error
        model.save()
        assertTrue(vault.savedValues.isEmpty())
        assertEquals("Required.", (model.state as FormCardState.Editing).form.shownError(cardFields[0]))

        model.edit(cardFields[0], "4242 4242 4242 4242")
        model.edit(cardFields[1], "03/28")
        model.edit(cardFields[2], "123")
        model.save()
        assertEquals("4242424242424242", vault.savedValues.single()["number"])
        val done = assertIs<FormCardState.Done>(model.state)
        assertTrue("bank_login" in done.line)
        // typed values are gone with the editing state
        model.edit(cardFields[0], "x")
        assertIs<FormCardState.Done>(model.state)
    }

    @Test fun aFailedSaveKeepsWhatWasTypedAndSaysWhy() = runTest {
        val vault = FakeVault(outcome = SaveOutcome.Failed("Can’t reach your server right now."))
        val model = CredentialFormModel(cardRequest.id, vault, now = { 2026 to 10 })
        vault.request = ApiResult.Success(cardRequest)
        model.load()
        model.edit(cardFields[0], "4242424242424242"); model.edit(cardFields[1], "03/28"); model.edit(cardFields[2], "123")
        model.save()
        val s = assertIs<FormCardState.Editing>(model.state)
        assertEquals("Can’t reach your server right now.", s.error)
        assertFalse(s.saving)
        assertEquals("4242424242424242", s.form.valueOf(cardFields[0]))
    }

    @Test fun anAlreadyAnsweredRequestShowsNoForm() = runTest {
        val vault = FakeVault(request = ApiResult.Success(credentialRequest(status = CredentialRequestStatus.FULFILLED)))
        val model = CredentialFormModel(CredentialRequest1, vault)
        model.load()
        assertIs<FormCardState.Done>(model.state)
    }

    @Test fun anUnreachableServerShowsAMessage() = runTest {
        val vault = FakeVault(request = ApiResult.Failure(ApiError.Unreachable("x")))
        val model = CredentialFormModel(CredentialRequest1, vault)
        model.load()
        assertIs<FormCardState.Failed>(model.state)
    }

    // --- sealing and hints ---

    @Test fun savingSealsForEveryDeviceAndUploadsOnlyCiphertextAndAllowedHints() = runTest {
        val api = FakeVaultApi()
        val keys = FakeVaultKeys()
        val other = newKeyPair()
        api.devices = listOf(DeviceVaultKey(DeviceId("me"), spki(keys.vault)), DeviceVaultKey(DeviceId("tablet"), spki(other)))
        api.requests["req2"] = cardRequest
        val repo = DefaultVaultRepository(api, keys)
        val values = mapOf("number" to "4242424242424242", "expiry" to "03/28", "cvc" to "123")

        val outcome = repo.save(cardRequest, values)
        assertIs<SaveOutcome.Saved>(outcome)
        val sent: NewCredential = api.saved.single()
        assertEquals(listOf(DeviceId("me"), DeviceId("tablet")), sent.envelopes.map { it.deviceId })
        assertTrue(sent.envelopes.all { it.context == "req2" })
        assertEquals(mapOf("brand" to "visa", "last4" to "4242", "expiry_month" to "3"), sent.hints)
        assertTrue(sent.hints.keys.all { it in VaultNames.HINT_KEYS })
        // nothing on the wire is plaintext
        val wire = ClarityJson.encodeToString(NewCredential.serializer(), sent)
        assertFalse("4242424242424242" in wire)
        assertFalse("123\"" in wire)
        // each device opens its own copy
        val mine = VaultCrypto.open(sent.envelopes[0]) { keys.agree(it) }
        assertEquals(values, mine)
        // keys were registered first
        assertEquals(spki(keys.vault), api.registered.first().publicKey)
    }

    @Test fun loginHintsCarryOnlyTheUsername() {
        val request = credentialRequest()
        assertEquals(mapOf("username" to "dana"), credentialHints(request, mapOf("username" to "dana", "password" to "secret")))
        assertTrue(credentialHints(request, mapOf("password" to "secret")).isEmpty())
        assertTrue(credentialHints(request, mapOf("username" to "x".repeat(80))).isEmpty())
    }

    @Test fun invalidatedKeysStopASave() = runTest {
        val api = FakeVaultApi()
        val keys = FakeVaultKeys(status = VaultKeyStatus.INVALIDATED)
        val outcome = DefaultVaultRepository(api, keys).save(credentialRequest(), mapOf("username" to "a", "password" to "b"))
        assertIs<SaveOutcome.Failed>(outcome)
        assertTrue(api.saved.isEmpty())
    }

    @Test fun noRegisteredDeviceMeansNothingIsSent() = runTest {
        val api = FakeVaultApi()
        val outcome = DefaultVaultRepository(api, FakeVaultKeys()).save(credentialRequest(), mapOf("username" to "a", "password" to "b"))
        assertIs<SaveOutcome.Failed>(outcome)
        assertTrue(api.saved.isEmpty())
    }

    private fun meta() = CredentialMeta(CredentialId("c1"), "bank_login", CredentialKind.LOGIN, "bank.example.com", "x", loginFields, createdAt = T0)

    private companion object {
        val CredentialRequest1 = net.robog.clarity.shared.vault.CredentialRequestId("req1")
    }
}
