package net.robog.clarity.app.ui

import com.google.crypto.tink.subtle.X25519
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.robog.clarity.app.connector.ConfigForms
import net.robog.clarity.app.connector.FieldError
import net.robog.clarity.app.connector.FieldKind
import net.robog.clarity.app.connector.FormValues
import net.robog.clarity.app.connector.ItemIds
import net.robog.clarity.app.connector.ItemValues
import net.robog.clarity.app.connector.PhoneConnectorKind
import net.robog.clarity.app.connector.PhonePermissions
import net.robog.clarity.app.connector.TinkConfigSealer
import net.robog.clarity.app.connector.accountTestLine
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.ConnectorsRepository
import net.robog.clarity.app.data.SaveConnectorOutcome
import net.robog.clarity.app.testutil.DAV_ACCOUNTS_SCHEMA
import net.robog.clarity.app.testutil.MAIL_ACCOUNTS_SCHEMA
import net.robog.clarity.app.testutil.withMain
import net.robog.clarity.app.ui.onboarding.ConnectorRow
import net.robog.clarity.app.ui.onboarding.ConnectorSetupViewModel
import net.robog.clarity.app.ui.onboarding.ConnectorsStepViewModel
import net.robog.clarity.app.ui.onboarding.NOT_RUNNING
import net.robog.clarity.app.ui.onboarding.TestState
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.noun.Noun
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.connector.ConnectorSummary
import net.robog.clarity.shared.connector.ConnectorTestResult
import net.robog.clarity.shared.connector.GrantState
import net.robog.clarity.shared.connector.SealedValue
import net.robog.clarity.shared.connector.Sealing

private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

private class Repo(var list: List<ConnectorSummary>) : ConnectorsRepository {
    var stored: ConnectorConfig? = null
    val saved = mutableListOf<Pair<ConnectorId, FormValues>>()
    val tested = mutableListOf<String>()
    override suspend fun list(): ApiResult<List<ConnectorSummary>> = ApiResult.Success(list)
    override suspend fun storedConfig(id: ConnectorId) = stored
    override suspend fun save(connector: ConnectorSummary, values: FormValues): SaveConnectorOutcome {
        saved += connector.id to values
        return SaveConnectorOutcome.Saved
    }
    override suspend fun test(id: ConnectorId): ApiResult<ConnectorTestResult> {
        tested += id.value
        return ApiResult.Success(ConnectorTestResult(true, summary = "ok"))
    }
}

private class Phone(var granted: Boolean = true) : PhonePermissions {
    override fun isGranted(kind: PhoneConnectorKind) = granted
}

private fun mailSummary(id: String = "mail", schema: JsonObject? = MAIL_ACCOUNTS_SCHEMA, granted: Boolean = false, lastTest: net.robog.clarity.shared.connector.ConnectorTestReport? = null) =
    ConnectorSummary(
        ConnectorId(id),
        schema?.let {
            ConnectorManifest(kind = "imap", displayName = "Mail", version = "1", produces = setOf(Noun.MESSAGE), configSchema = it, sealingKey = "k")
        },
        grants = mapOf(Noun.MESSAGE to if (granted) GrantState.GRANTED else GrantState.PENDING),
        lastSeenAt = null,
        lastTest = lastTest,
    )

private val STORED_SEALED = """{"type":"sealed","algorithm":"${Sealing.ALGORITHM}","ciphertext":"STORED-CIPHERTEXT"}"""

private fun storedMail() = obj(
    """
    {"accounts":[
      {"id":"k3j9x2p1","label":"Work","imapHost":"imap.work.example","imapPort":993,"imapSecurity":"ssl",
       "smtpHost":"smtp.work.example","smtpPort":465,"smtpSecurity":"ssl","username":"sam@work.example",
       "password":$STORED_SEALED,"folders":["INBOX","Receipts"]},
      {"id":"m4n8q7r2","label":"Personal","imapHost":"imap.mail.me.com","imapPort":993,"imapSecurity":"ssl",
       "smtpHost":"smtp.mail.me.com","smtpPort":587,"smtpSecurity":"starttls","username":"sam@icloud.com",
       "password":{"type":"sealed","algorithm":"${Sealing.ALGORITHM}","ciphertext":"OTHER-CIPHERTEXT"}}
    ]}
    """,
)

