package net.robog.clarity.connector.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.util.Properties
import net.robog.clarity.connector.sdk.FetchOutcome
import net.robog.clarity.connector.sdk.InMemoryStateStore
import net.robog.clarity.connector.sdk.readCapped
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.fetch.FetchFailure
import net.robog.clarity.shared.fetch.FetchRequest
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.noun.AttachmentMeta
import net.robog.clarity.shared.noun.MessagePayload

private fun freePort() = ServerSocket(0).use { it.localPort }

private fun withFile(bytes: ByteArray): MimeMessage {
    val m = MimeMessage(Session.getInstance(Properties()))
    m.setFrom(InternetAddress("sam@example.com"))
    m.setRecipient(Message.RecipientType.TO, InternetAddress("me@example.com"))
    m.subject = "Report"
    val text = MimeBodyPart().apply { setText("Report attached", "UTF-8") }
    val file = MimeBodyPart().apply {
        dataHandler = jakarta.activation.DataHandler(ByteArrayDataSource(bytes, "application/pdf"))
        fileName = "report.pdf"
        disposition = "attachment"
    }
    m.setContent(MimeMultipart("mixed", text, file))
    m.saveChanges()
    return m
}

class MailFetchTest : FunSpec({
    val smtpPort = freePort()
    val imapPort = freePort()
    val server = GreenMail(arrayOf(ServerSetup(smtpPort, "127.0.0.1", "smtp"), ServerSetup(imapPort, "127.0.0.1", "imap")))
    val me = "me@example.com"
    val config = MailConfig(
        imapHost = "127.0.0.1", imapPort = imapPort, imapSecurity = Security.NONE,
        smtpHost = "127.0.0.1", smtpPort = smtpPort, smtpSecurity = Security.NONE,
        username = me, password = "secret", fromAddress = me, fromName = null,
        folders = listOf("INBOX"), historyDays = 30,
    )
    val access = MailAccess(config)
    val content = ByteArray(5_000) { (it % 251).toByte() }

    beforeSpec {
        server.start()
        server.setUser(me, me, "secret")
        server.managers.userManager.getUserByEmail(me).deliver(withFile(content))
    }
    afterSpec { server.stop() }

    suspend fun reported(): IngestRecord.Upsert {
        val pushed = mutableListOf<IngestRecord>()
        MailSync(access, InMemoryStateStore()).syncAll { pushed += it }
        return pushed.single().shouldBeInstanceOf<IngestRecord.Upsert>()
    }

    test("sync reports the attachment and the fetch returns its exact bytes") {
        val record = reported()
        val payload = record.payload as MessagePayload
        payload.attachments.map { it.filename to it.part } shouldBe listOf("report.pdf" to "2")
        record.sourceVersion shouldBe SOURCE_VERSION

        val fetches = MailFetches({ listOf(access) }, maxBytes = 1_000_000)
        val found = fetches.fetch(FetchRequest("f1", record.sourceId, "2", 1_000_000)).shouldBeInstanceOf<FetchOutcome.Found>()
        found.bytes.toList() shouldBe content.toList()
        found.filename shouldBe "report.pdf"
        found.mimeType shouldBe "application/pdf"
    }

    test("an unknown part, a malformed one and an unknown message are not found") {
        val id = reported().sourceId
        val fetches = MailFetches({ listOf(access) }, maxBytes = 1_000_000)
        for (part in listOf("9", "1.1", "x", "0")) {
            fetches.fetch(FetchRequest("f", id, part, 1_000_000)).shouldBeInstanceOf<FetchOutcome.Failed>().code shouldBe FetchFailure.Code.NOT_FOUND
        }
        val ref = MailRef.parse(id.value)!!
        val gone = SourceId(ref.copy(uid = ref.uid + 500).sourceId)
        fetches.fetch(FetchRequest("f", gone, "2", 1_000_000)).shouldBeInstanceOf<FetchOutcome.Failed>().code shouldBe FetchFailure.Code.NOT_FOUND
        fetches.fetch(FetchRequest("f", SourceId("nonsense"), "2", 1_000_000)).shouldBeInstanceOf<FetchOutcome.Failed>().code shouldBe FetchFailure.Code.NOT_FOUND
    }

    test("a part over the limit is refused and never returned") {
        val id = reported().sourceId
        val small = MailFetches({ listOf(access) }, maxBytes = 1_000_000)
        val failed = small.fetch(FetchRequest("f", id, "2", 1_000)).shouldBeInstanceOf<FetchOutcome.Failed>()
        failed.code shouldBe FetchFailure.Code.TOO_LARGE
        // the connector's own ceiling wins over a bigger request
        val strict = MailFetches({ listOf(access) }, maxBytes = 2_000)
        strict.fetch(FetchRequest("f", id, "2", 1_000_000)).shouldBeInstanceOf<FetchOutcome.Failed>().code shouldBe FetchFailure.Code.TOO_LARGE
    }

    test("listing the parts works for a message that was ingested without metadata") {
        val id = reported().sourceId
        val fetches = MailFetches({ listOf(access) }, maxBytes = 1_000_000)
        val found = fetches.fetch(FetchRequest("f", id, FetchRequest.LIST_PARTS, 1_000_000)).shouldBeInstanceOf<FetchOutcome.Found>()
        val list = ClarityJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(AttachmentMeta.serializer()), found.bytes.decodeToString())
        list.single().filename shouldBe "report.pdf"
        found.mimeType shouldBe "application/json"
    }

    test("readCapped stops at the cap") {
        ByteArrayInputStream(ByteArray(10)).readCapped(10)!!.size shouldBe 10
        ByteArrayInputStream(ByteArray(11)).readCapped(10) shouldBe null
        ByteArrayInputStream(ByteArray(0)).readCapped(0)!!.size shouldBe 0
        val counting = object : java.io.InputStream() {
            var served = 0L
            override fun read(): Int { served++; return 1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { val n = minOf(len, 1024); served += n; return n }
        }
        counting.readCapped(100_000) shouldBe null
        (counting.served < 130_000) shouldBe true
    }
})
