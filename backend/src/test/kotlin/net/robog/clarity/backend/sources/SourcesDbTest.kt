package net.robog.clarity.backend.sources

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.testing.testApplication
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.agent.ExposedMemoryStore
import net.robog.clarity.backend.agent.ExposedRunStore
import net.robog.clarity.backend.agent.ExposedTaskStore
import net.robog.clarity.backend.agent.FailureGate
import net.robog.clarity.backend.agent.OwnStateTools
import net.robog.clarity.backend.agent.PgRunJournal
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.DevicePrincipal
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.dedup.DedupKey
import net.robog.clarity.backend.feed.ExposedFeedStore
import net.robog.clarity.backend.feed.FeedHub
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.governance.ConstitutionStore
import net.robog.clarity.backend.governance.GovernanceEvents
import net.robog.clarity.backend.governance.HeldActionStore
import net.robog.clarity.backend.ingest.softDelete
import net.robog.clarity.backend.ingest.upsertCanonical
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.ExposedNounReader
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Role
import net.robog.clarity.harness.ToolCallContext
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.noun.AttachmentMeta
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Noun
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.source.SourceItem
import net.robog.clarity.shared.source.SourceView
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.postgresql.PostgreSQLContainer

/** `send_to_user` with a sources card, and the route that serves a cited record, over Postgres. */
@EnabledIf(ContainerRuntimeAvailable::class)
class SourcesDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database
    lateinit var journal: PgRunJournal
    lateinit var runs: ExposedRunStore
    lateinit var reader: ExposedNounReader
    lateinit var own: OwnStateTools
    lateinit var feed: FeedService
    val clock = Clock.System
    val me = Handle.email("me@example.com", "Me")
    // A fresh run per test: the resolver keeps its index of a run while the journal keeps its length.
    var lead = Uuid.random().toString()

    fun addMessage(name: String, subject: String, attachments: List<AttachmentMeta> = emptyList()): String {
        val payload = MessagePayload(
            MessagePayload.Channel.EMAIL, me, Handle.email("sam@example.com", "Sam Example"), listOf(me), subject,
            "Lunch is at noon.", Instant.parse("2026-10-26T15:00:00Z"), attachments = attachments,
        )
        val key = DedupKey(MessageDigest.getInstance("SHA-256").digest(name.toByteArray()))
        return transaction(database) { upsertCanonical(payload, key, clock.now()).id.toString() }
    }

    suspend fun journalResult(run: String, content: String) {
        val use = Uuid.random().toString()
        journal.append(run, Message(Role.ASSISTANT, listOf(Block.ToolUse(use, "list_messages", buildJsonObject { put("q", "x") }))))
        journal.append(run, Message(Role.USER, listOf(Block.Result(use, content))))
    }

    suspend fun send(vararg items: JsonObject, run: String? = lead): ToolResult {
        val input = buildJsonObject {
            put("cards", JsonArray(listOf(
                buildJsonObject { put("type", "text"); put("text", "Lunch is at noon.") },
                buildJsonObject { put("type", "sources"); put("items", JsonArray(items.toList())) },
            )))
        }
        val tool = own.sendToUser()
        return if (run == null) tool.invoke(input) else withContext(ToolCallContext(run, "call-${Uuid.random()}")) { tool.invoke(input) }
    }

    fun id(value: String) = buildJsonObject { put("id", value) }
    fun url(value: String) = buildJsonObject { put("url", value) }

    suspend fun postedSources(): List<Card.Sources> =
        feed.page(0, 100).items.flatMap { it.cards }.filterIsInstance<Card.Sources>()

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
        journal = PgRunJournal(database, clock)
        runs = ExposedRunStore(database, clock)
        reader = ExposedNounReader(database)
        val hub = FeedHub()
        feed = FeedService(ExposedFeedStore(database, clock), hub)
        val constitutions = ConstitutionStore(database, clock, HeldActionStore(database, clock), GovernanceEvents())
        val settings = { Settings(homeZone = "America/New_York") }
        own = OwnStateTools(
            feed, ExposedMemoryStore(database, clock), constitutions, ExposedTaskStore(database, clock, feed), { settings() }, clock, FailureGate(null),
            SourceResolver(reader, { settings() }, journal, runs),
        )
    }
    afterSpec { postgres.stop() }
    beforeTest {
        lead = Uuid.random().toString()
        database.tx { exec("TRUNCATE feed_item, agent_journal RESTART IDENTITY CASCADE") }
    }

    test("a record the lead read is cited with its title and sub line from the database, not the model") {
        val lunch = addMessage("lunch", "Lunch Friday")
        journalResult(lead, """[{"id":"$lunch","subject":"Lunch Friday"}]""")
        val forged = buildJsonObject { put("id", lunch); put("title", "Trust me"); put("sub", "Totally") }
        send(forged).shouldBeInstanceOf<ToolResult.Ok>()
        val card = postedSources().single()
        card.items shouldBe listOf(SourceItem.Entity(lunch, net.robog.clarity.shared.source.SourceKind.MESSAGE, "Lunch Friday", "Sam Example · Mon Oct 26, 11:00 am", 0, "Sam Example", Instant.parse("2026-10-26T15:00:00Z")))
        card.fallbackText shouldBe "Sources. Lunch Friday."
    }

    test("an id the run never read is refused and the error names it; nothing is posted") {
        val unread = addMessage("unread", "Secret plans")
        val result = send(id(unread)).shouldBeInstanceOf<ToolResult.Error>()
        result.message shouldContain unread
        result.message shouldNotContain "Secret plans"
        postedSources() shouldBe emptyList()
    }

    test("an id read only by a helper this run started counts; one of another run's helper does not") {
        val mine = addMessage("mine", "Mine")
        val theirs = addMessage("theirs", "Theirs")
        val helper = Uuid.random().toString()
        val other = Uuid.random().toString()
        val otherLead = Uuid.random().toString()
        runs.create(helper, lead, "helper", "look", null, clock.now())
        runs.create(other, otherLead, "helper", "look", null, clock.now())
        journalResult(helper, "found $mine")
        journalResult(other, "found $theirs")
        send(id(mine)).shouldBeInstanceOf<ToolResult.Ok>()
        send(id(theirs)).shouldBeInstanceOf<ToolResult.Error>().message shouldContain theirs
    }

    test("a record that is gone is named as missing") {
        val gone = addMessage("gone", "Gone")
        journalResult(lead, "id $gone")
        transaction(database) { softDelete(Noun.MESSAGE, Uuid.parse(gone), clock.now()) }
        send(id(gone)).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "No such record"
    }

    test("pages the run fetched are cited with the fetched title and a punycode host; others are refused") {
        val use = "fetch-1"
        journal.append(lead, Message(Role.ASSISTANT, listOf(Block.ToolUse(use, "sandbox__fetch_page", buildJsonObject { put("url", "https://example.com/guide") }))))
        journal.append(lead, Message(Role.USER, listOf(Block.Result(use, "Title: Garden Guide\nURL: https://example.com/guide\nFetched: 2026-10-26T10:00:00Z\n\nPlant in spring."))))
        send(url("https://example.com/guide")).shouldBeInstanceOf<ToolResult.Ok>()
        postedSources().single().items shouldBe listOf(SourceItem.Web("https://example.com/guide", "Garden Guide", "example.com"))
        send(url("https://example.com/other")).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "https://example.com/other"
    }

    test("one error names every bad item, and a repeated item is listed once") {
        val a = addMessage("a", "A")
        journalResult(lead, "id $a")
        val bad = Uuid.random().toString()
        val both = send(id(a), id(bad), url("https://example.com/none")).shouldBeInstanceOf<ToolResult.Error>()
        both.message shouldContain bad
        both.message shouldContain "https://example.com/none"
        both.message shouldNotContain a
        send(id(a), id(a.uppercase())).shouldBeInstanceOf<ToolResult.Ok>()
        postedSources().single().items.size shouldBe 1
    }

    test("a read in a later step counts, an echo and the same step do not") {
        val a = addMessage("later", "Later")
        val echo = Uuid.random().toString()
        journal.append(lead, Message(Role.ASSISTANT, listOf(Block.ToolUse(echo, "create_trigger", buildJsonObject { put("q", "x") }))))
        journal.append(lead, Message(Role.USER, listOf(Block.Result(echo, "created, about $a"))))
        send(id(a)).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "later step"
        journalResult(lead, "id $a")
        send(id(a)).shouldBeInstanceOf<ToolResult.Ok>()
    }

    test("outside a run nothing can be cited") {
        val a = addMessage("a2", "A2")
        journalResult(lead, "id $a")
        send(id(a), run = null).shouldBeInstanceOf<ToolResult.Error>()
    }

    test("the route serves only records a sources card cites, as stored") {
        val cited = addMessage("route-cited", "Cited")
        val uncited = addMessage("route-uncited", "Not cited")
        journalResult(lead, "id $cited")
        send(id(cited)).shouldBeInstanceOf<ToolResult.Ok>()
        testApplication {
            application {
                install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) { json(ClarityJson) }
                install(Authentication) { bearer(AuthProviders.DEVICE) { authenticate { DevicePrincipal(DeviceId("d1"), null) } } }
                SourceRoutes(reader, database).install(this)
            }
            val client = createClient { install(ContentNegotiation) { json(ClarityJson) } }
            client.get("/api/v1/sources/$cited").status shouldBe HttpStatusCode.Unauthorized
            val view = client.get("/api/v1/sources/$cited") { header("Authorization", "Bearer d") }
            view.status shouldBe HttpStatusCode.OK
            view.body<SourceView>().shouldBeInstanceOf<SourceView.Message>().apply {
                title shouldBe "Cited"
                from shouldBe "Sam Example <sam@example.com>"
                body shouldBe "Lunch is at noon."
            }
            client.get("/api/v1/sources/$uncited") { header("Authorization", "Bearer d") }.status shouldBe HttpStatusCode.NotFound
            client.get("/api/v1/sources/${Uuid.random()}") { header("Authorization", "Bearer d") }.status shouldBe HttpStatusCode.NotFound
            client.get("/api/v1/sources/not-an-id") { header("Authorization", "Bearer d") }.status shouldBe HttpStatusCode.NotFound
            transaction(database) { softDelete(Noun.MESSAGE, Uuid.parse(cited), clock.now()) }
            client.get("/api/v1/sources/$cited") { header("Authorization", "Bearer d") }.status shouldBe HttpStatusCode.NotFound
        }
    }
})
