package net.robog.clarity.backend.agent

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import net.robog.clarity.backend.config.AttachmentsConfig
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.reports.reportsAppConfig
import net.robog.clarity.harness.ToolImage
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

/** The pictures tools showed, kept for a day in Postgres (V49) and read back when a request is built. */
@EnabledIf(ContainerRuntimeAvailable::class)
class ToolImageDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine").withStartupAttempts(3)
    lateinit var database: Database
    var now = Instant.parse("2026-11-13T12:00:00Z")
    val clock = object : kotlin.time.Clock { override fun now() = now }
    val picture = ToolImage("image/png", java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4)))

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }

    fun store() = PgToolImageStore(database, clock, reportsAppConfig(Files.createTempDirectory("img")).copy(attachments = AttachmentsConfig()))

    test("a picture comes back as it went in, until its day is over") {
        val store = store()
        runBlocking { store.save("run-1", "c1", picture) }
        runBlocking { store.load("run-1", "c1") } shouldBe picture
        runBlocking { store.load("run-1", "c2") } shouldBe null
        runBlocking { store.load("run-2", "c1") } shouldBe null
        now += 23.hours
        runBlocking { store.load("run-1", "c1") } shouldBe picture
        now += 2.hours
        runBlocking { store.load("run-1", "c1") } shouldBe null
    }

    test("the sweep drops old pictures and keeps new ones; saving again replaces") {
        val store = store()
        runBlocking { store.sweep(now + 1000.hours, 24.hours) } // what the test before left
        now = Instant.parse("2026-11-14T12:00:00Z")
        runBlocking { store.save("run-3", "old", picture) }
        now += 30.minutes
        runBlocking { store.save("run-3", "new", picture.copy(mimeType = "image/jpeg")) }
        runBlocking { store.save("run-3", "new", picture.copy(mimeType = "image/gif")) }
        runBlocking { store.sweep(now + 23.hours + 45.minutes, 24.hours) } shouldBe 1 // "old" is over a day old by then, "new" is not
        runBlocking { store.load("run-3", "old") } shouldBe null
        runBlocking { store.load("run-3", "new") }!!.mimeType shouldBe "image/gif"
    }

    test("the journal's own table never holds the bytes") {
        val store = store()
        now = Instant.parse("2026-11-15T12:00:00Z")
        runBlocking { store.save("run-4", "c1", picture) }
        val journal = PgRunJournal(database, clock)
        runBlocking {
            journal.append("run-4", net.robog.clarity.harness.Message(net.robog.clarity.harness.Role.USER, listOf(net.robog.clarity.harness.Block.Image("c1", "image/png", 4))))
            val loaded = journal.load("run-4").single().content.single()
            loaded shouldBe net.robog.clarity.harness.Block.Image("c1", "image/png", 4)
        }
        org.jetbrains.exposed.v1.jdbc.transactions.transaction(database) {
            exec("SELECT message::text FROM agent_journal WHERE run_id = 'run-4'") { rs -> rs.next(); rs.getString(1) }!!.contains(picture.base64) shouldBe false
        }
        // the transcript saved for the person's view is built from the same blocks
        val transcript = helperTranscript(runBlocking { journal.load("run-4") }) { it }.toString()
        transcript.contains(picture.base64) shouldBe false
    }
})
