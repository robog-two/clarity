package net.robog.clarity.backend.agent

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Role
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.feed.ClientInputId
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * A fake embeddings API: words in the same concept group share a dimension (so "automobile" finds
 * "car" without sharing a word), every other word hashes to its own. Never touches the network.
 */
class ConceptEmbeddings : EmbeddingClient {
    val calls = CopyOnWriteArrayList<List<String>>()
    @Volatile var fail = false
    private val groups = listOf(setOf("car", "automobile", "vehicle", "brakes"), setOf("dentist", "teeth", "dental", "tooth"), setOf("dog", "puppy", "canine"))

    override suspend fun embed(texts: List<String>): EmbeddingBatch {
        calls += texts
        if (fail) throw EmbeddingException(429, "rate limited")
        val vectors = texts.map { text ->
            val v = FloatArray(DIMS)
            Regex("[a-z]+").findAll(text.substringAfter("Query:").lowercase()).forEach { m ->
                val group = groups.indexOfFirst { m.value in it }
                if (group >= 0) v[group] += 3f else v[groups.size + abs(m.value.hashCode() % (DIMS - groups.size))] += 1f
            }
            v
        }
        return EmbeddingBatch(vectors, texts.sumOf { it.length / 4L }, null)
    }

    private companion object { const val DIMS = 64 }
}

private fun ok(result: ToolResult): String = result.shouldBeInstanceOf<ToolResult.Ok>().content

private fun err(result: ToolResult): String = result.shouldBeInstanceOf<ToolResult.Error>().message

private fun argsOf(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) ->
        when (v) {
            null -> Unit
            is Number -> put(k, v)
            is Boolean -> put(k, v)
            else -> put(k, v.toString())
        }
    }
}

private fun userText(m: Message) = m.content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }

@EnabledIf(ContainerRuntimeAvailable::class)
class MemoryRecallTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }

    suspend fun rig(embeddings: EmbeddingClient? = null): LeadRig =
        LeadRig(database, embeddingClient = embeddings).also {
            it.reset()
            database.tx { exec("TRUNCATE model_call, calendar_event RESTART IDENTITY CASCADE") }
        }

    suspend fun count(sql: String): Int = database.tx { jdbc().prepareStatement(sql).use { st -> st.executeQuery().use { rs -> rs.next(); rs.getInt(1) } } }

    suspend fun insertMessage(subject: String, body: String) = database.tx {
        exec(
            "INSERT INTO message (dedup_key, channel, account, sender, subject, body, sent_at) " +
                "VALUES (decode(md5(random()::text), 'hex'), 'mail', '{}'::jsonb, '{\"name\":\"Dana\"}'::jsonb, '$subject', '$body', now())",
        )
    }

    context("collections") {
        test("save_note creates a collection with a description, adds, dedupes, edits and removes notes") {
            val r = rig()
            val t = r.memoryTools
            err(t.saveNote().invoke(argsOf("collection" to "Trips", "text" to "Prefers aisle seats on long flights."))) shouldContain "description"
            ok(t.saveNote().invoke(argsOf("collection" to "Trips", "text" to "Prefers aisle seats on long flights.", "description" to "Travel plans and preferences"))) shouldContain
                "new collection"
            ok(t.saveNote().invoke(argsOf("collection" to "trips", "text" to "Lisbon in May: hotel Memmo Alfama, booked."))) shouldContain "Saved as #2 in trips"
            ok(t.saveNote().invoke(argsOf("collection" to "trips", "text" to "prefers aisle seats on long flights."))) shouldContain "Already in trips as #1"

            ok(t.openCollection().invoke(argsOf())) shouldContain "trips (2 notes"
            val opened = ok(t.openCollection().invoke(argsOf("name" to "trips")))
            opened shouldContain "trips: Travel plans and preferences (2 notes)"
            opened shouldContain "#2 "
            ok(t.openCollection().invoke(argsOf("name" to "trips", "query" to "Lisbon"))).let {
                it shouldContain "Memmo"
                it shouldNotContain "aisle"
            }
            ok(t.openCollection().invoke(argsOf("name" to "trips", "query" to "Alfam"))) shouldContain "Memmo" // substring fallback

            ok(t.saveNote().invoke(argsOf("id" to 2, "text" to "Lisbon in May: hotel Memmo Alfama, paid."))) shouldContain "updated"
            ok(t.openCollection().invoke(argsOf("name" to "trips"))) shouldContain "paid"
            ok(t.saveNote().invoke(argsOf("id" to 2, "remove" to true))) shouldContain "removed"
            err(t.openCollection().invoke(argsOf("name" to "nope"))) shouldContain "existing: trips"
        }

        test("secrets and never-store topics are refused") {
            val r = rig()
            r.constitutions.create(net.robog.clarity.shared.governance.Constitution(version = 1, lines = emptyList(), neverStore = listOf("health"), updatedAt = kotlin.time.Clock.System.now()))
            val t = r.memoryTools
            err(t.saveNote().invoke(argsOf("collection" to "bank", "description" to "Banking", "text" to "Online banking password: Hunter2!x"))) shouldContain "passwords"
            err(t.saveNote().invoke(argsOf("collection" to "bank", "description" to "Banking", "text" to "The SMS login code was 482913"))) shouldContain "Never store"
            err(t.saveNote().invoke(argsOf("collection" to "misc", "description" to "Misc", "text" to "Has a health appointment on Monday"))) shouldContain "never to store"
            count("SELECT count(*) FROM memory_note") shouldBe 0
        }

        test("manage_collection re-describes, renames, merges (dropping duplicates) and deletes only empty ones") {
            val r = rig()
            val t = r.memoryTools
            ok(t.saveNote().invoke(argsOf("collection" to "work-people", "description" to "Colleagues", "text" to "Dana runs finance.")))
            ok(t.saveNote().invoke(argsOf("collection" to "colleagues", "description" to "People at work", "text" to "Dana runs finance.")))
            ok(t.saveNote().invoke(argsOf("collection" to "colleagues", "description" to "People at work", "text" to "Sam is the new designer.")))
            ok(t.manageCollection().invoke(argsOf("name" to "work-people", "description" to "Colleagues and what they do", "rename" to "people")))
                .shouldContain("renamed to people")
            ok(t.manageCollection().invoke(argsOf("name" to "colleagues", "mergeInto" to "people"))) shouldContain "merged into people (1 notes moved"
            val people = ok(t.openCollection().invoke(argsOf("name" to "people")))
            people shouldContain "Colleagues and what they do (2 notes)"
            people shouldContain "Sam"
            err(t.manageCollection().invoke(argsOf("name" to "people", "delete" to true))) shouldContain "still has notes"
            ok(t.saveNote().invoke(argsOf("collection" to "empty", "description" to "Nothing", "text" to "x marks")))
            ok(t.saveNote().invoke(argsOf("id" to count("SELECT max(id) FROM memory_note"), "remove" to true)))
            ok(t.manageCollection().invoke(argsOf("name" to "empty", "delete" to true))) shouldContain "deleted"
        }

        test("the lead sees the collection index once, and again after a compaction took it out of view") {
            val r = rig()
            ok(r.memoryTools.saveNote().invoke(argsOf("collection" to "trips", "description" to "Travel plans and preferences", "text" to "Aisle seats.")))
            r.inbox.enqueue(InboxItem.UserInput(ClientInputId("u1"), "hi"))
            r.lead.start()
            r.lead.step()
            val first = r.model.requests.last().messages.joinToString("\n") { userText(it) }
            first shouldContain "[memory collections]"
            first shouldContain "- trips: Travel plans and preferences"
            // a note does not change the index: nothing re-sent
            ok(r.memoryTools.saveNote().invoke(argsOf("collection" to "trips", "text" to "Window seats on night flights.")))
            r.inbox.enqueue(InboxItem.UserInput(ClientInputId("u2"), "again"))
            r.lead.step()
            r.model.requests.last().messages.joinToString("\n") { userText(it) }.split("[memory collections]").size shouldBe 2
            // a compaction point: what came before is out of view, so the index comes back
            r.journal.append(r.threads.ensure().id, Message.summary("We talked about trips."))
            r.inbox.enqueue(InboxItem.UserInput(ClientInputId("u3"), "and now"))
            r.lead.step()
            val after = r.model.requests.last().messages
            after.joinToString("\n") { userText(it) }.split("[memory collections]").size shouldBe 2
            after.first().isSummary shouldBe true
        }
    }

    context("conversation archive") {
        test("spans before the latest compaction point are archived once, searchable, and readable around a hit") {
            val r = rig()
            val run = r.threads.ensure().id
            listOf(
                Message.user("Book me a table at Nopa for Friday"),
                Message(Role.ASSISTANT, listOf(Block.ToolUse("t1", "list_messages", buildJsonObject { put("query", "Nopa") }))),
                Message(Role.USER, listOf(Block.Result("t1", "Nopa: your reservation is confirmed for 7pm."))),
                Message(Role.ASSISTANT, listOf(Block.Text("done"))),
                Message.summary("Booked Nopa."),
                Message.user("Now the dentist on Tuesday please"),
                Message(Role.ASSISTANT, listOf(Block.Text("ok"))),
            ).forEach { r.journal.append(run, it) }

            r.archive.indexCompacted() shouldBe 1
            r.archive.indexCompacted() shouldBe 0
            database.tx { jdbc().prepareStatement("SELECT from_seq, to_seq, untrusted FROM conversation_chunk").use { st -> st.executeQuery().use { rs -> rs.next(); Triple(rs.getInt(1), rs.getInt(2), rs.getBoolean(3)) } } } shouldBe
                Triple(1, 4, true)

            // an Anthropic compaction block later: the span between the two points is archived next
            r.journal.append(run, Message(Role.ASSISTANT, listOf(Block.Raw(buildJsonObject { put("type", "compaction"); put("content", "summary") }), Block.Text("carry on"))))
            r.archive.indexCompacted() shouldBe 1
            count("SELECT count(*) FROM conversation_chunk") shouldBe 2

            val hits = ok(r.memoryTools.searchMemory().invoke(argsOf("query" to "Nopa reservation")))
            hits shouldContain "[conversation "
            hits shouldContain "· conversation:1]"
            hits shouldContain "<untrusted source=\"list_messages\">"
            val read = ok(r.memoryTools.readMemory().invoke(argsOf("ref" to "conversation:1")))
            read shouldContain "Person: Book me a table at Nopa"
            read shouldContain "(after: conversation:2)"
            ok(r.memoryTools.readMemory().invoke(argsOf("ref" to "conversation:2"))) shouldContain "dentist"
            err(r.memoryTools.readMemory().invoke(argsOf("ref" to "conversation:99")))
        }
    }

    context("search") {
        test("full-text covers journal, notes, messages (quoted as untrusted), events and helper results, with refs") {
            val r = rig()
            r.memory.writeJournal(kotlinx.datetime.LocalDate(2026, 10, 1), "Decided to move the budget review to Friday.")
            ok(r.memoryTools.saveNote().invoke(argsOf("collection" to "work", "description" to "Work", "text" to "The budget owner is Dana.")))
            insertMessage("Budget", "Here is the budget draft for Q4.")
            database.tx {
                exec("INSERT INTO calendar_event (dedup_key, calendar, title, start_at, end_at) VALUES (decode(md5('e1'), 'hex'), 'work', 'Budget review', now(), now())")
                // All-day, stored as UTC midnights: shown as its days, not as the evening before in the home zone.
                exec("INSERT INTO calendar_event (dedup_key, calendar, title, start_at, end_at, all_day) VALUES (decode(md5('e2'), 'hex'), 'work', 'Budget offsite', '2026-10-26T00:00:00Z', '2026-10-28T00:00:00Z', true)")
                exec("INSERT INTO agent_run (reason, status, task, result) VALUES ('helper', 'done', to_jsonb('find the budget file'::text), 'The budget file is in Drive/Finance.')")
            }
            val hits = ok(r.memoryTools.searchMemory().invoke(argsOf("query" to "budget", "limit" to 10)))
            hits shouldContain "[journal 2026-10-01 (Thu) · journal:"
            hits shouldContain "[event 2026-10-26 (Mon) to 2026-10-27 (Tue) · event:"
            hits shouldContain "[note "
            hits shouldContain "work: The budget owner is Dana."
            hits shouldContain "<untrusted source=\"message\">Budget"
            hits shouldContain "[event "
            hits shouldContain "[helper result "
            ok(r.memoryTools.searchMemory().invoke(argsOf("query" to "budget", "sources" to null))).length shouldBeGreaterThan 0
            ok(r.memoryTools.searchMemory().invoke(argsOf("query" to "zebra"))) shouldBe "(no matches)"
        }

        test("with embeddings: meaning finds what words miss, usage is recorded, secrets never leave, edits re-embed, deletions drop") {
            val fake = ConceptEmbeddings()
            val r = rig(fake)
            r.memory.writeJournal(kotlinx.datetime.LocalDate(2026, 10, 1), "Car brakes replaced at Joe's garage.")
            r.memory.writeJournal(kotlinx.datetime.LocalDate(2026, 10, 1), "My bank password is Hunter2x and the dog needs a walk.")
            ok(r.memoryTools.saveNote().invoke(argsOf("collection" to "health", "description" to "Appointments", "text" to "Dr. Lee checks teeth every March.")))

            r.embeddingIndexer.run() shouldBe 3
            r.embeddingIndexer.run() shouldBe 0
            fake.calls.flatten().joinToString(" ") shouldNotContain "Hunter2x"
            count("SELECT count(*) FROM model_call WHERE role = 'embedding' AND model = 'qwen/qwen3-embedding-8b'") shouldBe 2

            // "automobile" shares no word with the journal entry; keyword search alone finds nothing
            ok(r.memoryTools.searchMemory().invoke(argsOf("query" to "automobile"))) shouldContain "Car brakes replaced"
            ok(r.memoryTools.searchMemory().invoke(argsOf("query" to "dental"))) shouldContain "Dr. Lee"
            ok(LeadRig(database).memoryTools.searchMemory().invoke(argsOf("query" to "automobile"))) shouldBe "(no matches)"
            count("SELECT count(*) FROM model_call WHERE role = 'embedding'") shouldBeGreaterThan 2

            // an edited note is embedded again; a deleted row's vector goes
            val r2 = LeadRig(database, embeddingClient = fake)
            ok(r2.memoryTools.saveNote().invoke(argsOf("id" to 1, "text" to "Dr. Lee checks teeth every April.")))
            r2.embeddingIndexer.run() shouldBe 1
            database.tx { exec("DELETE FROM memory_journal WHERE text LIKE 'Car%'") }
            r2.embeddingIndexer.run() shouldBe 0
            count("SELECT count(*) FROM memory_embedding") shouldBe 2
        }

        test("embedding failures fall back to full-text and pause embeddings; nothing throws") {
            val fake = ConceptEmbeddings().apply { fail = true }
            val r = rig(fake)
            r.memory.writeJournal(kotlinx.datetime.LocalDate(2026, 10, 1), "Car brakes replaced at Joe's garage.")
            r.embeddingIndexer.run() shouldBe 0
            r.embeddings.available shouldBe false
            ok(r.memoryTools.searchMemory().invoke(argsOf("query" to "garage"))) shouldContain "Car brakes"
            fake.calls.size shouldBe 1 // paused: the search did not call again
        }
    }
})