private val seal: (String, String) -> SealedValue = { field, plaintext -> SealedValue(Sealing.ALGORITHM, "SEALED[$field]") }

class SchemaSubsetTest {
    private val schema = obj(
        """
        {"type":"object","required":["name","url"],"properties":{
          "name":{"type":"string","title":"Display name","description":"Shown in lists","examples":["Sam"],"default":"Me"},
          "url":{"type":"string","format":"uri"},
          "link":{"type":"string","format":"url"},
          "mail":{"type":"string","format":"email"},
          "p1":{"type":"string","format":"password"},
          "p2":{"type":"string","writeOnly":true},
          "p3":{"type":"string","x-secret":true},
          "mode":{"type":"string","enum":["a","b"],"x-enum-titles":["Alpha","Beta"],"default":"b"},
          "plain":{"type":"string","enum":["x","y"]},
          "wrong":{"type":"string","enum":["x","y"],"x-enum-titles":["only one"]},
          "n":{"type":"integer","minimum":1,"maximum":9},
          "f":{"type":"number"},
          "on":{"type":"boolean","default":true},
          "tags":{"type":"array","items":{"type":"string"},"default":["INBOX"]},
          "things":{"type":"array","items":{"type":"object","properties":{"id":{"type":"string","readOnly":true},"a":{"type":"string"}}}},
          "numbers":{"type":"array","items":{"type":"integer"}},
          "deep":{"type":"object","properties":{"a":{"type":"string"}}}
        }}
        """,
    )
    private val form = ConfigForms.parse(schema)
    private val byKey = form.fields.associateBy { it.key }

    @Test fun everyKindInTheTableHasAField() {
        assertEquals(
            listOf("name", "url", "link", "mail", "p1", "p2", "p3", "mode", "plain", "wrong", "n", "f", "on", "tags", "things"),
            form.fields.map { it.key }, "in the order of properties; arrays of numbers and nested objects are outside the subset",
        )
        assertEquals(FieldKind.TEXT, byKey["name"]!!.kind)
        assertEquals(FieldKind.URL, byKey["url"]!!.kind)
        assertEquals(FieldKind.URL, byKey["link"]!!.kind)
        assertEquals(FieldKind.EMAIL, byKey["mail"]!!.kind)
        assertEquals(listOf(FieldKind.PASSWORD, FieldKind.PASSWORD, FieldKind.PASSWORD), listOf("p1", "p2", "p3").map { byKey[it]!!.kind })
        assertEquals(FieldKind.ENUM, byKey["mode"]!!.kind)
        assertEquals(FieldKind.NUMBER, byKey["n"]!!.kind)
        assertTrue(byKey["n"]!!.integer && !byKey["f"]!!.integer)
        assertEquals(FieldKind.BOOLEAN, byKey["on"]!!.kind)
        assertEquals(FieldKind.STRING_LIST, byKey["tags"]!!.kind)
        assertEquals(FieldKind.COLLECTION, byKey["things"]!!.kind)
    }

    @Test fun labelDescriptionPlaceholderDefaultAndRequired() {
        val name = byKey["name"]!!
        assertEquals("Display name", name.label)
        assertEquals("Shown in lists", name.description)
        assertEquals("Sam", name.placeholder)
        assertEquals("Me", name.default)
        assertTrue(name.required && byKey["url"]!!.required && !byKey["mail"]!!.required)
    }

    @Test fun dropdownsUseEnumTitlesWhenTheyMatchInLength() {
        assertEquals(listOf("a" to "Alpha", "b" to "Beta"), byKey["mode"]!!.options.map { it.value to it.title })
        assertEquals(listOf("x" to "x", "y" to "y"), byKey["plain"]!!.options.map { it.value to it.title })
        assertEquals(listOf("x" to "x", "y" to "y"), byKey["wrong"]!!.options.map { it.value to it.title }, "a title list of another length is ignored")
        assertEquals(FieldError.InvalidChoice, ConfigForms.validate(byKey["mode"]!!, "c"))
        assertNull(ConfigForms.validate(byKey["mode"]!!, "a"))
    }

