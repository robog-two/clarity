package net.robog.clarity.connector.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import jakarta.mail.Flags
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import java.net.ServerSocket
import java.util.Date
import java.util.Properties
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import net.robog.clarity.connector.sdk.InMemoryStateStore
import net.robog.clarity.connector.sdk.StateStore
import net.robog.clarity.connector.sdk.getAs
import net.robog.clarity.connector.sdk.putAs
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.noun.MessagePayload

private fun port() = ServerSocket(0).use { it.localPort }

private val IngestRecord.Upsert.subject get() = (payload as MessagePayload).subject

class MailBackfillTest : FunSpec({
    val imapPort = port()
    val server = GreenMail(arrayOf(ServerSetup(port(), "127.0.0.1", "smtp"), ServerSetup(imapPort, "127.0.0.1", "imap")))
    beforeSpec { server.start() }
    afterSpec { server.stop() }

    var users = 0

    /** A fresh mailbox of its own and the config that reads it. */
    fun mailbox(): MailConfig {
        val me = "user${++users}@example.com"
        server.setUser(me, me, "secret")
        return MailConfig(
            imapHost = "127.0.0.1", imapPort = imapPort, imapSecurity = Security.NONE,
            smtpHost = "127.0.0.1", smtpPort = 1, smtpSecurity = Security.NONE,
            username = me, password = "secret", fromAddress = me, fromName = null,
            folders = listOf("INBOX"), historyDays = 30,
        )
    }

    /** Puts a message received [ageDays] ago into the INBOX of [config]. */
    fun receive(config: MailConfig, subject: String, ageDays: Int) {
        val at = Date((Clock.System.now() - ageDays.days).toEpochMilliseconds())
        val m = MimeMessage(Session.getInstance(Properties())).apply {
            setFrom(InternetAddress("alice@example.com"))
            setRecipient(Message.RecipientType.TO, InternetAddress(config.username))
            this.subject = subject
            sentDate = at
            setText("about $subject", "UTF-8")
            saveChanges()
        }
        val user = server.managers.userManager.getUserByEmail(config.username)
        server.managers.imapHostManager.getInbox(user).appendMessage(m, Flags(), at)
    }

    fun sync(config: MailConfig, state: StateStore, budget: Duration = Duration.INFINITE) =
        MailSync(MailAccess(config), state, backfillBudget = budget)

    suspend fun MailSync.run(): List<IngestRecord.Upsert> {
        val pushed = mutableListOf<IngestRecord>()
        syncAll { pushed += it }
        pushed.filterIsInstance<IngestRecord.Tombstone>().shouldBeEmpty()
        return pushed.filterIsInstance<IngestRecord.Upsert>()
    }


    test("the first import takes the history window, marked as history; new mail afterwards is not") {
        val config = mailbox()
        receive(config, "10 days", 10)
        receive(config, "60 days", 60)
        val state = InMemoryStateStore()

        val first = sync(config, state).run()
        first.map { it.subject } shouldContainExactly listOf("10 days")
        first.all { it.history } shouldBe true

        receive(config, "today", 0)
        val next = sync(config, state).run()
        next.map { it.subject } shouldContainExactly listOf("today")
        next.single().history shouldBe false
        sync(config, state).run().shouldBeEmpty()
    }

    test("a longer history fetches the gap, newest first, as history; a shorter one deletes nothing; all time takes the rest") {
        val config = mailbox()
        listOf(10, 60, 80, 200, 1000).forEach { receive(config, "$it days", it) }
        val state = InMemoryStateStore()
        sync(config, state).run().map { it.subject } shouldContainExactly listOf("10 days")

        val ninety = sync(config.copy(historyDays = MailHistory.DAYS_90.days), state).run()
        ninety.map { it.subject } shouldContainExactly listOf("80 days", "60 days")
        ninety.all { it.history } shouldBe true
        sync(config.copy(historyDays = 90), state).run().shouldBeEmpty()

        sync(config.copy(historyDays = MailHistory.YEAR.days), state).run().map { it.subject } shouldContainExactly listOf("200 days")

        // back to 30 days: nothing is tombstoned (run() checks) and nothing is fetched
        sync(config, state).run().shouldBeEmpty()
        val cursor = state.getAs<FolderCursor>("folder:INBOX")!!
        UidSet.decode(cursor.known).size shouldBe 4

        sync(config.copy(historyDays = MailHistory.ALL.days), state).run().map { it.subject } shouldContainExactly listOf("1000 days")
        state.getAs<FolderCursor>("folder:INBOX")!!.historyFrom shouldBe ALL_TIME
        sync(config.copy(historyDays = null), state).run().shouldBeEmpty()
    }

    test("an interrupted backfill resumes where it stopped, with no message fetched twice") {
        val config = mailbox()
        repeat(120) { receive(config, "old $it", 100 + it) }
        val state = InMemoryStateStore()
        sync(config, state).run().shouldBeEmpty()

        // the connector dies after the first backfill batch was accepted
        val accepted = mutableListOf<IngestRecord.Upsert>()
        var pushes = 0
        shouldThrow<IllegalStateException> {
            sync(config.copy(historyDays = null), state).syncAll { batch ->
                check(++pushes == 1) { "connector stopped" }
                accepted += batch.filterIsInstance<IngestRecord.Upsert>()
            }
        }
        accepted shouldHaveSize 50

        val rest = sync(config.copy(historyDays = null), state).run()
        rest shouldHaveSize 70
        (accepted + rest).map { it.sourceId }.toSet() shouldHaveSize 120
        (accepted + rest).map { it.subject } shouldContainExactlyInAnyOrder List(120) { "old $it" }
        sync(config.copy(historyDays = null), state).run().shouldBeEmpty()
    }

    test("a spent budget leaves the rest for the next run, which carries on") {
        val config = mailbox()
        repeat(120) { receive(config, "old $it", 40 + it) }
        val state = InMemoryStateStore()
        val sync = sync(config.copy(historyDays = null), state, budget = Duration.ZERO)

        sync.run() shouldHaveSize 50
        sync.backfillPending shouldBe true
        receive(config, "new", 0)
        // new mail comes first in every run, and is not history
        val second = sync.run()
        second.first().subject shouldBe "new"
        second.first().history shouldBe false
        second shouldHaveSize 51
        sync.run() shouldHaveSize 20
        sync.backfillPending shouldBe false
        sync.run().shouldBeEmpty()
    }

    test("a cursor written before backfill existed is not re-imported") {
        val config = mailbox()
        receive(config, "10 days", 10)
        receive(config, "20 days", 20)
        val state = InMemoryStateStore()
        sync(config, state).run() shouldHaveSize 2
        val cursor = state.getAs<FolderCursor>("folder:INBOX")!!
        state.putAs("folder:INBOX", cursor.copy(historyFrom = null))

        sync(config, state).run().shouldBeEmpty()
        state.getAs<FolderCursor>("folder:INBOX")!!.historyFrom.shouldNotBeNull()
    }
})
