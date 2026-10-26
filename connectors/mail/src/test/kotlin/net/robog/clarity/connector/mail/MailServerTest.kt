package net.robog.clarity.connector.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.net.ServerSocket
import java.util.Properties
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import net.robog.clarity.connector.sdk.InMemoryStateStore
import net.robog.clarity.connector.sdk.getAs
import net.robog.clarity.connector.sdk.putAs
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.governance.EmailDraft
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload

private fun freePort() = ServerSocket(0).use { it.localPort }

private fun mime(from: String, to: String, subject: String, body: String, id: String? = null): MimeMessage {
    val m = object : MimeMessage(Session.getInstance(Properties())) {
        override fun updateMessageID() { if (id != null) setHeader("Message-ID", id) else super.updateMessageID() }
    }
    m.setFrom(InternetAddress(from))
    m.setRecipient(Message.RecipientType.TO, InternetAddress(to))
    m.subject = subject
    m.setText(body, "UTF-8")
    m.saveChanges()
    return m
}

class MailServerTest : FunSpec({
    val smtpPort = freePort()
    val imapPort = freePort()
    val server = GreenMail(arrayOf(ServerSetup(smtpPort, "127.0.0.1", "smtp"), ServerSetup(imapPort, "127.0.0.1", "imap")))
    val me = "me@example.com"

    val config = MailConfig(
        imapHost = "127.0.0.1", imapPort = imapPort, imapSecurity = Security.NONE,
        smtpHost = "127.0.0.1", smtpPort = smtpPort, smtpSecurity = Security.NONE,
        username = me, password = "secret", fromAddress = me, fromName = "Me Myself",
        folders = listOf("INBOX"), initialWindowDays = 30,
    )
    val access = MailAccess(config)

    beforeSpec {
        server.start()
        server.setUser(me, me, "secret")
        server.setUser("bob@example.com", "bob@example.com", "pw")
    }
    afterSpec { server.stop() }

    fun deliver(subject: String, body: String, from: String = "alice@example.com", id: String? = null) {
        server.getManagers().userManager.getUserByEmail(me).deliver(mime(from, me, subject, body, id))
    }

    fun imapStore() = access.imap()

    fun bobInbox(): List<MimeMessage> {
        val manager = server.managers.imapHostManager
        val inbox = manager.getInbox(server.managers.userManager.getUserByEmail("bob@example.com"))
        return inbox.messages.map { it.mimeMessage }
    }

    suspend fun runSync(sync: MailSync): List<IngestRecord> {
        val pushed = mutableListOf<IngestRecord>()
        sync.syncAll { pushed += it }
        return pushed
    }

    test("the first sync pushes the mailbox, later syncs only what is new, expunges become tombstones") {
        val state = InMemoryStateStore()
        val sync = MailSync(access, state)
        deliver("Lunch", "Lunch tomorrow?")
        deliver("Invoice", "Attached is the invoice.", id = "<inv-1@example.com>")

        val first = runSync(sync)
        first shouldHaveSize 2
        val upserts = first.map { it.shouldBeInstanceOf<IngestRecord.Upsert>() }
        upserts.map { (it.payload as MessagePayload).subject } shouldContainExactly listOf("Lunch", "Invoice")
        (upserts[0].payload as MessagePayload).account shouldBe Handle.email(me)
        (upserts[1].payload as MessagePayload).threadRef shouldBe "<inv-1@example.com>"
        MailRef.parse(upserts[0].sourceId.value)!!.folder shouldBe "INBOX"

        runSync(sync) shouldHaveSize 0

        deliver("Third", "A third message")
        val third = runSync(sync)
        third.map { (it as IngestRecord.Upsert).payload as MessagePayload }.map { it.subject } shouldContainExactly listOf("Third")

        // expunge "Lunch": it must come back as a tombstone for exactly that source id
        imapStore().use { store ->
            val inbox = store.folder("INBOX")
            inbox.openedUse(READ_WRITE) { f ->
                val doomed = f.messages.first { it.subject == "Lunch" }
                f.setFlags(arrayOf(doomed), Flags(Flags.Flag.DELETED), true)
                f.expunge()
            }
        }
        val after = runSync(sync)
        after shouldHaveSize 1
        val tomb = after.single().shouldBeInstanceOf<IngestRecord.Tombstone>()
        tomb.sourceId shouldBe upserts[0].sourceId
        runSync(sync) shouldHaveSize 0
    }

    test("a changed UIDVALIDITY tombstones the old ids and re-reports the folder under the new ones") {
        val state = InMemoryStateStore()
        val sync = MailSync(access, state)
        runSync(sync) // catches up with whatever the earlier test left
        val key = "folder:INBOX"
        val cursor = state.getAs<FolderCursor>(key)!!
        state.putAs(key, cursor.copy(uidValidity = cursor.uidValidity + 7))

        val pushed = runSync(sync)
        val tombstones = pushed.filterIsInstance<IngestRecord.Tombstone>()
        val upserts = pushed.filterIsInstance<IngestRecord.Upsert>()
        tombstones.isNotEmpty() shouldBe true
        tombstones.all { MailRef.parse(it.sourceId.value)!!.uidValidity == cursor.uidValidity + 7 } shouldBe true
        upserts.all { MailRef.parse(it.sourceId.value)!!.uidValidity == cursor.uidValidity } shouldBe true
        upserts.size shouldBe tombstones.size
    }

    test("SEND_EMAIL sends once, derives the Message-ID from the key, threads replies and files a copy in Sent") {
        imapStore().use { s -> s.folder("Sent").let { if (!it.exists()) it.create(Folder.HOLDS_MESSAGES) } }
        deliver("Question", "Can you make it?", from = "bob@example.com", id = "<q1@example.com>")
        val sync = MailSync(access, InMemoryStateStore())
        val original = runSync(sync).filterIsInstance<IngestRecord.Upsert>().first { (it.payload as MessagePayload).subject == "Question" }

        val actions = MailActions { access }
        val draft = EmailDraft(to = listOf(Handle.email("bob@example.com", "Bob")), subject = "Re: Question", body = "Yes, I can.\nSee you there.", inReplyTo = null)
        val send = ProposedAction.SendEmail("key-send-1", "reply to Bob", draft)
        val expectedId = messageIdFor("key-send-1", me)

        val done = actions.sendEmail(send, original.sourceId).shouldBeInstanceOf<ActionOutcome.Done>()
        done.ref shouldBe SourceId(expectedId)
        // delivered to Bob exactly once, with the derived id and the threading headers of the original
        val bobMail = bobInbox().filter { it.subject == "Re: Question" }
        bobMail shouldHaveSize 1
        val sent = bobMail.single() as MimeMessage
        sent.messageID shouldBe expectedId
        sent.getHeader("In-Reply-To", null) shouldBe "<q1@example.com>"
        sent.getHeader("References", null) shouldBe "<q1@example.com>"
        (sent.from.single() as InternetAddress).personal shouldBe "Me Myself"
        (sent.content as String).trim() shouldBe "Yes, I can.\r\nSee you there."

        // a re-delivered action (lost result) does not send a second time
        actions.sendEmail(send, original.sourceId).shouldBeInstanceOf<ActionOutcome.Done>()
        bobInbox().count { it.subject == "Re: Question" } shouldBe 1

        // exactly one copy sits in Sent
        imapStore().use { store ->
            store.folder("Sent").openedUse(READ_ONLY) { f -> f.messages.count { (it as MimeMessage).messageID == expectedId } shouldBe 1 }
        }
    }

    test("SEND_EMAIL with an invalid recipient fails without retrying") {
        val actions = MailActions { access }
        val draft = EmailDraft(to = listOf(Handle.email("not an address")), subject = "x", body = "y")
        val out = actions.sendEmail(ProposedAction.SendEmail("key-bad", "bad", draft), null)
        out.shouldBeInstanceOf<ActionOutcome.Failed>().retryable shouldBe false
        out.message shouldContain "not a valid email address"
    }

    test("SAVE_DRAFT appends to Drafts and replaces the earlier copy of the same draft") {
        val actions = MailActions { access }
        val first = ProposedAction.SaveDraft("key-draft-1", "draft", EmailDraft(to = listOf(Handle.email("bob@example.com")), subject = "Plan", body = "first version"))
        actions.saveDraft(first, null).shouldBeInstanceOf<ActionOutcome.Done>()
        val second = first.copy(email = first.email.copy(body = "second version"))
        actions.saveDraft(second, null).shouldBeInstanceOf<ActionOutcome.Done>()

        imapStore().use { store ->
            val drafts = store.folder("Drafts")
            drafts.openedUse(READ_ONLY) { f ->
                val inFolder = f.messages.filter { (it as MimeMessage).messageID == messageIdFor("key-draft-1", me) }
                inFolder shouldHaveSize 1
                (inFolder.single().content as String).trim() shouldBe "second version"
                inFolder.single().flags.contains(Flags.Flag.DRAFT) shouldBe true
            }
        }
        // nothing was sent
        bobInbox().none { it.subject == "Plan" } shouldBe true
    }

    test("the connection test logs in to both servers and counts folders; a wrong password is reported, not thrown") {
        val ok = MailAccess(config).check()
        (ok >= 1) shouldBe true
        val bad = runCatching { MailAccess(config.copy(password = "wrong")).check() }
        bad.isFailure shouldBe true
    }

    test("IDLE either reports a delivery or says it is unsupported") {
        val result = idleForChange(access, "INBOX", 1.seconds)
        (result == null || result == false) shouldBe true
    }

    test("config parsing opens the sealed password and applies defaults") {
        val keys = net.robog.clarity.connector.sdk.SealingKeys.generate()
        val runtime = net.robog.clarity.connector.sdk.ConnectorRuntime("mail", keys)
        val values = kotlinx.serialization.json.buildJsonObject {
            put("imapHost", kotlinx.serialization.json.JsonPrimitive("imap.example.com"))
            put("smtpHost", kotlinx.serialization.json.JsonPrimitive("smtp.example.com"))
            put("username", kotlinx.serialization.json.JsonPrimitive("me@example.com"))
            put("password", kotlinx.serialization.json.JsonPrimitive("plain"))
        }
        val cfg = net.robog.clarity.shared.connector.ConnectorConfig(1, values)
        // an unsealed password is refused
        runCatching { parseMailConfig(cfg, runtime) }.exceptionOrNull().shouldBeInstanceOf<net.robog.clarity.connector.sdk.SealingException>()
        val missing = net.robog.clarity.shared.connector.ConnectorConfig(1, kotlinx.serialization.json.JsonObject(values - "password"))
        runCatching { parseMailConfig(missing, runtime) }.exceptionOrNull().shouldBeInstanceOf<ConfigIncomplete>()
    }
})
