package net.robog.clarity.backend.persistence

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.robog.clarity.backend.agent.DocumentFetch
import net.robog.clarity.backend.agent.DocumentTools
import net.robog.clarity.backend.agent.DomainTools
import net.robog.clarity.backend.agent.SettingsProvider
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.dedup.DedupKey
import net.robog.clarity.backend.ingest.softDelete
import net.robog.clarity.backend.ingest.upsertCanonical
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.triggers.StoredTrigger
import net.robog.clarity.backend.triggers.TriggerStore
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.noun.ContactPayload
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.MessagePayload.Channel
import net.robog.clarity.shared.noun.Noun
import net.robog.clarity.shared.noun.Payload
import net.robog.clarity.shared.query.TriggerSpec
import net.robog.clarity.shared.session.Settings
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.postgresql.PostgreSQLContainer

private class NoTriggers : TriggerStore {
    override suspend fun list() = emptyList<StoredTrigger>()
    override suspend fun enabledFor(noun: Noun) = emptyList<StoredTrigger>()
    override suspend fun create(spec: TriggerSpec): StoredTrigger = error("unused")
    override suspend fun delete(id: String) = false
}

/** Agents see saved contact names on messages that carry only a bare number or address. */
@EnabledIf(ContainerRuntimeAvailable::class)
class ContactNamesDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine").withStartupAttempts(3)
    lateinit var database: Database
    lateinit var reader: ExposedNounReader
    lateinit var tools: Map<String, Tool>
    val t0 = Instant.parse("2026-09-01T12:00:00Z")
    val device = Handle(Handle.Kind.OTHER, "device-abc", "This phone")
    val ids = mutableMapOf<String, Uuid>()

    fun save(payload: Payload, key: String, deleted: Boolean = false) {
        val dedup = DedupKey(MessageDigest.getInstance("SHA-256").digest(key.toByteArray()))
        transaction(database) {
            val id = upsertCanonical(payload, dedup, Clock.System.now()).id
            ids[key] = id
            if (deleted) softDelete(Noun.CONTACT, id, Clock.System.now())
        }
    }

    fun sms(key: String, from: Handle, at: Instant) =
        save(MessagePayload(Channel.SMS, device, from, listOf(device), null, "see you soon $key", at), key)

    fun call(tool: String, json: String): JsonArray {
        val result = kotlinx.coroutines.runBlocking { tools.getValue(tool).invoke(Json.parseToJsonElement(json).jsonObject) }
        return Json.parseToJsonElement((result as ToolResult.Ok).content).jsonArray
    }

    fun JsonArray.bodyHas(word: String) = map { it.jsonObject }.first { it.getValue("body").jsonPrimitive.content.contains(word) }
    fun kotlinx.serialization.json.JsonObject.str(field: String) = getValue(field).jsonPrimitive.content

    val window = """"from":"2026-08-01","until":"2026-10-01""""

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
        reader = ExposedNounReader(database)
        tools = DomainTools(reader, NoTriggers(), DocumentTools(reader) { DocumentFetch.Unavailable("none") }, SettingsProvider { Settings(homeZone = "UTC") })
            .all.associateBy { it.name }
        save(ContactPayload("Sam Example", listOf(Handle.phone("+1 (555) 555-0123"), Handle.email("Sam@Example.com"))), "c-sam")
        save(ContactPayload("Old Pal", listOf(Handle.phone("+15555550999"))), "c-old", deleted = true)
        sms("sms-bare", Handle.phone("+15555550123"), t0)
        sms("sms-old", Handle.phone("+15555550999"), t0 + 1.hours)
        sms("sms-stranger", Handle.phone("+15555550777"), t0 + 2.hours)
        save(
            MessagePayload(
                Channel.EMAIL, Handle.email("me@example.com"), Handle.email("sam@example.com", "Samantha E."),
                listOf(Handle.email("me@example.com")), "Hi", "mail body", t0 + 3.hours,
            ),
            "mail-named",
        )
    }
    afterSpec { postgres.stop() }

    test("an SMS from a bare number shows the saved name in the same form as a name on the message") {
        val sms = call("list_messages", """{$window,"limit":50}""").bodyHas("sms-bare")
        println(sms)
        sms.str("from") shouldBe "Sam Example <+15555550123>"
        sms.getValue("to").jsonArray.single().jsonPrimitive.content shouldBe "This phone <device-abc>"
    }

    test("a different phone formatting still matches the contact") {
        val asked = listOf(Handle.phone("5555550123"), Handle.phone("+1 555-555-0123"), Handle.email("SAM@example.COM"))
        val names = kotlinx.coroutines.runBlocking { reader.contactNames(asked) }
        asked.map { names.nameFor(it) } shouldBe listOf("Sam Example", "Sam Example", "Sam Example")
    }

    test("a deleted contact does not match and an unknown number stays bare") {
        val all = call("list_messages", """{$window}""")
        all.bodyHas("sms-old").str("from") shouldBe "+15555550999"
        all.bodyHas("sms-stranger").str("from") shouldBe "+15555550777"
    }

    test("an email keeps the name it arrived with") {
        val mail = call("list_messages", """{$window,"folder":"*"}""").bodyHas("mail body")
        mail.str("from") shouldBe "Samantha E. <sam@example.com>"
    }

    test("search by the contact's name finds the text, and every word may match") {
        call("search_messages", """{"query":"sam"}""").toString() shouldContain "sms-bare"
        val both = call("search_messages", """{"query":"sam example"}""").toString()
        both shouldContain "sms-bare"
        both shouldNotContain "sms-stranger"
        both shouldNotContain "sms-old"
        call("search_messages", """{"query":"sam","channel":"sms"}""").map { it.jsonObject.str("from") } shouldBe
            listOf("Sam Example <+15555550123>")
    }

    test("list_messages participant accepts a contact name or another number formatting") {
        call("list_messages", """{$window,"participant":"sam example","folder":"*"}""").size shouldBe 2
        call("list_messages", """{$window,"participant":"(555) 555-0123"}""").size shouldBe 1
        call("list_messages", """{$window,"participant":"+15555550777"}""").size shouldBe 1
    }
})