    @Test fun defaultsFillTheInitialValues() {
        val v = form.initialValues()
        assertEquals("Me", v.text("name"))
        assertEquals("b", v.text("mode"))
        assertEquals("true", v.text("on"))
        assertEquals(listOf("INBOX"), v.list("tags"))
    }

    @Test fun readOnlyItemIdIsNotAFormField() {
        val item = byKey["things"]!!.collection!!.itemForm
        assertEquals(listOf("a"), item.fields.map { it.key })
    }

    @Test fun typedValuesAndLists() {
        val v = form.initialValues().withText("name", " Sam ").withText("n", "4").withText("on", "false")
            .withText("url", "https://x.example").withList("tags", listOf(" INBOX ", "", "Archive"))
        val out = form.toConfigValues(v, seal)
        assertEquals("Sam", out["name"]!!.jsonPrimitive.content)
        assertEquals(4L, out["n"]!!.jsonPrimitive.content.toLong())
        assertEquals("false", out["on"]!!.jsonPrimitive.content)
        assertEquals(listOf("INBOX", "Archive"), out["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue("p1" !in out && "things" !in out)
    }

    @Test fun mailAndDavSchemasParse() {
        val mail = ConfigForms.parse(MAIL_ACCOUNTS_SCHEMA)
        val accounts = mail.fields.single()
        assertEquals(FieldKind.COLLECTION, accounts.kind)
        assertTrue(accounts.required)
        val spec = accounts.collection!!
        assertEquals("label", spec.titleKey)
        assertEquals("username", spec.subtitleKey)
        assertEquals(1, spec.minItems)
        assertEquals(listOf("iCloud", "Fastmail", "Gmail"), spec.presets.map { it.title })
        val items = spec.itemForm.fields.associateBy { it.key }
        assertEquals(
            listOf(
                "label", "imapHost", "imapPort", "imapSecurity", "smtpHost", "smtpPort", "smtpSecurity", "username", "password",
                "fromAddress", "fromName", "folders", "history",
            ),
            spec.itemForm.fields.map { it.key },
        )
        assertEquals(FieldKind.ENUM, items["imapSecurity"]!!.kind)
        assertEquals(listOf("SSL/TLS", "STARTTLS", "None"), items["imapSecurity"]!!.options.map { it.title })
        assertEquals(FieldKind.STRING_LIST, items["folders"]!!.kind)
        assertEquals(listOf("INBOX"), items["folders"]!!.defaultList)
        val history = items["history"]!!
        assertEquals(FieldKind.ENUM, history.kind)
        assertEquals("Mail history", history.label)
        assertEquals(listOf("30 days", "90 days", "1 year", "All time"), history.options.map { it.title })
        assertEquals("30d", history.defaultChoice)
        // a new account starts at the default; an account stored before the field existed has no value (the dropdown shows the default)
        assertEquals("30d", spec.itemForm.initialValues().text("history"))
        assertEquals("", spec.itemForm.valuesFromStored(JsonObject(mapOf("label" to JsonPrimitive("Work")))).text("history"))
        assertNull(items["imapPort"]!!.defaultChoice)
        assertEquals(FieldKind.PASSWORD, items["password"]!!.kind)
        assertEquals("imap.example.com", items["imapHost"]!!.placeholder)

        val dav = ConfigForms.parse(DAV_ACCOUNTS_SCHEMA).fields.single().collection!!
        val d = dav.itemForm.fields.associateBy { it.key }
        assertFalse(d["baseUrl"]!!.required, "baseUrl is not required: a preset can set caldavUrl and carddavUrl instead")
        assertEquals(FieldKind.STRING_LIST, d["calendars"]!!.kind)
        assertEquals(FieldKind.STRING_LIST, d["webdavFolders"]!!.kind)
        assertEquals(listOf("iCloud", "Fastmail", "Nextcloud"), dav.presets.map { it.title })
    }
}

class ItemIdTest {
    @Test fun idsAreTwelveLowercaseAlphanumerics() {
        repeat(50) {
            val id = ItemIds.generate()
            assertEquals(12, id.length)
            assertTrue(ItemIds.isValid(id), id)
            assertTrue(Regex("[a-z0-9]{8,16}").matches(id))
        }
    }

    @Test fun anIdIsNeverReusedInACollection() {
        val first = ItemIds.generate(random = Random(7))
        val second = ItemIds.generate(taken = setOf(first), random = Random(7))
        assertNotEquals(first, second)
    }

    @Test fun validityRules() {
        assertTrue(ItemIds.isValid("k3j9x2p1"))
        assertFalse(ItemIds.isValid("Has-Caps"))
        assertFalse(ItemIds.isValid("short"))
    }
}

class CollectionStateTest {
    private val itemForm = ConfigForms.parse(MAIL_ACCOUNTS_SCHEMA).fields.single().collection!!.itemForm
    private fun item(id: String, label: String) = ItemValues(id, itemForm.initialValues().withText("label", label))

    @Test fun addEditAndRemove() {
        var v = FormValues()
        v = v.withItem("accounts", item("aaaaaaaa", "Work"))
        v = v.withItem("accounts", item("bbbbbbbb", "Personal"))
        assertEquals(listOf("Work", "Personal"), v.items("accounts").map { it.values.text("label") })
        v = v.withItem("accounts", item("aaaaaaaa", "Office"))
        assertEquals(listOf("Office", "Personal"), v.items("accounts").map { it.values.text("label") }, "same id replaces in place")
        assertEquals(listOf("aaaaaaaa", "bbbbbbbb"), v.items("accounts").map { it.id })
        v = v.withoutItem("accounts", "aaaaaaaa")
        assertEquals(listOf("bbbbbbbb"), v.items("accounts").map { it.id })
    }

    @Test fun minItemsIsEnforced() {
        val form = ConfigForms.parse(MAIL_ACCOUNTS_SCHEMA)
        assertEquals(FieldError.TooFewItems(1), form.errors(FormValues())["accounts"])
    }
}

class ItemSealingTest {
    private val form = ConfigForms.parse(MAIL_ACCOUNTS_SCHEMA)
    private val itemForm = form.fields.single().collection!!.itemForm

    @Test fun itemSecretsAreSealedUnderTheItemField() {
        val v = FormValues().withItem(
            "accounts",
            ItemValues("k3j9x2p1", complete().withText("password", "hunter2")),
        )
        val fields = mutableListOf<Pair<String, String>>()
        val out = form.toConfigValues(v) { field, plaintext ->
            fields += field to plaintext
            SealedValue(Sealing.ALGORITHM, "CT")
        }
        assertEquals(listOf("accounts/k3j9x2p1/password" to "hunter2"), fields)
        assertEquals(Sealing.itemField("accounts", "k3j9x2p1", "password"), fields.single().first)
        val account = out["accounts"]!!.jsonArray.single().jsonObject
        assertEquals("k3j9x2p1", account["id"]!!.jsonPrimitive.content)
        assertEquals("sealed", account["password"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue("hunter2" !in out.toString())
    }

    @Test fun theInfoStringIsPerConnectorAndPerItem() {
        val field = Sealing.itemField("accounts", "k3j9x2p1", "password")
        assertEquals("accounts/k3j9x2p1/password", field)
        assertEquals(
            "clarity-connector-config-v1:mail:accounts/k3j9x2p1/password",
            String(TinkConfigSealer.info(ConnectorId("mail"), field)),
        )
        assertNotEquals(
            String(TinkConfigSealer.info(ConnectorId("mail"), field)),
            String(TinkConfigSealer.info(ConnectorId("mail"), Sealing.itemField("accounts", "m4n8q7r2", "password"))),
        )
    }

    @Test fun twoAccountsGetTwoSealedPasswords() {
        val v = FormValues()
            .withItem("accounts", ItemValues("aaaaaaaa", complete().withText("password", "one")))
            .withItem("accounts", ItemValues("bbbbbbbb", complete().withText("password", "two")))
        val out = form.toConfigValues(v, seal)
        val ciphertexts = out["accounts"]!!.jsonArray.map { it.jsonObject["password"]!!.jsonObject["ciphertext"]!!.jsonPrimitive.content }
        assertEquals(listOf("SEALED[accounts/aaaaaaaa/password]", "SEALED[accounts/bbbbbbbb/password]"), ciphertexts)
    }

    @Test fun realSealingRoundTripsWithTheItemInfo() {
        val priv = X25519.generatePrivateKey()
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(X25519.publicFromPrivate(priv))
        val sealed = TinkConfigSealer().seal(ConnectorId("mail"), Sealing.itemField("accounts", "k3j9x2p1", "password"), key, "pw")
        assertEquals(Sealing.ALGORITHM, sealed.algorithm)
    }

    private fun complete(): FormValues = itemForm.initialValues()
        .withText("label", "Work").withText("imapHost", "imap.x").withText("smtpHost", "smtp.x").withText("username", "sam")
}

class KeepStoredSecretsTest {
    private val form = ConfigForms.parse(MAIL_ACCOUNTS_SCHEMA)

    @Test fun storedSecretsAreLoadedPerItemAndNeverShown() {
        val v = form.valuesFromStored(storedMail())
        val work = v.items("accounts").first()
        assertEquals("k3j9x2p1", work.id)
        assertEquals(listOf("INBOX", "Receipts"), work.values.list("folders"))
        assertEquals("", work.values.text("password"))
        assertEquals(setOf("password"), work.values.storedSecrets.keys)
    }

    @Test fun untouchedSecretsAreSentBackUnchanged() {
        val stored = storedMail()
        val v = form.valuesFromStored(stored)
        // Edit one field of one account; do not retype any password.
        val edited = v.items("accounts").map {
            if (it.id == "k3j9x2p1") it.copy(values = it.values.withText("label", "Office")) else it
        }
        val out = form.toConfigValues(v.withItems("accounts", edited)) { _, _ -> error("nothing was typed, so nothing is sealed") }
        val accounts = out["accounts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(stored["accounts"]!!.jsonArray[0].jsonObject["password"], accounts[0]["password"])
        assertEquals(stored["accounts"]!!.jsonArray[1].jsonObject["password"], accounts[1]["password"])
        assertEquals("Office", accounts[0]["label"]!!.jsonPrimitive.content)
    }

    @Test fun aTypedPasswordReplacesOnlyThatAccountsSecret() {
        val v = form.valuesFromStored(storedMail())
        val edited = v.items("accounts").map {
            if (it.id == "m4n8q7r2") it.copy(values = it.values.withText("password", "new-app-password")) else it
        }
        val sealedFields = mutableListOf<String>()
        val out = form.toConfigValues(v.withItems("accounts", edited)) { field, _ ->
            sealedFields += field
            SealedValue(Sealing.ALGORITHM, "NEW")
        }
        assertEquals(listOf("accounts/m4n8q7r2/password"), sealedFields)
        val accounts = out["accounts"]!!.jsonArray.map { it.jsonObject }
        assertEquals("STORED-CIPHERTEXT", accounts[0]["password"]!!.jsonObject["ciphertext"]!!.jsonPrimitive.content)
        assertEquals("NEW", accounts[1]["password"]!!.jsonObject["ciphertext"]!!.jsonPrimitive.content)
    }

    @Test fun aStoredSecretSatisfiesRequired() {
        val v = form.valuesFromStored(storedMail())
        assertTrue(form.errors(v).isEmpty(), form.errors(v).toString())
        // Without the stored secret the account is incomplete.
        val bare = v.items("accounts").map { it.copy(values = it.values.copy(storedSecrets = emptyMap())) }
        assertEquals(FieldError.ItemInvalid, form.errors(v.withItems("accounts", bare))["accounts"])
        val itemForm = form.fields.single().collection!!.itemForm
        assertEquals(FieldError.Required, itemForm.errors(bare.first().values)["password"])
    }

    @Test fun removingAnAccountKeepsTheOthersSecretsValid() {
        val v = form.valuesFromStored(storedMail()).withoutItem("accounts", "k3j9x2p1")
        val out = form.toConfigValues(v) { _, _ -> error("not sealed") }
        val only = out["accounts"]!!.jsonArray.single().jsonObject
        assertEquals("m4n8q7r2", only["id"]!!.jsonPrimitive.content)
        assertEquals("OTHER-CIPHERTEXT", only["password"]!!.jsonObject["ciphertext"]!!.jsonPrimitive.content)
    }

    @Test fun anItemWithoutAnIdGetsOneAndLosesSecretsThatCouldNotOpen() {
        val v = form.valuesFromStored(
            obj("""{"accounts":[{"label":"Old","password":$STORED_SEALED}]}"""),
        )
        val item = v.items("accounts").single()
        assertTrue(ItemIds.isValid(item.id))
        assertTrue(item.values.storedSecrets.isEmpty())
    }
}

class AccountFlowTest {
    private fun vm(repo: Repo = Repo(listOf(mailSummary())), seed: Int = 1) =
        ConnectorSetupViewModel(repo, Phone()).also { it.useRandom(Random(seed)) }

    @Test fun addingAccountOffersPresetsThenOpensAPrefilledForm() = withMain {
        val vm = vm()
        vm.open(ConnectorRow.Server(mailSummary()), null)
        vm.startAdd("accounts")
        assertEquals("accounts", vm.state.value.choosing)
        assertNull(vm.state.value.editor)

        val spec = vm.state.value.collectionField("accounts")!!.collection!!
        vm.choosePreset("accounts", spec.presets.first { it.title == "iCloud" })
        val editor = vm.state.value.editor!!
        assertNull(vm.state.value.choosing)
        assertTrue(editor.isNew)
        assertEquals("imap.mail.me.com", editor.values.text("imapHost"))
        assertEquals("smtp.mail.me.com", editor.values.text("smtpHost"))
        assertEquals("587", editor.values.text("smtpPort"))
        assertEquals("starttls", editor.values.text("smtpSecurity"))
        assertEquals(listOf("INBOX"), editor.values.list("folders"), "defaults the preset does not set stay")
        assertEquals("Use an app-specific password from account.apple.com", editor.hint)
        assertTrue(ItemIds.isValid(editor.id))
    }

    @Test fun otherStartsFromTheDefaultsOnly() = withMain {
        val vm = vm()
        vm.open(ConnectorRow.Server(mailSummary()), null)
        vm.startAdd("accounts")
        vm.choosePreset("accounts", null)
        val editor = vm.state.value.editor!!
        assertEquals("", editor.values.text("imapHost"))
        assertEquals("993", editor.values.text("imapPort"))
        assertNull(editor.hint)
    }

    @Test fun aCollectionWithoutPresetsGoesStraightToTheForm() = withMain {
        val repo = Repo(listOf(mailSummary(schema = DAV_ACCOUNTS_SCHEMA.let { s ->
            // Same schema without x-presets.
            val coll = s["properties"]!!.jsonObject["accounts"]!!.jsonObject
            JsonObject(s + ("properties" to JsonObject(mapOf("accounts" to JsonObject(coll - "x-presets")))))
        })))
        val vm = vm(repo)
        vm.open(ConnectorRow.Server(repo.list.first()), null)
        vm.startAdd("accounts")
        assertNull(vm.state.value.choosing)
        assertTrue(vm.state.value.editor!!.isNew)
    }

    @Test fun anIncompleteAccountStaysInTheEditorWithErrors() = withMain {
        val vm = vm()
        vm.open(ConnectorRow.Server(mailSummary()), null)
        vm.startAdd("accounts"); vm.choosePreset("accounts", null)
        vm.saveItem()
        val s = vm.state.value
        assertTrue(s.editor!!.showErrors)
        assertEquals(FieldError.Required, s.editorErrors["label"])
        assertEquals(FieldError.Required, s.editorErrors["password"])
        assertTrue(s.values.items("accounts").isEmpty())
    }

    @Test fun addEditRemoveThenSaveSendsTheWholeConfig() = withMain {
        val repo = Repo(listOf(mailSummary()))
        val vm = vm(repo)
        vm.open(ConnectorRow.Server(repo.list.first()), null)

        fun addAccount(label: String, password: String) {
            vm.startAdd("accounts"); vm.choosePreset("accounts", null)
            vm.onItemValueChanged("label", label)
            vm.onItemValueChanged("imapHost", "imap.$label.example")
            vm.onItemValueChanged("smtpHost", "smtp.$label.example")
            vm.onItemValueChanged("username", "sam@$label.example")
            vm.onItemValueChanged("password", password)
            vm.onItemListChanged("folders", listOf("INBOX", "Receipts"))
            vm.saveItem()
            assertNull(vm.state.value.editor)
        }
        addAccount("work", "pw1")
        addAccount("home", "pw2")
        val ids = vm.state.value.values.items("accounts").map { it.id }
        assertEquals(2, ids.toSet().size)

        vm.editItem("accounts", ids[0])
        assertFalse(vm.state.value.editor!!.isNew)
        vm.onItemValueChanged("label", "office")
        vm.saveItem()
        assertEquals(listOf("office", "home"), vm.state.value.values.items("accounts").map { it.values.text("label") })
        assertEquals(ids, vm.state.value.values.items("accounts").map { it.id }, "editing keeps the id")

        vm.removeItem("accounts", ids[1])
        assertEquals(listOf(ids[0]), vm.state.value.values.items("accounts").map { it.id })

        assertTrue(repo.saved.isEmpty(), "nothing is sent until the connector is saved")
        vm.save()
        assertEquals(1, repo.saved.size)
        assertEquals(listOf("office"), repo.saved.single().second.items("accounts").map { it.values.text("label") })
        assertTrue(vm.state.value.saved)
    }

    @Test fun cancellingTheEditorDiscardsTheChange() = withMain {
        val repo = Repo(listOf(mailSummary()))
        repo.stored = ConnectorConfig(2, storedMail())
        val vm = vm(repo)
        vm.open(ConnectorRow.Server(repo.list.first()), null)
        vm.editItem("accounts", "k3j9x2p1")
        vm.onItemValueChanged("label", "Changed")
        vm.cancelItem()
        assertNull(vm.state.value.editor)
        assertEquals("Work", vm.state.value.values.items("accounts").first().values.text("label"))
    }

    @Test fun savingWithNoAccountShowsTheMinimumError() = withMain {
        val repo = Repo(listOf(mailSummary()))
        val vm = vm(repo)
        vm.open(ConnectorRow.Server(repo.list.first()), null)
        vm.save()
        assertEquals(FieldError.TooFewItems(1), vm.state.value.errors["accounts"])
        assertTrue(repo.saved.isEmpty())
    }

    @Test fun storedAccountsLoadAndASaveKeepsTheirSecrets() = withMain {
        val repo = Repo(listOf(mailSummary(granted = true)))
        repo.stored = ConnectorConfig(2, storedMail())
        val vm = vm(repo)
        vm.open(ConnectorRow.Server(repo.list.first()), null)
        assertEquals(listOf("Work", "Personal"), vm.state.value.values.items("accounts").map { it.values.text("label") })
        assertTrue(vm.state.value.canSave, "stored passwords satisfy required")
        vm.save()
        val sent = repo.saved.single().second
        assertTrue(sent.items("accounts").all { it.values.storedSecrets.containsKey("password") })
    }

    @Test fun accountLinesComeFromTheTestText() {
        val text = "Work: 4,218 messages · Personal: 312 messages"
        assertEquals("4,218 messages", accountTestLine(text, "Work"))
        assertEquals("312 messages", accountTestLine(text, "Personal"))
        assertNull(accountTestLine(text, "Other"))
        assertEquals("password rejected", accountTestLine("Personal: password rejected", "Personal"))
        assertNull(accountTestLine(null, "Work"))
        assertNull(accountTestLine("Workshop: fine", "Work"))
    }

    @Test fun accountCardsShowTheirOwnTestLine() = withMain {
        val vm = vm()
        vm.open(
            ConnectorRow.Server(mailSummary()), failedReason = "Personal: password rejected",
            test = TestState.Failed("Personal: password rejected"),
        )
        val s = vm.state.value
        assertEquals("password rejected", s.testLineFor("Personal")!!.text)
        assertFalse(s.testLineFor("Personal")!!.ok)
        assertNull(s.testLineFor("Work"))
    }
}

class NotRunningTest {
    private val notRunning = mailSummary(id = "dav", schema = null)
    private val ready = mailSummary(id = "files", granted = true, schema = obj("""{"properties":{"host":{"type":"string"}}}"""))

    @Test fun aConnectorWithoutAManifestIsShownAsNotRunning() = withMain {
        val vm = ConnectorsStepViewModel(Repo(listOf(notRunning, ready)), Phone(true))
        vm.refresh()
        val item = vm.state.value.items.first { it.row.key == "dav" }
        assertTrue(item.notRunning)
        assertEquals(NOT_RUNNING, item.caption)
        assertEquals("not running", item.status)
        assertEquals(NOT_RUNNING, item.row.proto)
    }

    @Test fun itDoesNotBlockSyncAllOrContinue() = withMain {
        val repo = Repo(listOf(notRunning, ready))
        val vm = ConnectorsStepViewModel(repo, Phone(true))
        vm.refresh()
        assertTrue(vm.state.value.canSync, "the not-running connector is not required to be set up")
        vm.syncAll()
        assertEquals(listOf("files"), repo.tested, "it is not tested")
        assertTrue(vm.state.value.allSynced, "continue is offered")
        assertEquals("all 2 synced · 1 not running", vm.state.value.summaryLine)
    }

    @Test fun deviceConnectorsAreHiddenInOnboarding() = withMain {
        val device = mailSummary(id = "device-abc", schema = null)
        val vm = ConnectorsStepViewModel(Repo(listOf(device, ready)), Phone(true))
        vm.refresh()
        assertEquals(listOf("files", "phone-messages"), vm.state.value.items.map { it.row.key })
    }

    @Test fun itsScreenExplainsInsteadOfShowingAnEmptyForm() = withMain {
        val vm = ConnectorSetupViewModel(Repo(listOf(notRunning)), Phone())
        vm.open(ConnectorRow.Server(notRunning), null)
        val s = vm.state.value
        assertTrue(s.notRunning)
        assertTrue(s.form.fields.isEmpty())
        assertFalse("compose" in s.blurb || "`" in s.blurb, "the person sees no deployment jargon: ${s.blurb}")
        assertTrue(s.adminHint.orEmpty().contains("“dav”"), "the admin detail is kept behind How to fix")
        assertFalse(s.canSave)
    }

    @Test fun checkingAgainOpensTheFormOnceItsManifestArrives() = withMain {
        val repo = Repo(listOf(notRunning))
        val vm = ConnectorSetupViewModel(repo, Phone())
        vm.open(ConnectorRow.Server(notRunning), null)
        repo.list = listOf(mailSummary(id = "dav"))
        vm.recheck()
        assertFalse(vm.state.value.notRunning)
        assertEquals("accounts", vm.state.value.form.fields.single().key)
    }

    @Test fun openingFromSettingsLooksTheConnectorUpById() = withMain {
        val repo = Repo(listOf(mailSummary(id = "mail", granted = true)))
        repo.stored = ConnectorConfig(1, storedMail())
        val vm = ConnectorSetupViewModel(repo, Phone())
        vm.openById("mail")
        assertEquals(2, vm.state.value.values.items("accounts").size)
        vm.openById("nope")
        assertTrue(vm.state.value.missing)
    }
}
