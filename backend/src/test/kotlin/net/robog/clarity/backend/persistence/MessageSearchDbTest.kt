package net.robog.clarity.backend.persistence

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.dedup.DedupKey
import net.robog.clarity.backend.ingest.softDelete
import net.robog.clarity.backend.ingest.upsertCanonical
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.MessagePayload.Channel
import net.robog.clarity.shared.noun.Noun
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.postgresql.PostgreSQLContainer

/** search_messages over Postgres (V28): name/address fragments, stemmed words, filters and ranking. */
@EnabledIf(ContainerRuntimeAvailable::class)
class MessageSearchDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database
    lateinit var reader: ExposedNounReader
    val t0 = Instant.parse("2026-09-01T12:00:00Z")
    val me = Handle.email("me@example.com", "Me")
    val ids = mutableMapOf<String, Uuid>()

    fun add(name: String, sender: Handle, subject: String?, body: String, at: Instant, channel: Channel = Channel.EMAIL, folder: String? = "INBOX") {
        val payload = MessagePayload(channel, me, sender, listOf(me), subject, body, at)
        val key = DedupKey(MessageDigest.getInstance("SHA-256").digest(name.toByteArray()))
        transaction(database) {
            val id = upsertCanonical(payload, key, Clock.System.now()).id
            ids[name] = id
            if (folder != null) {
                exec("INSERT INTO source_link (connector_id, noun, source_id, entity_id, latest_version) VALUES ('mail', 'message', '${"me@example.com/$folder/1/" + ids.size}', '$id', '1')")
            }
        }
    }

    fun search(query: String, block: MessageSearch.() -> MessageSearch = { this }) =
        kotlinx.coroutines.runBlocking { reader.searchMessages(MessageSearch(searchTerms(query)).block()) }

    fun MessageHits.names() = messages.map { m -> ids.entries.single { it.value.toString() == m.id.value }.key }

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
        reader = ExposedNounReader(database)
        transaction(database) { exec("INSERT INTO connector (id) VALUES ('mail')") }
        val tim = Handle.email("turecekt@gmail.com", "Tim Turecek")
        add("tim-old", tim, "Lunch", "See you at noon.", t0)
        add("tim-new", tim, "Weekend", "Hiking on Saturday?", t0 + 5.days)
        add("club-subject", Handle.email("news@school.edu"), "Astrology Club meeting", "Room 4 at 3pm.", t0 + 1.days)
        add("club-body", Handle.email("friend@example.com"), "Hi", "Are you going to the astrology club tonight? " + "Filler words here. ".repeat(80), t0 + 2.days)
        add("mentions-tim", Handle.email("boss@example.com"), "Team", "Please loop in Turecek on the plan.", t0 + 3.days)
        add("sms-club", Handle.phone("+15550001111"), null, "astrology club is cancelled", t0 + 4.days, Channel.SMS, folder = null)
        add("spam-club", Handle.email("spam@bad.biz"), "Win big", "astrology club prize", t0 + 6.days, folder = "[Gmail]/Spam")
        add("deleted-club", Handle.email("gone@example.com"), "Astrology club", "old", t0 + 7.days)
        transaction(database) { softDelete(Noun.MESSAGE, ids.getValue("deleted-club"), Clock.System.now()) }
    }
    afterSpec { postgres.stop() }

    test("a name fragment finds the address and display name, and ranks people over body mentions, newest first") {
        search("turecek").names() shouldContainExactly listOf("tim-new", "tim-old", "mentions-tim")
        search("Tim Turecek").names() shouldContainExactly listOf("tim-new", "tim-old")
        search("gmail.com").names() shouldContainExactly listOf("tim-new", "tim-old")
    }

    test("a body word matches stemmed and case-insensitively; subject matches outrank body matches") {
        search("astrological").names().first() shouldBe "club-subject"
        search("astrology").names() shouldContainExactly listOf("club-subject", "spam-club", "sms-club", "club-body")
        search("\"astrology club\"").names().first() shouldBe "club-subject"
    }

    test("window, channel and folder filters; deleted messages never match") {
        search("astrology") { copy(range = OpenRange(t0 + 2.days, t0 + 5.days)) }.names() shouldContainExactly listOf("sms-club", "club-body")
        search("astrology") { copy(range = OpenRange(from = t0 + 5.days)) }.names() shouldContainExactly listOf("spam-club")
        search("astrology") { copy(channel = Channel.SMS) }.names() shouldContainExactly listOf("sms-club")
        search("astrology") { copy(folder = "INBOX") }.names() shouldContainExactly listOf("club-subject", "sms-club", "club-body")
        search("astrology") { copy(folder = "spam") }.names() shouldContainExactly listOf("spam-club", "sms-club")
        search("old").names() shouldBe emptyList()
    }

    test("when nothing matches every word, the best partial matches come back marked partial") {
        val hits = search("astrology zebra")
        hits.partial shouldBe true
        hits.names().first() shouldBe "club-subject"
        search("astrology club").partial shouldBe false
        search("zebra").messages shouldBe emptyList()
        search("astrology") { copy(limit = 2) }.messages.size shouldBe 2
    }
})
