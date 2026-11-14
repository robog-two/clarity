package net.robog.clarity.backend.attachments

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.robog.clarity.backend.config.AttachmentsConfig
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.dedup.DedupKey
import net.robog.clarity.backend.governance.OutboxSignals
import net.robog.clarity.backend.ingest.upsertCanonical
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.ExposedNounReader
import net.robog.clarity.backend.reports.reportsAppConfig
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.fetch.FetchFailure
import net.robog.clarity.shared.fetch.FetchRequest
import net.robog.clarity.shared.noun.AttachmentMeta
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.postgresql.PostgreSQLContainer

/** The fetch queue, its authorization and its cache over Postgres (V49), and the fetcher on top of it. */
@EnabledIf(ContainerRuntimeAvailable::class)
class AttachmentFetchDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database
    lateinit var store: AttachmentFetchStore
    lateinit var reader: ExposedNounReader
    val mail = ConnectorId("mail")
    val other = ConnectorId("other")
    val t0 = Instant.parse("2026-11-13T12:00:00Z")
    val me = Handle.email("me@example.com", "Me")
    val content = ByteArray(2_000) { (it % 200).toByte() }

    fun fetcher(maxBytes: Long = 1_000_000, wait: kotlin.time.Duration = 5_000.milliseconds): AttachmentFetcher {
        val config = reportsAppConfig(Files.createTempDirectory("att")).copy(attachments = AttachmentsConfig(maxBytes = maxBytes, waitTimeout = wait))
        return AttachmentFetcher(database, store, reader, Clock.System, config, OutboxSignals())
    }

    /** A message with attachments 2 (report.pdf) and 3 (huge.bin), held by `mail` as [source] at version "2". */
    fun message(name: String, source: String, attachments: List<AttachmentMeta>, connector: String = "mail", version: String = "2"): EntityId {
        val payload = MessagePayload(
            MessagePayload.Channel.EMAIL, me, Handle.email("sam@example.com", "Sam Example"), listOf(me), "Report", "see attached",
            t0, messageId = "<$name@example.com>", attachments = attachments,
        )
        val id = transaction(database) {
            upsertCanonical(payload, DedupKey(MessageDigest.getInstance("SHA-256").digest(name.toByteArray())), t0).id
        }
        transaction(database) {
            exec("INSERT INTO source_link (connector_id, noun, source_id, entity_id, latest_version) VALUES ('$connector', 'message', '$source', '$id', '$version')")
        }
        return EntityId(id.toString())
    }

    val standard = listOf(
        AttachmentMeta("2", "report.pdf", "application/pdf", 2_000),
        AttachmentMeta("3", "huge.bin", "application/octet-stream", 50_000_000),
    )

    /** What a connector does: leases what is addressed to it and answers with [answer]. */
    suspend fun serve(connector: ConnectorId, answer: suspend (FetchRequest) -> Unit) {
        repeat(100) {
            val found = store.lease(connector, Clock.System.now(), 1_000_000)
            if (found.isNotEmpty()) { found.forEach { answer(it) }; return }
            delay(50)
        }
    }

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
        store = AttachmentFetchStore(database)
        reader = ExposedNounReader(database)
        transaction(database) {
            exec("INSERT INTO connector (id, manifest) VALUES ('mail', '{\"kind\":\"mail\",\"displayName\":\"Mail\",\"version\":\"1\",\"produces\":[\"message\"],\"fetches\":true}'), ('other', '{\"kind\":\"mail\",\"displayName\":\"Mail\",\"version\":\"1\",\"produces\":[\"message\"],\"fetches\":true}'), ('plain', '{\"kind\":\"plain\",\"displayName\":\"Plain\",\"version\":\"1\",\"produces\":[\"message\"]}')")
            exec("INSERT INTO connector_grant (connector_id, noun, state, updated_at) VALUES ('mail', 'message', 'granted', now()), ('other', 'message', 'granted', now()), ('plain', 'message', 'granted', now())")
        }
    }
    afterSpec { postgres.stop() }
    beforeTest { transaction(database) { exec("TRUNCATE attachment_fetch") } }

    test("the message keeps its id and attachment list") {
        val id = message("keeps", "acct/INBOX/1/1", standard)
        val stored = runBlocking { reader.message(id) }!!.payload
        stored.messageId shouldBe "<keeps@example.com>"
        stored.attachments shouldBe standard
    }

    test("a fetch waits for the connector, returns the bytes, and the second one is served from the cache") {
        val id = message("roundtrip", "acct/INBOX/1/2", standard)
        val fetcher = fetcher()
        val first = coroutineScope {
            val pending = async { fetcher.fetch(id, "2") }
            serve(mail) { request ->
                request.sourceId.value shouldBe "acct/INBOX/1/2"
                request.part shouldBe "2"
                store.complete(mail, Uuid.parse(request.id), content, "report.pdf", "application/pdf", Clock.System.now(), 1_000_000) shouldBe null
            }
            pending.await()
        }
        val file = first.getOrNull()!!
        file.bytes.toList() shouldBe content.toList()
        file.filename shouldBe "report.pdf"
        file.mimeType shouldBe "application/pdf"

        // no connector is running now: only the cache can answer
        val again = fetcher.fetch(id, "2").getOrNull()!!
        again.bytes.toList() shouldBe content.toList()
        store.lease(mail, Clock.System.now(), 1_000_000) shouldBe emptyList()
    }

    test("only the connector that owns the message is offered the request, and only it can answer") {
        val id = message("owner", "acct/INBOX/1/3", standard)
        val fetcher = fetcher()
        coroutineScope {
            val pending = async { fetcher.fetch(id, "2") }
            delay(300)
            // another connector, even one granted messages, sees nothing and cannot answer or fail it
            store.lease(other, Clock.System.now(), 1_000_000) shouldBe emptyList()
            val leased = store.lease(mail, Clock.System.now(), 1_000_000).single()
            val request = Uuid.parse(leased.id)
            store.complete(other, request, content, "x.pdf", "application/pdf", Clock.System.now(), 1_000_000) shouldBe AnswerRefused.UNKNOWN_REQUEST
            store.fail(other, request, FetchFailure(FetchFailure.Code.FAILED, "nope"), Clock.System.now()) shouldBe AnswerRefused.UNKNOWN_REQUEST
            store.answer(request) shouldBe Answer.Pending
            store.complete(mail, request, content, "report.pdf", "application/pdf", Clock.System.now(), 1_000_000) shouldBe null
            pending.await().getOrNull()!!.filename shouldBe "report.pdf"
        }
    }

    test("a connector whose messages grant was revoked is not offered requests") {
        val id = message("revoked", "acct/INBOX/1/4", standard, connector = "other")
        // the fetcher itself refuses a source whose connector is not granted
        transaction(database) { exec("UPDATE connector_grant SET state = 'revoked' WHERE connector_id = 'other'") }
        try {
            fetcher().fetch(id, "2").leftOrNull().shouldBeInstanceOf<FetchError.NotFound>()
            store.lease(other, Clock.System.now(), 1_000_000) shouldBe emptyList()
        } finally {
            transaction(database) { exec("UPDATE connector_grant SET state = 'granted' WHERE connector_id = 'other'") }
        }
    }

    test("unknown messages, bad and unlisted parts are not found without asking the connector") {
        val id = message("lists", "acct/INBOX/1/5", standard)
        val fetcher = fetcher()
        fetcher.fetch(EntityId(Uuid.random().toString()), "2").leftOrNull().shouldBeInstanceOf<FetchError.NotFound>()
        fetcher.fetch(EntityId("nonsense"), "2").leftOrNull().shouldBeInstanceOf<FetchError.NotFound>()
        fetcher.fetch(id, "../2").leftOrNull().shouldBeInstanceOf<FetchError.NotFound>()
        fetcher.fetch(id, "*").leftOrNull().shouldBeInstanceOf<FetchError.NotFound>()
        fetcher.fetch(id, "9").leftOrNull().shouldBeInstanceOf<FetchError.NotFound>()
        store.lease(mail, Clock.System.now(), 1_000_000) shouldBe emptyList()
    }

    test("a part known to be over the limit is refused before the connector is asked") {
        val id = message("huge", "acct/INBOX/1/6", standard)
        fetcher().fetch(id, "3").leftOrNull().shouldBeInstanceOf<FetchError.TooLarge>()
        store.lease(mail, Clock.System.now(), 1_000_000) shouldBe emptyList()
    }

    test("the connector's failures come back as the matching error") {
        val id = message("failures", "acct/INBOX/1/7", standard + AttachmentMeta("4", "a.bin", size = 10))
        for ((code, check) in listOf<Pair<FetchFailure.Code, (FetchError?) -> Unit>>(
            FetchFailure.Code.NOT_FOUND to { it.shouldBeInstanceOf<FetchError.NotFound>() },
            FetchFailure.Code.TOO_LARGE to { it.shouldBeInstanceOf<FetchError.TooLarge>() },
            FetchFailure.Code.FAILED to { it.shouldBeInstanceOf<FetchError.Failed>() },
        )) {
            val fetcher = fetcher()
            val error = coroutineScope {
                val pending = async { fetcher.fetch(id, "4") }
                serve(mail) { store.fail(mail, Uuid.parse(it.id), FetchFailure(code, "went wrong"), Clock.System.now()) }
                pending.await().leftOrNull()
            }
            check(error)
        }
    }

    test("an upload over the limit fails the request instead of being stored") {
        val id = message("toobig", "acct/INBOX/1/8", standard + AttachmentMeta("5", "a.bin"))
        val fetcher = fetcher(maxBytes = 1_000)
        val error = coroutineScope {
            val pending = async { fetcher.fetch(id, "5") }
            serve(mail) { store.complete(mail, Uuid.parse(it.id), content, "a.bin", "application/octet-stream", Clock.System.now(), 1_000) }
            pending.await().leftOrNull()
        }
        error.shouldBeInstanceOf<FetchError.TooLarge>()
    }

    test("no answer in time is a timeout, and the request is retried by the next ask") {
        val id = message("slow", "acct/INBOX/1/9", standard)
        fetcher(wait = 400.milliseconds).fetch(id, "2").leftOrNull() shouldBe FetchError.Timeout
        // the connector comes back later: the request it missed is still there for it
        val leased = store.lease(mail, Clock.System.now(), 1_000_000)
        leased.single().part shouldBe "2"
    }

    test("a lease that lapses is delivered again, and after the last attempt the request fails") {
        val id = Uuid.random()
        val key = FetchKey(mail, Uuid.random(), "acct/INBOX/1/10", "2", "7")
        val ticket = runBlocking { store.request(key, t0, 24.hours) }.shouldBeInstanceOf<Ticket.Waiting>()
        repeat(FETCH_MAX_ATTEMPTS) { n ->
            val at = t0 + (n * 5).minutes
            runBlocking { store.lease(mail, at, 1_000) }.single().id shouldBe ticket.id.toString()
            // leased and not lapsed: not delivered a second time
            runBlocking { store.lease(mail, at + 10.milliseconds, 1_000) } shouldBe emptyList()
        }
        runBlocking { store.lease(mail, t0 + 30.minutes, 1_000) } shouldBe emptyList()
        runBlocking { store.answer(ticket.id) }.shouldBeInstanceOf<Answer.Failed>()
        runBlocking { store.answer(ticket.id) } shouldBe Answer.Failed(FetchFailure.Code.FAILED, "the connector never answered")
        id.toString().length shouldBe 36
    }

    test("cached bytes expire: an old row is asked again, and the sweep drops it") {
        runBlocking { store.sweep(t0 + 10_000.hours, 24.hours, 0) } // what earlier tests left
        val key = FetchKey(mail, Uuid.random(), "acct/INBOX/1/11", "2", "2")
        val ttl = 24.hours
        val ticket = runBlocking { store.request(key, t0, ttl) }.shouldBeInstanceOf<Ticket.Waiting>()
        runBlocking { store.lease(mail, t0, 1_000_000) }
        runBlocking { store.complete(mail, ticket.id, content, "a.pdf", "application/pdf", t0, 1_000_000) } shouldBe null

        runBlocking { store.request(key, t0 + 23.hours, ttl) }.shouldBeInstanceOf<Ticket.Cached>()
        runBlocking { store.sweep(t0 + 23.hours, ttl, Long.MAX_VALUE) } shouldBe 0
        runBlocking { store.sweep(t0 + 25.hours, ttl, Long.MAX_VALUE) } shouldBe 1
        runBlocking { store.request(key, t0 + 25.hours, ttl) }.shouldBeInstanceOf<Ticket.Waiting>()
    }

    test("the sweep keeps the cache under its total cap, oldest first, and drops stale requests") {
        runBlocking { store.sweep(t0 + 10_000.hours, 24.hours, 0) }
        val keys = (1..3).map { FetchKey(mail, Uuid.random(), "acct/INBOX/1/2$it", "2", "2") }
        keys.forEachIndexed { i, key ->
            val ticket = runBlocking { store.request(key, t0 + i.hours, 24.hours) }.shouldBeInstanceOf<Ticket.Waiting>()
            runBlocking { store.lease(mail, t0 + i.hours, 1_000_000) }
            runBlocking { store.complete(mail, ticket.id, ByteArray(1_000), "f$i", "text/plain", t0 + i.hours, 1_000_000) } shouldBe null
        }
        runBlocking { store.cachedBytes() } shouldBe 3_000
        runBlocking { store.sweep(t0 + 4.hours, 24.hours, 2_000) } shouldBe 1
        runBlocking { store.cachedBytes() } shouldBe 2_000
        // the oldest went: asking for it queues a new request, the others are still cached
        runBlocking { store.request(keys[0], t0 + 4.hours, 24.hours) }.shouldBeInstanceOf<Ticket.Waiting>()
        runBlocking { store.request(keys[2], t0 + 4.hours, 24.hours) }.shouldBeInstanceOf<Ticket.Cached>()
        // a request nobody answered is dropped after a while
        runBlocking { store.sweep(t0 + 4.hours + 20.minutes, 24.hours, 10_000) } shouldBe 1
    }

    test("completing a file never evicts one completed in the last minute, even over the cap") {
        runBlocking { store.sweep(t0 + 10_000.hours, 24.hours, 0) }
        val keys = (1..3).map { FetchKey(mail, Uuid.random(), "acct/INBOX/1/4$it", "2", "2") }
        // two files 10 minutes apart, then a third just after the second; the cap holds only two
        val times = listOf(t0, t0 + 10.minutes, t0 + 10.minutes + 20.seconds)
        keys.forEachIndexed { i, key ->
            val ticket = runBlocking { store.request(key, times[i], 24.hours) }.shouldBeInstanceOf<Ticket.Waiting>()
            runBlocking { store.lease(mail, times[i], 1_000_000) }
            runBlocking { store.complete(mail, ticket.id, ByteArray(1_000), "f$i", "text/plain", times[i], 1_000_000, cacheMaxBytes = 2_000) } shouldBe null
        }
        // the oldest went to make room; the one from 20 seconds ago is safe
        runBlocking { store.cachedBytes() } shouldBe 2_000
        runBlocking { store.request(keys[0], times[2], 24.hours) }.shouldBeInstanceOf<Ticket.Waiting>()
        runBlocking { store.request(keys[1], times[2], 24.hours) }.shouldBeInstanceOf<Ticket.Cached>()
        // a file bigger than the whole cap is stored; only older files were dropped
        val big = FetchKey(mail, Uuid.random(), "acct/INBOX/1/50", "2", "2")
        val ticket = runBlocking { store.request(big, times[2] + 5.seconds, 24.hours) }.shouldBeInstanceOf<Ticket.Waiting>()
        runBlocking { store.lease(mail, times[2] + 5.seconds, 1_000_000) }
        runBlocking { store.complete(mail, ticket.id, ByteArray(5_000), "big", "text/plain", times[2] + 5.seconds, 1_000_000, cacheMaxBytes = 2_000) } shouldBe null
        runBlocking { store.request(big, times[2] + 5.seconds, 24.hours) }.shouldBeInstanceOf<Ticket.Cached>()
        runBlocking { store.request(keys[2], times[2] + 5.seconds, 24.hours) }.shouldBeInstanceOf<Ticket.Cached>()
    }

    test("a failed request is asked again by the next fetch") {
        val key = FetchKey(mail, Uuid.random(), "acct/INBOX/1/30", "2", "2")
        val first = runBlocking { store.request(key, t0, 24.hours) }.shouldBeInstanceOf<Ticket.Waiting>()
        runBlocking { store.lease(mail, t0, 1_000) }
        runBlocking { store.fail(mail, first.id, FetchFailure(FetchFailure.Code.FAILED, "imap down"), t0) } shouldBe null
        runBlocking { store.answer(first.id) } shouldBe Answer.Failed(FetchFailure.Code.FAILED, "imap down")
        val second = runBlocking { store.request(key, t0 + 1.minutes, 24.hours) }.shouldBeInstanceOf<Ticket.Waiting>()
        second.id shouldBe first.id
        runBlocking { store.answer(second.id) } shouldBe Answer.Pending
    }

    test("a message ingested before lists existed is asked for its parts, and a part that is not there is not found") {
        val id = message("legacy", "acct/INBOX/1/40", emptyList(), version = "1")
        val fetcher = fetcher()
        val error = coroutineScope {
            val pending = async { fetcher.fetch(id, "9") }
            serve(mail) { store.fail(mail, Uuid.parse(it.id), FetchFailure(FetchFailure.Code.NOT_FOUND, "the message has no part 9"), Clock.System.now()) }
            pending.await().leftOrNull()
        }
        error.shouldBeInstanceOf<FetchError.NotFound>()
    }

    test("a record that lists its attachments and lists none has none: answered without asking the connector") {
        val id = message("none", "acct/INBOX/1/41", emptyList(), version = "2")
        fetcher().fetch(id, "2").leftOrNull().shouldBeInstanceOf<FetchError.NotFound>()
        fetcher().list(id).getOrNull() shouldBe emptyList()
        store.lease(mail, Clock.System.now(), 1_000_000) shouldBe emptyList()
    }

    test("a connector that does not declare fetch support is not asked") {
        val id = message("plainly", "acct/INBOX/1/42", standard, connector = "plain")
        fetcher().fetch(id, "2").leftOrNull().shouldBeInstanceOf<FetchError.NotFound>()
        store.lease(ConnectorId("plain"), Clock.System.now(), 1_000_000) shouldBe emptyList()
    }

    test("the newest version of a source is asked first") {
        val id = message("versions", "acct/INBOX/1/43", standard, version = "2")
        transaction(database) {
            exec("INSERT INTO source_link (connector_id, noun, source_id, entity_id, latest_version) VALUES ('mail', 'message', 'acct/Archive/9/1', '${id.value}', '3')")
        }
        val asked = coroutineScope {
            val pending = async { fetcher().fetch(id, "2") }
            var first: FetchRequest? = null
            serve(mail) { first = it; store.complete(mail, Uuid.parse(it.id), content, "report.pdf", "application/pdf", Clock.System.now(), 1_000_000) }
            pending.await()
            first
        }
        asked!!.sourceId.value shouldBe "acct/Archive/9/1"
    }

    test("two attachments with the same name are saved under different names") {
        val id = message("twins", "acct/INBOX/1/44", listOf(AttachmentMeta("2", "scan.pdf", "application/pdf", 10), AttachmentMeta("3", "scan.pdf", "application/pdf", 10)))
        val fetcher = fetcher()
        val names = (listOf("2", "3")).map { part ->
            coroutineScope {
                val pending = async { fetcher.fetch(id, part) }
                serve(mail) { store.complete(mail, Uuid.parse(it.id), content, "scan.pdf", "application/pdf", Clock.System.now(), 1_000_000) }
                pending.await().getOrNull()!!.filename
            }
        }
        names shouldBe listOf("scan-p2.pdf", "scan-p3.pdf")
    }

    test("an answer that comes after the lease lapsed is still taken") {
        val key = FetchKey(mail, Uuid.random(), "acct/INBOX/1/45", "2", "2")
        val ticket = runBlocking { store.request(key, t0, 24.hours) }.shouldBeInstanceOf<Ticket.Waiting>()
        runBlocking { store.lease(mail, t0, 1_000_000) }.single()
        // 20 minutes later nothing has re-leased it: the slow upload arrives
        runBlocking { store.complete(mail, ticket.id, content, "late.pdf", "application/pdf", t0 + 20.minutes, 1_000_000) } shouldBe null
        runBlocking { store.answer(ticket.id) }.shouldBeInstanceOf<Answer.Done>().file.filename shouldBe "late.pdf"
    }

    test("a connector whose grant was revoked cannot answer what it was asked before") {
        val key = FetchKey(other, Uuid.random(), "acct/INBOX/1/46", "2", "2")
        val ticket = runBlocking { store.request(key, t0, 24.hours) }.shouldBeInstanceOf<Ticket.Waiting>()
        runBlocking { store.lease(other, t0, 1_000_000) }.single()
        transaction(database) { exec("UPDATE connector_grant SET state = 'revoked' WHERE connector_id = 'other'") }
        try {
            runBlocking { store.complete(other, ticket.id, content, "x", "text/plain", t0, 1_000_000) } shouldBe AnswerRefused.UNKNOWN_REQUEST
            runBlocking { store.fail(other, ticket.id, FetchFailure(FetchFailure.Code.FAILED, "x"), t0) } shouldBe AnswerRefused.UNKNOWN_REQUEST
        } finally {
            transaction(database) { exec("UPDATE connector_grant SET state = 'granted' WHERE connector_id = 'other'") }
        }
        runBlocking { store.answer(ticket.id) } shouldBe Answer.Pending
    }

    test("storing a file makes room first: the cache never goes over its cap") {
        runBlocking { store.sweep(t0 + 10_000.hours, 24.hours, 0) }
        val keys = (1..4).map { FetchKey(mail, Uuid.random(), "acct/INBOX/1/5$it", "2", "2") }
        keys.forEachIndexed { i, key ->
            val ticket = runBlocking { store.request(key, t0 + i.hours, 24.hours) }.shouldBeInstanceOf<Ticket.Waiting>()
            runBlocking { store.lease(mail, t0 + i.hours, 1_000_000) }
            runBlocking { store.complete(mail, ticket.id, ByteArray(1_000), "f$i", "text/plain", t0 + i.hours, 1_000_000, cacheMaxBytes = 2_500) } shouldBe null
            (runBlocking { store.cachedBytes() } <= 2_500) shouldBe true
        }
        runBlocking { store.cachedBytes() } shouldBe 2_000
        runBlocking { store.request(keys[0], t0 + 5.hours, 24.hours) }.shouldBeInstanceOf<Ticket.Waiting>() // the oldest went
        runBlocking { store.request(keys[3], t0 + 5.hours, 24.hours) }.shouldBeInstanceOf<Ticket.Cached>()
    }

    test("the sweep leaves a request that is still waiting for its upload") {
        runBlocking { store.sweep(t0 + 10_000.hours, 24.hours, 0) }
        val waiting = FetchKey(mail, Uuid.random(), "acct/INBOX/1/60", "2", "2")
        val leased = FetchKey(mail, Uuid.random(), "acct/INBOX/1/61", "2", "2")
        val stale = FetchKey(other, Uuid.random(), "acct/INBOX/1/62", "2", "2")
        val c = runBlocking { store.request(stale, t0 - 3.hours, 24.hours) }.shouldBeInstanceOf<Ticket.Waiting>()
        val b = runBlocking { store.request(leased, t0 - 30.minutes, 24.hours) }.shouldBeInstanceOf<Ticket.Waiting>()
        runBlocking { store.lease(other, t0, 1_000_000, limit = 1) }.single().id shouldBe c.id.toString()
        val a = runBlocking { store.request(waiting, t0 + 15.minutes, 24.hours) }.shouldBeInstanceOf<Ticket.Waiting>()
        // b was requested long ago but handed to the connector only now: its upload may be on the way
        runBlocking { store.lease(mail, t0 + 15.minutes, 1_000_000, limit = 1) }.single().id shouldBe b.id.toString()
        runBlocking { store.sweep(t0 + 20.minutes, 24.hours, Long.MAX_VALUE) } shouldBe 1 // only c, whose lease ended long ago
        runBlocking { store.answer(b.id) } shouldBe Answer.Pending
        runBlocking { store.answer(a.id) } shouldBe Answer.Pending
        runBlocking { store.answer(c.id) }.shouldBeInstanceOf<Answer.Failed>() // gone
    }

    test("asking again after a lost delivery keeps the count of deliveries") {
        val key = FetchKey(mail, Uuid.random(), "acct/INBOX/1/70", "2", "2")
        val first = runBlocking { store.request(key, t0, 24.hours) }.shouldBeInstanceOf<Ticket.Waiting>()
        repeat(FETCH_MAX_ATTEMPTS) { n -> runBlocking { store.lease(mail, t0 + (n * 5).minutes, 1_000) }.single() }
        runBlocking { store.lease(mail, t0 + 30.minutes, 1_000) } shouldBe emptyList() // failed for good
        val again = runBlocking { store.request(key, t0 + 31.minutes, 24.hours) }.shouldBeInstanceOf<Ticket.Waiting>()
        again.id shouldBe first.id
        runBlocking { store.lease(mail, t0 + 31.minutes, 1_000) }.single().id shouldBe first.id.toString()
        transaction(database) {
            exec("SELECT attempts FROM attachment_fetch WHERE id = '${first.id}'") { rs -> rs.next(); rs.getInt(1) } shouldBe FETCH_MAX_ATTEMPTS + 1
        }
        // an expired download starts over
        val done = FetchKey(mail, Uuid.random(), "acct/INBOX/1/71", "2", "2")
        val ticket = runBlocking { store.request(done, t0, 24.hours) }.shouldBeInstanceOf<Ticket.Waiting>()
        runBlocking { store.lease(mail, t0, 1_000) }
        runBlocking { store.complete(mail, ticket.id, content, "a", "text/plain", t0, 1_000_000) } shouldBe null
        runBlocking { store.request(done, t0 + 25.hours, 24.hours) }.shouldBeInstanceOf<Ticket.Waiting>()
        transaction(database) {
            exec("SELECT attempts FROM attachment_fetch WHERE id = '${ticket.id}'") { rs -> rs.next(); rs.getInt(1) } shouldBe 0
        }
    }
})
