package net.robog.clarity.connector.mail

import com.google.crypto.tink.hybrid.internal.TestSealer
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.net.ServerSocket
import java.util.Properties
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.connector.sdk.ConnectorRuntime
import net.robog.clarity.connector.sdk.InMemoryStateStore
import net.robog.clarity.connector.sdk.SealingException
import net.robog.clarity.connector.sdk.SealingKeys
import net.robog.clarity.connector.sdk.getAs
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.connector.Sealing
import net.robog.clarity.shared.governance.EmailDraft
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload

private const val CONNECTOR = "mail"

private fun sealed(keys: SealingKeys, field: String, plain: String): JsonElement {
    val s = TestSealer.seal(keys.publicKey, CONNECTOR, field, plain)
    return buildJsonObject { put("type", "sealed"); put("algorithm", s.algorithm); put("ciphertext", s.ciphertext) }
}

private fun account(
    keys: SealingKeys, id: String, label: String, user: String, password: String, imapPort: Int, smtpPort: Int,
    sealedWith: String = id, extra: Map<String, JsonElement> = emptyMap(),
): JsonObject = JsonObject(
    mapOf(
        "id" to JsonPrimitive(id), "label" to JsonPrimitive(label),
        "imapHost" to JsonPrimitive("127.0.0.1"), "imapPort" to JsonPrimitive(imapPort), "imapSecurity" to JsonPrimitive("none"),
        "smtpHost" to JsonPrimitive("127.0.0.1"), "smtpPort" to JsonPrimitive(smtpPort), "smtpSecurity" to JsonPrimitive("none"),
        "username" to JsonPrimitive(user),
        "password" to sealed(keys, Sealing.itemField("accounts", sealedWith, "password"), password),
    ) + extra,
)

private fun accountsConfig(vararg accounts: JsonObject) = ConnectorConfig(1, JsonObject(mapOf("accounts" to JsonArray(accounts.toList()))))

private fun mailConfig(id: String, user: String, from: String = user) = MailConfig(
    imapHost = "h", imapPort = 1, imapSecurity = Security.NONE, smtpHost = "h", smtpPort = 2, smtpSecurity = Security.NONE,
    username = user, password = "pw", fromAddress = from, fromName = null, folders = listOf("INBOX"), initialWindowDays = 30, id = id,
)

private fun mime(from: String, to: String, subject: String): MimeMessage = MimeMessage(Session.getInstance(Properties())).apply {
    setFrom(InternetAddress(from)); setRecipient(Message.RecipientType.TO, InternetAddress(to)); this.subject = subject; setText("body", "UTF-8"); saveChanges()
}

