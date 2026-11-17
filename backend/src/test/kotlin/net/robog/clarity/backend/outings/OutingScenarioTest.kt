package net.robog.clarity.backend.outings

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.LeadRig
import net.robog.clarity.backend.agent.Priority
import net.robog.clarity.backend.agent.callTool
import net.robog.clarity.backend.agent.end
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Role
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.MessageKind
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

private fun text(m: net.robog.clarity.harness.Message) = m.content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }

/** The scout's wake-up reaches the lead, which posts one suggestion with buttons; the person's tap reaches it as their words. */
@EnabledIf(ContainerRuntimeAvailable::class)
class OutingScenarioTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine").withStartupAttempts(3)
    lateinit var database: Database

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
    }
    afterSpec { postgres.stop() }

    test("the lead posts one look_forward suggestion with Add to calendar and Not this time, and a tap reaches it") {
        val r = LeadRig(database).also { it.reset() }
        val slot = Slot(kotlin.time.Instant.parse("2026-11-19T23:00:00Z"), kotlin.time.Instant.parse("2026-11-20T03:00:00Z"))
        val brief = outingInstruction(listOf(slot), listOf(RecentContact("c-sam", "Sam Example", kotlin.time.Instant.parse("2026-11-10T10:00:00Z"))), kotlinx.datetime.TimeZone.of("America/New_York"))
        val seen = mutableListOf<String>()
        r.model.handler = { request ->
            val last = request.messages.last()
            seen += request.messages.joinToString("\n") { text(it) }
            when {
                // the scout's wake-up: suggest one thing
                request.messages.any { text(it).contains("Outing scout") } && request.messages.none { m -> m.content.any { it is Block.ToolUse } } ->
                    callTool(
                        "t1", "send_to_user",
                        buildJsonObject {
                            put("kind", "look_forward")
                            put(
                                "cards",
                                JsonArray(
                                    listOf(
                                        buildJsonObject { put("type", "text"); put("text", "Thursday evening is free. Jazz at the Blue Room with Sam Example?") },
                                        buildJsonObject {
                                            put("type", "choices")
                                            put("prompt", "Add Jazz at the Blue Room?")
                                            putJsonArray("options") {
                                                addJsonObject { put("label", "Add Jazz to calendar"); put("reply", "Add Jazz to calendar") }
                                                addJsonObject { put("label", "Not this time"); put("reply", "Not this time") }
                                            }
                                        },
                                    ),
                                ),
                            )
                        },
                    )
                // the tap: acknowledge it in a reply
                request.messages.any { text(it) == "Add Jazz to calendar" } && request.messages.none { m -> m.content.any { it is Block.ToolUse && it.id == "t2" } } ->
                    callTool("t2", "send_to_user", buildJsonObject { put("cards", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "Added.") }))) })
                else -> end("done")
            }
        }
        r.inbox.enqueue(InboxItem.JobDue(job = OUTING_JOB_NAME, instruction = brief, priority = Priority.LOW), dedupKey = "job:outing-scout:1")
        r.lead.start()
        // low priority waits for an idle lead: the job came due long ago
        r.lead.step()

        val offered = r.feed.page(0, 20).items.single { it.kind == MessageKind.LOOK_FORWARD }
        val choices = offered.cards.filterIsInstance<Card.Choices>().single()
        choices.options.map { it.label } shouldBe listOf("Add Jazz to calendar", "Not this time")
        seen.first() shouldContain "Outing scout"
        seen.first() shouldContain "Sam Example (contact c-sam)"

        // the person taps "Add Jazz to calendar": it is their input, and the lead's answer is a reply
        r.feed.chooseOption(offered.id, "Add Jazz to calendar", ClientInputId("tap-1"))
        r.inbox.enqueue(InboxItem.UserInput(ClientInputId("tap-1"), "Add Jazz to calendar"), dedupKey = "user:tap-1")
        r.lead.step()
        val items = r.feed.page(0, 20).items
        (items.first { it.id == offered.id }.cards.filterIsInstance<Card.Choices>().single().chosen) shouldBe "Add Jazz to calendar"
        items.filter { it.kind == MessageKind.REPLY } shouldHaveSize 1
        seen.last() shouldContain "Add Jazz to calendar"
    }
})

private const val OUTING_JOB_NAME = "outing-scout"