class MailAccountsTest : FunSpec({
    val keys = SealingKeys.generate()
    val runtime = ConnectorRuntime(CONNECTOR, keys)

    context("config") {
        test("a legacy flat config is read as the account default, its password sealed under the plain field name") {
            val values = JsonObject(mapOf(
                "imapHost" to JsonPrimitive("imap.example.com"), "smtpHost" to JsonPrimitive("smtp.example.com"),
                "username" to JsonPrimitive("me@example.com"), "password" to sealed(keys, "password", "pw"),
            ))
            val entry = parseMailAccounts(ConnectorConfig(1, values), runtime).single()
            entry.id shouldBe "default"
            val cfg = entry.result.getOrThrow()
            cfg.password shouldBe "pw"
            cfg.imapPort shouldBe 993
            cfg.fromAddress shouldBe "me@example.com"
        }

        test("an item's password is bound to its id: a value sealed for account a1 does not open as a2") {
            val config = accountsConfig(
                account(keys, "a1", "Work", "work@example.com", "pw-work", 1, 2),
                account(keys, "a2", "Personal", "me@example.com", "pw-other", 1, 2, sealedWith = "a1"),
            )
            val entries = parseMailAccounts(config, runtime)
            entries.map { it.label } shouldContainExactly listOf("Work", "Personal")
            entries[0].result.getOrThrow().password shouldBe "pw-work"
            entries[1].result.exceptionOrNull().shouldBeInstanceOf<SealingException>()
        }

        test("an unusable account is named in its message and does not hide the others") {
            val noHost = account(keys, "a2", "Broken", "x@example.com", "pw", 1, 2).let { JsonObject(it - "imapHost") }
            val entries = parseMailAccounts(accountsConfig(account(keys, "a1", "Work", "w@example.com", "pw", 1, 2), noHost), runtime)
            entries[0].result.isSuccess shouldBe true
            entries[1].result.exceptionOrNull()!!.message shouldBe "Broken: IMAP host is not set"
        }

        test("the schema is a collection of accounts with presets") {
            val accounts = mailConfigSchema["properties"]!!.let { (it as JsonObject)["accounts"] as JsonObject }
            accounts["x-item-title"] shouldBe JsonPrimitive("label")
            (accounts["x-presets"] as JsonArray).map { ((it as JsonObject)["title"] as JsonPrimitive).content } shouldContainExactly listOf("iCloud", "Fastmail", "Gmail")
        }
    }

    context("source ids") {
        test("a source id carries the account and round-trips, including folders with slashes") {
            val ref = MailRef("Archive/2024", 7, 42, "k3j9x2p1")
            ref.sourceId shouldBe "k3j9x2p1/Archive/2024/7/42"
            MailRef.parse(ref.sourceId) shouldBe ref
            MailRef.parse(ref.sourceId, listOf("k3j9x2p1", "other")) shouldBe ref
        }

        test("the old three-part form is account default") {
            MailRef.parse("INBOX/7/42") shouldBe MailRef("INBOX", 7, 42, "default")
            // an old folder with a slash is told apart from a prefixed id by the configured account ids
            MailRef.parse("Archive/2024/7/42", listOf("default", "k3j9x2p1")) shouldBe MailRef("Archive/2024", 7, 42, "default")
        }
    }

    context("account selection") {
        val a = mailConfig("a1", "work@example.com")
        val b = mailConfig("a2", "b-login", from = "me@example.com")
        val all = listOf(a, b)

        test("the sender address picks the account, by fromAddress or username") {
            selectAccount(all, Handle.email("me@example.com"), null) shouldBe b
            selectAccount(all, Handle.email("B-LOGIN"), null) shouldBe b
        }

        test("else the account of the message being answered; else the first") {
            selectAccount(all, null, SourceId("a2/INBOX/1/5")) shouldBe b
            selectAccount(all, Handle.email("unknown@example.com"), SourceId("a2/INBOX/1/5")) shouldBe b
            selectAccount(all, Handle.email("work@example.com"), SourceId("a2/INBOX/1/5")) shouldBe a
            selectAccount(all, null, SourceId("gone/INBOX/1/5")) shouldBe a
            selectAccount(all, null, null) shouldBe a
            selectAccount(emptyList(), null, null) shouldBe null
        }
    }

    val smtp = ServerSocket(0).use { it.localPort }
    val imap = ServerSocket(0).use { it.localPort }
    val server = GreenMail(arrayOf(ServerSetup(smtp, "127.0.0.1", "smtp"), ServerSetup(imap, "127.0.0.1", "imap")))
    val work = "work@example.com"
    val home = "home@example.com"
    val bob = "bob@example.com"
    val config = accountsConfig(
        account(keys, "a1", "Work", work, "pw-work", imap, smtp, extra = mapOf("fromName" to JsonPrimitive("Work Me"))),
        account(keys, "a2", "Home", home, "pw-home", imap, smtp, extra = mapOf("fromName" to JsonPrimitive("Home Me"))),
    )

    beforeSpec {
        server.start()
        listOf(work to "pw-work", home to "pw-home", bob to "pw-bob").forEach { (user, pw) -> server.setUser(user, user, pw) }
    }
    afterSpec { server.stop() }

    context("against a mail server") {
        fun deliver(to: String, subject: String) = server.managers.userManager.getUserByEmail(to).deliver(mime("alice@example.com", to, subject))

        fun bobMail(subject: String) = server.managers.imapHostManager
            .getInbox(server.managers.userManager.getUserByEmail(bob)).messages.map { it.mimeMessage }.filter { it.subject == subject }

        test("each account syncs on its own, and records and cursors are keyed by account") {
            deliver(work, "For work"); deliver(home, "For home")
            val state = InMemoryStateStore()
            val pushed = mutableListOf<IngestRecord>()
            val reports = syncAccounts(parseMailAccounts(config, runtime), state, { pushed += it })

            reports.map { it.result.getOrThrow() } shouldContainExactly listOf(1, 1)
            val byAccount = pushed.filterIsInstance<IngestRecord.Upsert>().associateBy { (it.payload as MessagePayload).subject }
            byAccount.getValue("For work").sourceId.value shouldStartWith "a1/INBOX/"
            byAccount.getValue("For home").sourceId.value shouldStartWith "a2/INBOX/"
            (byAccount.getValue("For work").payload as MessagePayload).account shouldBe Handle.email(work)
            (byAccount.getValue("For home").payload as MessagePayload).account shouldBe Handle.email(home)
            (state.getAs<FolderCursor>("a1/folder:INBOX") != null && state.getAs<FolderCursor>("a2/folder:INBOX") != null) shouldBe true
        }

        test("one failing account does not stop another") {
            val broken = account(keys, "a3", "Broken", "nobody@example.com", "wrong", imap, smtp)
            val missing = JsonObject(account(keys, "a4", "Incomplete", "x@example.com", "pw", imap, smtp) - "smtpHost")
            val mixed = accountsConfig(broken, missing, config.values.let { (it["accounts"] as JsonArray)[0] as JsonObject })
            deliver(work, "After failures")
            val pushed = mutableListOf<IngestRecord>()
            val reports = syncAccounts(parseMailAccounts(mixed, runtime), InMemoryStateStore(), { pushed += it })

            reports.map { it.result.isSuccess } shouldContainExactly listOf(false, false, true)
            pushed.filterIsInstance<IngestRecord.Upsert>().map { (it.payload as MessagePayload).subject } shouldContainExactly listOf("For work", "After failures")
        }

        test("the connection test names every account; a rejected password comes first and makes it not ok") {
            val bad = account(keys, "a3", "Personal", "nobody@example.com", "wrong", imap, smtp)
            val result = testMailAccounts(runtime, accountsConfig(bad, config.values.let { (it["accounts"] as JsonArray)[0] as JsonObject }))
            result.ok shouldBe false
            result.message.startsWith("Personal: ") shouldBe true
            result.message.contains(" · Work: ") shouldBe true
            result.message.endsWith(" messages") shouldBe true

            val good = testMailAccounts(runtime, config)
            good.ok shouldBe true
            good.message.startsWith("Work: ") shouldBe true
            good.message.contains(" · Home: ") shouldBe true
        }

        test("SEND_EMAIL and SAVE_DRAFT use the account of the sender address, else of the replied-to message, else the first") {
            val actions = MailActions { usableMailAccounts(config, runtime).map(::MailAccess) }
            fun send(key: String, subject: String, from: String?) = ProposedAction.SendEmail(
                key, "send", EmailDraft(from = from?.let(Handle::email), to = listOf(Handle.email(bob)), subject = subject, body = "hi"),
            )
            fun fromOf(subject: String) = (bobMail(subject).single().from.single() as InternetAddress).address

            actions.sendEmail(send("k1", "by sender", home), null).shouldBeInstanceOf<ActionOutcome.Done>()
            fromOf("by sender") shouldBe home

            actions.sendEmail(send("k2", "by reply", null), SourceId("a2/INBOX/1/1")).shouldBeInstanceOf<ActionOutcome.Done>()
            fromOf("by reply") shouldBe home

            actions.sendEmail(send("k3", "by default", null), null).shouldBeInstanceOf<ActionOutcome.Done>()
            fromOf("by default") shouldBe work

            // a draft goes to the Drafts folder of the chosen account, and nothing is sent
            val draft = ProposedAction.SaveDraft("k4", "draft", EmailDraft(from = Handle.email(home), to = listOf(Handle.email(bob)), subject = "draft only", body = "x"))
            val done = actions.saveDraft(draft, null).shouldBeInstanceOf<ActionOutcome.Done>()
            done.ref!!.value shouldStartWith "a2/Drafts/"
            bobMail("draft only") shouldHaveSize 0
        }
    }
})
